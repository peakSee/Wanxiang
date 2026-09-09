package top.wanxiang.app.harness.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import top.wanxiang.app.core.model.McpServerConfig
import top.wanxiang.app.core.model.McpTransportType

class McpCommandBuilderTest {
    private val builder = McpCommandBuilder()

    private fun gitServer(repository: String = "/workspace") = McpServerConfig(
        id = "mcp_git",
        name = "Git",
        transportType = McpTransportType.STDIO,
        command = "python3",
        args = listOf("-u", "/opt/taixu/scripts/git_mcp_server.py", "--repository", repository),
    )

    @Test
    fun `bindWorkspaceRepository rewrites default workspace placeholder`() {
        val bound = builder.bindWorkspaceRepository(gitServer(), "/workspace/RelayGo")
        assertEquals("/workspace/RelayGo", bound.args.last())
    }

    @Test
    fun `bindWorkspaceRepository leaves custom repository untouched`() {
        val custom = gitServer("/home/user/repo")
        assertSame(custom, builder.bindWorkspaceRepository(custom, "/workspace/RelayGo"))
    }

    @Test
    fun `bindWorkspaceRepository no-ops for blank or root workspace`() {
        val server = gitServer()
        assertSame(server, builder.bindWorkspaceRepository(server, ""))
        assertSame(server, builder.bindWorkspaceRepository(server, "/workspace"))
        assertSame(server, builder.bindWorkspaceRepository(server, "/workspace/"))
    }
}
