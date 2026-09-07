package top.wanxiang.app.harness.mcp

import kotlinx.serialization.json.JsonObject
import top.wanxiang.app.core.model.McpServerConfig
import top.wanxiang.app.core.model.McpToolInfo

interface McpTransport {
    suspend fun check(server: McpServerConfig): Boolean
    suspend fun discover(server: McpServerConfig): List<McpToolInfo>
    suspend fun execute(server: McpServerConfig, toolName: String, arguments: JsonObject): Pair<Boolean, String>
}

internal fun McpToolDto.toInfo(server: McpServerConfig, parametersJson: String) = McpToolInfo(
    serverId = server.id, serverName = server.name, name = name,
    description = description, parametersJson = parametersJson,
)
