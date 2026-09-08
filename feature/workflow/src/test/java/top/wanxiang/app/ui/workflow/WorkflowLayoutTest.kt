package top.wanxiang.app.ui.workflow

import org.junit.Assert.*
import org.junit.Test
import top.wanxiang.app.core.model.workflow.*

class WorkflowLayoutTest {
    @Test fun builtinLayoutsAreStableAndPreserveGraph() {
        BuiltinWorkflows.all.forEach { workflow ->
            val layout = WorkflowLayout.arrange(workflow)
            assertEquals(layout, WorkflowLayout.arrange(layout))
            assertEquals(workflow.edges, layout.edges)
            assertEquals(workflow.nodes.map { it.id }, layout.nodes.map { it.id })
            assertEquals(layout.nodes.size, layout.nodes.map { it.canvasX to it.canvasY }.distinct().size)
            val positions = layout.nodes.associateBy { it.id }
            layout.edges.forEach { assertTrue(positions.getValue(it.fromNodeId).canvasX < positions.getValue(it.toNodeId).canvasX) }
        }
    }

    @Test fun forkAndJoinAreLayeredWithoutOverlapping() {
        val graph = WorkflowDefinition("fork", "Fork", nodes = listOf("a", "b", "c", "d").map { WorkflowNode(it, WorkflowNodeType.BASH_COMMAND, it) },
            edges = listOf("a" to "b", "a" to "c", "b" to "d", "c" to "d").map { WorkflowEdge("${it.first}-${it.second}", it.first, "success", it.second) })
        val nodes = WorkflowLayout.arrange(graph).nodes.associateBy { it.id }
        assertEquals(nodes.getValue("b").canvasX, nodes.getValue("c").canvasX)
        assertTrue(kotlin.math.abs(nodes.getValue("b").canvasY - nodes.getValue("c").canvasY) >= 104f)
    }
}
