package top.wanxiang.app.harness.projection

import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.HarnessTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 会话抽屉 / 状态点所展示的"正在执行什么"动作描述。 */
object ToolStatusDescriber {
    const val MAX_STATUS_ARG_LENGTH = 60

    private fun arg(args: JsonObject, name: String): String? =
        runCatching { args[name]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

    fun describe(tool: HarnessTool, args: JsonObject, rawToolName: String? = null): String = when (tool) {
        HarnessTool.BASE -> {
            val command = arg(args, "command")?.lineSequence()?.first()?.trim().orEmpty()
            if (command.isEmpty()) "执行命令" else "执行命令：${command.take(MAX_STATUS_ARG_LENGTH)}"
        }
        HarnessTool.PROCESS -> "管理后台进程：${arg(args, "action") ?: "process"}${arg(args, "id")?.let { " · ${it.take(MAX_STATUS_ARG_LENGTH)}" }.orEmpty()}"
        HarnessTool.HOST -> {
            val action = arg(args, "action") ?: "host"
            when (action) {
                "screen_observe" -> "正在感知屏幕控件与前台应用…"
                "screen_click" -> "正在模拟点击屏幕坐标 (${arg(args, "x")}, ${arg(args, "y")})…"
                "screen_swipe" -> "正在滑动屏幕 (${arg(args, "x1")}, ${arg(args, "y1")}) ➔ (${arg(args, "x2")}, ${arg(args, "y2")})…"
                "screen_input_text" -> "正在向当前输入框打字：${arg(args, "text")?.take(20)}…"
                "screen_key" -> "正在触发系统按键：${arg(args, "key")}…"
                "app_launch" -> "正在调起宿主应用：${arg(args, "package")}…"
                "screen_capture" -> "正在截取屏幕画面…"
                else -> "正在使用宿主权限：$action${arg(args, "command")?.let { " · ${it.take(MAX_STATUS_ARG_LENGTH)}" }.orEmpty()}"
            }
        }
        HarnessTool.DOWNLOAD -> arg(args, "destination")?.let { "下载文件：${it.takeLast(MAX_STATUS_ARG_LENGTH)}" } ?: "下载文件"
        HarnessTool.READ -> arg(args, "path")?.let { "读取文件：${it.takeLast(MAX_STATUS_ARG_LENGTH)}" } ?: "读取文件"
        HarnessTool.WRITE -> arg(args, "path")?.let { "写入文件：${it.takeLast(MAX_STATUS_ARG_LENGTH)}" } ?: "写入文件"
        HarnessTool.EDIT -> arg(args, "path")?.let { "编辑文件：${it.takeLast(MAX_STATUS_ARG_LENGTH)}" } ?: "编辑文件"
        HarnessTool.MEMORY -> "正在存取长期记忆：${arg(args, "key") ?: arg(args, "action") ?: "memory"}"
        HarnessTool.PLAN -> "正在更新任务执行规划：${arg(args, "goal") ?: arg(args, "action") ?: "plan"}"
        HarnessTool.SCRATCHPAD -> "正在记录工作草稿便签：${arg(args, "key") ?: arg(args, "action") ?: "scratchpad"}"
        HarnessTool.HISTORY_SEARCH -> "正在检索历史消息：${arg(args, "query")?.take(MAX_STATUS_ARG_LENGTH).orEmpty()}"
        HarnessTool.HISTORY_READ -> "正在读取历史消息：${arg(args, "message_id") ?: arg(args, "index") ?: "history"}"
        HarnessTool.BUILD_SCRIPT -> "正在管理构建脚本：${arg(args, "action") ?: "build_script"}${arg(args, "name")?.let { " · $it" }.orEmpty()}"
        HarnessTool.SUBAGENT -> "正在派发并执行子智能体协同任务…"
        HarnessTool.MCP -> "正在调用 MCP 插件工具：${rawToolName ?: "mcp"}…"
        HarnessTool.LOAD_RULE -> "正在加载规则块：${arg(args, "rule") ?: "load_rule"}…"
    }
}
