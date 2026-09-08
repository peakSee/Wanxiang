package top.wanxiang.app.harness.workflow

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.wanxiang.app.core.model.workflow.WorkflowApprovalDecision
import top.wanxiang.app.core.model.workflow.WorkflowApprovalRequest

@Singleton
class WorkflowApprovalBroker @Inject constructor() {
    private val waiting = linkedMapOf<String, Pair<WorkflowApprovalRequest, CompletableDeferred<WorkflowApprovalDecision>>>()
    private val _currentRequest = MutableStateFlow<WorkflowApprovalRequest?>(null)
    val currentRequest: StateFlow<WorkflowApprovalRequest?> = _currentRequest.asStateFlow()

    suspend fun await(request: WorkflowApprovalRequest): WorkflowApprovalDecision {
        val key = key(request.executionId, request.nodeId)
        val deferred = CompletableDeferred<WorkflowApprovalDecision>()
        synchronized(waiting) {
            check(key !in waiting) { "审批请求已存在：$key" }
            waiting[key] = request to deferred
            _currentRequest.value = waiting.values.firstOrNull()?.first
        }
        return try {
            deferred.await()
        } finally {
            synchronized(waiting) {
                waiting.remove(key)
                _currentRequest.value = waiting.values.firstOrNull()?.first
            }
        }
    }

    fun decide(executionId: String, nodeId: String, decision: WorkflowApprovalDecision): Boolean =
        synchronized(waiting) { waiting[key(executionId, nodeId)]?.second?.complete(decision) == true }

    fun cancelExecution(executionId: String) {
        synchronized(waiting) {
            val keys = waiting.filterValues { it.first.executionId == executionId }.keys.toList()
            keys.forEach { waiting.remove(it)?.second?.cancel() }
            _currentRequest.value = waiting.values.firstOrNull()?.first
        }
    }

    private fun key(executionId: String, nodeId: String) = "$executionId:$nodeId"
}
