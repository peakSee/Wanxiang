package top.wanxiang.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.UserMessage
import top.wanxiang.app.harness.events.HarnessEvent

class RuntimeTimelineGroupTest {

    @Test
    fun `diagnostic text is bounded before compose renders it`() {
        val bounded = limitDiagnosticText("x".repeat(100), maxChars = 16)

        assertTrue(bounded.startsWith("x".repeat(16)))
        assertTrue(bounded.contains("已截断"))
    }

    @Test
    fun `model response diagnostics hide generated image base64`() {
        val payload = "A".repeat(500_000)
        val response = "生成完成：![星空](data:image/png;base64,$payload) 希望你喜欢"

        val sanitized = sanitizeModelResponseForDiagnostics(response)

        assertTrue(sanitized.contains("[图片数据已隐藏：image/png]"))
        assertTrue(sanitized.contains("希望你喜欢"))
        assertTrue(!sanitized.contains(payload.take(64)))
        assertTrue(sanitized.length < 1_000)
    }

    @Test
    fun `model response diagnostics hide b64 json payload`() {
        val payload = "B".repeat(200_000)
        val sanitized = sanitizeModelResponseForDiagnostics("{\"b64_json\":\"$payload\",\"size\":\"1024x1024\"}")

        assertTrue(sanitized.contains("[图片 Base64 已隐藏]"))
        assertTrue(sanitized.contains("1024x1024"))
        assertTrue(!sanitized.contains(payload.take(64)))
    }

    @Test
    fun `buildRoundGroups maps corresponding user message to each round`() {
        val user1 = UserMessage(id = "user_1", createdAt = 1000L, text = "First user query")
        val user2 = UserMessage(id = "user_2", createdAt = 3000L, text = "Second user query")
        val assistant1 = AssistantText(id = "asst_1", createdAt = 2000L, text = "First reply")
        val assistant2 = AssistantText(id = "asst_2", createdAt = 4000L, text = "Second reply")

        val messages = listOf(user1, assistant1, user2, assistant2)
        val events = listOf(
            HarnessEvent.ProviderRoundStarted(
                sessionId = "sess_1",
                timestamp = 1100L,
                operationId = "op_1",
                round = 0,
                attempt = 1,
                modelId = "gpt-4o",
            ),
            HarnessEvent.ProviderRoundSettled(
                sessionId = "sess_1",
                timestamp = 1900L,
                operationId = "op_1",
                round = 0,
                entryId = "asst_1",
                inputTokens = 100,
                outputTokens = 50,
            ),
            HarnessEvent.ProviderRoundStarted(
                sessionId = "sess_1",
                timestamp = 3100L,
                operationId = "op_2",
                round = 1,
                attempt = 1,
                modelId = "gpt-4o",
            ),
            HarnessEvent.ProviderRoundSettled(
                sessionId = "sess_1",
                timestamp = 3900L,
                operationId = "op_2",
                round = 1,
                entryId = "asst_2",
                inputTokens = 200,
                outputTokens = 80,
            ),
        )

        val (rounds, _) = buildRoundGroups(events, messages)

        assertEquals(2, rounds.size)
        assertNotNull(rounds[0].userPromptMessage)
        assertEquals("user_1", rounds[0].userPromptMessage?.id)
        assertEquals("First user query", rounds[0].userPromptMessage?.text)
        assertEquals(1, rounds[0].displayIndex)

        assertNotNull(rounds[1].userPromptMessage)
        assertEquals("user_2", rounds[1].userPromptMessage?.id)
        assertEquals("Second user query", rounds[1].userPromptMessage?.text)
        assertEquals(2, rounds[1].displayIndex)
    }

    @Test
    fun `mergeHistoricalAndLiveEvents preserves historical events when a new live event occurs`() {
        val user1 = UserMessage(id = "user_1", createdAt = 1000L, text = "你好")
        val asst1 = AssistantText(id = "asst_1", createdAt = 2000L, text = "你好！")
        val user2 = UserMessage(id = "user_2", createdAt = 3000L, text = "你能做什么")
        val asst2 = AssistantText(id = "asst_2", createdAt = 4000L, text = "我可以写代码")

        val messages = listOf(user1, asst1, user2, asst2)

        // 模拟重启后发送第 3 条消息，触发了实时的 round=0 报错事件
        val liveEvents = listOf(
            HarnessEvent.ProviderRoundStarted(
                sessionId = "sess_1",
                timestamp = 5000L,
                operationId = "op_live",
                round = 0,
                attempt = 1,
                modelId = "gpt-4o",
            ),
        )

        val merged = mergeHistoricalAndLiveEvents("sess_1", messages, liveEvents)

        val (rounds, _) = buildRoundGroups(merged, messages)

        // 验证：不会因为只传入了 1 个 live 报错事件就把历史 2 轮冲掉
        assertEquals(3, rounds.size)
        assertEquals(1, rounds[0].displayIndex)
        assertEquals("user_1", rounds[0].userPromptMessage?.id)

        assertEquals(2, rounds[1].displayIndex)
        assertEquals("user_2", rounds[1].userPromptMessage?.id)

        assertEquals(3, rounds[2].displayIndex)
    }
}
