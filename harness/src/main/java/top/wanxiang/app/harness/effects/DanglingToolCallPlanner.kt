package top.wanxiang.app.harness.effects

import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.harness.operation.ReplayPolicy

/**
 * 为所有尚无 [ToolResult] 的悬空 [ToolCall] 生成修复计划（策略与执行分离，
 * 便于单元测试）：
 *
 * - 用户主动停止（interrupted=true）：一律写中断占位，绝不重放。
 * - 新运行开始时的历史修复（interrupted=false；能走到这里的悬空调用只可能来自
 *   进程死亡残留——用户取消的运行在取消处理器里已用 interrupted=true 修复）：
 *   SAFE 工具（read / history 查询类只读操作）计划为重放执行，无缝续接上一轮；
 *   NEVER 工具（写操作 / 外部副作用）写占位结果交由模型重新发起——
 *   防止崩溃前的写操作在未经用户确认的情况下被再次执行。
 */
object DanglingToolCallPlanner {

    sealed interface RepairAction {
        val call: ToolCall
    }

    /** 可自动重放的 SAFE 只读工具（仅进程死亡残留场景），由外层负责执行与落盘。 */
    data class Replay(override val call: ToolCall) : RepairAction

    /** 写占位结果说明原因，不再次执行。 */
    data class Stubbed(override val call: ToolCall, val note: String) : RepairAction

    fun plan(messages: List<HarnessMessage>, interrupted: Boolean): List<RepairAction> {
        val answeredIds = messages.filterIsInstance<ToolResult>().mapTo(mutableSetOf()) { it.toolCallId }
        return messages.filterIsInstance<ToolCall>()
            .filter { it.id !in answeredIds }
            .map { call ->
                val replayable = !interrupted &&
                    ToolReplayPolicy.forTool(call.tool, call.rawToolName) == ReplayPolicy.SAFE
                if (replayable) {
                    Replay(call)
                } else {
                    val note = if (interrupted) {
                        "用户停止了本次执行，工具被中断。"
                    } else {
                        "工具执行期间应用进程中断。该工具不可自动重放，未再次执行；如仍需要请重新发起。"
                    }
                    Stubbed(call, note)
                }
            }
    }
}
