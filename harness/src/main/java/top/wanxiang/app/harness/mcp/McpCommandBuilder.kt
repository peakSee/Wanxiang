package top.wanxiang.app.harness.mcp

import javax.inject.Inject
import javax.inject.Singleton
import top.wanxiang.app.core.model.McpServerConfig

/**
 * Quoting helpers for the MCP STDIO PTY invocation. Kept separate so unit tests can lock down
 * command-line construction without spinning up a Linux session.
 */
@Singleton
class McpCommandBuilder @Inject constructor() {
    fun commandLine(server: McpServerConfig): String {
        require(server.command.isNotBlank()) { "MCP STDIO server requires a non-blank command" }
        val argv = (listOf(server.command) + server.args).joinToString(" ", transform = ::shellQuote)
        return "stty raw -echo; exec " + argv
    }

    fun fingerprint(server: McpServerConfig) =
        server.command + "|" + server.args.joinToString(",") + "|" + server.env.entries.joinToString(",") { it.key + "=" + it.value }

    fun shellQuote(value: String): String {
        val escaped = value.replace("'", "'" + "\"" + "'" + "\"" + "'")
        return "'" + escaped + "'"
    }

    /**
     * 将内置 MCP 的默认 `--repository /workspace` 绑定到当前会话工作区。
     * 用户自定义路径保持不动；工作区为空或仍是 `/workspace` 时原样返回。
     */
    fun bindWorkspaceRepository(server: McpServerConfig, workspace: String): McpServerConfig {
        val repo = workspace.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: return server
        val idx = server.args.indexOf("--repository")
        if (idx < 0 || idx + 1 >= server.args.size) return server
        if (server.args[idx + 1] != DEFAULT_REPOSITORY) return server
        if (repo == DEFAULT_REPOSITORY) return server
        return server.copy(args = server.args.toMutableList().also { it[idx + 1] = repo })
    }

    private companion object {
        const val DEFAULT_REPOSITORY = "/workspace"
    }
}
