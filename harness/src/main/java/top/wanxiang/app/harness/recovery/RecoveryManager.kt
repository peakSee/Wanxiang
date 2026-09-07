package top.wanxiang.app.harness.recovery

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import top.wanxiang.app.core.database.AgentApprovalRepository
import top.wanxiang.app.core.database.HarnessRuntimeRepository
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.harness.events.HarnessEvent
import top.wanxiang.app.harness.events.HarnessEventBus
import top.wanxiang.app.harness.operation.OperationCoordinator
import top.wanxiang.app.harness.operation.OperationPhase
import top.wanxiang.app.harness.operation.OperationSnapshot
import top.wanxiang.app.harness.operation.OperationStatus
import top.wanxiang.app.harness.operation.ReplayPolicy

sealed interface RecoveryOutcome {
    data object Clean : RecoveryOutcome
    data class Suspended(val operationId: String, val reason: String) : RecoveryOutcome
    data class ToolInterrupted(val operationId: String, val toolCallId: String) : RecoveryOutcome
    data object WaitingApproval : RecoveryOutcome
}

/** Applies explicit crash policy from the last durable operation snapshot. */
@Singleton
class RecoveryManager @Inject constructor(
    private val repository: HarnessRuntimeRepository,
    private val coordinator: OperationCoordinator,
    private val approvalRepository: AgentApprovalRepository? = null,
    private val json: Json,
    private val eventBus: HarnessEventBus,
) {
    suspend fun recoverSession(sessionId: String): RecoveryOutcome {
        val outcome = recoverSessionInternal(sessionId)
        if (outcome !is RecoveryOutcome.Clean) {
            val detail = when (outcome) {
                is RecoveryOutcome.Suspended -> outcome.reason
                is RecoveryOutcome.ToolInterrupted -> "不可重放工具已写入中断结果"
                RecoveryOutcome.WaitingApproval -> "存在等待中的审批"
                RecoveryOutcome.Clean -> null
            }
            val operationId = when (outcome) {
                is RecoveryOutcome.Suspended -> outcome.operationId
                is RecoveryOutcome.ToolInterrupted -> outcome.operationId
                else -> null
            }
            eventBus.emit(
                HarnessEvent.RecoveryApplied(
                    sessionId = sessionId,
                    timestamp = System.currentTimeMillis(),
                    operationId = operationId,
                    outcome = outcome::class.simpleName ?: "unknown",
                    detail = detail,
                ),
            )
        }
        return outcome
    }

    private suspend fun recoverSessionInternal(sessionId: String): RecoveryOutcome {
        val operation = coordinator.active(sessionId) ?: return RecoveryOutcome.Clean
        if (operation.status == OperationStatus.WAITING_APPROVAL.id) {
            // 审批等待期间进程死亡：审批可能已在停机期间过期/丢失。
            // pendingNow 会先清扫过期请求；无可恢复审批时终止该操作，避免 lane 永久占用。
            if (approvalRepository != null && approvalRepository.pendingNow(sessionId).isEmpty()) {
                coordinator.finish(sessionId, "aborted", details = "等待中的审批已过期或失效", laneName = operation.laneName)
                return RecoveryOutcome.Suspended(operation.id, "等待中的审批已过期或失效")
            }
            return RecoveryOutcome.WaitingApproval
        }
        val snapshot = runCatching {
            json.decodeFromString(OperationSnapshot.serializer(), operation.stateJson)
        }.getOrElse {
            coordinator.suspendOperation(operation.id, "无法解码持久化运行状态")
            return RecoveryOutcome.Suspended(operation.id, "运行状态损坏")
        }

        if (snapshot.phase == OperationPhase.TOOL_INTENT.id && operation.replayPolicy == ReplayPolicy.NEVER.id) {
            val toolCallId = snapshot.effectId ?: operation.pendingEffectId ?: "unknown"
            coordinator.toolSettled(
                operationId = operation.id,
                message = ToolResult(
                    id = UUID.randomUUID().toString(),
                    createdAt = System.currentTimeMillis(),
                    toolCallId = toolCallId,
                    success = false,
                    output = "工具执行期间应用进程中断。该工具声明为不可安全重放，因此未再次执行。",
                ),
                round = snapshot.round,
            )
            coordinator.suspendOperation(operation.id, "不可重放工具已写入中断结果，等待恢复运行")
            return RecoveryOutcome.ToolInterrupted(operation.id, toolCallId)
        }

        val reason = when {
            snapshot.phase == OperationPhase.TOOL_INTENT.id -> "可安全重放的工具在执行期间中断"
            snapshot.phase == OperationPhase.PROVIDER_INTENT.id -> "模型请求结果未落盘，是否产生费用无法确定"
            else -> "运行在检查点之外中断"
        }
        coordinator.suspendOperation(operation.id, reason)
        return RecoveryOutcome.Suspended(operation.id, reason)
    }
}
