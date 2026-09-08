package top.wkbin.taixu.harness.metrics

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import top.wkbin.taixu.harness.ChatUsage

/**
 * 单次 Agent 运行的过程指标采集（Harness 2.0 Phase 0 基线埋点）。
 *
 * 目的：在引入自主规划 / 自我反思等能力之前，先让"任务自主完成率、错误自恢复率、
 * 人工干预次数"这些目标指标可度量。每次运行结束时以结构化单行写入 Agent 日志
 * （AppLogger.logAgent，不受用户日志开关影响），用于离线汇总 1.0 真实基线。
 *
 * 指标定义：
 * - rounds：Provider 请求轮数
 * - toolCalls / toolFailures：工具调用总数与失败数（含校验被拒的调用）
 * - approvalRequests：触发用户审批的次数（人工干预的直接信号）
 * - streamRetries：限流 + 网络退避重试次数
 * - circuitBreakerTripped：是否触发连续失败熔断
 * - droppedToolCalls：超出单轮上限被丢弃的调用数
 * - cacheHitRate：DeepSeek 等厂商 KV 前缀缓存命中率；高命中率意味着更低成本
 */
class RunMetrics(
    private val startedAt: Long,
) {
    private val rounds = AtomicInteger()
    private val toolCalls = AtomicInteger()
    private val toolFailures = AtomicInteger()
    private val approvalRequests = AtomicInteger()
    private val streamRetries = AtomicInteger()
    private val droppedToolCalls = AtomicInteger()
    private val steeringMessages = AtomicInteger()
    private val followUpMessages = AtomicInteger()
    private val maxConsecutiveFailuresSeen = AtomicInteger()
    @Volatile private var circuitBreakerTripped = false
    @Volatile var outcome: String = "unknown"
        private set

    // Cache-hit tracking: accumulated across all rounds for the session run.
    private val totalInputTokens = AtomicLong()
    private val totalCacheReadTokens = AtomicLong()

    fun roundStarted() { rounds.incrementAndGet() }
    fun toolCallRecorded(failed: Boolean) {
        toolCalls.incrementAndGet()
        if (failed) toolFailures.incrementAndGet()
    }
    fun toolCallsDropped(count: Int) { droppedToolCalls.addAndGet(count) }
    fun approvalRequested() { approvalRequests.incrementAndGet() }
    fun streamRetry() { streamRetries.incrementAndGet() }
    fun steeringInjected(count: Int) { steeringMessages.addAndGet(count) }
    fun followUpConsumed(count: Int) { followUpMessages.addAndGet(count) }
    fun consecutiveFailuresObserved(count: Int) {
        maxConsecutiveFailuresSeen.updateAndGet { current -> maxOf(current, count) }
    }
    fun circuitBreaker() { circuitBreakerTripped = true }
    fun finish(result: String) { outcome = result }

    /**
     * Record per-turn provider usage for cache-hit rate aggregation.
     * Call this once per successful provider response (after streaming completes).
     */
    fun recordUsage(usage: ChatUsage) {
        if (usage.inputTokens > 0) {
            totalInputTokens.addAndGet(usage.inputTokens)
            totalCacheReadTokens.addAndGet(usage.cacheReadTokens)
        }
    }

    fun summary(): String = buildString {
        append("Rounds=").append(rounds.get())
        append(", ToolCalls=").append(toolCalls.get())
        append(", ToolFailures=").append(toolFailures.get())
        append(", ApprovalRequests=").append(approvalRequests.get())
        append(", StreamRetries=").append(streamRetries.get())
        append(", DroppedToolCalls=").append(droppedToolCalls.get())
        append(", Steering=").append(steeringMessages.get())
        append(", FollowUps=").append(followUpMessages.get())
        append(", MaxConsecutiveFailures=").append(maxConsecutiveFailuresSeen.get())
        append(", CircuitBreaker=").append(circuitBreakerTripped)
        // Cache-hit rate: show percentage when we have meaningful data; "n/a" when no
        // provider usage was reported (e.g. all turns failed before streaming started).
        val inputToks = totalInputTokens.get()
        val cacheHitStr = if (inputToks > 0) {
            val hitPct = (totalCacheReadTokens.get() * 100L / inputToks).toInt()
            "$hitPct% (${totalCacheReadTokens.get()}/${inputToks} tokens)"
        } else {
            "n/a"
        }
        append(", CacheHit=").append(cacheHitStr)
        append(", Outcome=").append(outcome)
    }
}
