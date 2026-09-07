package top.wanxiang.app.harness.task

import javax.inject.Inject
import javax.inject.Singleton
import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.core.database.task.AgentTaskCheckpoint
import top.wanxiang.app.core.database.task.AgentTaskEntity
import top.wanxiang.app.core.database.task.AgentTaskRepository
import top.wanxiang.app.core.database.task.AgentTaskStatus
import top.wanxiang.app.core.database.task.AgentTaskTransition

/**
 * Durable task lifecycle only. Execution belongs to HarnessLoop; this class owns validated,
 * compare-and-set persistence transitions so process recovery can never report phantom success.
 */
@Singleton
class AgentStateMachine @Inject constructor(
    private val repository: AgentTaskRepository,
    private val logger: AppLogger? = null,
) {
    suspend fun createQueued(
        id: String,
        sessionId: String,
        title: String,
        description: String,
        autoResume: Boolean = true,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        nowMs: Long = System.currentTimeMillis(),
    ): AgentTaskEntity {
        require(sessionId.isNotBlank()) { "Durable task requires a session id" }
        require(description.isNotBlank()) { "Durable task requires a prompt" }
        val task = AgentTaskEntity(
            id = id,
            sessionId = sessionId,
            title = title.ifBlank { description.lineSequence().firstOrNull().orEmpty() }.take(MAX_TITLE_CHARS),
            description = description,
            status = AgentTaskStatus.QUEUED,
            createdAt = nowMs,
            updatedAt = nowMs,
            maxAttempts = maxAttempts.coerceIn(1, MAX_ATTEMPTS),
            autoResume = autoResume,
            statusDetail = "等待执行",
        )
        repository.upsert(task)
        return task
    }

    suspend fun markRunning(
        id: String,
        operationId: String? = null,
        detail: String = "正在执行",
        incrementAttempt: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = transition(
        id = id,
        expected = setOf(
            AgentTaskStatus.QUEUED,
            AgentTaskStatus.WAITING_APPROVAL,
            AgentTaskStatus.RECOVERING,
            AgentTaskStatus.SUSPENDED,
            AgentTaskStatus.FAILED,
        ),
        next = AgentTaskStatus.RUNNING,
        operationId = operationId,
        detail = detail,
        attemptIncrement = if (incrementAttempt) 1 else 0,
        startedAt = nowMs,
        nowMs = nowMs,
    )

    suspend fun markWaitingApproval(
        id: String,
        detail: String = "等待用户批准",
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = transition(
        id,
        setOf(AgentTaskStatus.RUNNING, AgentTaskStatus.RECOVERING),
        AgentTaskStatus.WAITING_APPROVAL,
        detail = detail,
        nowMs = nowMs,
    )

    suspend fun markRecovering(
        id: String,
        detail: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = transition(
        id,
        setOf(AgentTaskStatus.RUNNING, AgentTaskStatus.RECOVERING, AgentTaskStatus.SUSPENDED),
        AgentTaskStatus.RECOVERING,
        detail = detail,
        nowMs = nowMs,
    )

    suspend fun markSuspended(
        id: String,
        detail: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = transition(
        id,
        setOf(
            AgentTaskStatus.QUEUED,
            AgentTaskStatus.RUNNING,
            AgentTaskStatus.WAITING_APPROVAL,
            AgentTaskStatus.RECOVERING,
            AgentTaskStatus.FAILED,
        ),
        AgentTaskStatus.SUSPENDED,
        detail = detail,
        nowMs = nowMs,
    )

    suspend fun markCompleted(id: String, nowMs: Long = System.currentTimeMillis()): Boolean = transition(
        id,
        setOf(AgentTaskStatus.RUNNING, AgentTaskStatus.RECOVERING, AgentTaskStatus.WAITING_APPROVAL),
        AgentTaskStatus.COMPLETED,
        detail = "已完成",
        completedAt = nowMs,
        nowMs = nowMs,
    )

    suspend fun markFailed(
        id: String,
        error: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = transition(
        id,
        setOf(AgentTaskStatus.RUNNING, AgentTaskStatus.RECOVERING, AgentTaskStatus.WAITING_APPROVAL),
        AgentTaskStatus.FAILED,
        error = error,
        detail = "执行失败",
        completedAt = nowMs,
        nowMs = nowMs,
    )

    suspend fun markCancelled(
        id: String,
        detail: String = "用户已取消",
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = transition(
        id,
        setOf(
            AgentTaskStatus.QUEUED,
            AgentTaskStatus.RUNNING,
            AgentTaskStatus.WAITING_APPROVAL,
            AgentTaskStatus.RECOVERING,
            AgentTaskStatus.SUSPENDED,
            AgentTaskStatus.FAILED,
        ),
        AgentTaskStatus.CANCELLED,
        detail = detail,
        completedAt = nowMs,
        nowMs = nowMs,
    )

    suspend fun checkpoint(
        id: String,
        operationId: String?,
        round: Int,
        maxRounds: Int,
        detail: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = repository.checkpoint(
        AgentTaskCheckpoint(
            id = id,
            operationId = operationId,
            lastRound = round.coerceAtLeast(0),
            maxRounds = maxRounds.coerceAtLeast(0),
            progress = if (maxRounds <= 0) 0f else (round.toFloat() / maxRounds).coerceIn(0f, 0.99f),
            statusDetail = detail,
            updatedAt = nowMs,
        ),
    )

    suspend fun activeForSession(sessionId: String): AgentTaskEntity? =
        repository.listForSession(sessionId, AgentTaskStatus.ACTIVE)
            .minByOrNull { task -> ACTIVE_PRIORITY[task.status] ?: Int.MAX_VALUE }

    suspend fun recoverable(nowMs: Long = System.currentTimeMillis()): List<AgentTaskEntity> =
        repository.listByStatus(AgentTaskStatus.RECOVERABLE).filter { task ->
            task.sessionId.isNotBlank() && task.autoResume && task.attemptCount < task.maxAttempts &&
                (task.nextRunAt?.let { it <= nowMs } ?: true)
        }

    suspend fun exhaustedRecoverable(): List<AgentTaskEntity> =
        repository.listByStatus(AgentTaskStatus.RECOVERABLE).filter { task ->
            task.sessionId.isBlank() || !task.autoResume || task.attemptCount >= task.maxAttempts
        }

    suspend fun queued(): List<AgentTaskEntity> = repository.listByStatus(listOf(AgentTaskStatus.QUEUED))

    suspend fun deleteForSession(sessionId: String) = repository.deleteForSession(sessionId)

    private suspend fun transition(
        id: String,
        expected: Set<String>,
        next: String,
        error: String? = null,
        detail: String? = null,
        operationId: String? = null,
        startedAt: Long? = null,
        completedAt: Long? = null,
        nextRunAt: Long? = null,
        attemptIncrement: Int = 0,
        nowMs: Long,
    ): Boolean {
        require(AgentTaskTransitionPolicy.isAllowed(expected, next)) {
            "Illegal durable task transition ${expected.joinToString()} -> $next"
        }
        val changed = repository.transition(
            AgentTaskTransition(
                id = id,
                expectedStatuses = expected.toList(),
                nextStatus = next,
                errorMessage = error,
                statusDetail = detail,
                operationId = operationId,
                startedAt = startedAt,
                completedAt = completedAt,
                nextRunAt = nextRunAt,
                attemptIncrement = attemptIncrement,
                updatedAt = nowMs,
            ),
        )
        if (!changed) logger?.w("[AgentStateMachine] Rejected stale transition for task $id -> $next")
        return changed
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 2
        private const val MAX_ATTEMPTS = 5
        private const val MAX_TITLE_CHARS = 80
        private val ACTIVE_PRIORITY = mapOf(
            AgentTaskStatus.RUNNING to 0,
            AgentTaskStatus.RECOVERING to 1,
            AgentTaskStatus.WAITING_APPROVAL to 2,
            AgentTaskStatus.QUEUED to 3,
        )
    }
}

/** Pure transition table kept separate so lifecycle semantics are exhaustively unit-testable. */
object AgentTaskTransitionPolicy {
    private val allowed = mapOf(
        AgentTaskStatus.QUEUED to setOf(
            AgentTaskStatus.RUNNING,
            AgentTaskStatus.SUSPENDED,
            AgentTaskStatus.CANCELLED,
        ),
        AgentTaskStatus.RUNNING to setOf(
            AgentTaskStatus.WAITING_APPROVAL,
            AgentTaskStatus.RECOVERING,
            AgentTaskStatus.SUSPENDED,
            AgentTaskStatus.FAILED,
            AgentTaskStatus.COMPLETED,
            AgentTaskStatus.CANCELLED,
        ),
        AgentTaskStatus.WAITING_APPROVAL to setOf(
            AgentTaskStatus.RUNNING,
            AgentTaskStatus.SUSPENDED,
            AgentTaskStatus.FAILED,
            AgentTaskStatus.COMPLETED,
            AgentTaskStatus.CANCELLED,
        ),
        AgentTaskStatus.RECOVERING to setOf(
            AgentTaskStatus.RECOVERING,
            AgentTaskStatus.RUNNING,
            AgentTaskStatus.WAITING_APPROVAL,
            AgentTaskStatus.SUSPENDED,
            AgentTaskStatus.FAILED,
            AgentTaskStatus.COMPLETED,
            AgentTaskStatus.CANCELLED,
        ),
        AgentTaskStatus.SUSPENDED to setOf(
            AgentTaskStatus.RECOVERING,
            AgentTaskStatus.RUNNING,
            AgentTaskStatus.CANCELLED,
        ),
        AgentTaskStatus.FAILED to setOf(
            AgentTaskStatus.RUNNING,
            AgentTaskStatus.SUSPENDED,
            AgentTaskStatus.CANCELLED,
        ),
        AgentTaskStatus.COMPLETED to emptySet(),
        AgentTaskStatus.CANCELLED to emptySet(),
    )

    fun isAllowed(from: String, to: String): Boolean = to in allowed[from].orEmpty()
    fun isAllowed(from: Set<String>, to: String): Boolean = from.isNotEmpty() && from.all { isAllowed(it, to) }
}
