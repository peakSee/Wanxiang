package top.wanxiang.app.harness

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.harness.effects.RetryPolicy
import top.wanxiang.app.harness.effects.ToolReplayPolicy
import top.wanxiang.app.harness.operation.OperationPhase
import top.wanxiang.app.harness.operation.OperationSnapshot
import top.wanxiang.app.harness.operation.ReplayPolicy

class HarnessRuntimePolicyTest {
    @Test
    fun `only read-only tools are safe to replay`() {
        assertEquals(ReplayPolicy.SAFE, ToolReplayPolicy.forTool(HarnessTool.READ))
        assertEquals(ReplayPolicy.SAFE, ToolReplayPolicy.forTool(HarnessTool.HISTORY_SEARCH))
        assertEquals(ReplayPolicy.NEVER, ToolReplayPolicy.forTool(HarnessTool.WRITE))
        assertEquals(ReplayPolicy.NEVER, ToolReplayPolicy.forTool(HarnessTool.READ, "mcp__server__read"))
    }

    @Test
    fun `retry policy uses bounded exponential delays`() {
        val policy = RetryPolicy(enabled = true, maxRetries = 3, baseDelayMs = 1_000)
        assertEquals(4, policy.maxAttempts)
        assertEquals(1_000, policy.delayForRetry(1))
        assertEquals(2_000, policy.delayForRetry(2))
        assertEquals(4_000, policy.delayForRetry(3))
    }

    @Test
    fun `operation snapshot is a complete serializable program counter`() {
        val snapshot = OperationSnapshot(
            phase = OperationPhase.TOOL_INTENT.id,
            round = 2,
            effectKind = "tool",
            effectId = "call-1",
            effectPayloadJson = "{\"path\":\"a\"}",
            replayPolicy = ReplayPolicy.NEVER.id,
            attempt = 1,
            maxAttempts = 1,
        )
        val encoded = Json.encodeToString(OperationSnapshot.serializer(), snapshot)
        val decoded = Json.decodeFromString(OperationSnapshot.serializer(), encoded)
        assertEquals(snapshot, decoded)
        assertTrue(encoded.contains("tool_intent"))
    }
}
