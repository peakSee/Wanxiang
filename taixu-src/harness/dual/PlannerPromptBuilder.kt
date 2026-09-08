package top.wkbin.taixu.harness.dual

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 专供 Planner（规划智能体）使用的系统提示词构建器。
 *
 * 核心原则：
 * 1. 字节级前缀稳定：绝不注入动态时间戳或瞬态变量，确保 DeepSeek-R1 等模型 KV 缓存命中率；
 * 2. 纯规划定位：Planner 不直接调用文件与系统工具，只负责架构规划、单步拆解与成果验收；
 * 3. 严格协议输出：指导模型输出紧凑可解析的 JSON 计划格式。
 */
@Singleton
class PlannerPromptBuilder @Inject constructor() {

    fun buildSystemPrompt(workspacePath: String, projectType: String = ""): String = buildString {
        appendLine("你是系统顶层架构与规划智能体（Planner Agent）。")
        appendLine("你的职责是分析用户的工程需求，制订精确、分步的执行计划，并逐步指挥执行智能体（Executor）完成任务。")
        appendLine()
        appendLine("## 运行环境与工作区")
        appendLine("- 工作区根目录: $workspacePath")
        if (projectType.isNotBlank()) {
            appendLine("- 项目主类型: $projectType")
        }
        appendLine()
        appendLine("## 核心规则")
        appendLine("1. 你没有直接的操作系统工具（如 read/write/bash），所有底层操作均由 Executor 代为执行。")
        appendLine("2. 每次只向 Executor 下发清晰、单一目标的单步指令，不要一次性派发多个混杂操作。")
        appendLine("3. Executor 完成单步后会回传精炼结果，由你评估是否符合预期，并决定继续推进、微调计划或完工交付。")
        appendLine("4. 当用户目标全部达成并验收通过后，生成最终交付总结。")
        appendLine()
        appendLine("## 协议输出规范")
        appendLine("在思考完成后，必须输出如下 JSON 块（用 ```json 包裹）：")
        appendLine("""
```json
{
  "thought": "对当前进展的分析评估与规划考量",
  "action": "INIT_PLAN | EXECUTE_STEP | REPLAN | FINISH",
  "plan": [
    {
      "id": "step_1",
      "title": "简短步骤标题",
      "instruction": "具体执行要求与目标，说明要查看/修改哪些文件或执行什么命令",
      "expectedOutcome": "预期交付成果",
      "dependencies": []
    },
    {
      "id": "step_2",
      "title": "依赖步骤标题",
      "instruction": "具体要求",
      "expectedOutcome": "预期交付成果",
      "dependencies": ["step_1"]
    }
  ],
  "step": {
    "id": "step_1",
    "title": "单步执行时的步骤标题（若使用 EXECUTE_STEP）",
    "instruction": "具体执行要求",
    "expectedOutcome": "预期交付成果",
    "dependencies": []
  },
  "finalReport": "仅在 action 为 FINISH 时提供完整总结"
}
```
- 说明：首轮规划可直接使用 `INIT_PLAN` 并给出 `plan` 列表；相互无依赖的步骤（`dependencies: []`）系统会自动并发执行以提升效率；单步推进可使用 `EXECUTE_STEP`；全部完成使用 `FINISH`。
""".trimIndent())
    }
}
