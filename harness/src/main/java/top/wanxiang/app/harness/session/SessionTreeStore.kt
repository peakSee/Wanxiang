package top.wanxiang.app.harness.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.core.database.HarnessEntryEntity
import top.wanxiang.app.core.database.HarnessRuntimeRepository
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.CapabilityEvent
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.harness.UserMessage

/** Serialization and active-branch projection for the immutable session tree. */
@Singleton
class SessionTreeStore @Inject constructor(
    private val repository: HarnessRuntimeRepository,
    private val json: Json,
    private val logger: AppLogger,
) {
    suspend fun ensureMainLane(sessionId: String) {
        repository.ensureLane(sessionId, MAIN_LANE)
    }

    suspend fun load(sessionId: String, laneName: String = MAIN_LANE): List<HarnessMessage> = runCatching {
        val lane = repository.ensureLane(sessionId, laneName)
        repository.branchTail(sessionId, lane.leafId, MAX_LIVE_ENTRIES).mapNotNull(::decode)
    }.onFailure { throwable ->
        logger.e("Failed to load harness branch for $sessionId/$laneName: ${throwable.message}", throwable)
    }.getOrDefault(emptyList())

    suspend fun loadAt(sessionId: String, leafId: String?): List<HarnessMessage> = runCatching {
        repository.branchTail(sessionId, leafId, MAX_LIVE_ENTRIES).mapNotNull(::decode)
    }.onFailure { throwable ->
        logger.e("Failed to load harness branch at $sessionId/$leafId: ${throwable.message}", throwable)
    }.getOrDefault(emptyList())

    suspend fun append(sessionId: String, message: HarnessMessage, laneName: String = MAIN_LANE) {
        val lane = repository.ensureLane(sessionId, laneName)
        val entry = HarnessEntryEntity(
            id = message.id,
            sessionId = sessionId,
            parentId = lane.leafId,
            createdAt = message.createdAt,
            entryType = "message",
            customType = messageType(message),
            payloadJson = json.encodeToString(HarnessMessage.serializer(), message),
        )
        repository.appendToLane(sessionId, laneName, entry)
    }

    /** Navigate to the parent of [entryId], preserving the abandoned branch. */
    suspend fun rewindBefore(sessionId: String, entryId: String, laneName: String = MAIN_LANE) {
        val target = repository.findEntry(sessionId, entryId) ?: return
        repository.moveLane(sessionId, laneName, target.parentId)
    }

    suspend fun moveTo(sessionId: String, entryId: String?, laneName: String = MAIN_LANE) {
        repository.moveLane(sessionId, laneName, entryId)
    }

    suspend fun deleteSession(sessionId: String) {
        repository.deleteSessionData(sessionId)
    }

    suspend fun search(sessionId: String, query: String, limit: Int = 8): List<HarnessMessage> {
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()
        val lane = repository.ensureLane(sessionId, MAIN_LANE)
        return repository.searchBranch(sessionId, lane.leafId, needle, limit.coerceIn(1, 20))
            .mapNotNull(::decode)
    }

    suspend fun read(sessionId: String, messageId: String? = null, index: Int? = null): HarnessMessage? {
        return when {
            !messageId.isNullOrBlank() -> {
                repository.findEntry(sessionId, messageId)?.let(::decode)
            }
            index != null && index >= 0 -> {
                val lane = repository.ensureLane(sessionId, MAIN_LANE)
                repository.branchEntryAt(sessionId, lane.leafId, index)?.let(::decode)
            }
            else -> null
        }
    }

    internal fun decode(entity: HarnessEntryEntity): HarnessMessage? {
        if (entity.entryType != "message") return null
        return runCatching { json.decodeFromString(HarnessMessage.serializer(), entity.payloadJson) }
            .onFailure { logger.w("Skipping invalid harness entry ${entity.id}: ${it.message}") }
            .getOrNull()
    }

    private fun messageType(message: HarnessMessage): String = when (message) {
        is UserMessage -> "user"
        is AssistantText -> "assistant"
        is ToolCall -> "tool_call"
        is ToolResult -> "tool_result"
        is CapabilityEvent -> "capability_event"
    }

    private fun searchableText(message: HarnessMessage): String = when (message) {
        is CapabilityEvent -> "${message.kind} ${message.name} ${message.details}"
        is UserMessage -> message.text
        is AssistantText -> "${message.text}\n${message.reasoning.orEmpty()}"
        is ToolCall -> "${message.rawToolName.orEmpty()} ${message.tool} ${message.args} ${message.reasoning.orEmpty()}"
        is ToolResult -> message.output
    }

    companion object {
        const val MAIN_LANE = "main"
        /** Maximum decoded messages retained per live UI/session projection. */
        const val MAX_LIVE_ENTRIES = 600
    }
}
