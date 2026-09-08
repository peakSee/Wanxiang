package top.wanxiang.app.ui.workflow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.model.workflow.WorkflowNode
import top.wanxiang.app.core.model.workflow.WorkflowNodeType

class WorkflowCanvasTransformTest {
    @Test
    fun `zoom keeps world point under gesture centroid`() {
        val current = CanvasTransform(scale = 1f, pan = Offset(40f, 20f))
        val centroid = Offset(300f, 180f)
        val worldPoint = (centroid - current.pan) / current.scale

        val zoomed = zoomAround(current, centroid, zoomFactor = 1.8f, minScale = 0.3f, maxScale = 2.4f)
        val projected = worldPoint * zoomed.scale + zoomed.pan

        assertEquals(centroid.x, projected.x, 0.001f)
        assertEquals(centroid.y, projected.y, 0.001f)
    }

    @Test
    fun `fit centers content inside viewport`() {
        val transform = fitTransform(
            bounds = CanvasContentBounds(100f, 50f, 900f, 450f),
            viewport = IntSize(1000, 600),
            padding = 50f,
            minScale = 0.3f,
            maxScale = 2f,
        )!!

        val projectedCenter = Offset(500f, 250f) * transform.scale + transform.pan
        assertEquals(500f, projectedCenter.x, 0.001f)
        assertEquals(300f, projectedCenter.y, 0.001f)
    }

    @Test
    fun `new node gets a render position before async state synchronization`() {
        val nodes = listOf(
            WorkflowNode("existing", WorkflowNodeType.TRIGGER, "Existing", canvasX = 80f, canvasY = 120f),
            WorkflowNode("new", WorkflowNodeType.TRIGGER, "New", canvasX = 340f, canvasY = 120f),
        )
        val resolved = resolveNodePositions(
            nodes = nodes,
            current = mapOf("existing" to Offset(80f, 120f)),
            density = 1f,
        )

        assertEquals(nodes.map { it.id }.toSet(), resolved.keys)
        assertTrue(resolved.getValue("new") != Offset.Zero)
        assertEquals(Offset(340f, 120f), resolved.getValue("new"))
    }

    @Test
    fun `calculateNextNodePosition handles empty, selected, and unselected graphs`() {
        // Empty graph
        val emptyPos = WorkflowGraphEditor.calculateNextNodePosition(emptyList())
        assertEquals(80f to 160f, emptyPos)

        val start = WorkflowNode("start", WorkflowNodeType.TRIGGER, "Start", canvasX = 80f, canvasY = 160f)
        val done = WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "Done", canvasX = 420f, canvasY = 160f)
        val nodes = listOf(start, done)

        // No selection: placed to the right of the rightmost node
        val (noSelX, noSelY) = WorkflowGraphEditor.calculateNextNodePosition(nodes, selectedNodeId = null)
        assertEquals(680f, noSelX)
        assertEquals(160f, noSelY)

        // Start selected: candidate (340, 160) collides with done at (420, 160), so it cascades down
        val (selX, selY) = WorkflowGraphEditor.calculateNextNodePosition(nodes, selectedNodeId = "start")
        assertEquals(340f, selX)
        assertEquals(300f, selY)
    }

    @Test
    fun `fitTransform projects all nodes within viewport after adding new node`() {
        val viewport = IntSize(1080, 1920)
        val padding = 110f // 40.dp at 2.75x density

        // Content bounds including newly added node at (680, 160) with NodeWidth 208 and NodeHeight 104
        val bounds = CanvasContentBounds(
            minX = 80f * 2.75f,
            minY = 160f * 2.75f,
            maxX = (680f + 208f) * 2.75f,
            maxY = (160f + 104f) * 2.75f,
        )

        val transform = fitTransform(bounds, viewport, padding, minScale = 0.3f, maxScale = 1.35f)!!

        val projectedMin = Offset(bounds.minX, bounds.minY) * transform.scale + transform.pan
        val projectedMax = Offset(bounds.maxX, bounds.maxY) * transform.scale + transform.pan

        assertTrue("minX must be >= padding", projectedMin.x >= padding - 1f)
        assertTrue("maxX must be <= viewport width - padding", projectedMax.x <= viewport.width - padding + 1f)
        assertTrue("minY must be >= padding", projectedMin.y >= padding - 1f)
        assertTrue("maxY must be <= viewport height - padding", projectedMax.y <= viewport.height - padding + 1f)
    }
}
