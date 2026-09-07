package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.wanxiang.app.core.model.AgentSubagent
import top.wanxiang.app.core.model.AgentDepartmentCount
import top.wanxiang.app.core.model.AgentSubagentIndexEntry
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "agent_subagents")
data class AgentSubagentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    @ColumnInfo(defaultValue = "null") val defaultModelId: String? = null,
    @ColumnInfo(defaultValue = "null") val defaultModelVariant: String? = null,
    @ColumnInfo(defaultValue = "'custom'") val departmentId: String,
    val isEnabled: Boolean,
    val isBuiltin: Boolean,
    val sortOrder: Int,
)

@Entity(tableName = "agent_subagent_settings")
data class AgentSubagentSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val autoDelegationEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "''") val catalogRevision: String = "",
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Dao
interface AgentSubagentDao {
    @Query("SELECT * FROM agent_subagents ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<AgentSubagentEntity>>

    @Query("SELECT * FROM agent_subagents WHERE isEnabled = 1 ORDER BY sortOrder ASC, name ASC")
    suspend fun listEnabled(): List<AgentSubagentEntity>

    @Query("SELECT id, name, description, departmentId FROM agent_subagents WHERE isEnabled = 1 ORDER BY sortOrder ASC, name ASC")
    suspend fun listEnabledIndex(): List<AgentSubagentIndexEntry>

    @Query("SELECT departmentId, COUNT(*) AS enabledCount FROM agent_subagents WHERE isEnabled = 1 GROUP BY departmentId")
    suspend fun listEnabledDepartmentCounts(): List<AgentDepartmentCount>

    @Query("SELECT * FROM agent_subagents WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AgentSubagentEntity?

    @Query("SELECT * FROM agent_subagents WHERE isEnabled = 1 AND (id = :role COLLATE NOCASE OR name = :role COLLATE NOCASE) LIMIT 1")
    suspend fun findEnabledByRole(role: String): AgentSubagentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: AgentSubagentEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(profiles: List<AgentSubagentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<AgentSubagentEntity>)

    @Query("UPDATE agent_subagents SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM agent_subagents WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM agent_subagents")
    suspend fun nextSortOrder(): Int

    @Query("SELECT catalogRevision FROM agent_subagent_settings WHERE id = 1 LIMIT 1")
    suspend fun getCatalogRevision(): String?

    @Query("SELECT * FROM agent_subagents WHERE isBuiltin = 1")
    suspend fun listBuiltin(): List<AgentSubagentEntity>

    @Query("DELETE FROM agent_subagents WHERE isBuiltin = 1")
    suspend fun deleteBuiltin()

    @Query("SELECT * FROM agent_subagent_settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<AgentSubagentSettingsEntity?>

    @Query("SELECT * FROM agent_subagent_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): AgentSubagentSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: AgentSubagentSettingsEntity)

    @Query("UPDATE agent_subagent_settings SET autoDelegationEnabled = :enabled WHERE id = 1")
    suspend fun setAutoDelegationEnabled(enabled: Boolean)

    @Transaction
    suspend fun syncBuiltinCatalog(revision: String, defaults: List<AgentSubagentEntity>) {
        val settings = getSettings()
        if (settings?.catalogRevision == revision) return
        val preferencesById = listBuiltin().associateBy { it.id }
        deleteBuiltin()
        upsertAll(defaults.map { profile ->
            val saved = preferencesById[profile.id]
            profile.copy(
                isEnabled = saved?.isEnabled ?: profile.isEnabled,
                defaultModelId = saved?.defaultModelId ?: profile.defaultModelId,
                defaultModelVariant = saved?.defaultModelVariant ?: profile.defaultModelVariant,
            )
        })
        upsertSettings((settings ?: AgentSubagentSettingsEntity()).copy(catalogRevision = revision))
    }

    @Transaction
    suspend fun replace(previousId: String?, profile: AgentSubagentEntity) {
        if (previousId != null && previousId != profile.id) delete(previousId)
        upsert(profile)
    }
}

@Singleton
class AgentSubagentRepository @Inject constructor(
    private val dao: AgentSubagentDao,
    private val catalogLoader: AgencyAgentCatalogLoader,
) {
    val profiles: Flow<List<AgentSubagent>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }
    val autoDelegationEnabled: Flow<Boolean> = dao.observeSettings().map { it?.autoDelegationEnabled ?: true }

    suspend fun ensureInitialized() {
        val revision = catalogLoader.sourceRevision()
        if (dao.getCatalogRevision() == revision) return
        val catalog = catalogLoader.load()
        dao.syncBuiltinCatalog(catalog.revision, catalog.profiles.map { it.toEntity() })
    }

    suspend fun enabledProfiles(): List<AgentSubagent> {
        ensureInitialized()
        return dao.listEnabled().map { it.toModel() }
    }

    suspend fun enabledIndex(): List<AgentSubagentIndexEntry> {
        ensureInitialized()
        return dao.listEnabledIndex()
    }

    suspend fun enabledDepartmentCounts(): List<AgentDepartmentCount> {
        ensureInitialized()
        return dao.listEnabledDepartmentCounts()
    }

    suspend fun findEnabledProfile(role: String): AgentSubagent? {
        ensureInitialized()
        return dao.findEnabledByRole(role)?.toModel()
    }

    suspend fun replace(previousId: String?, profile: AgentSubagent) {
        ensureInitialized()
        dao.replace(previousId, profile.toEntity())
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        ensureInitialized()
        dao.setEnabled(id, enabled)
    }

    suspend fun delete(id: String) {
        ensureInitialized()
        dao.delete(id)
    }

    suspend fun setAutoDelegationEnabled(enabled: Boolean) {
        ensureInitialized()
        dao.setAutoDelegationEnabled(enabled)
    }

    suspend fun nextSortOrder(): Int {
        ensureInitialized()
        return dao.nextSortOrder()
    }
}

private fun AgentSubagentEntity.toModel(): AgentSubagent = AgentSubagent(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    defaultModelId = defaultModelId,
    defaultModelVariant = defaultModelVariant,
    departmentId = departmentId,
    isEnabled = isEnabled,
    isBuiltin = isBuiltin,
    sortOrder = sortOrder,
)

private fun AgentSubagent.toEntity(): AgentSubagentEntity = AgentSubagentEntity(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    defaultModelId = defaultModelId,
    defaultModelVariant = defaultModelVariant,
    departmentId = departmentId,
    isEnabled = isEnabled,
    isBuiltin = isBuiltin,
    sortOrder = sortOrder,
)
