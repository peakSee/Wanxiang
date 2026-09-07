package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InstallLogDao {
    @Insert
    suspend fun insert(log: InstallLogEntity)

    @Query("SELECT * FROM install_logs WHERE distroId = :distroId AND toolId = :toolId ORDER BY createdAt ASC, id ASC")
    fun observeForTool(distroId: String, toolId: String): Flow<List<InstallLogEntity>>

    @Query("DELETE FROM install_logs WHERE distroId = :distroId AND toolId = :toolId")
    suspend fun deleteForTool(distroId: String, toolId: String)

    @Query("DELETE FROM install_logs WHERE distroId = :distroId")
    suspend fun deleteByDistro(distroId: String)
}

