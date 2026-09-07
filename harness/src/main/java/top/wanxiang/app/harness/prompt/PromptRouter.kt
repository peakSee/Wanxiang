package top.wanxiang.app.harness.prompt

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统提示词分层路由器（L2 任务规则选择）。
 *
 * L0（core / tools）与 L1（environment-proot / 权限章节）常驻；
 * 本路由器根据最新用户消息、项目类型与当前状态，决定本轮额外注入哪些 L2 规则块。
 *
 * 路由判断保守宁滥勿缺：多注入一个块的成本远小于模型在缺少规则时犯错；
 * 若代码层判断不确定，模型仍可通过 load_rule 工具按需自取。
 */
@Singleton
class PromptRouter @Inject constructor(
    private val promptAssets: PromptAssetLoader,
) {
    /** 可按需加载的规则块。[assetPath] 为 assets 内路径，[loadName] 为 load_rule 工具使用的标识。 */
    enum class RuleBlock(val assetPath: String, val loadName: String) {
        TOOLS("prompts/system/tools.md", "tools"),
        ENVIRONMENT_PROOT("prompts/system/environment-proot.md", "environment-proot"),
        WORKFLOW("prompts/system/workflow.md", "workflow"),
        CODE_NAVIGATION("prompts/system/code-navigation.md", "code-navigation"),
        SECURITY("prompts/system/security.md", "security"),
        MEMORY("prompts/system/memory.md", "memory"),
    }

    /** 每轮常驻注入的规则块（L0 工具说明 + L1 PRoot 约束）。 */
    val permanentBlocks: List<RuleBlock> = listOf(RuleBlock.TOOLS, RuleBlock.ENVIRONMENT_PROOT)

    /**
     * 根据当前任务上下文选择本轮需要注入的 L2 规则块（不含常驻块）。
     *
     * @param latestUserMessage 最新一条用户消息文本（用于启发式判断）
     * @param projectType 检测到的项目类型（Android / Flutter / Android APK 逆向 / 通用工程）
     * @param hasWorkspace 是否关联了工作区
     * @param activePlanExists 当前是否已有进行中的 plan
     */
    fun route(
        latestUserMessage: String,
        projectType: String,
        hasWorkspace: Boolean,
        activePlanExists: Boolean,
    ): Set<RuleBlock> {
        val blocks = mutableSetOf<RuleBlock>()
        val text = latestUserMessage.lowercase()

        // 代码项目默认注入代码导航；通用工程仅在消息出现强代码信号时注入。
        val codeProject = projectType == "Android" || projectType == "Flutter" || projectType == "Android APK 逆向"
        if (codeProject || (hasWorkspace && CODE_SIGNALS.any { it in text })) {
            blocks.add(RuleBlock.CODE_NAVIGATION)
        }

        // 复杂任务信号或已有活动 plan 时注入工作流规则。
        if (activePlanExists || WORKFLOW_SIGNALS.any { it in text }) {
            blocks.add(RuleBlock.WORKFLOW)
        }

        // 高风险操作信号注入安全确认规则。
        if (SECURITY_SIGNALS.any { it in text }) {
            blocks.add(RuleBlock.SECURITY)
        }

        // 明确的记忆意图注入记忆策略。
        if (MEMORY_SIGNALS.any { it in text }) {
            blocks.add(RuleBlock.MEMORY)
        }

        return blocks
    }

    /** load_rule 工具入口：按名称读取规则块正文；未知名称返回 null。 */
    fun loadRule(name: String): String? {
        val block = RuleBlock.entries.firstOrNull { it.loadName == name.trim().lowercase() } ?: return null
        return runCatching { promptAssets.read(block.assetPath) }.getOrNull()
    }

    companion object {
        private val CODE_SIGNALS = listOf(
            "重构", "调用链", "调用方", "被调用", "callee", "caller", "影响面", "符号",
            "函数", "方法", "类定义", "接口定义", "定义在哪", "代码分析", "架构", "源码",
            "编译失败", "编译报错", "崩溃", "crash", "stacktrace", "堆栈", "异常",
            "retrofit", "room", "compose", "activity", "fragment", "gradle", "codegraph",
            "refactor", "where is", "call chain", "implementation of",
        )

        private val WORKFLOW_SIGNALS = listOf(
            "排错", "排查", "调试", "debug", "报错", "错误", "失败", "崩溃", "crash",
            "异常", "exception", "stacktrace", "堆栈", "逆向", "反编译", "重构",
            "构建失败", "编译失败", "安装并", "配置并", "部署", "为什么不", "不工作",
            "doesn't work", "does not work", "not working", "troubleshoot", "fix",
            "一步步", "多步", "复杂", "规划", "计划", "plan",
            "实现", "开发", "编写", "创建", "构建", "设计", "新增", "添加",
            "爬虫", "爬取", "批量", "调研", "方案", "迁移", "集成", "重写", "改造", "优化",
            "implement", "develop", "create", "build", "design", "crawler", "scrape", "batch", "migrate", "integrate",
        )

        private val SECURITY_SIGNALS = listOf(
            "删除", "删掉", "清空", "清除", "卸载", "移除", "格式化", "重置", "冻结",
            "rm -rf", "wipe", "uninstall", "format", "delete all", "drop table",
        )

        private val MEMORY_SIGNALS = listOf(
            "记住", "记一下", "以后都", "以后默认", "我的偏好", "这是规范",
            "remember", "my preference",
        )
    }
}
