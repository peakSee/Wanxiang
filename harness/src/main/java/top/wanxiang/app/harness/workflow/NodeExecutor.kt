package top.wanxiang.app.harness.workflow

import top.wanxiang.app.core.model.workflow.NodeExecutionOutput
import top.wanxiang.app.core.model.workflow.NodeRunStatus
import top.wanxiang.app.core.model.workflow.WorkflowNode
import top.wanxiang.app.core.model.workflow.WorkflowNodeType
import top.wanxiang.app.core.model.workflow.WorkflowRuntimeContext

interface NodeExecutor {
    val supportedTypes: Set<WorkflowNodeType>

    suspend fun execute(
        node: WorkflowNode,
        context: WorkflowRuntimeContext,
        onProgress: suspend (NodeRunStatus, String) -> Unit,
    ): NodeExecutionOutput
}

