package top.wanxiang.app.harness.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunMetricsTest {

    @Test
    fun `summary aggregates all recorded counters`() {
        val metrics = RunMetrics(startedAt = 0L)
        metrics.roundStarted()
        metrics.roundStarted()
        metrics.roundStarted()
        metrics.toolCallRecorded(failed = false)
        metrics.toolCallRecorded(failed = false)
        metrics.toolCallRecorded(failed = true)
        metrics.toolCallsDropped(2)
        metrics.approvalRequested()
        metrics.streamRetry()
        metrics.steeringInjected(1)
        metrics.followUpConsumed(3)
        metrics.consecutiveFailuresObserved(3)
        metrics.consecutiveFailuresObserved(5)
        metrics.consecutiveFailuresObserved(2)
        metrics.circuitBreaker()
        metrics.finish("failed")

        val summary = metrics.summary()

        assertTrue(summary.contains("Rounds=3"))
        assertTrue(summary.contains("ToolCalls=3"))
        assertTrue(summary.contains("ToolFailures=1"))
        assertTrue(summary.contains("ApprovalRequests=1"))
        assertTrue(summary.contains("StreamRetries=1"))
        assertTrue(summary.contains("DroppedToolCalls=2"))
        assertTrue(summary.contains("Steering=1"))
        assertTrue(summary.contains("FollowUps=3"))
        assertTrue(summary.contains("MaxConsecutiveFailures=5"))
        assertTrue(summary.contains("CircuitBreaker=true"))
        assertTrue(summary.contains("Outcome=failed"))
    }

    @Test
    fun `defaults reflect a clean untouched run`() {
        val metrics = RunMetrics(startedAt = 0L)
        metrics.finish("completed")

        val summary = metrics.summary()

        assertTrue(summary.contains("Rounds=0"))
        assertTrue(summary.contains("ToolCalls=0"))
        assertTrue(summary.contains("CircuitBreaker=false"))
        assertTrue(summary.contains("Outcome=completed"))
    }

    @Test
    fun `circuit breaker flag only set once`() {
        val metrics = RunMetrics(startedAt = 0L)
        assertFalse(metrics.summary().contains("CircuitBreaker=true"))
        metrics.circuitBreaker()
        assertTrue(metrics.summary().contains("CircuitBreaker=true"))
        metrics.finish("failed")
        assertEquals("failed", metrics.outcome)
    }

    @Test
    fun `cache hit rate is tracked and formatted in summary`() {
        val metrics = RunMetrics(startedAt = 0L)
        // Record round 1: 1000 input, 800 cache read (80% hit)
        metrics.recordUsage(top.wanxiang.app.harness.ChatUsage(inputTokens = 1000, cacheReadTokens = 800))
        // Record round 2: 1000 input, 600 cache read (cumulative: 2000 input, 1400 cache read -> 70%)
        metrics.recordUsage(top.wanxiang.app.harness.ChatUsage(inputTokens = 1000, cacheReadTokens = 600))
        metrics.finish("completed")

        val summary = metrics.summary()
        assertTrue(summary.contains("CacheHit=70% (1400/2000 tokens)"))
    }
}

