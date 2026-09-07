package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolDao {

    @Query("SELECT * FROM tools WHERE distroId = :distroId ORDER BY name")
    fun observeForDistro(distroId: String): Flow<List<ToolEntity>>

    @Query("SELECT * FROM tools WHERE distroId = :distroId AND state = 'INSTALLED' ORDER BY name")
    suspend fun getInstalledForDistro(distroId: String): List<ToolEntity>

    @Query("SELECT * FROM tools WHERE distroId = :distroId ORDER BY name")
    suspend fun getForDistro(distroId: String): List<ToolEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tool: ToolEntity)

    @Query("SELECT * FROM tools WHERE distroId = :distroId AND id = :id LIMIT 1")
    suspend fun findById(distroId: String, id: String): ToolEntity?

    @Query("UPDATE tools SET state = :state WHERE distroId = :distroId AND id = :id")
    suspend fun updateState(distroId: String, id: String, state: String)

    @Query("UPDATE tools SET state = :state, installedVersion = :installedVersion WHERE distroId = :distroId AND id = :id")
    suspend fun updateStateAndInstalledVersion(
        distroId: String,
        id: String,
        state: String,
        installedVersion: String?,
    )

    /** 卸载发行版时清理该系统的全部工具状态。 */
    @Query("DELETE FROM tools WHERE distroId = :distroId")
    suspend fun deleteByDistro(distroId: String)
}
