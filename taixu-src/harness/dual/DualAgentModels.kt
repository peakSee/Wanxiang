package top.wkbin.taixu.harness.dual

import kotlinx.serialization.Serializable

/**
 * 规划者（Planner）生成的单步任务。
 */
@Serializable
data class PlanStep(
    val id: String,
    val title: String,
    val instruction: String,
    val expectedOutcome: String = "",
    val dependencies: List<String> = emptyList(),
    val status: StepStatus = StepStatus.PENDING,
    val resultSummary: String? = null,
) {
    /**
     * 判断当前步骤是否就绪（前置依赖的所有步骤均已 COMPLETED）。
     */
    fun isReady(completedStepIds: Set<String>): Boolean =
        status == StepStatus.PENDING && dependencies.all { it in completedStepIds }
}

@Serializable
enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}

/**
 * 执行者（Executor）在独立物理 Lane 完成单步工具执行后的紧凑汇报。
 * 绝不把原生几万字符的工具日志回传给 Planner，只保留精炼摘要与关键产物。
 */
@Serializable
data class StepExecutionResult(
    val stepId: String,
    val success: Boolean,
    val summary: String,
    val diffStat: String? = null,
    val toolCallsCount: Int = 0,
    val durationMs: Long = 0L,
)

/**
 * Planner 面对当前执行进度做出的下一步决策。
 */
sealed interface PlannerDecision {
    /** 首轮或全局多步计划初始化 */
    data class InitializePlan(
        val thought: String = "",
        val plan: List<PlanStep>,
    ) : PlannerDecision

    /** 计划已就绪或继续执行下一单步 */
    data class ExecuteStep(
        val step: PlanStep,
        val updatedPlan: List<PlanStep>,
    ) : PlannerDecision

    /** 某步执行遇到不可逆偏差，局部调整后续计划 */
    data class Replan(
        val reason: String,
        val newSteps: List<PlanStep>,
    ) : PlannerDecision

    /** 全部步骤已通过验收，交付最终结论 */
    data class Finish(
        val finalReport: String,
        val completedSteps: List<PlanStep>,
    ) : PlannerDecision
}

/**
 * 双智能体协同运行的最终输出。
 */
sealed interface DualAgentOutcome {
    data class Success(
        val finalReport: String,
        val plan: List<PlanStep>,
        val totalRounds: Int,
        val totalToolCalls: Int,
        val totalDurationMs: Long,
    ) : DualAgentOutcome

    data class Failed(
        val message: String,
        val plan: List<PlanStep>,
        val failedStep: PlanStep?,
    ) : DualAgentOutcome
}
