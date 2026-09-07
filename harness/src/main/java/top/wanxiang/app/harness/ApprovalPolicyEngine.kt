package top.wanxiang.app.harness

import top.wanxiang.app.core.database.AgentApprovalRequestEntity
import top.wanxiang.app.core.model.ApprovalMode
import top.wanxiang.app.core.model.BuiltinMcpPresets
import top.wanxiang.app.core.model.McpToolInfo
import top.wanxiang.app.harness.mcp.McpToolApiName
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

import javax.inject.Inject
import javax.inject.Singleton

data class ApprovalDecision(
    val required: Boolean,
    val riskLevel: String = "low",
    val reason: String = "",
    val summary: String = "",
)

/** Host-side policy. The model prompt is deliberately not part of this decision. */
@Singleton
class ApprovalPolicyEngine @Inject constructor(
    private val pathResolver: HarnessPathResolver,
) {
    /**
     * 内置 browser server 在工具 API 名里可能出现的 server 段集合：
     * 编码名 `mcp__<server 前 16 字符>__<tool>__<hash>` 与 legacy 名 `mcp__<完整 server id>__<tool>`。
     * 借 [McpToolApiName] 生成，保证与 harness 实际编码规则一致。
     */
    private val builtinBrowserServerSegments: Set<String> by lazy {
        val probe = McpToolInfo(
            serverId = BuiltinMcpPresets.BROWSER_BUILTIN_ID,
            serverName = BuiltinMcpPresets.BROWSER_BUILTIN_ID,
            name = "browser_snapshot",
            description = "",
        )
        setOf(McpToolApiName.encode(probe), McpToolApiName.legacy(probe))
            .map { it.substringAfter("__").substringBefore("__") }
            .toSet()
    }

    fun decide(
        mode: ApprovalMode,
        tool: HarnessTool,
        args: JsonObject,
        workspace: String,
        rawToolName: String? = null,
    ): ApprovalDecision {
        // 宿主特权命令作用于真实 Android 系统。只读操作始终放行；
        // 完全访问 = 用户显式授权一切宿主操作（含 exec / 卸载应用），全部自动放行；
        // 仅 REQUEST（每次审批）与 ASSISTED 模式对可变宿主操作要求确认。
        if (tool == HarnessTool.HOST) {
            val action = args["action"]?.jsonPrimitive?.content.orEmpty().trim().lowercase()
            if (action in HOST_READ_ONLY_ACTIONS) {
                return ApprovalDecision(false)
            }
            if (mode == ApprovalMode.FULL_ACCESS) {
                return ApprovalDecision(false)
            }
            val critical = action == "exec" || action == "package_uninstall_user"
            return ApprovalDecision(
                required = true,
                riskLevel = if (critical) "critical" else "high",
                reason = when (action) {
                    "settings_put" -> "操作将修改真实 Android 系统设置。"
                    "package_disable", "package_enable", "app_freeze", "app_unfreeze" -> "操作将改变真实 Android 应用的启用状态。"
                    "app_grant_permission" -> "操作将为真实 Android 应用授予权限。"
                    "package_uninstall_user" -> "操作将为指定 Android 用户卸载应用；系统应用通常可用 install-existing 恢复，但其数据可能丢失。"
                    else -> "命令将通过 Shizuku 或 Root 修改真实 Android 宿主，可能改变系统设置、停用或卸载应用。"
                },
                summary = summarize(tool, args),
            )
        }
        if (mode == ApprovalMode.FULL_ACCESS) return ApprovalDecision(false)
        // 内置浏览器 MCP 工具按风险矩阵细化审批：只读（LOW）工具在任何模式下免审
        if (tool == HarnessTool.MCP && mcpBrowserRisk(rawToolName) == "low") {
            return ApprovalDecision(false)
        }
        if (tool == HarnessTool.READ || tool == HarnessTool.MEMORY || tool == HarnessTool.PLAN ||
            tool == HarnessTool.SCRATCHPAD || tool == HarnessTool.HISTORY_SEARCH || tool == HarnessTool.HISTORY_READ ||
            tool == HarnessTool.LOAD_RULE
        ) {
            return ApprovalDecision(false)
        }
        if (tool == HarnessTool.SUBAGENT) return ApprovalDecision(false)
        if (tool == HarnessTool.BUILD_SCRIPT && args["action"]?.jsonPrimitive?.content.orEmpty().trim().lowercase() in setOf("list", "get")) return ApprovalDecision(false)
        if (tool == HarnessTool.PROCESS && processAction(args) in setOf("status", "logs", "list")) {
            return ApprovalDecision(false)
        }

        val summary = summarize(tool, args, rawToolName)
        if (mode == ApprovalMode.REQUEST) {
            return ApprovalDecision(true, "normal", "当前权限模式要求所有会改变状态或产生外部副作用的工具操作先获得批准。", summary)
        }

        return when (tool) {
            HarnessTool.WRITE, HarnessTool.EDIT -> {
                val path = args["path"]?.jsonPrimitive?.content.orEmpty()
                if (workspace.isNotBlank() && isWithinWorkspace(path, workspace)) {
                    ApprovalDecision(false)
                } else {
                    ApprovalDecision(true, "high", "写入目标不在当前工作区内，可能影响工作区之外的文件。", summary)
                }
            }
            HarnessTool.BASE -> {
                val command = args["command"]?.jsonPrimitive?.content.orEmpty()
                if (isRoutineCommand(command)) ApprovalDecision(false)
                else ApprovalDecision(true, if (isDestructiveCommand(command)) "critical" else "high", reasonForCommand(command), summary)
            }
            HarnessTool.PROCESS -> when (processAction(args)) {
                "start" -> {
                    val command = args["command"]?.jsonPrimitive?.content.orEmpty()
                    if (isRoutineCommand(command)) ApprovalDecision(false)
                    else ApprovalDecision(true, if (isDestructiveCommand(command)) "critical" else "high", reasonForCommand(command), summary)
                }
                "stop" -> ApprovalDecision(true, "high", "停止操作会终止一个由 WanXiang 托管的后台进程。", summary)
                else -> ApprovalDecision(false)
            }
            HarnessTool.DOWNLOAD -> ApprovalDecision(true, "high", "下载会访问外部网络并写入工作区文件。", summary)
            HarnessTool.BUILD_SCRIPT -> ApprovalDecision(true, "normal", "操作将修改构建脚本或项目挂载关系。", summary)
            HarnessTool.HOST -> error("HOST 已在审批策略入口处理")
            HarnessTool.MCP -> when (mcpBrowserRisk(rawToolName)) {
                "medium" -> ApprovalDecision(true, "medium", "浏览器操作会改变页面状态或新开会话。", summary)
                "high" -> ApprovalDecision(true, "high", "浏览器操作将修改页面内容或写入本地存储。", summary)
                "critical" -> ApprovalDecision(true, "critical", "浏览器操作涉及代码执行或读取敏感数据（Cookie/页面源码）。", summary)
                else -> ApprovalDecision(true, "high", "MCP 工具可能访问外部服务或产生工作区之外的副作用。", summary)
            }
            HarnessTool.READ, HarnessTool.MEMORY, HarnessTool.PLAN, HarnessTool.SCRATCHPAD,
            HarnessTool.HISTORY_SEARCH, HarnessTool.HISTORY_READ, HarnessTool.SUBAGENT, HarnessTool.LOAD_RULE -> ApprovalDecision(false)
        }
    }

    fun createRequest(
        sessionId: String,
        toolCall: ToolCall,
        workspace: String,
        decision: ApprovalDecision,
        operationId: String? = null,
    ): AgentApprovalRequestEntity {
        val now = System.currentTimeMillis()
        return AgentApprovalRequestEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            toolCallId = toolCall.id,
            toolName = toolCall.rawToolName ?: toolCall.tool.name.lowercase(),
            argumentsJson = toolCall.args.toString(),
            workspace = workspace,
            riskLevel = decision.riskLevel,
            reason = decision.reason,
            summary = decision.summary,
            createdAt = now,
            operationId = operationId,
            argsHash = argsHash(toolCall.args.toString()),
            expiresAt = now + APPROVAL_TTL_MS,
        )
    }

    companion object {
        /** 审批有效期：超时未决的请求自动失效，恢复执行前也会复核。 */
        const val APPROVAL_TTL_MS: Long = 10 * 60 * 1000L
        private val HOST_READ_ONLY_ACTIONS = setOf("status", "settings_get", "package_list", "app_list", "logcat", "device_status")

        /** argumentsJson 的 SHA-256 十六进制摘要；创建时写入，执行前复核。 */
        fun argsHash(argumentsJson: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(argumentsJson.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    private fun summarize(tool: HarnessTool, args: JsonObject, rawToolName: String? = null): String = when (tool) {
        HarnessTool.WRITE, HarnessTool.EDIT -> "${tool.name.lowercase()} ${args["path"]?.jsonPrimitive?.content.orEmpty()}"
        HarnessTool.BASE -> args["command"]?.jsonPrimitive?.content.orEmpty().lineSequence().firstOrNull().orEmpty()
        HarnessTool.PROCESS -> "process ${processAction(args)} ${args["id"]?.jsonPrimitive?.content.orEmpty()}".trim()
        HarnessTool.DOWNLOAD -> "download ${args["destination"]?.jsonPrimitive?.content.orEmpty()}"
        HarnessTool.HOST -> "host ${args["action"]?.jsonPrimitive?.content.orEmpty()} ${args["command"]?.jsonPrimitive?.content.orEmpty().lineSequence().firstOrNull().orEmpty()}".trim()
        HarnessTool.MCP -> {
            val toolName = rawToolName?.substringAfter("__")?.substringAfter("__")?.substringBefore("__")
                ?: args["name"]?.jsonPrimitive?.content
            "MCP ${toolName ?: "工具调用"}"
        }
        HarnessTool.BUILD_SCRIPT -> "build_script ${args["action"]?.jsonPrimitive?.content.orEmpty()} ${args["name"]?.jsonPrimitive?.content.orEmpty()}".trim()
        else -> tool.name.lowercase()
    }

    /**
     * 解析 MCP 工具名（mcp__<server>__<tool>__<hash>）并映射内置浏览器工具风险档位。
     * 仅当 server 段确认为内置 browser server（编码截断段或 legacy 完整 id）时才套用浏览器风险矩阵，
     * 防止外部 MCP server 用同名工具冒充内置白名单；非内置浏览器工具返回 null。
     */
    private fun mcpBrowserRisk(rawToolName: String?): String? {
        if (rawToolName == null) return null
        val server = rawToolName.substringAfter("__").substringBefore("__")
        if (server !in builtinBrowserServerSegments) return null
        val tool = rawToolName.substringAfter("__").substringAfter("__").substringBefore("__")
        return when (tool) {
            "browser_back", "browser_forward", "browser_refresh", "browser_list_tabs", "browser_close_tab",
            "browser_snapshot", "browser_scroll", "browser_screenshot", "browser_current_url", "browser_title",
            "browser_console_list", "browser_network_list" -> "low"
            "browser_open", "browser_navigate", "browser_page_source", "browser_console_clear",
            "browser_local_keys", "browser_session_keys" -> "medium"
            "browser_click", "browser_type", "browser_press", "browser_cookies_set", "browser_cookies_delete",
            "browser_local_get", "browser_local_set", "browser_local_delete",
            "browser_session_get", "browser_session_set", "browser_session_delete" -> "high"
            "browser_evaluate", "browser_cookies_get" -> "critical"
            else -> null
        }
    }

    private fun processAction(args: JsonObject): String =
        args["action"]?.jsonPrimitive?.content.orEmpty().trim().lowercase()

    private fun isWithinWorkspace(path: String, workspace: String): Boolean =
        pathResolver.isWithinWorkspace(path, workspace)

    private fun isRoutineCommand(command: String): Boolean {
        val normalized = command.trim().lowercase()
        if (normalized.isBlank()) return false
        if (isDestructiveCommand(normalized)) return false
        // Block dynamic shell syntax before whitelist matching. Command substitution, backtick
        // expansion, eval, and nested shells can hide a second mutation behind an otherwise
        // harmless prefix (e.g. `ls $(rm -rf /tmp)`). Require explicit approval for these.
        if (hasDynamicShellSyntax(normalized)) return false
        // Auto-approval is intentionally limited to one transparent command.
        // Shell composition, substitution and redirection can hide a second mutation
        // behind an otherwise harmless prefix such as `git status`.
        if (listOf("\n", "\r", ";", "&&", "||", "|", ">", "<", "`", "$(").any { it in normalized }) return false
        if (Regex("\\bfind\\b.*\\s(-delete|-exec|-execdir)\\b").containsMatchIn(normalized)) return false
        if (Regex("\\b(curl|wget|nc|ssh|scp|adb|wanxiang-host|mount|umount|kill|pkill|chmod|chown|apt(-get)?|apk|dnf|pacman|npm\\s+(install|publish)|pip\\s+install|git\\s+(push|reset|clean))\\b").containsMatchIn(normalized)) return false
        return Regex("^(pwd|ls|find|rg|grep|head|tail|cat|git\\s+(status|diff|log|show)|gradle(w)?\\b.*(test|check|assemble)|npm\\s+(test|run\\s+(test|lint|build))|flutter\\s+(test|analyze|build)|pytest\\b|kotlinc\\b|./gradlew\\b.*(test|check|assemble))").containsMatchIn(normalized)
    }

    /**
     * Detect dynamic shell syntax patterns that could bypass whitelist-based auto-approval.
     *
     * Even if a command's prefix matches a known-safe pattern (e.g. starts with `ls`),
     * command substitution or nested-shell invocations can carry arbitrary side effects.
     * These constructs require explicit user approval regardless of the command prefix.
     *
     * Detected patterns:
     * - `$(...)` — command substitution
     * - `` `...` `` — backtick command substitution (already covered by the `\`` check in
     *   the caller, but also matched here for clarity and defence-in-depth)
     * - `eval` / `source` — delayed execution of arbitrary code
     * - `sh -c` / `bash -c` / `ksh -c` — nested shell with inline command string
     */
    private fun hasDynamicShellSyntax(command: String): Boolean {
        // Command substitution: $( or backtick (backtick also caught by caller's contains check)
        if (command.contains("\$(") || command.contains("`")) return true
        // Nested-shell invocation patterns
        if (Regex("""\b(eval|source)\b""").containsMatchIn(command)) return true
        if (Regex("""\b(sh|bash|ksh|zsh|dash)\s+-c\b""").containsMatchIn(command)) return true
        return false
    }

    private fun isDestructiveCommand(command: String): Boolean = listOf(
        Regex("\\brm\\s+-(?:[^\\s]*r[^\\s]*f|[^\\s]*f[^\\s]*r)\\b", RegexOption.IGNORE_CASE),
        Regex("\\brm\\s+-r\\b", RegexOption.IGNORE_CASE),
        Regex("\\brm\\s+-rf?\\s+--no-preserve-root"),
        Regex("\\bmkfs\\.", RegexOption.IGNORE_CASE),
        Regex("\\bdd\\s+if=.*\\bof=/dev/", RegexOption.IGNORE_CASE),
        Regex("\\b(shutdown|reboot|halt|poweroff)\\b"),
        Regex("\\btruncate\\s+-s\\s+0\\b", RegexOption.IGNORE_CASE),
        Regex(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\}"),
        Regex(">\\s*/dev/(sd|mmcblk|nvme)", RegexOption.IGNORE_CASE),
        Regex("\\bchmod\\s+-r\\s+777\\s+/", RegexOption.IGNORE_CASE),
    ).any { it.containsMatchIn(command) }

    private fun reasonForCommand(command: String): String = if (isDestructiveCommand(command)) {
        "命令匹配到删除、格式化、设备写入或关机等不可逆高危模式。"
    } else {
        "命令可能安装软件、访问外部网络、修改系统状态或影响工作区之外的资源。"
    }
}
