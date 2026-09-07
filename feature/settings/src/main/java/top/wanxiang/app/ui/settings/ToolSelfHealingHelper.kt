package top.wanxiang.app.ui.settings

/**
 * 工具安装/更新失败时，辅助生成 AI Agent 沙箱诊断与自愈 Prompt 的工具类
 */
object ToolSelfHealingHelper {
    fun buildHealingPrompt(
        toolId: String,
        toolName: String,
        errorLogs: List<String>,
    ): String = buildString {
        appendLine("【系统工具安装失败诊断与沙箱自愈任务】")
        appendLine("- 目标工具：$toolName ($toolId)")
        appendLine("- 预期安装路径：/opt/wanxiang/tools/$toolId")
        appendLine("- 关联专精能力：@Linux沙箱运维专精")
        if (errorLogs.isNotEmpty()) {
            appendLine("- 失败上下文与日志输出：")
            appendLine("```")
            errorLogs.takeLast(25).forEach { appendLine(it) }
            appendLine("```")
        }
        appendLine()
        appendLine("【Agent 自愈目标与行动指南】：")
        appendLine("1. 分析上述 PRoot 沙箱内的失败报错（如 dpkg 依赖破损、锁残留、网络下载受阻、commandLinks 软链接缺失或环境缺失）；")
        appendLine("2. 直接调用 base 工具执行针对性的修复命令（如清理 /var/lib/dpkg 锁、dpkg --configure -a、apt-get --fix-broken install、手动从备用源拉取或补齐软链接）；")
        appendLine("3. 修复完成后，在终端验证工具命令（如执行 /opt/wanxiang/bin/$toolId 或对应二进制），并在本会话中向我汇报排查原因与自愈结果。")
    }
}
