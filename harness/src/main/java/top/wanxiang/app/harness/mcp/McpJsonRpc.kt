package top.wanxiang.app.harness.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Latest MCP protocol version implemented by this client. It is a spec identifier, not today's date. */
internal const val MCP_PROTOCOL_VERSION = "2025-06-18"

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: String? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
data class McpInitializeParams(
    val protocolVersion: String = MCP_PROTOCOL_VERSION,
    val capabilities: JsonObject = JsonObject(emptyMap()),
    val clientInfo: McpClientInfo = McpClientInfo("WanXiang-Agent", "1.0.0"),
)

@Serializable
data class McpInitializeResult(
    val protocolVersion: String,
)

@Serializable
data class McpClientInfo(
    val name: String,
    val version: String,
)

@Serializable
data class McpToolDto(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class McpToolsListResponse(
    val tools: List<McpToolDto> = emptyList(),
)

@Serializable
data class McpCallToolParams(
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class McpContentItem(
    val type: String = "text",
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class McpCallToolResult(
    val content: List<McpContentItem> = emptyList(),
    val isError: Boolean = false,
)
