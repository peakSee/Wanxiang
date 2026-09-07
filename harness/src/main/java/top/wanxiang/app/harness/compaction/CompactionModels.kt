package top.wanxiang.app.harness.compaction

import kotlinx.serialization.Serializable
import top.wanxiang.app.harness.HarnessMessage

@Serializable
data class CompactionPayload(
    val sourceLeafId: String?,
    val summary: String,
    val retainedMessagesJson: String,
    val compactedMessageCount: Int,
    /** Cumulative folded count, added later for O(1) snapshots; null on legacy payloads. */
    val cumulativeCompactedMessageCount: Int? = null,
    val retainedMessageCount: Int,
    val estimatedTokensBefore: Int,
    val createdAt: Long,
)

data class CompactedContext(
    val summary: String? = null,
    val messages: List<HarnessMessage> = emptyList(),
)

/**
 * 最近一次压缩的只读快照，供 UI 展示折叠透明度信息：
 * 被折叠进摘要的早期消息条数与摘要文本预览。
 */
data class CompactionSnapshot(
    val summary: String,
    /** 累计被折叠进摘要的早期消息条数（多次压缩会累加）。 */
    val foldedMessageCount: Int,
    val createdAt: Long,
)
