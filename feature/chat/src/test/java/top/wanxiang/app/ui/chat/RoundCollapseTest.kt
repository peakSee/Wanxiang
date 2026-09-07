package top.wanxiang.app.ui.chat

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.HarnessTool
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.harness.UserMessage

class RoundCollapseTest {

    private fun makeUser(id: String, text: String = "query") =
        UserMessage(id = id, createdAt = 1000L, text = text)

    private fun makeAssistant(id: String, text: String) =
        AssistantText(id = id, createdAt = 1000L, text = text)

    private fun makeToolCall(id: String, tool: HarnessTool = HarnessTool.READ) =
        ToolCall(id = id, createdAt = 1000L, tool = tool, args = buildJsonObject {})

    private fun makeToolResult(toolCallId: String, durationMs: Long? = 1000L) =
        ToolResult(
            id = "res_$toolCallId",
            createdAt = 1000L,
            toolCallId = toolCallId,
            success = true,
            output = "ok",
            durationMs = durationMs,
        )

    @Test
    fun `empty messages returns empty list`() {
        val items = projectChatMessages(emptyList())
        assertTrue(items.isEmpty())
    }

    @Test
    fun `round with 2 or fewer steps is never collapsed and shows no button`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeAssistant("a1", "Done"),
            makeUser("u2"), // creates a new round so u1 becomes historical
            makeAssistant("a2", "Hello"),
        )
        val items = projectChatMessages(messages)
        // Check u1 round: should have u1, t1, t2, a1 (no CollapseButtonItem)
        val buttons = items.filterIsInstance<ChatRenderItem.CollapseButtonItem>()
        assertTrue("≤ 2 步的历史轮不应该有折叠按钮", buttons.isEmpty())

        val messageIds = items.filterIsInstance<ChatRenderItem.MessageItem>().map { it.message.id }
        assertEquals(listOf("u1", "t1", "t2", "a1", "u2", "a2"), messageIds)
    }

    @Test
    fun `tool results are excluded from direct render items`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1", 1200L),
            makeAssistant("a1", "Done"),
        )
        val items = projectChatMessages(messages)
        val messageIds = items.filterIsInstance<ChatRenderItem.MessageItem>().map { it.message.id }
        assertEquals(listOf("u1", "t1", "a1"), messageIds)
    }

    @Test
    fun `all assistant and user messages and tool calls are preserved naturally in order`() {
        val messages = listOf(
            makeAssistant("a_init", "Welcome"),
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeAssistant("a1", "Answer"),
            makeUser("u2"),
            makeAssistant("a2", "Next"),
        )
        val items = projectChatMessages(messages)
        val messageIds = items.filterIsInstance<ChatRenderItem.MessageItem>().map { it.message.id }
        assertEquals(listOf("a_init", "u1", "t1", "t2", "a1", "u2", "a2"), messageIds)
    }
}
