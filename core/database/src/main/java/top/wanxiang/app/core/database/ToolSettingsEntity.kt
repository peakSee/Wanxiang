package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "tool_settings", primaryKeys = ["distroId", "toolId"])
data class ToolSettingsEntity(
    val distroId: String,
    val toolId: String,
    val autoStart: Boolean = false,
)

@Dao
interface ToolSettingsDao {
    @Query("SELECT autoStart FROM tool_settings WHERE distroId = :distroId AND toolId = :toolId LIMIT 1")
    fun observeAutoStart(distroId: String, toolId: String): Flow<Boolean?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: ToolSettingsEntity)

    @Query("DELETE FROM tool_settings WHERE distroId = :distroId AND toolId = :toolId")
    suspend fun delete(distroId: String, toolId: String)

    @Query("DELETE FROM tool_settings WHERE distroId = :distroId")
    suspend fun deleteByDistro(distroId: String)
}

@Singleton
class ToolSettingsRepository @Inject constructor(
    private val dao: ToolSettingsDao,
) {
    fun autoStart(distroId: String, toolId: String): Flow<Boolean> = dao.observeAutoStart(distroId, toolId).map { it ?: false }
    suspend fun setAutoStart(distroId: String, toolId: String, enabled: Boolean) = dao.upsert(ToolSettingsEntity(distroId, toolId, enabled))
    suspend fun delete(distroId: String, toolId: String) = dao.delete(distroId, toolId)
    suspend fun deleteByDistro(distroId: String) = dao.deleteByDistro(distroId)
}
