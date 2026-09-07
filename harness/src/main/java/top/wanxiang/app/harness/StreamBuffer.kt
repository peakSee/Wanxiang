package top.wanxiang.app.harness

/**
 * 流式增量累积缓冲 + 发布节流器。
 *
 * SSE chunk 频率（几十字节级、每秒上百个）远高于 UI 帧率：逐 chunk 把累积内容
 * 全量 toString 发布，分配总量随内容长度呈 O(n²) 增长，长推理会把 GC 拖到假死/OOM
 * （256MB 堆直接打满）。因此按时间间隔采样发布；流结束后由调用方无条件全量刷新兜底，
 * 不会丢失尾部内容。
 *
 * [maxChars] 限制累积上限（用于推理等过程数据；正文不可设限，工具调用协议依赖完整原文），
 * 超限后静默丢弃后续增量——推理是执行草稿，不是长期上下文。
 */
class StreamBuffer(private val maxChars: Int = Int.MAX_VALUE) {
    private val builder = StringBuilder()
    private var lastPublishAt = 0L
    private var publishedLength = 0

    val length: Int get() = builder.length

    fun append(chunk: String) {
        if (builder.length < maxChars) builder.append(chunk.take(maxChars - builder.length))
    }

    fun clear() {
        builder.clear()
        publishedLength = 0
        // 重试是新回合：清除发布时钟，允许清空后立即重新发布
        lastPublishAt = 0L
    }

    override fun toString(): String = builder.toString()

    /**
     * 距上次发布超过 [intervalMs] 且内容有新增时，返回当前全量内容；否则返回 null。
     * 内容不变（如已达累积上限）时不重复发布。
     */
    fun publishIfDue(nowMs: Long, intervalMs: Long): String? {
        if (builder.isEmpty() || builder.length == publishedLength) return null
        if (nowMs - lastPublishAt < intervalMs) return null
        lastPublishAt = nowMs
        publishedLength = builder.length
        return builder.toString()
    }
}
