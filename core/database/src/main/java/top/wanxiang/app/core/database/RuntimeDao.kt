package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RuntimeDao {
    @Query("SELECT * FROM shared_runtimes WHERE id = :id LIMIT 1")
    suspend fun findRuntime(id: String): RuntimeEntity?

    @Query("SELECT * FROM shared_runtimes WHERE state = 'INSTALLED' ORDER BY name")
    suspend fun listInstalledRuntimes(): List<RuntimeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRuntime(runtime: RuntimeEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addReference(reference: RuntimeDependencyRefEntity)

    @Query("DELETE FROM runtime_dependency_ref WHERE toolId = :toolId AND runtimeId = :runtimeId")
    suspend fun removeReference(toolId: String, runtimeId: String)

    @Query("SELECT COUNT(*) FROM runtime_dependency_ref WHERE runtimeId = :runtimeId")
    suspend fun referenceCount(runtimeId: String): Int

    @Query("DELETE FROM shared_runtimes WHERE id = :runtimeId")
    suspend fun deleteRuntime(runtimeId: String)
}
