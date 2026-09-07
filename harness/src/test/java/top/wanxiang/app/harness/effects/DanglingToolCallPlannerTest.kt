package top.wanxiang.app.harness.effects

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.harness.HarnessTool
import top.wanxiang.app.harness.ToolCall

private fun call(id: String, tool: HarnessTool, raw: String? = null) =
    ToolCall(id = id, createdAt = 1L, tool = tool, args = buildJsonObject { }, rawToolName = raw)

class DanglingToolCallPlannerTest {

    @Test
    fun `interrupted run never replays and stubs everything`() {
        val actions = DanglingToolCallPlanner.plan(
            listOf(
                call("c1", HarnessTool.READ),
                call("c2", HarnessTool.WRITE, raw = "write"),
                call("c3", HarnessTool.MCP, raw = "mcp__fs__cat"),
            ),
            interrupted = true,
        )
        assertTrue(actions.all { it is DanglingToolCallPlanner.Stubbed })
        val notes = actions.filterIsInstance<DanglingToolCallPlanner.Stubbed>().map { it.note }
        assertTrue(notes.all { it.contains("用户停止") })
    }

    @Test
    fun `process death residue replays safe read-only tools only`() {
        val actions = DanglingToolCallPlanner.plan(
            listOf(
                call("r1", HarnessTool.READ),
                call("r2", HarnessTool.HISTORY_SEARCH, raw = "history_search"),
                call("w1", HarnessTool.WRITE),
                call("e1", HarnessTool.EDIT),
                call("b1", HarnessTool.BASE),
                call("m1", HarnessTool.MCP, raw = "mcp__fs__cat"),
                call("m2", HarnessTool.SUBAGENT, raw = "subagent"),
            ),
            interrupted = false,
        )
        val replayed = actions.filterIsInstance<DanglingToolCallPlanner.Replay>().map { it.call.id }.toSet()
        assertEquals(setOf("r1", "r2"), replayed)
        actions.filterIsInstance<DanglingToolCallPlanner.Stubbed>().forEach {
            assertTrue(it.note.contains("不可自动重放"))
        }
    }

    @Test
    fun `answered calls are ignored`() {
        val answered = ToolResultAnswered.stubOf(call("done", HarnessTool.READ))
        val actions = DanglingToolCallPlanner.plan(listOf(answered), interrupted = false)
        assertTrue(actions.isEmpty())
    }

    private object ToolResultAnswered {
        fun stubOf(call: ToolCall): top.wanxiang.app.harness.ToolResult =
            top.wanxiang.app.harness.ToolResult(
                id = "res-${call.id}",
                createdAt = 2L,
                toolCallId = call.id,
                success = true,
                output = "",
            )
    }
}
