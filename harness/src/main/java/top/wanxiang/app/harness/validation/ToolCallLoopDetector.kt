package top.wanxiang.app.harness.validation

import kotlinx.serialization.json.JsonObject

/**
 * 智能体工具调用死循环与重复调用检测器。
 *
 * 专门防御以下异常行为：
 * 1. 同一工具使用完全相同的参数连续失败多次（盲目重试而不纠错）；
 * 2. 相同工具调用序列在多轮对话中反复空转（无论成功与否，没有取得实质性进展）；
 * 3. 参数校验连续失败却不按 Schema 修正。
 */
class ToolCallLoopDetector(
    private val maxSameCallFailures: Int = 2,
    private val maxIdenticalCallsStreak: Int = 3,
) {
    private val callHistory = mutableListOf<CallRecord>()

    data class CallRecord(
        val toolName: String,
        val argsJson: String,
        var success: Boolean? = null,
        val timestamp: Long = System.currentTimeMillis(),
    )

    sealed interface LoopVerdict {
        data object Pass : LoopVerdict
        data class Warn(val message: String) : LoopVerdict
        data class Block(val reason: String, val guidance: String) : LoopVerdict
    }

    /**
     * 在工具执行前进行循环检测。
     *
     * @param toolName 工具 API 名称
     * @param args 工具入参
     * @return 检测裁决结果
     */
    @Synchronized
    fun evaluate(toolName: String, args: JsonObject): LoopVerdict {
        val currentArgsJson = args.toString()
        val recentCalls = callHistory.takeLast(10)

        // 1. 检测连续相同调用的失败历史
        val sameFailedStreak = recentCalls.reversed().takeWhile { record ->
            record.toolName.equals(toolName, ignoreCase = true) &&
                record.argsJson == currentArgsJson &&
                record.success == false
        }.count()

        if (sameFailedStreak >= maxSameCallFailures) {
            return LoopVerdict.Block(
                reason = "检测到死循环：工具 `$toolName` 已使用完全相同的参数连续执行失败 $sameFailedStreak 次",
                guidance = buildString {
                    append("【已强制拦截该重复调用并要求反思】\n")
                    append("你正在反复发起完全相同的失败操作：`$toolName($currentArgsJson)`。\n")
                    append("这是典型的死循环行为，已直接阻断执行。\n\n")
                    append("请立即执行以下反思与策略变更：\n")
                    append("1. **定位根因**：仔细阅读前述错误输出，分析上几次失败的具体原因（路径错误？内容不匹配？参数缺失？缺少依赖？）；\n")
                    append("2. **严禁重试**：严禁再次发起完全相同的调用；\n")
                    append("3. **改变策略**：\n")
                    append("   - 如果是 `edit` 匹配失败：必须先调用 `read` 查看文件的最新精确内容与行号；\n")
                    append("   - 如果是 `base` 命令失败：调用 `read`/`base` 检查环境、路径或查看日志，或切换实现方式；\n")
                    append("   - 如果缺少工具或能力：向用户明确说明当前遇到的障碍与已完成的步骤。")
                },
            )
        }

        // 2. 检测完全相同的调用连续出现（即使成功也可能是无进展空转）
        val identicalStreak = recentCalls.reversed().takeWhile { record ->
            record.toolName.equals(toolName, ignoreCase = true) &&
                record.argsJson == currentArgsJson
        }.count()

        if (identicalStreak >= maxIdenticalCallsStreak) {
            return LoopVerdict.Block(
                reason = "检测到重复空转：工具 `$toolName` 已连续执行 $identicalStreak 次且无状态变化",
                guidance = buildString {
                    append("【已强制拦截该无进展重复调用】\n")
                    append("你已连续 $identicalStreak 次执行完全相同的操作 `$toolName`，系统检测到任务陷入停滞空转。\n")
                    append("请跳出当前循环，总结已获取的信息，推进到下一阶段或向用户汇报。")
                },
            )
        }

        // 3. 检测多工具交替震荡死循环（例如 A -> B -> A -> B 或 A -> B -> C -> A -> B -> C）
        val simulatedHistory = recentCalls.map { "${it.toolName.lowercase()}:${it.argsJson}" } + "${toolName.lowercase()}:$currentArgsJson"
        for (period in 2..3) {
            if (simulatedHistory.size >= period * 3) {
                val cycle1 = simulatedHistory.takeLast(period)
                val cycle2 = simulatedHistory.dropLast(period).takeLast(period)
                val cycle3 = simulatedHistory.dropLast(period * 2).takeLast(period)
                if (cycle1 == cycle2 && cycle2 == cycle3) {
                    return LoopVerdict.Block(
                        reason = "检测到交替震荡死循环：工具调用序列以周期 $period 反复交替循环了 3 次",
                        guidance = buildString {
                            append("【已强制拦截交替震荡死循环】\n")
                            append("你正在反复交替执行相同的操作序列（周期 $period）。\n")
                            append("系统检测到任务陷入来回摆动的震荡死循环，已直接阻断执行。\n\n")
                            append("请立即执行以下反思：\n")
                            append("1. 为什么上一个步骤的结果无法推进任务？\n")
                            append("2. 立即停止当前循环模式，切换到新的解决思路或向用户汇报当前阻碍。")
                        },
                    )
                }
            }
        }

        return LoopVerdict.Pass
    }

    /**
     * 登记单次工具调用的开始。
     */
    @Synchronized
    fun recordIntent(toolName: String, args: JsonObject) {
        callHistory.add(CallRecord(toolName = toolName, argsJson = args.toString()))
        // 限制历史记录上限
        if (callHistory.size > 50) {
            callHistory.removeAt(0)
        }
    }

    /**
     * 记录单次工具调用的结算结果。
     */
    @Synchronized
    fun recordSettled(toolName: String, args: JsonObject, success: Boolean) {
        val currentArgsJson = args.toString()
        val lastRecord = callHistory.lastOrNull {
            it.toolName.equals(toolName, ignoreCase = true) && it.argsJson == currentArgsJson && it.success == null
        }
        if (lastRecord != null) {
            lastRecord.success = success
        } else {
            callHistory.add(CallRecord(toolName = toolName, argsJson = currentArgsJson, success = success))
        }
    }

    /**
     * 清空当前检测器的历史（在用户发送新消息或会话重置时调用）。
     */
    @Synchronized
    fun reset() {
        callHistory.clear()
    }
}
