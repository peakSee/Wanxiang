package top.wanxiang.app.core.model

import kotlinx.serialization.Serializable

/**
 * MCP (Model Context Protocol) 传输协议类型
 */
@Serializable
enum class McpTransportType {
    STDIO,  // 在 Linux 沙箱内通过标准输入输出与进程交互
    /**
     * 远程 HTTP 服务。客户端按 URL 自动协商：URL 不以 /sse 结尾时先 Streamable HTTP
     * （POST /mcp 请求-响应），失败再回落 legacy HTTP+SSE；服务端只需实现其一。
     */
    SSE,
}
/**
 * MCP 服务配置
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val description: String = "",
    val transportType: McpTransportType = McpTransportType.STDIO,
    /** 沙箱内执行命令（例如 "npx" 或 "python3"） */
    val command: String = "",
    /** 命令行参数列表（例如 ["-y", "@modelcontextprotocol/server-sqlite", "--db-path", "/workspace/app.db"]） */
    val args: List<String> = emptyList(),
    /** 环境变量 */
    val env: Map<String, String> = emptyMap(),
    /** 远程 MCP 服务的 HTTP 端点 URL（例如 http://127.0.0.1:8000/mcp 或 .../sse） */
    val serverUrl: String = "",
    /**
     * 远程 HTTP MCP 服务的 Bearer token（发送 `Authorization: Bearer <token>`）。
     * 运行时注入用（例如内置 browser server 的自环凭据）：@Transient 保证不参与序列化，
     * 既不落 Room 也不随 [toExportJsonConfig] 导出。
     */
    @kotlinx.serialization.Transient
    val authToken: String = "",
    /** 是否启用 */
    val isEnabled: Boolean = true,
    /** 是否为内置预设服务 */
    val isBuiltin: Boolean = false,
    /** 内置 server 默认风险等级（如 "low" / "medium" / "high" / "critical"），MCP bootstrap 据此判定审批粒度。 */
    val builtinRisk: String = "low",
    /** 内置 server bootstrap 优先级。< 0 表示排在所有用户 MCP server 之前；>= 0 表示标准用户配置。 */
    val bootstrapOrder: Int = if (isBuiltin) -1 else 0,

) {
    /** 导出为 mcpServers JSON 配置片段（键为服务 id）；配置格式约定归模型层，View 只读展示 */
    fun toExportJsonConfig(): String = buildString {
        appendLine("{")
        appendLine("  \"$id\": {")
        if (transportType == McpTransportType.STDIO) {
            appendLine("    \"command\": \"$command\",")
            appendLine("    \"args\": [${args.joinToString(", ") { "\"$it\"" }}]")
            if (env.isNotEmpty()) {
                appendLine("    \"env\": {")
                env.entries.forEachIndexed { i, (k, v) ->
                    val comma = if (i == env.size - 1) "" else ","
                    appendLine("      \"$k\": \"$v\"$comma")
                }
                appendLine("    }")
            }
        } else {
            appendLine("    \"url\": \"$serverUrl\"")
        }
        appendLine("  }")
        append("}")
    }
}

/**
 * MCP 服务连通性检测状态（运行时状态，不持久化）
 */
enum class McpConnectionState {
    /** 尚未检测（默认态 / 服务未启用） */
    UNKNOWN,
    /** 正在检测连通性 */
    CHECKING,
    /** 已连通，服务在线可用 */
    ONLINE,
    /** 检测失败 / 服务离线不可达 */
    OFFLINE,
}

/**
 * 动态从 MCP Server 发现并注册的工具定义
 */
@Serializable
data class McpToolInfo(
    val serverId: String,
    val serverName: String,
    val name: String,
    val description: String,
    val parametersJson: String = "{}",
)

/**
 * 子智能体任务规格参数
 */
@Serializable
data class SubagentTaskSpec(
    val taskName: String,
    /** Optional exact profile id/name override retained for persisted and legacy calls. */
    val role: String = "",
    val prompt: String,
    /** Built-in department id used to constrain local catalog matching. */
    val department: String = "",
    /** Short English professional keywords matched against id/name/description locally. */
    val agentQuery: String = "",
    /** 子任务声明的写目标文件/目录。空列表 = 只读并行；["*"] = 整工作区独占写租约。 */
    val writePaths: List<String> = emptyList(),
    /** 可选：已保存模型档案的 ID/名称，或该档案中已配置的具体模型名；null/"inherit" 继承父会话。 */
    val model: String? = null,
)

/**
 * 内置 MCP 预设服务
 */
object BuiltinMcpPresets {
    /** 内置 in-app browser MCP server 的 id：自环 Bearer 认证与浏览器工具审批白名单都以它为准。 */
    const val BROWSER_BUILTIN_ID = "wanxiang-browser-builtin"

    val presets: List<McpServerConfig> = listOf(
        McpServerConfig(
            id = "mcp_sqlite",
            name = "SQLite 数据库探索器",
            description = "查询、分析与操作沙箱或工作区内的 SQLite 数据库文件",
            transportType = McpTransportType.STDIO,
            command = "python3",
            args = listOf("-u", "/opt/wanxiang/scripts/sqlite_mcp_server.py", "--db-path", "/root/wanxiang.db"),
            isEnabled = true,
            isBuiltin = true,
        ),
        McpServerConfig(
            id = "mcp_git",
            name = "Git 仓库协同中心",
            description = "深入分析 Git 历史提交、分支拓扑、Diff 差异与工作区状态",
            transportType = McpTransportType.STDIO,
            command = "python3",
            args = listOf("-u", "/opt/wanxiang/scripts/git_mcp_server.py", "--repository", "/workspace"),
            isEnabled = true,
            isBuiltin = true,
        ),
        McpServerConfig(
            id = "mcp_apktool",
            name = "Android 逆向与 APK 审计",
            description = "自动化反编译 APK、解析清单权限、提取硬编码凭据、Smali 敏感代码检索与重打包签名（万象内置 MCP 服务，依赖 android-suite 的 apktool/jadx/aapt 工具链）",
            transportType = McpTransportType.STDIO,
            command = "python3",
            args = listOf("-u", "/opt/wanxiang/scripts/apktool_mcp_server.py"),
            isEnabled = true,
            isBuiltin = true,
        ),
        McpServerConfig(
            id = "mcp_codegraph",
            name = "CodeGraph 代码知识图谱",
            description = "毫秒级索引工作区符号定义、调用链与影响面（支持 Python/Java/Kotlin/C/C++/JS/TS/Smali），大幅减少代码探索的 Token 与工具调用轮次",
            transportType = McpTransportType.STDIO,
            command = "python3",
            args = listOf("-u", "/opt/wanxiang/scripts/codegraph_mcp_server.py", "--repository", "/workspace"),
            isEnabled = true,
            isBuiltin = true,
        ),
        McpServerConfig(
            id = "mcp_websearch",
            name = "Web 搜索（Open-WebSearch）",
            description = "免 API Key 的多引擎网络搜索与网页正文抓取（万象内置零依赖 MCP 服务）：支持 Baidu、Bing、DuckDuckGo 等多引擎直连搜索与正文清洗提取，毫秒级响应，无需 Node.js/npx，开箱即用",
            transportType = McpTransportType.STDIO,
            command = "python3",
            args = listOf("-u", "/opt/wanxiang/scripts/websearch_mcp_server.py"),
            env = mapOf(
                "DEFAULT_SEARCH_ENGINE" to "baidu",
            ),
            isEnabled = true,
            isBuiltin = true,
        ),
        McpServerConfig(
            id = BROWSER_BUILTIN_ID,
            name = "WanXiang Browser (Built-in)",
            description = "WanXiang 内置 in-app WebView 的 MCP server（in-process loopback 127.0.0.1:8787，Streamable HTTP POST /mcp 请求-响应，Bearer 认证）；由 harness 层 BrowserMcpBootstrap 启动，暴露 mcp__browser__* 工具给 Agent 与外接 IDE。",
            transportType = McpTransportType.SSE,
            serverUrl = "http://127.0.0.1:8787/mcp",
            isEnabled = true,
            isBuiltin = true,
            builtinRisk = "medium",
            bootstrapOrder = -100,
        ),
    )
}
