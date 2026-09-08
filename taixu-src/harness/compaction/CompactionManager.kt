package top.wkbin.taixu.harness.compaction

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.database.HarnessEntryEntity
import top.wkbin.taixu.core.database.HarnessRuntimeRepository
import top.wkbin.taixu.harness.ContextWindowPolicy
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.session.SessionTreeStore

/** Persists compaction as an immutable tree entry and projects provider context from it. */
@Singleton
class CompactionManager @Inject constructor(
    private val repository: HarnessRuntimeRepository,
    private val json: Json,
) {
    suspend fun project(sessionId: String, laneName: String = SessionTreeStore.MAIN_LANE): CompactedContext {
        val lane = repository.ensureLane(sessionId, laneName)
        // Provider projection must walk the complete active branch. A UI-sized tail limit here
        // silently drops unsummarized messages before token budgeting gets a chance to compact them.
        // The latest compaction is still fetched separately to recover its retained snapshot.
        val latestCompaction = repository.latestBranchEntryOfType(sessionId, lane.leafId, ENTRY_TYPE)
        val entries = repository.branch(sessionId, lane.leafId)
        if (latestCompaction == null) return CompactedContext(messages = entries.mapNotNull(::decodeMessage))

        val payload = json.decodeFromString(CompactionPayload.serializer(), latestCompaction.payloadJson)
        val retained = json.decodeFromString(ListSerializer(HarnessMessage.serializer()), payload.retainedMessagesJson)
        val after = entries.asSequence()
            .filter { it.sequence > latestCompaction.sequence }
            .mapNotNull(::decodeMessage)
            .toList()
        return CompactedContext(summary = payload.summary, messages = retained + after)
    }

    /**
     * 最近一次压缩的轻量快照（不解码保留消息）。
     * 折叠条数为历次压缩累计；摘要与时间取最新一条——与 UI 横幅对齐。
     * 会话从未压缩时返回 null——UI 据此隐藏折叠提示。
     */
    suspend fun latestSnapshot(sessionId: String, laneName: String = SessionTreeStore.MAIN_LANE): CompactionSnapshot? {
        val lane = runCatching { repository.ensureLane(sessionId, laneName) }.getOrNull() ?: return null
        val latestEntry = runCatching {
            repository.latestBranchEntryOfType(sessionId, lane.leafId, ENTRY_TYPE)
        }.getOrNull() ?: return null
        val newest = runCatching {
            json.decodeFromString(CompactionPayload.serializer(), latestEntry.payloadJson)
        }.getOrNull() ?: return null
        return CompactionSnapshot(
            summary = newest.summary,
            foldedMessageCount = newest.cumulativeCompactedMessageCount ?: newest.compactedMessageCount,
            createdAt = newest.createdAt,
        )
    }

    suspend fun compact(
        sessionId: String,
        context: CompactedContext,
        keepFromIndex: Int,
        laneName: String = SessionTreeStore.MAIN_LANE,
    ): CompactedContext {
        require(keepFromIndex in 1..context.messages.size) { "Compaction must remove at least one message" }
        val lane = repository.ensureLane(sessionId, laneName)
        val collapsed = context.messages.take(keepFromIndex)
        val retained = context.messages.drop(keepFromIndex)
        val incrementalSummary = ContextWindowPolicy.buildHistorySummary(collapsed)
        val summary = mergeRollingSummary(context.summary, incrementalSummary)
        val now = System.currentTimeMillis()
        val previousFoldedCount = repository.latestBranchEntryOfType(sessionId, lane.leafId, ENTRY_TYPE)
            ?.let { entry ->
                runCatching { json.decodeFromString(CompactionPayload.serializer(), entry.payloadJson) }.getOrNull()
            }
            ?.let { it.cumulativeCompactedMessageCount ?: it.compactedMessageCount }
            ?: 0
        val payload = CompactionPayload(
            sourceLeafId = lane.leafId,
            summary = summary,
            retainedMessagesJson = json.encodeToString(ListSerializer(HarnessMessage.serializer()), retained),
            compactedMessageCount = collapsed.size,
            cumulativeCompactedMessageCount = previousFoldedCount + collapsed.size,
            retainedMessageCount = retained.size,
            estimatedTokensBefore = context.messages.sumOf(::messageTokens),
            createdAt = now,
        )
        val entry = HarnessEntryEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            parentId = lane.leafId,
            createdAt = now,
            entryType = ENTRY_TYPE,
            customType = null,
            payloadJson = json.encodeToString(CompactionPayload.serializer(), payload),
        )
        repository.appendToLane(sessionId, laneName, entry)
        return CompactedContext(summary, retained)
    }

    private fun decodeMessage(entry: HarnessEntryEntity): HarnessMessage? =
        entry.takeIf { it.entryType == "message" }?.let {
            runCatching { json.decodeFromString(HarnessMessage.serializer(), it.payloadJson) }.getOrNull()
        }

    private fun messageTokens(message: HarnessMessage): Int = ContextWindowPolicy.estimateTokens(message.toString())

    /** Preserve both durable early context and the newest folded state after the cap is reached. */
    private fun mergeRollingSummary(previous: String?, incremental: String): String {
        val old = previous.orEmpty().trim()
        val newest = incremental.trim()
        val combined = listOf(old, newest).filter { it.isNotBlank() }.joinToString("\n\n")
        if (combined.length <= MAX_SUMMARY_CHARS) return combined
        if (old.isBlank()) return newest.takeLast(MAX_SUMMARY_CHARS)
        if (newest.isBlank()) return old.take(MAX_SUMMARY_CHARS)

        val marker = "\n\n[较早摘要中段已省略]\n\n"
        val newestBudget = minOf(newest.length, MAX_SUMMARY_CHARS / 2)
        val oldBudget = (MAX_SUMMARY_CHARS - marker.length - newestBudget).coerceAtLeast(0)
        val oldHead = old.take((oldBudget + 1) / 2)
        val oldTail = old.takeLast(oldBudget / 2)
        return (oldHead + marker + oldTail + "\n\n" + newest.takeLast(newestBudget)).takeLast(MAX_SUMMARY_CHARS)
    }

    companion object {
        const val ENTRY_TYPE = "compaction"
        private const val MAX_SUMMARY_CHARS = 16_000
    }
}
