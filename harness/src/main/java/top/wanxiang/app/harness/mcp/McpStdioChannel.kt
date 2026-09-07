package top.wanxiang.app.harness.mcp

import kotlinx.coroutines.channels.ReceiveChannel
import top.wanxiang.app.core.model.McpServerConfig

/**
 * Lifecycle boundary for an MCP STDIO subprocess. Production code wraps a LinuxSession;
 * unit tests inject an in-memory implementation so request dispatch, frame filtering and
 * reaping can be exercised without a real PRoot.
 */
interface McpStdioChannel {
    val isAlive: Boolean
    suspend fun writeLine(line: String)
    suspend fun close()
    /** Lines arriving on the subprocess stdout, already newline-stripped. Channel is closed when the process dies. */
    val incoming: ReceiveChannel<String>
}

/** Builds MCP STDIO channels. Lets tests inject an in-memory factory. */
interface McpStdioChannelFactory {
    suspend fun open(server: McpServerConfig): McpStdioChannel
}
