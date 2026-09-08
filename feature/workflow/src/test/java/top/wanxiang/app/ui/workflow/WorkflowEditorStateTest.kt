package top.wanxiang.app.ui.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.model.workflow.WorkflowDefinition
import top.wanxiang.app.core.model.workflow.WorkflowEdge
import top.wanxiang.app.core.model.workflow.WorkflowNode
import top.wanxiang.app.core.model.workflow.WorkflowNodeType

class WorkflowEditorStateTest {
    private val base = WorkflowDefinition(
        id = "custom",
        name = "Custom",
        nodes = listOf(
            WorkflowNode("a", WorkflowNodeType.TRIGGER, "A"),
            WorkflowNode("b", WorkflowNodeType.BASH_COMMAND, "B"),
            WorkflowNode("c", WorkflowNodeType.TERMINAL_OUTPUT, "C"),
        ),
        edges = listOf(WorkflowEdge("ab", "a", toNodeId = "b")),
    )

    @Test
    fun `history supports undo redo and saved baseline`() {
        val history = WorkflowEditHistory(base, initiallySaved = true)
        history.commit(base.copy(name = "Changed"))
        assertTrue(history.isDirty)
        assertTrue(history.undo())
        assertEquals("Custom", history.current.name)
        assertFalse(history.isDirty)
        assertTrue(history.redo())
        history.markSaved()
        assertFalse(history.isDirty)
    }

    @Test
    fun `removing a node also removes attached edges`() {
        val result = WorkflowGraphEditor.removeNode(base, "b")
        assertEquals(listOf("a", "c"), result.nodes.map { it.id })
        assertTrue(result.edges.isEmpty())
    }

    @Test
    fun `connection that creates cycle is rejected`() {
        val chained = WorkflowGraphEditor.connect(base, WorkflowEdge("bc", "b", toNodeId = "c"))
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowGraphEditor.connect(chained, WorkflowEdge("ca", "c", toNodeId = "a"))
        }
    }

    @Test
    fun `edge port and condition can be edited and invalid regex is rejected`() {
        val updated = WorkflowGraphEditor.updateEdge(
            base,
            base.edges.single().copy(fromPort = "failure", conditionExpression = "output contains timeout"),
        )
        assertEquals("failure", updated.edges.single().fromPort)
        assertEquals("output contains timeout", updated.edges.single().conditionExpression)

        assertThrows(IllegalArgumentException::class.java) {
            WorkflowGraphEditor.updateEdge(updated, updated.edges.single().copy(conditionExpression = "["))
        }
    }
}
