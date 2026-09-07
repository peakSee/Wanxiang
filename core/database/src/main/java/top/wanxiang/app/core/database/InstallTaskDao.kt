package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface InstallTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: InstallTaskEntity)

    @Query("SELECT * FROM install_tasks WHERE distroId = :distroId AND toolId = :toolId LIMIT 1")
    suspend fun findByTool(distroId: String, toolId: String): InstallTaskEntity?

    @Query("SELECT * FROM install_tasks WHERE state = :state")
    suspend fun listByState(state: String): List<InstallTaskEntity>

    @Query("DELETE FROM install_tasks WHERE distroId = :distroId")
    suspend fun deleteByDistro(distroId: String)
}

