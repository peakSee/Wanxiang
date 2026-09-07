package top.wanxiang.app.harness

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessApiMapperTest {

    @Test
    fun `user message maps to user role`() {
        val api = HarnessApiMapper.toApiMessage(UserMessage("u", 1L, "hello"))
        assertEquals("user", api.role)
        assertEquals("hello", api.content)
    }

    @Test
    fun `assistant text maps to assistant role`() {
        val api = HarnessApiMapper.toApiMessage(AssistantText("a", 1L, "done"))
        assertEquals("assistant", api.role)
        assertEquals("done", api.content)
    }

    @Test
    fun `tool call maps to assistant tool_calls with function name`() {
        val call = ToolCall(
            id = "c1",
            createdAt = 1L,
            tool = HarnessTool.READ,
            args = buildJsonObject { put("path", "x.txt") },
        )
        val api = HarnessApiMapper.toApiMessage(call)
        assertEquals("assistant", api.role)
        val toolCall = api.tool_calls!!.single()
        assertEquals("c1", toolCall.id)
        assertEquals("read", toolCall.function.name)
        assertEquals("""{"path":"x.txt"}""", toolCall.function.arguments)
    }

    @Test
    fun `tool result maps to tool role with tool_call_id`() {
        val result = ToolResult("r", 1L, "c1", success = false, output = "boom")
        val api = HarnessApiMapper.toApiMessage(result)
        assertEquals("tool", api.role)
        assertEquals("c1", api.tool_call_id)
        assertEquals("boom", api.content)
    }

    @Test
    fun `tool name mapping`() {
        assertEquals(HarnessTool.READ, HarnessApiMapper.toolByName("read"))
        assertEquals(HarnessTool.WRITE, HarnessApiMapper.toolByName("Write"))
        assertEquals(HarnessTool.EDIT, HarnessApiMapper.toolByName("edit"))
        assertEquals(HarnessTool.DOWNLOAD, HarnessApiMapper.toolByName("download"))
        assertEquals(HarnessTool.BASE, HarnessApiMapper.toolByName("base"))
        assertEquals(HarnessTool.PROCESS, HarnessApiMapper.toolByName("process"))
        assertEquals(HarnessTool.BASE, HarnessApiMapper.toolByName("execute"))
        assertEquals(HarnessTool.SUBAGENT, HarnessApiMapper.toolByName("invoke_subagent"))
        assertEquals(HarnessTool.SUBAGENT, HarnessApiMapper.toolByName("subagent"))
        assertEquals(HarnessTool.SUBAGENT, HarnessApiMapper.toolByName("invoke_dual_agent"))
        assertEquals(HarnessTool.BUILD_SCRIPT, HarnessApiMapper.toolByName("build_script"))
        assertEquals(HarnessTool.MCP, HarnessApiMapper.toolByName("mcp__sqlite__read_query"))
        assertEquals(HarnessTool.BASE, HarnessApiMapper.toolByName("unknown_tool"))
    }
}
