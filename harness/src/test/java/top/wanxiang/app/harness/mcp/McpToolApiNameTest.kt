package top.wanxiang.app.harness.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.model.McpToolInfo

class McpToolApiNameTest {
    @Test
    fun `unsafe names become stable portable names`() {
        val tool = tool(serverId = "服务器 / one", name = "files.read all:now")
        val first = McpToolApiName.encode(tool)

        assertEquals(first, McpToolApiName.encode(tool))
        assertTrue(McpToolApiName.isValid(first))
        assertTrue(first.startsWith("mcp__"))
    }

    @Test
    fun `sanitization collisions remain distinct`() {
        val dot = McpToolApiName.encode(tool(serverId = "server", name = "files.read"))
        val slash = McpToolApiName.encode(tool(serverId = "server", name = "files/read"))

        assertNotEquals(dot, slash)
    }

    @Test
    fun `legacy names still match persisted tool calls`() {
        val tool = tool(serverId = "sqlite", name = "read_query")

        assertTrue(McpToolApiName.matches(tool, "mcp__sqlite__read_query"))
        assertTrue(McpToolApiName.matches(tool, McpToolApiName.encode(tool)))
    }

    private fun tool(serverId: String, name: String) = McpToolInfo(
        serverId = serverId,
        serverName = serverId,
        name = name,
        description = "test",
    )
}
