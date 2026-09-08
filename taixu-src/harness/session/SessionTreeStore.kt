package top.wkbin.taixu.harness.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.database.HarnessEntryEntity
import top.wkbin.taixu.core.database.HarnessRuntimeRepository
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.CapabilityEvent
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage

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
        val messages = repository.branch(sessionId, lane.leafId).mapNotNull(::decode)
        val resultsByCallId = messages.filterIsInstance<ToolResult>().groupBy { it.toolCallId }
        val callsById = messages.filterIsInstance<ToolCall>().associateBy { it.id }
        val terms = needle.split(SEARCH_TERM_SEPARATOR).filter { it.isNotBlank() }

        return messages.withIndex().mapNotNull { (index, message) ->
            val relatedText = when (message) {
                is ToolCall -> resultsByCallId[message.id].orEmpty().joinToString("\n") { searchableText(it) }
                is ToolResult -> callsById[message.toolCallId]?.let(::searchableText).orEmpty()
                else -> ""
            }
            val haystack = searchableText(message) + "\n" + relatedText
            val exactMatch = haystack.contains(needle, ignoreCase = true)
            val matchedTerms = terms.count { haystack.contains(it, ignoreCase = true) }
            if (!exactMatch && (terms.isEmpty() || matchedTerms != terms.size)) null
            else SearchMatch(message, index, if (exactMatch) matchedTerms + 2 else matchedTerms)
        }.sortedWith(compareByDescending<SearchMatch> { it.score }.thenByDescending { it.index })
            .take(limit.coerceIn(1, 20))
            .map { it.message }
    }

    suspend fun read(sessionId: String, messageId: String? = null, index: Int? = null): HarnessMessage? {
        val lane = repository.ensureLane(sessionId, MAIN_LANE)
        val messages = repository.branch(sessionId, lane.leafId).mapNotNull(::decode)
        return when {
            !messageId.isNullOrBlank() -> messages.firstOrNull { it.id == messageId }
            index != null && index >= 0 -> messages.getOrNull(index)
            else -> null
        }
    }

    /** Read one active-branch message and keep a tool call/result exchange together. */
    suspend fun readWithRelated(
        sessionId: String,
        messageId: String? = null,
        index: Int? = null,
    ): List<HarnessMessage> {
        val lane = repository.ensureLane(sessionId, MAIN_LANE)
        val messages = repository.branch(sessionId, lane.leafId).mapNotNull(::decode)
        val selected = when {
            !messageId.isNullOrBlank() -> messages.firstOrNull { it.id == messageId }
            index != null && index >= 0 -> messages.getOrNull(index)
            else -> null
        } ?: return emptyList()
        val relatedIds = when (selected) {
            is ToolCall -> messages.filterIsInstance<ToolResult>()
                .filter { it.toolCallId == selected.id }
                .mapTo(mutableSetOf(selected.id)) { it.id }
            is ToolResult -> mutableSetOf(selected.id, selected.toolCallId)
            else -> mutableSetOf(selected.id)
        }
        return messages.filter { it.id in relatedIds }
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
        private val SEARCH_TERM_SEPARATOR = Regex("[\\s,，;；|]+")
    }

    private data class SearchMatch(val message: HarnessMessage, val index: Int, val score: Int)
}
