package top.wanxiang.app.harness.projection

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.model.SessionRunState
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.ToolCall

class CurrentSessionTrackerTest {

    @Test
    fun `tracks foreground session`() = runBlocking {
        val tracker = CurrentSessionTracker()
        assertEquals("", tracker.foregroundId)
        assertFalse(tracker.isForeground("s1"))

        tracker.setCurrent("s1")
        assertEquals("s1", tracker.currentSessionId.first())
        assertTrue(tracker.isForeground("s1"))
    }
}

class SessionStateMirrorsTest {

    private fun mirrors(foreground: String = "s1"): Pair<CurrentSessionTracker, SessionStateMirrors> {
        val tracker = CurrentSessionTracker().apply { setCurrent(foreground) }
        return tracker to SessionStateMirrors(tracker)
    }

    @Test
    fun `status foreground mirror syncs and cleanup removes entry`() = runBlocking {
        val (_, m) = mirrors("s1")
        m.setStatus("s1", "思考中")
        assertEquals("思考中", m.status.value)
        assertEquals(mapOf("s1" to "思考中"), m.sessionStatuses.value)

        m.setStatus("s1", null)
        assertEquals(null, m.status.value)
        assertTrue(m.sessionStatuses.value.isEmpty())
    }

    @Test
    fun `background session does not touch global mirror`() = runBlocking {
        val (_, m) = mirrors("current")
        m.setStatus("other", "后台状态")
        assertEquals(null, m.status.value)
        assertTrue(m.lastStatus("other") == "后台状态")
    }

    @Test
    fun `run state drives running flag for foreground only`() = runBlocking {
        val (_, m) = mirrors("cur")
        m.setRunState("cur", SessionRunState.RUNNING)
        assertTrue(m.running.value)

        m.setRunState("bg", SessionRunState.RUNNING)
        assertTrue(m.runStateOf("bg") == SessionRunState.RUNNING)
        // running 只受前台影响（仍为 RUNNING 状态）
        assertTrue(m.isWaitingApproval("bg").not())

        m.setRunState("cur", SessionRunState.WAITING_APPROVAL)
        assertFalse(m.running.value)
        assertTrue(m.onRunFinished("cur"))
    }

    @Test
    fun `onRunFinished resets mirrors and reports waiting approval`() = runBlocking {
        val (_, m) = mirrors("cur")
        m.setStatus("cur", "回复中")
        m.setThinkingLive("cur", true)
        m.setRunState("cur", SessionRunState.RUNNING)
        assertTrue(m.thinkingLive.value)

        val waiting = m.onRunFinished("cur")
        assertFalse(waiting)
        assertEquals(false, m.running.value)
        assertEquals(null, m.status.value)
        assertEquals(false, m.thinkingLive.value)
        assertTrue(m.sessionStatuses.value.isEmpty())
    }

    @Test
    fun `onRunFinished preserves waiting-approval status`() = runBlocking {
        val (_, m) = mirrors("cur")
        m.setRunState("cur", SessionRunState.RUNNING)
        m.setRunState("cur", SessionRunState.WAITING_APPROVAL)
        m.setStatus("cur", "等待用户批准")

        val waiting = m.onRunFinished("cur")
        assertTrue(waiting)
        assertEquals("等待用户批准", m.sessionStatuses.value["cur"])
        assertTrue(m.isWaitingApproval("cur"))
    }

    @Test
    fun `thinking mode recording from history`() = runBlocking {
        val (_, m) = mirrors("cur")
        m.recordThinkingModeFromHistory(
            "cur",
            listOf(AssistantText(id = "a", createdAt = 1L, text = "", reasoning = "思考")),
        )
        assertTrue(m.requestThinkingMode("cur"))

        m.recordThinkingModeFromHistory("other", emptyList())
        assertFalse(m.requestThinkingMode("other"))
    }

    @Test
    fun `removeSession clears all per-session entries`() = runBlocking {
        val (_, m) = mirrors("gone")
        m.ensureFlows("gone")
        m.setStatus("gone", "x")
        m.setRunState("gone", SessionRunState.FAILED)
        m.removeSession("gone")
        assertEquals(null, m.runStateOf("gone"))
        assertEquals(null, m.lastStatus("gone"))
    }
}
