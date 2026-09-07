package top.wanxiang.app.core.database.task

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentTaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY updatedAt DESC")
    fun observeAllTasks(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE status = :status ORDER BY updatedAt DESC")
    fun observeTasksByStatus(status: String): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE status IN (:statuses) ORDER BY createdAt ASC")
    suspend fun listTasksByStatus(statuses: List<String>): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE sessionId = :sessionId AND status IN (:statuses) ORDER BY createdAt ASC")
    suspend fun listSessionTasksByStatus(sessionId: String, statuses: List<String>): List<AgentTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: AgentTaskEntity)

    @Query("UPDATE agent_tasks SET status = :status, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskStatus(id: String, status: String, errorMessage: String?, updatedAt: Long)

    @Query(
        """
        UPDATE agent_tasks
        SET status = :nextStatus,
            errorMessage = :errorMessage,
            statusDetail = :statusDetail,
            operationId = COALESCE(:operationId, operationId),
            startedAt = COALESCE(startedAt, :startedAt),
            completedAt = :completedAt,
            nextRunAt = :nextRunAt,
            attemptCount = attemptCount + :attemptIncrement,
            updatedAt = :updatedAt
        WHERE id = :id AND status IN (:expectedStatuses)
        """,
    )
    suspend fun transition(
        id: String,
        expectedStatuses: List<String>,
        nextStatus: String,
        errorMessage: String?,
        statusDetail: String?,
        operationId: String?,
        startedAt: Long?,
        completedAt: Long?,
        nextRunAt: Long?,
        attemptIncrement: Int,
        updatedAt: Long,
    ): Int

    @Query("UPDATE agent_tasks SET progress = :progress, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskProgress(id: String, progress: Float, updatedAt: Long)

    @Query(
        """
        UPDATE agent_tasks
        SET operationId = :operationId,
            lastRound = :lastRound,
            maxRounds = :maxRounds,
            progress = :progress,
            statusDetail = :statusDetail,
            updatedAt = :updatedAt
        WHERE id = :id AND status IN ('RUNNING', 'RECOVERING')
        """,
    )
    suspend fun checkpoint(
        id: String,
        operationId: String?,
        lastRound: Int,
        maxRounds: Int,
        progress: Float,
        statusDetail: String?,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM agent_tasks WHERE sessionId = :sessionId")
    suspend fun deleteTasksForSession(sessionId: String)

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun deleteTask(id: String)
}
