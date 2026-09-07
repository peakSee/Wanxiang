package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 超时取消后的工具调用计数恢复：laneRunner 协程被中止时其内存统计丢失，
 * 必须从 lane 已落库的 tool_call entry 恢复，而不是向汇总报告 0 次。
 */
class SubagentToolCallCountTest {

    private fun now() = System.currentTimeMillis()

    @Test
    fun `timed-out lane recovers count from persisted transcript instead of reporting zero`() {
        val transcript = listOf(
            UserMessage("u1", now(), "任务"),
            ToolCall("c1", now(), HarnessTool.READ, kotlinx.serialization.json.JsonObject(emptyMap()), rawToolName = "read"),
            ToolResult("r1", now(), "c1", true, "ok"),
            ToolCall("c2", now(), HarnessTool.BASE, kotlinx.serialization.json.JsonObject(emptyMap()), rawToolName = "base"),
            ToolResult("r2", now(), "c2", true, "ok"),
            ToolCall("c3", now(), HarnessTool.READ, kotlinx.serialization.json.JsonObject(emptyMap()), rawToolName = "read"),
            ToolResult("r3", now(), "c3", false, "boom"),
            AssistantText("a1", now(), "部分结论"),
        )

        // laneResultToolCalls = null 模拟 withTimeoutOrNull 超时取消
        assertEquals(3, resolveSubagentToolCallCount(null, transcript))
    }

    @Test
    fun `completed lane keeps runner-reported count without touching transcript`() {
        // 正常完成时以 laneRunner 返回值为准，即使 transcript 读取为空
        assertEquals(5, resolveSubagentToolCallCount(5, emptyList()))
    }

    @Test
    fun `timed-out lane with empty transcript still reports zero`() {
        // 尚未执行任何工具即超时（如首个模型请求挂满 6 分钟）
        assertEquals(0, resolveSubagentToolCallCount(null, listOf(UserMessage("u1", now(), "任务"))))
    }
}
