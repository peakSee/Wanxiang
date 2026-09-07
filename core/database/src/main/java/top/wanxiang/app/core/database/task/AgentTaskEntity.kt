package top.wanxiang.app.core.database.task

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.ColumnInfo

@Entity(
    tableName = "agent_tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["updatedAt"]),
        Index(value = ["sessionId"]),
        Index(value = ["nextRunAt"]),
    ],
)
data class AgentTaskEntity(
    @PrimaryKey val id: String,
    /** Harness session that owns the durable run. Empty only for rows created before schema v41. */
    @ColumnInfo(defaultValue = "''") val sessionId: String = "",
    val title: String,
    val description: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val errorMessage: String? = null,
    val progress: Float = 0f,
    /** Current harness operation. It changes when a recovered task starts a successor operation. */
    val operationId: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    /** Reserved for delayed retry/automation; null means immediately eligible. */
    val nextRunAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val attemptCount: Int = 0,
    @ColumnInfo(defaultValue = "2") val maxAttempts: Int = 2,
    /** Explicit task-level authority to continue after process death. */
    @ColumnInfo(defaultValue = "1") val autoResume: Boolean = true,
    @ColumnInfo(defaultValue = "0") val lastRound: Int = 0,
    @ColumnInfo(defaultValue = "0") val maxRounds: Int = 0,
    val statusDetail: String? = null,
)

/** Persisted values are stable API: migrations and notification/UI projections depend on them. */
object AgentTaskStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val WAITING_APPROVAL = "WAITING_APPROVAL"
    const val RECOVERING = "RECOVERING"
    const val SUSPENDED = "SUSPENDED"
    const val FAILED = "FAILED"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"

    val ACTIVE = listOf(QUEUED, RUNNING, WAITING_APPROVAL, RECOVERING)
    val RECOVERABLE = listOf(RUNNING, RECOVERING)
    val TERMINAL = setOf(FAILED, COMPLETED, CANCELLED)
}
