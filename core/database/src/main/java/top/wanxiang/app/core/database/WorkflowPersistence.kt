package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.wanxiang.app.core.model.workflow.BuiltinWorkflows
import top.wanxiang.app.core.model.workflow.WorkflowDefinition
import top.wanxiang.app.core.model.workflow.WorkflowRuntimeState
import top.wanxiang.app.core.model.workflow.WorkflowTrigger
import top.wanxiang.app.core.model.workflow.WorkflowValidator

@Entity(
    tableName = "workflows",
    indices = [Index(value = ["slashCommand"])],
)
data class WorkflowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val category: String,
    val isBuiltin: Boolean,
    val slashCommand: String?,
    val jsonContent: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "workflow_execution_logs",
    foreignKeys = [
        ForeignKey(
            entity = WorkflowEntity::class,
            parentColumns = ["id"],
            childColumns = ["workflowId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workflowId"), Index("startTime")],
)
data class WorkflowExecutionLogEntity(
    @PrimaryKey val executionId: String,
    val workflowId: String,
    val startTime: Long,
    val endTime: Long?,
    val status: String,
    val finalContextJson: String,
)

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflows ORDER BY isBuiltin DESC, updatedAt DESC")
    fun observeAll(): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflows WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): WorkflowEntity?

    @Query("SELECT * FROM workflows WHERE slashCommand = :command LIMIT 1")
    suspend fun findBySlashCommand(command: String): WorkflowEntity?

    @Upsert
    suspend fun upsert(entity: WorkflowEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(entity: WorkflowEntity)

    @Query("DELETE FROM workflows WHERE id = :id AND isBuiltin = 0")
    suspend fun deleteCustom(id: String): Int

    @Query("SELECT * FROM workflow_execution_logs ORDER BY startTime DESC LIMIT :limit")
    fun observeRecentExecutions(limit: Int): Flow<List<WorkflowExecutionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExecution(entity: WorkflowExecutionLogEntity)

    @Transaction
    suspend fun saveRun(parent: WorkflowEntity, log: WorkflowExecutionLogEntity) {
        insertIfMissing(parent)
        upsertExecution(log)
    }

    @Transaction
    suspend fun upsertAll(entities: List<WorkflowEntity>) {
        entities.forEach { upsert(it) }
    }
}

interface WorkflowRepository {
    fun observeDefinitions(): Flow<List<WorkflowDefinition>>
    fun observeRecentExecutions(limit: Int = 5): Flow<List<WorkflowExecutionLogEntity>>
    fun observeHistory(): Flow<List<WorkflowRuntimeState>> = observeRecentExecutions().map { logs ->
        val decoder = Json { ignoreUnknownKeys = true }
        logs.mapNotNull { runCatching { decoder.decodeFromString<WorkflowRuntimeState>(it.finalContextJson) }.getOrNull() }
    }
    suspend fun findById(id: String): WorkflowDefinition?
    suspend fun findBySlashCommand(command: String): WorkflowDefinition?
    suspend fun upsert(definition: WorkflowDefinition)
    suspend fun deleteCustom(id: String): Boolean
    suspend fun ensureBuiltins()
    suspend fun saveExecution(state: WorkflowRuntimeState)
}

@Singleton
class RoomWorkflowRepository @Inject constructor(
    private val dao: WorkflowDao,
    private val json: Json,
) : WorkflowRepository {
    override fun observeDefinitions(): Flow<List<WorkflowDefinition>> = dao.observeAll().map { entities ->
        entities.mapNotNull(::decode)
    }

    override fun observeRecentExecutions(limit: Int) = dao.observeRecentExecutions(limit.coerceIn(1, 200))

    override suspend fun findById(id: String) = dao.findById(id)?.let(::decode)

    override suspend fun findBySlashCommand(command: String) = dao.findBySlashCommand(command.trim())?.let(::decode)

    override suspend fun upsert(definition: WorkflowDefinition) {
        val issues = WorkflowValidator.validate(definition)
        require(issues.isEmpty()) { issues.joinToString(separator = "; ") { it.message } }
        dao.upsert(definition.toEntity())
    }

    override suspend fun deleteCustom(id: String): Boolean = dao.deleteCustom(id) > 0

    override suspend fun ensureBuiltins() {
        dao.upsertAll(BuiltinWorkflows.all.map { it.toEntity() })
    }

    override suspend fun saveExecution(state: WorkflowRuntimeState) {
        // The catalog exposes built-ins optimistically before Room initialization finishes.
        // Ensure the parent exists so a very fast run cannot violate the execution FK.
        dao.saveRun(
            state.definition.toEntity(),
            WorkflowExecutionLogEntity(
                executionId = state.executionId,
                workflowId = state.definition.id,
                startTime = state.startedAt ?: System.currentTimeMillis(),
                endTime = state.finishedAt,
                status = state.status.name,
                finalContextJson = json.encodeToString(state),
            ),
        )
    }

    private fun WorkflowDefinition.toEntity(): WorkflowEntity {
        val now = System.currentTimeMillis()
        val normalized = copy(
            createdAt = createdAt.takeIf { it > 0 } ?: now,
            updatedAt = updatedAt.takeIf { it > 0 } ?: now,
        )
        return WorkflowEntity(
            id = id,
            name = name,
            description = description,
            category = category,
            isBuiltin = isBuiltin,
            slashCommand = (trigger as? WorkflowTrigger.Manual)?.slashCommand,
            jsonContent = json.encodeToString(normalized),
            updatedAt = normalized.updatedAt,
        )
    }

    private fun decode(entity: WorkflowEntity): WorkflowDefinition? =
        runCatching { json.decodeFromString<WorkflowDefinition>(entity.jsonContent) }.getOrNull()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkflowRepositoryModule {
    @Binds abstract fun bindWorkflowRepository(impl: RoomWorkflowRepository): WorkflowRepository
}
