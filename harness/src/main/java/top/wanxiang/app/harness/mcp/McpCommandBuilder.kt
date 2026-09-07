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
}
