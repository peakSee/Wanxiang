package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [sanitizeApiTranscript] 的协议合法性修复测试。 */
class TranscriptSanitizerTest {

    private fun assistantWithCalls(vararg ids: String) = ApiMessage(
        role = "assistant",
        content = "text",
        tool_calls = ids.map { id ->
            ApiToolCall(id = id, function = ApiFunctionCall(name = "read", arguments = "{}"))
        },
    )

    private fun toolResult(id: String, output: String = "ok") =
        ApiMessage(role = "tool", content = output, tool_call_id = id)

    /** 校验结果序列满足「tool 消息必须响应最近的 assistant(tool_calls)：越过连续 tool 消息回溯到包含其 id 的 assistant」。 */
    private fun assertValidProviderTranscript(messages: List<ApiMessage>) {
        messages.forEachIndexed { index, message ->
            if (message.role == "tool") {
                var cursor = index - 1
                while (cursor >= 0 && messages[cursor].role == "tool") cursor--
                val owner = messages.getOrNull(cursor)
                assertTrue(
                    "tool(${message.tool_call_id}) 必须响应包含该 id 的 assistant(tool_calls)，实际归属 ${owner?.role}",
                    owner != null &&
                        owner.role == "assistant" &&
                        owner.tool_calls.orEmpty().any { it.id == message.tool_call_id },
                )
            }
        }
    }

    @Test
    fun `合法序列原样保留`() {
        val messages = listOf(
            ApiMessage(role = "system", content = "sys"),
            ApiMessage(role = "user", content = "hi"),
            assistantWithCalls("a", "b"),
            toolResult("a"),
            toolResult("b"),
            ApiMessage(role = "assistant", content = "done"),
        )
        assertEquals(messages, sanitizeApiTranscript(messages))
    }

    @Test
    fun `跨用户消息边界的 tool 结果被转写为 user 文本`() {
        val messages = listOf(
            ApiMessage(role = "user", content = "hi"),
            assistantWithCalls("a"),
            ApiMessage(role = "user", content = "打断"),
            toolResult("a", "real output"),
        )
        val sanitized = sanitizeApiTranscript(messages)
        assertValidProviderTranscript(sanitized)
        // 原结果信息以 user 文本保留
        val converted = sanitized.last()
        assertEquals("user", converted.role)
        assertTrue(converted.content.orEmpty().contains("real output"))
        // assistant 的调用得到占位结果而不是悬空
        assertEquals("tool", sanitized[2].role)
        assertEquals("a", sanitized[2].tool_call_id)
    }

    @Test
    fun `孤立 tool 结果（无对应 assistant tool_calls）被转写`() {
        val messages = listOf(
            ApiMessage(role = "user", content = "hi"),
            ApiMessage(role = "assistant", content = "plain text"),
            toolResult("ghost"),
        )
        val sanitized = sanitizeApiTranscript(messages)
        assertValidProviderTranscript(sanitized)
        assertEquals("user", sanitized.last().role)
        assertTrue(sanitized.last().content.orEmpty().contains("【工具执行结果"))
    }

    @Test
    fun `连续 tool 结果且前一条不是其 assistant 时被转写`() {
        val messages = listOf(
            ApiMessage(role = "user", content = "hi"),
            assistantWithCalls("a"),
            toolResult("a"),
            toolResult("b"),
        )
        val sanitized = sanitizeApiTranscript(messages)
        assertValidProviderTranscript(sanitized)
        // b 的结果被转写为 user 文本
        assertEquals("user", sanitized.last().role)
    }

    @Test
    fun `assistant tool_calls 缺失结果时补占位`() {
        val messages = listOf(
            ApiMessage(role = "user", content = "hi"),
            assistantWithCalls("a", "b"),
            ApiMessage(role = "user", content = "next turn"),
        )
        val sanitized = sanitizeApiTranscript(messages)
        assertValidProviderTranscript(sanitized)
        // a、b 均补占位 tool 结果，且在 user 消息之前
        val stubIds = sanitized.filter { it.role == "tool" }.mapNotNull { it.tool_call_id }
        assertEquals(listOf("a", "b"), stubIds)
        assertEquals("user", sanitized.last().role)
    }

    @Test
    fun `无 tool 消息的纯净序列不受影响`() {
        val messages = listOf(
            ApiMessage(role = "system", content = "sys"),
            ApiMessage(role = "user", content = "hi"),
            ApiMessage(role = "assistant", content = "hello"),
        )
        assertEquals(messages, sanitizeApiTranscript(messages))
    }
}
