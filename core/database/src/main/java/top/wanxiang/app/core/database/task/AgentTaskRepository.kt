package top.wanxiang.app.core.database.task

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Stable persistence port for durable Agent runs. Harness never talks to the Room DAO directly. */
interface AgentTaskRepository {
    fun observeAll(): Flow<List<AgentTaskEntity>>
    suspend fun find(id: String): AgentTaskEntity?
    suspend fun listByStatus(statuses: List<String>): List<AgentTaskEntity>
    suspend fun listForSession(sessionId: String, statuses: List<String>): List<AgentTaskEntity>
    suspend fun upsert(task: AgentTaskEntity)
    suspend fun transition(transition: AgentTaskTransition): Boolean
    suspend fun checkpoint(checkpoint: AgentTaskCheckpoint): Boolean
    suspend fun deleteForSession(sessionId: String)
    suspend fun delete(id: String)
}

data class AgentTaskTransition(
    val id: String,
    val expectedStatuses: List<String>,
    val nextStatus: String,
    val errorMessage: String? = null,
    val statusDetail: String? = null,
    val operationId: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val nextRunAt: Long? = null,
    val attemptIncrement: Int = 0,
    val updatedAt: Long,
)

data class AgentTaskCheckpoint(
    val id: String,
    val operationId: String?,
    val lastRound: Int,
    val maxRounds: Int,
    val progress: Float,
    val statusDetail: String?,
    val updatedAt: Long,
)

@Singleton
class RoomAgentTaskRepository @Inject constructor(
    private val dao: AgentTaskDao,
) : AgentTaskRepository {
    override fun observeAll() = dao.observeAllTasks()
    override suspend fun find(id: String) = dao.getTaskById(id)
    override suspend fun listByStatus(statuses: List<String>) = dao.listTasksByStatus(statuses)
    override suspend fun listForSession(sessionId: String, statuses: List<String>) =
        dao.listSessionTasksByStatus(sessionId, statuses)

    override suspend fun upsert(task: AgentTaskEntity) = dao.upsertTask(task)

    override suspend fun transition(transition: AgentTaskTransition): Boolean =
        dao.transition(
            id = transition.id,
            expectedStatuses = transition.expectedStatuses,
            nextStatus = transition.nextStatus,
            errorMessage = transition.errorMessage,
            statusDetail = transition.statusDetail,
            operationId = transition.operationId,
            startedAt = transition.startedAt,
            completedAt = transition.completedAt,
            nextRunAt = transition.nextRunAt,
            attemptIncrement = transition.attemptIncrement,
            updatedAt = transition.updatedAt,
        ) == 1

    override suspend fun checkpoint(checkpoint: AgentTaskCheckpoint): Boolean =
        dao.checkpoint(
            id = checkpoint.id,
            operationId = checkpoint.operationId,
            lastRound = checkpoint.lastRound,
            maxRounds = checkpoint.maxRounds,
            progress = checkpoint.progress.coerceIn(0f, 1f),
            statusDetail = checkpoint.statusDetail,
            updatedAt = checkpoint.updatedAt,
        ) == 1

    override suspend fun deleteForSession(sessionId: String) = dao.deleteTasksForSession(sessionId)
    override suspend fun delete(id: String) = dao.deleteTask(id)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentTaskRepositoryModule {
    @Binds
    abstract fun bindAgentTaskRepository(impl: RoomAgentTaskRepository): AgentTaskRepository
}
