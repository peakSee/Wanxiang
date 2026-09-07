package top.wanxiang.app.harness.approval

import javax.inject.Inject
import javax.inject.Singleton
import top.wanxiang.app.core.database.AgentApprovalRequestEntity
import top.wanxiang.app.core.database.HarnessSessionRepository
import top.wanxiang.app.harness.ApprovalPolicyEngine
import top.wanxiang.app.harness.operation.OperationCoordinator

/**
 * 审批恢复执行前的四重校验裁决：
 *
 * 1. 过期：到期未决的请求自动失效；
 * 2. 参数摘要：防止“批准的是旧参数、执行的是新参数”；
 * 3. 工作区：审批后用户切换了工作区，环境已改变；
 * 4. operation 归属：审批所属的运行已结束或被新运行接管。
 *
 * 返回 null 表示有效；非 null 为拒绝原因（会作为 ToolResult 写回，
 * 让模型知晓未执行的理由并重新发起）。
 */
data class ApprovalVerdict(
    val invalidationReason: String?,
    /** claimPending 时写入的请求状态：EXPIRED / FAILED / APPROVED / REJECTED。 */
    val claimStatus: String,
) {
    val isInvalid: Boolean get() = invalidationReason != null
}

@Singleton
class ApprovalResumePolicy @Inject constructor(
    private val sessionDao: HarnessSessionRepository,
    private val operationCoordinator: OperationCoordinator,
) {
    suspend fun evaluate(
        request: AgentApprovalRequestEntity,
        approved: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): ApprovalVerdict {
        val invalidation = invalidationOf(request, nowMs)
        val claimStatus = when {
            invalidation != null && request.expiresAt <= nowMs -> AgentApprovalRequestEntity.STATUS_EXPIRED
            invalidation != null -> AgentApprovalRequestEntity.STATUS_FAILED
            approved -> AgentApprovalRequestEntity.STATUS_APPROVED
            else -> AgentApprovalRequestEntity.STATUS_REJECTED
        }
        return ApprovalVerdict(invalidation, claimStatus)
    }

    suspend fun invalidationOf(
        request: AgentApprovalRequestEntity,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        if (request.expiresAt <= nowMs) {
            val ttlMinutes = ApprovalPolicyEngine.APPROVAL_TTL_MS / 60_000L
            return "该审批已过期（等待超过 $ttlMinutes 分钟）"
        }
        if (request.argsHash.isNotBlank() && request.argsHash != ApprovalPolicyEngine.argsHash(request.argumentsJson)) {
            return "审批记录的参数摘要校验不一致，审批可能已损坏"
        }
        val currentWorkspace = sessionDao.findById(request.sessionId)?.workspace.orEmpty()
        if (currentWorkspace != request.workspace) {
            return "会话工作区已变更（审批时：${request.workspace.ifBlank { "无" }}，当前：${currentWorkspace.ifBlank { "无" }}）"
        }
        val boundOperationId = request.operationId
        if (boundOperationId != null && !operationCoordinator.operationExists(boundOperationId)) {
            return "该审批所属的运行已结束或被新运行接管"
        }
        return null
    }

    /** 工具实际执行完成后的终态落库状态。 */
    fun finalStatus(approved: Boolean, resultSuccess: Boolean): String = when {
        !approved -> AgentApprovalRequestEntity.STATUS_REJECTED
        resultSuccess -> AgentApprovalRequestEntity.STATUS_EXECUTED
        else -> AgentApprovalRequestEntity.STATUS_FAILED
    }

    fun invalidationResultMessage(reason: String): String =
        "$reason。该工具调用未执行；如仍需要，请重新发起。"

    fun rejectionResultMessage(): String =
        "用户拒绝了该工具操作。请尊重用户决定，并选择不需要该权限的替代方案。"
}
