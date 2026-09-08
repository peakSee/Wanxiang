package top.wanxiang.app.ui.workflow

import top.wanxiang.app.core.model.workflow.WorkflowDefinition
import top.wanxiang.app.core.model.workflow.WorkflowEdge
import top.wanxiang.app.core.model.workflow.WorkflowEdgeCondition
import top.wanxiang.app.core.model.workflow.WorkflowNode
import top.wanxiang.app.core.model.workflow.WorkflowValidator

data class WorkflowEditorUiState(
    val definition: WorkflowDefinition,
    val selectedNodeId: String? = null,
    val connectionSourceId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null,
)

/** Small immutable-snapshot history. Selection is UI state and deliberately does not pollute undo. */
internal class WorkflowEditHistory(
    initial: WorkflowDefinition,
    initiallySaved: Boolean,
    private val capacity: Int = 60,
) {
    var current: WorkflowDefinition = initial
        private set
    private var saved: WorkflowDefinition? = initial.takeIf { initiallySaved }
    private val undo = ArrayDeque<WorkflowDefinition>()
    private val redo = ArrayDeque<WorkflowDefinition>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    val isDirty get() = current != saved

    fun commit(next: WorkflowDefinition): Boolean {
        if (next == current) return false
        undo.addLast(current)
        while (undo.size > capacity) undo.removeFirst()
        current = next
        redo.clear()
        return true
    }

    fun undo(): Boolean {
        val previous = undo.removeLastOrNull() ?: return false
        redo.addLast(current)
        current = previous
        return true
    }

    fun redo(): Boolean {
        val next = redo.removeLastOrNull() ?: return false
        undo.addLast(current)
        current = next
        return true
    }

    fun markSaved() {
        saved = current
    }
}

internal object WorkflowGraphEditor {
    fun calculateNextNodePosition(
        existingNodes: List<WorkflowNode>,
        selectedNodeId: String? = null,
    ): Pair<Float, Float> {
        if (existingNodes.isEmpty()) return 80f to 160f
        val selectedNode = existingNodes.firstOrNull { it.id == selectedNodeId }
        val baseX = selectedNode?.canvasX ?: (existingNodes.maxOfOrNull { it.canvasX } ?: 80f)
        val baseY = selectedNode?.canvasY ?: (existingNodes.lastOrNull()?.canvasY ?: 160f)

        var targetX = baseX + 260f
        var targetY = baseY

        fun overlaps(x: Float, y: Float): Boolean = existingNodes.any { node ->
            kotlin.math.abs(node.canvasX - x) < 220f && kotlin.math.abs(node.canvasY - y) < 120f
        }

        while (overlaps(targetX, targetY)) {
            targetY += 140f
        }

        return targetX to targetY
    }

    fun addNode(definition: WorkflowDefinition, node: WorkflowNode): WorkflowDefinition {
        require(definition.nodes.none { it.id == node.id }) { "节点 ID 已存在" }
        return definition.copy(nodes = definition.nodes + node, updatedAt = System.currentTimeMillis())
    }

    fun updateNode(definition: WorkflowDefinition, node: WorkflowNode): WorkflowDefinition {
        require(definition.nodes.any { it.id == node.id }) { "节点不存在" }
        return definition.copy(
            nodes = definition.nodes.map { if (it.id == node.id) node else it },
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun removeNode(definition: WorkflowDefinition, nodeId: String): WorkflowDefinition = definition.copy(
        nodes = definition.nodes.filterNot { it.id == nodeId },
        edges = definition.edges.filterNot { it.fromNodeId == nodeId || it.toNodeId == nodeId },
        updatedAt = System.currentTimeMillis(),
    )

    fun connect(definition: WorkflowDefinition, edge: WorkflowEdge): WorkflowDefinition {
        require(edge.fromNodeId != edge.toNodeId) { "节点不能连接到自身" }
        require(definition.nodes.any { it.id == edge.fromNodeId } && definition.nodes.any { it.id == edge.toNodeId }) {
            "连接引用了不存在的节点"
        }
        require(definition.edges.none { it.fromNodeId == edge.fromNodeId && it.toNodeId == edge.toNodeId }) {
            "这两个节点已经连接"
        }
        val candidate = definition.copy(edges = definition.edges + edge, updatedAt = System.currentTimeMillis())
        require(WorkflowValidator.validate(candidate).none { it.field == "edges" && it.message.contains("无环图") }) {
            "该连接会形成循环"
        }
        return candidate
    }

    fun updateEdge(definition: WorkflowDefinition, edge: WorkflowEdge): WorkflowDefinition {
        require(definition.edges.any { it.id == edge.id }) { "连接不存在" }
        require(definition.nodes.any { it.id == edge.fromNodeId } && definition.nodes.any { it.id == edge.toNodeId }) {
            "连接引用了不存在的节点"
        }
        val validationError = WorkflowEdgeCondition.validationError(edge)
        require(validationError == null) { validationError ?: "连接配置无效" }
        return definition.copy(
            edges = definition.edges.map { if (it.id == edge.id) edge else it },
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun removeEdge(definition: WorkflowDefinition, edgeId: String): WorkflowDefinition = definition.copy(
        edges = definition.edges.filterNot { it.id == edgeId },
        updatedAt = System.currentTimeMillis(),
    )
}
