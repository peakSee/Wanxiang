package top.wanxiang.app.harness.subagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.ApiToolCallSpec
import top.wanxiang.app.harness.HarnessTool
import top.wanxiang.app.harness.TextToolCallCodec
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.harness.UserMessage

class SubagentLaneRunnerTest {

    @Test
    fun `isolated messages omit parent history and dangling parent tool call`() {
        val messages = listOf(
            UserMessage("parent-user", 1L, "父任务"),
            AssistantText("parent-assistant", 2L, "父回复"),
            ToolCall("delegate", 3L, HarnessTool.SUBAGENT, JsonObject(emptyMap())),
            UserMessage("child-user", 4L, "子任务"),
            ToolResult("child-result", 5L, "read-call", true, "读取完成"),
        )

        val result = isolatedProviderMessages(messages, "子智能体系统提示", forceFinalAnswer = false)

        assertEquals(listOf("system", "user", "tool"), result.map { it.role })
        assertEquals("子任务", result[1].content)
        assertFalse(result.any { it.content == "父任务" || it.content == "父回复" })
        assertFalse(result.any { it.tool_calls?.any { call -> call.id == "delegate" } == true })
    }

    @Test
    fun `final round system prompt forces a direct conclusion`() {
        val result = isolatedProviderMessages(
            messages = listOf(UserMessage("child-user", 1L, "请分析")),
            systemPrompt = "子智能体系统提示",
            forceFinalAnswer = true,
        )

        assertTrue(result.first().content.orEmpty().contains("禁止继续调用工具"))
        assertTrue(result.first().content.orEmpty().contains("直接输出结论"))
    }

    @Test
    fun `final round rejects textual or structured tool calls as conclusions`() {
        val json = Json { isLenient = true }
        val textual = TextToolCallCodec.normalize(
            json,
            """说明文字<vendor_tool_call>{"name":"read","arguments":{"path":"a.kt"}}</vendor_tool_call>""",
        )
        val plain = TextToolCallCodec.normalize(json, "分析完成")

        assertFalse(isDirectSubagentConclusion(textual.displayText, emptyList(), textual))
        assertFalse(
            isDirectSubagentConclusion(
                "仍想调用工具",
                listOf(ApiToolCallSpec("native", "read", "{}")),
                plain,
            ),
        )
        assertTrue(isDirectSubagentConclusion("分析完成", emptyList(), plain))
    }

    @Test
    fun `subagent uses configured round budget instead of forcing round twelve`() {
        assertFalse(shouldForceSubagentFinalAnswer(round = 11, maxRounds = 100))
        assertFalse(shouldForceSubagentFinalAnswer(round = 98, maxRounds = 100))
        assertTrue(shouldForceSubagentFinalAnswer(round = 99, maxRounds = 100))
        assertTrue(shouldForceSubagentFinalAnswer(round = 11, maxRounds = 12))
    }
}
