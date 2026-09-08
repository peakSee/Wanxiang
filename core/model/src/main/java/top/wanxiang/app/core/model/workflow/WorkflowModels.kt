package top.wanxiang.app.core.model.workflow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkflowDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val iconName: String = "Play",
    val category: String = "通用",
    val isBuiltin: Boolean = false,
    val trigger: WorkflowTrigger = WorkflowTrigger.Manual(),
    val nodes: List<WorkflowNode> = emptyList(),
    val edges: List<WorkflowEdge> = emptyList(),
    val defaultVariables: Map<String, String> = emptyMap(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
sealed interface WorkflowTrigger {
    @Serializable
    @SerialName("manual")
    data class Manual(val slashCommand: String? = null) : WorkflowTrigger

    @Serializable
    @SerialName("proactive")
    data class Proactive(
        val eventType: String,
        val conditionRule: String? = null,
        val suggestionLabel: String,
    ) : WorkflowTrigger

    @Serializable
    @SerialName("file_watch")
    data class FileWatch(val watchPattern: String) : WorkflowTrigger
}

@Serializable
enum class WorkflowNodeType {
    TRIGGER,
    BASH_COMMAND,
    PROCESS_SERVICE,
    AGENT_INFERENCE,
    SUBAGENT_DELEGATE,
    TAIXU_BUILD,
    CONDITION_BRANCH,
    HUMAN_APPROVAL,
    HOST_ACTION,
    DELAY,
    SET_VARIABLE,
    TERMINAL_OUTPUT,
}

@Serializable
data class WorkflowNode(
    val id: String,
    val type: WorkflowNodeType,
    val title: String,
    val description: String = "",
    val config: Map<String, String> = emptyMap(),
    val canvasX: Float = 0f,
    val canvasY: Float = 0f,
    val timeoutSeconds: Int = 300,
    val failurePolicy: FailurePolicy = FailurePolicy.ABORT,
)

@Serializable
enum class FailurePolicy { ABORT, CONTINUE, RETRY_ONCE, ASK_USER }

@Serializable
data class WorkflowEdge(
    val id: String,
    val fromNodeId: String,
    val fromPort: String = "output",
    val toNodeId: String,
    val toPort: String = "input",
    val conditionExpression: String? = null,
)

@Serializable
enum class NodeRunStatus {
    IDLE,
    PENDING,
    RUNNING,
    STREAMING,
    WAITING_APPROVAL,
    SUCCESS,
    FAILED,
    SKIPPED,
    CANCELLED,
}

@Serializable
enum class WorkflowRunStatus { IDLE, RUNNING, WAITING_APPROVAL, SUCCESS, FAILED, CANCELLED }

@Serializable
data class NodeExecutionOutput(
    val status: NodeRunStatus,
    val exitCode: Int = 0,
    val textOutput: String = "",
    val artifacts: List<String> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
    val durationMs: Long = 0L,
    val error: String? = null,
)

@Serializable
data class WorkflowRuntimeContext(
    val executionId: String,
    val workflowId: String,
    val workspacePath: String,
    val globalVariables: Map<String, String> = emptyMap(),
    val nodeOutputs: Map<String, NodeExecutionOutput> = emptyMap(),
    val upstreamNodeIds: List<String>? = null,
)

fun WorkflowRuntimeContext.previousOutput(): String = upstreamNodeIds?.mapNotNull { nodeOutputs[it] }
    ?.filter { it.status != NodeRunStatus.SKIPPED }?.joinToString("\n\n") { it.textOutput }
    ?: nodeOutputs.values.lastOrNull()?.textOutput.orEmpty()

@Serializable
data class WorkflowNodeRunState(
    val status: NodeRunStatus = NodeRunStatus.IDLE,
    val progressMessage: String = "",
    val output: NodeExecutionOutput? = null,
)

@Serializable
data class WorkflowRuntimeState(
    val executionId: String,
    val definition: WorkflowDefinition,
    val status: WorkflowRunStatus = WorkflowRunStatus.IDLE,
    val nodeStates: Map<String, WorkflowNodeRunState> = emptyMap(),
    val context: WorkflowRuntimeContext? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val error: String? = null,
) {
    companion object {
        fun initial(executionId: String, definition: WorkflowDefinition) = WorkflowRuntimeState(
            executionId = executionId,
            definition = definition,
            nodeStates = definition.nodes.associate { it.id to WorkflowNodeRunState() },
        )
    }
}

@Serializable
data class WorkflowApprovalRequest(
    val executionId: String,
    val nodeId: String,
    val title: String,
    val description: String,
    val requestedVariables: List<String> = emptyList(),
)

@Serializable
data class WorkflowApprovalDecision(
    val approved: Boolean,
    val variables: Map<String, String> = emptyMap(),
)
