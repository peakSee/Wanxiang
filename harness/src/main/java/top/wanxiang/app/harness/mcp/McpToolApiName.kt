package top.wanxiang.app.harness.mcp

import java.security.MessageDigest
import top.wanxiang.app.core.model.McpToolInfo

/**
 * Maps arbitrary MCP server/tool names to the conservative function-name contract shared by
 * OpenAI-compatible providers. The hash keeps names stable and prevents sanitization collisions.
 */
internal object McpToolApiName {
    private const val SERVER_PART_LENGTH = 16
    private const val TOOL_PART_LENGTH = 24
    private const val HASH_LENGTH = 12
    const val MAX_LENGTH = 64

    private val allowedName = Regex("^[a-zA-Z0-9_-]+$")

    fun encode(tool: McpToolInfo): String {
        val server = safePart(tool.serverId, SERVER_PART_LENGTH, "server")
        val name = safePart(tool.name, TOOL_PART_LENGTH, "tool")
        val hash = sha256("${tool.serverId}\u0000${tool.name}").take(HASH_LENGTH)
        return "mcp__${server}__${name}__$hash"
    }

    /** Compatibility for tool calls persisted by releases before safe API-name encoding. */
    fun legacy(tool: McpToolInfo): String = "mcp__${tool.serverId}__${tool.name}"

    fun matches(tool: McpToolInfo, apiName: String): Boolean =
        apiName == encode(tool) || apiName == legacy(tool)

    fun isValid(apiName: String): Boolean = apiName.length <= MAX_LENGTH && allowedName.matches(apiName)

    private fun safePart(raw: String, maxLength: Int, fallback: String): String {
        val sanitized = buildString(raw.length) {
            raw.forEach { char ->
                append(
                    if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '-' || char == '_') {
                        char
                    } else {
                        '_'
                    },
                )
            }
        }.replace(Regex("_+"), "_").trim('_', '-')
        return sanitized.ifBlank { fallback }.take(maxLength)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
