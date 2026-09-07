package top.wanxiang.app.harness.task

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.database.task.AgentTaskCheckpoint
import top.wanxiang.app.core.database.task.AgentTaskEntity
import top.wanxiang.app.core.database.task.AgentTaskRepository
import top.wanxiang.app.core.database.task.AgentTaskStatus
import top.wanxiang.app.core.database.task.AgentTaskTransition

class AgentStateMachineTest {
    @Test
    fun `task follows queued running approval running completed lifecycle`() = runBlocking {
        val repository = FakeAgentTaskRepository()
        val machine = AgentStateMachine(repository, null)
        machine.createQueued("t1", "s1", "Build", "build app", nowMs = 10)

        assertTrue(machine.markRunning("t1", operationId = "op1", nowMs = 20))
        assertTrue(machine.checkpoint("t1", "op1", round = 4, maxRounds = 20, detail = "tools", nowMs = 30))
        assertTrue(machine.markWaitingApproval("t1", nowMs = 40))
        assertTrue(machine.markRunning("t1", incrementAttempt = false, nowMs = 50))
        assertTrue(machine.markCompleted("t1", nowMs = 60))

        val task = repository.find("t1")!!
        assertEquals(AgentTaskStatus.COMPLETED, task.status)
        assertEquals(1, task.attemptCount)
        assertEquals(4, task.lastRound)
        assertEquals(0.2f, task.progress)
        assertEquals(60L, task.completedAt)
        assertEquals("op1", task.operationId)
    }

    @Test
    fun `stale terminal task cannot be restarted`() = runBlocking {
        val repository = FakeAgentTaskRepository()
        val machine = AgentStateMachine(repository, null)
        machine.createQueued("t1", "s1", "Task", "do it", nowMs = 1)
        machine.markRunning("t1", nowMs = 2)
        machine.markCompleted("t1", nowMs = 3)

        assertFalse(machine.markRunning("t1", nowMs = 4))
        assertEquals(AgentTaskStatus.COMPLETED, repository.find("t1")!!.status)
    }

    @Test
    fun `recovery honors authority schedule and attempt budget`() = runBlocking {
        val repository = FakeAgentTaskRepository()
        val machine = AgentStateMachine(repository, null)
        repository.upsert(task("ready", autoResume = true, attempt = 1, maxAttempts = 2, nextRunAt = 99))
        repository.upsert(task("too-early", autoResume = true, attempt = 1, maxAttempts = 2, nextRunAt = 101))
        repository.upsert(task("disabled", autoResume = false, attempt = 0, maxAttempts = 2))
        repository.upsert(task("exhausted", autoResume = true, attempt = 2, maxAttempts = 2))

        assertEquals(listOf("ready"), machine.recoverable(nowMs = 100).map { it.id })
        assertEquals(setOf("disabled", "exhausted"), machine.exhaustedRecoverable().map { it.id }.toSet())
    }

    @Test
    fun `transition table rejects terminal and impossible edges`() {
        assertTrue(AgentTaskTransitionPolicy.isAllowed(AgentTaskStatus.RUNNING, AgentTaskStatus.WAITING_APPROVAL))
        assertFalse(AgentTaskTransitionPolicy.isAllowed(AgentTaskStatus.QUEUED, AgentTaskStatus.COMPLETED))
        assertFalse(AgentTaskTransitionPolicy.isAllowed(AgentTaskStatus.COMPLETED, AgentTaskStatus.RUNNING))
        assertFalse(AgentTaskTransitionPolicy.isAllowed(AgentTaskStatus.CANCELLED, AgentTaskStatus.RECOVERING))
    }

    private fun task(
        id: String,
        autoResume: Boolean,
        attempt: Int,
        maxAttempts: Int,
        nextRunAt: Long? = null,
    ) = AgentTaskEntity(
        id = id,
        sessionId = "s-$id",
        title = id,
        description = "prompt",
        status = AgentTaskStatus.RUNNING,
        createdAt = 1,
        updatedAt = 1,
        attemptCount = attempt,
        maxAttempts = maxAttempts,
        autoResume = autoResume,
        nextRunAt = nextRunAt,
    )
}

private class FakeAgentTaskRepository : AgentTaskRepository {
    private val tasks = linkedMapOf<String, AgentTaskEntity>()
    private val flow = MutableStateFlow<List<AgentTaskEntity>>(emptyList())

    override fun observeAll(): Flow<List<AgentTaskEntity>> = flow
    override suspend fun find(id: String) = tasks[id]
    override suspend fun listByStatus(statuses: List<String>) =
        tasks.values.filter { it.status in statuses }.sortedBy { it.createdAt }
    override suspend fun listForSession(sessionId: String, statuses: List<String>) =
        tasks.values.filter { it.sessionId == sessionId && it.status in statuses }.sortedBy { it.createdAt }
    override suspend fun upsert(task: AgentTaskEntity) {
        tasks[task.id] = task
        publish()
    }

    override suspend fun transition(transition: AgentTaskTransition): Boolean {
        val current = tasks[transition.id] ?: return false
        if (current.status !in transition.expectedStatuses) return false
        tasks[transition.id] = current.copy(
            status = transition.nextStatus,
            errorMessage = transition.errorMessage,
            statusDetail = transition.statusDetail,
            operationId = transition.operationId ?: current.operationId,
            startedAt = current.startedAt ?: transition.startedAt,
            completedAt = transition.completedAt,
            nextRunAt = transition.nextRunAt,
            attemptCount = current.attemptCount + transition.attemptIncrement,
            updatedAt = transition.updatedAt,
        )
        publish()
        return true
    }

    override suspend fun checkpoint(checkpoint: AgentTaskCheckpoint): Boolean {
        val current = tasks[checkpoint.id] ?: return false
        if (current.status !in setOf(AgentTaskStatus.RUNNING, AgentTaskStatus.RECOVERING)) return false
        tasks[checkpoint.id] = current.copy(
            operationId = checkpoint.operationId,
            lastRound = checkpoint.lastRound,
            maxRounds = checkpoint.maxRounds,
            progress = checkpoint.progress,
            statusDetail = checkpoint.statusDetail,
            updatedAt = checkpoint.updatedAt,
        )
        publish()
        return true
    }

    override suspend fun deleteForSession(sessionId: String) {
        tasks.entries.removeAll { it.value.sessionId == sessionId }
        publish()
    }

    override suspend fun delete(id: String) {
        tasks.remove(id)
        publish()
    }

    private fun publish() {
        flow.value = tasks.values.toList()
    }
}
