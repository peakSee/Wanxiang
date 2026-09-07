package top.wanxiang.app.harness.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import top.wanxiang.app.core.database.HarnessLaneEntity
import top.wanxiang.app.core.database.HarnessRuntimeRepository
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.UserMessage

enum class ConversationBranchKind { MAIN, BRANCH, SUBAGENT, HISTORY }

data class ConversationBranch(
    val id: String,
    val name: String,
    /** Durable lane name used to load a read-only transcript. */
    val laneName: String? = null,
    val leafId: String?,
    val depth: Int,
    val preview: String,
    val updatedAt: Long,
    val kind: ConversationBranchKind,
    val isCurrent: Boolean,
    val isBusy: Boolean,
    val faulted: Boolean,
    val toolCallCount: Int,
)

/** Public lane surface: create, inspect, navigate, and project shared conversation branches. */
@Singleton
class LaneManager @Inject constructor(
    private val repository: HarnessRuntimeRepository,
    private val treeStore: SessionTreeStore,
) {
    suspend fun create(sessionId: String, name: String, atEntryId: String? = null): HarnessLaneEntity {
        require(LANE_NAME.matches(name)) { "Invalid lane name: $name" }
        require(repository.findLane(sessionId, name) == null) { "Lane already exists: $name" }
        return repository.ensureLane(sessionId, name, atEntryId)
    }

    suspend fun get(sessionId: String, name: String): HarnessLaneEntity? = repository.findLane(sessionId, name)
    fun observe(sessionId: String): Flow<List<HarnessLaneEntity>> = repository.observeLanes(sessionId)
    suspend fun transcript(sessionId: String, name: String): List<HarnessMessage> = treeStore.load(sessionId, name)

    /** Loads only the isolated child task, excluding the parent conversation shared before the fork. */
    suspend fun subagentTranscript(sessionId: String, laneName: String): List<HarnessMessage> {
        require(laneName.startsWith(SUBAGENT_PREFIX)) { "Not a subagent lane: $laneName" }
        val fullPath = transcript(sessionId, laneName)
        val taskStart = fullPath.indexOfLast { it is UserMessage }.takeIf { it >= 0 } ?: 0
        return fullPath.drop(taskStart)
    }

    /** Projects named lanes and otherwise-unreferenced tree leaves into a branch browser model. */
    suspend fun branches(sessionId: String): List<ConversationBranch> {
        val entries = repository.listEntries(sessionId)
        val lanes = repository.observeLanes(sessionId).first()
        val main = lanes.firstOrNull { it.name == SessionTreeStore.MAIN_LANE }
        val entryMap = entries.associateBy { it.id }
        val parentIds = entries.mapNotNullTo(mutableSetOf()) { it.parentId }
        val leafIds = entries.asSequence().map { it.id }.filter { it !in parentIds }.toList()
        val targets = (leafIds + lanes.mapNotNull { it.leafId } + listOfNotNull(main?.leafId)).distinct()

        return targets.mapIndexed { index, leafId ->
            val pathEntries = buildList {
                var current = entryMap[leafId]
                val visited = mutableSetOf<String>()
                while (current != null && visited.add(current.id)) {
                    add(current)
                    current = current.parentId?.let { entryMap[it] }
                }
            }.asReversed()
            val pathIds = pathEntries.mapTo(hashSetOf()) { it.id }
            val namedLane = lanes
                .filter { it.name != SessionTreeStore.MAIN_LANE && it.leafId in pathIds }
                .maxByOrNull { lane -> pathEntries.indexOfLast { it.id == lane.leafId } }
            val kind = when {
                namedLane?.name?.startsWith(SUBAGENT_PREFIX) == true -> ConversationBranchKind.SUBAGENT
                namedLane?.name?.startsWith(BRANCH_PREFIX) == true -> ConversationBranchKind.BRANCH
                leafId == main?.leafId -> ConversationBranchKind.MAIN
                else -> ConversationBranchKind.HISTORY
            }
            val scopedPathEntries = if (kind == ConversationBranchKind.SUBAGENT) {
                val taskStart = pathEntries.indexOfLast { it.customType == "user" }.takeIf { it >= 0 } ?: 0
                pathEntries.drop(taskStart)
            } else {
                pathEntries
            }
            val scopedMessages = scopedPathEntries.mapNotNull { treeStore.decode(it) }
            val roleName = namedLane?.name.orEmpty()
                .removePrefix(SUBAGENT_PREFIX)
                .substringBefore(':')
                .ifBlank { "子智能体" }
            val displayName = when (kind) {
                ConversationBranchKind.MAIN -> "主线"
                ConversationBranchKind.BRANCH -> namedLane?.name.orEmpty()
                    .removePrefix(BRANCH_PREFIX)
                    .substringAfter(':', "分支 ${index + 1}")
                    .replace('-', ' ')
                ConversationBranchKind.SUBAGENT -> {
                    val taskName = scopedMessages.filterIsInstance<UserMessage>().firstOrNull()
                        ?.text?.let(::subagentTaskTitle)
                    if (taskName.isNullOrBlank()) roleName else "$taskName · $roleName"
                }
                ConversationBranchKind.HISTORY -> "历史分支 ${index + 1}"
            }
            val preview = scopedMessages.asReversed().asSequence()
                .firstNotNullOfOrNull { message ->
                    when (message) {
                        is AssistantText -> message.text.takeIf { it.isNotBlank() }?.let(::branchPreviewText)
                        is UserMessage -> if (kind == ConversationBranchKind.SUBAGENT) {
                            subagentTaskTitle(message.text)
                        } else {
                            message.text.takeIf { it.isNotBlank() }?.let(::branchPreviewText)
                        }
                        else -> null
                    }
                }.orEmpty()
            val toolCallCount = scopedPathEntries.count { it.customType == "tool_call" }
            ConversationBranch(
                // Always key by leaf: one named lane can sit on many leaf paths (siblings/descendants).
                id = namedLane?.let { "${it.name}@$leafId" } ?: "leaf:$leafId",
                name = displayName,
                laneName = namedLane?.name,
                leafId = leafId,
                depth = scopedPathEntries.size,
                preview = preview,
                updatedAt = pathEntries.lastOrNull()?.createdAt ?: namedLane?.updatedAt ?: 0L,
                kind = kind,
                isCurrent = leafId == main?.leafId,
                isBusy = namedLane?.currentOperationId != null ||
                    (main != null && leafId == main.leafId && main.currentOperationId != null),
                faulted = namedLane?.faulted == true,
                toolCallCount = toolCallCount,
            )
        }.sortedWith(compareByDescending<ConversationBranch> { it.isCurrent }.thenByDescending { it.updatedAt })
    }

    suspend fun createConversationBranch(sessionId: String, displayName: String, atEntryId: String): HarnessLaneEntity {
        val slug = displayName.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '_').take(48)
            .ifBlank { "branch" }
        val laneName = "$BRANCH_PREFIX${java.util.UUID.randomUUID().toString().take(8)}:$slug"
        return create(sessionId, laneName, atEntryId)
    }

    suspend fun navigate(sessionId: String, name: String, targetEntryId: String?) {
        val lane = repository.findLane(sessionId, name) ?: error("Unknown lane $name")
        check(lane.currentOperationId == null) { "Lane $name is busy" }
        repository.moveLane(sessionId, name, targetEntryId)
    }

    companion object {
        private const val BRANCH_PREFIX = "branch:"
        private const val SUBAGENT_PREFIX = "subagent:"
        private val LANE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

/**
 * Branch cards must never receive an entire generated-image data URL. Besides being useless as a
 * summary, laying out a multi-megabyte Base64 string can exhaust Compose's paragraph limits and
 * crash as soon as the branch browser is opened.
 */
internal fun branchPreviewText(text: String, maxLength: Int = 240): String {
    val out = StringBuilder(maxLength.coerceAtLeast(0))
    var cursor = 0
    while (cursor < text.length && out.length < maxLength) {
        val dataStart = text.indexOf("data:image/", cursor, ignoreCase = true)
        if (dataStart < 0) {
            out.append(text, cursor, minOf(text.length, cursor + (maxLength - out.length)))
            break
        }

        // Preserve the human-readable prefix, but discard the data URL itself without copying it.
        val markdownStart = text.lastIndexOf("![", dataStart).takeIf { start ->
            start >= cursor && text.indexOf("](", start).let { it in start until dataStart }
        }
        val htmlStart = text.lastIndexOf("<img", dataStart, ignoreCase = true).takeIf { it >= cursor }
        val mediaStart = listOfNotNull(markdownStart, htmlStart).maxOrNull() ?: dataStart
        val prefixEnd = minOf(mediaStart, cursor + (maxLength - out.length))
        out.append(text, cursor, prefixEnd)
        if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
        out.append("[图片]")

        val markdownEnd = text.indexOf(')', dataStart).takeIf { it >= 0 }
        val htmlEnd = text.indexOfAny(charArrayOf('"', '\'', '>', ' ', '\n', '\r', '\t'), dataStart)
            .takeIf { it >= 0 }
        cursor = listOfNotNull(markdownEnd, htmlEnd).minOrNull()?.plus(1) ?: text.length
    }
    return out.toString()
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maxLength)
        .ifBlank { "[图片]" }
}

internal fun subagentTaskTitle(prompt: String): String? = prompt.lineSequence()
    .firstOrNull { it.trimStart().startsWith("任务目标：") }
    ?.substringAfter("任务目标：")
    ?.trim()
    ?.takeIf { it.isNotBlank() }
