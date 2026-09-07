package top.wanxiang.app.harness

/**
 * HarnessMessage ↔ OpenAI 兼容 API 消息的纯转换逻辑（无依赖，便于测试）。
 */
internal object HarnessApiMapper {
    fun toApiMessage(message: HarnessMessage): ApiMessage = when (message) {
        is CapabilityEvent -> ApiMessage(role = "system", content = null)
        is UserMessage -> ApiMessage(role = "user", content = message.text, imageUrls = message.imageUrls)
        is AssistantText -> ApiMessage(
            role = "assistant",
            content = message.text,
            reasoning_content = message.reasoning,
        )
        is ToolCall -> ApiMessage(
            role = "assistant",
            content = null,
            reasoning_content = message.reasoning,
            tool_calls = listOf(
                ApiToolCall(
                    id = message.id,
                    function = ApiFunctionCall(
                        name = message.rawToolName ?: apiName(message.tool),
                        arguments = message.args.toString(),
                    ),
                ),
            ),
        )
        is ToolResult -> ApiMessage(
            role = "tool",
            content = message.output,
            tool_call_id = message.toolCallId,
        )
    }

    /** LLM 返回的函数名 → HarnessTool。未知工具统一归入 base 由执行层报错。 */
    fun toolByName(name: String): HarnessTool {
        val trimmed = name.trim()
        val lower = trimmed.lowercase()
        return when {
            lower == "read" -> HarnessTool.READ
            lower == "write" -> HarnessTool.WRITE
            lower == "edit" -> HarnessTool.EDIT
            lower == "process" -> HarnessTool.PROCESS
            lower == "host" -> HarnessTool.HOST
            lower == "download" -> HarnessTool.DOWNLOAD
            lower == "memory" -> HarnessTool.MEMORY
            lower == "plan" -> HarnessTool.PLAN
            lower == "scratchpad" -> HarnessTool.SCRATCHPAD
            lower == "history.search" || lower == "history_search" -> HarnessTool.HISTORY_SEARCH
            lower == "history.read" || lower == "history_read" -> HarnessTool.HISTORY_READ
            lower == "build_script" -> HarnessTool.BUILD_SCRIPT
            lower == "invoke_subagent" || lower == "subagent" || lower == "invoke_dual_agent" -> HarnessTool.SUBAGENT
            lower == "load_rule" -> HarnessTool.LOAD_RULE
            trimmed.startsWith("mcp__") -> HarnessTool.MCP
            else -> HarnessTool.BASE
        }
    }

    fun apiName(tool: HarnessTool): String = when (tool) {
        HarnessTool.READ -> "read"
        HarnessTool.WRITE -> "write"
        HarnessTool.EDIT -> "edit"
        HarnessTool.BASE -> "base"
        HarnessTool.PROCESS -> "process"
        HarnessTool.HOST -> "host"
        HarnessTool.DOWNLOAD -> "download"
        HarnessTool.MEMORY -> "memory"
        HarnessTool.PLAN -> "plan"
        HarnessTool.SCRATCHPAD -> "scratchpad"
        HarnessTool.HISTORY_SEARCH -> "history_search"
        HarnessTool.HISTORY_READ -> "history_read"
        HarnessTool.BUILD_SCRIPT -> "build_script"
        HarnessTool.SUBAGENT -> "invoke_subagent"
        HarnessTool.MCP -> "mcp"
        HarnessTool.LOAD_RULE -> "load_rule"
    }
}
