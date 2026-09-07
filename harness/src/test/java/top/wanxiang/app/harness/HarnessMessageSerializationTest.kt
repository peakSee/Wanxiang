package top.wanxiang.app.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessMessageSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `user message round trip`() {
        val message = UserMessage("u1", 1000L, "帮我看看工作区")
        val decoded = json.decodeFromString(HarnessMessage.serializer(), json.encodeToString(HarnessMessage.serializer(), message))
        assertEquals(message, decoded)
    }

    @Test
    fun `tool call with args round trip`() {
        val message = ToolCall(
            id = "c1",
            createdAt = 1000L,
            tool = HarnessTool.EDIT,
            args = buildJsonObject {
                put("path", "a.txt")
                put("oldText", "x")
                put("newText", "y")
            },
        )
        val encoded = json.encodeToString(HarnessMessage.serializer(), message)
        assertTrue(encoded.contains("\"tool\":\"edit\""))
        val decoded = json.decodeFromString(HarnessMessage.serializer(), encoded)
        assertEquals(message, decoded)
    }

    @Test
    fun `tool result round trip`() {
        val message = ToolResult("r1", 2000L, "c1", success = false, output = "找不到文件")
        val decoded = json.decodeFromString(HarnessMessage.serializer(), json.encodeToString(HarnessMessage.serializer(), message))
        assertEquals(message, decoded)
    }

    @Test
    fun `assistant text round trip`() {
        val message = AssistantText("a1", 3000L, "已安装 node v22.22.3")
        val decoded = json.decodeFromString(HarnessMessage.serializer(), json.encodeToString(HarnessMessage.serializer(), message))
        assertEquals(message, decoded)
    }

    @Test
    fun `capability event round trip`() {
        val message = CapabilityEvent("mcp:user:sqlite", 4000L, CapabilityEvent.Kind.MCP, "sqlite", "MCP 工具已挂载")
        val encoded = json.encodeToString(HarnessMessage.serializer(), message)
        assertTrue(encoded.contains("\"type\":\"capability_event\""))
        assertEquals(message, json.decodeFromString(HarnessMessage.serializer(), encoded))
    }

    @Test
    fun `polymorphic decode dispatches on serial name`() {
        val userJson = """{"type":"user","id":"u","createdAt":1,"text":"hi"}"""
        val callJson = """{"type":"tool_call","id":"c","createdAt":1,"tool":"base","args":{"command":"ls"}}"""
        val subagentJson = """{"type":"tool_call","id":"sub1","createdAt":1,"tool":"invoke_subagent","args":{"role":"coder"}}"""
        val mcpJson = """{"type":"tool_call","id":"mcp1","createdAt":1,"tool":"mcp","rawToolName":"mcp__sqlite__query","args":{"sql":"select 1"}}"""
        assertTrue(json.decodeFromString(HarnessMessage.serializer(), userJson) is UserMessage)
        assertTrue(json.decodeFromString(HarnessMessage.serializer(), callJson) is ToolCall)
        val subCall = json.decodeFromString(HarnessMessage.serializer(), subagentJson) as ToolCall
        assertEquals(HarnessTool.SUBAGENT, subCall.tool)
        val mcpCall = json.decodeFromString(HarnessMessage.serializer(), mcpJson) as ToolCall
        assertEquals(HarnessTool.MCP, mcpCall.tool)
        assertEquals("mcp__sqlite__query", mcpCall.rawToolName)
    }
}
