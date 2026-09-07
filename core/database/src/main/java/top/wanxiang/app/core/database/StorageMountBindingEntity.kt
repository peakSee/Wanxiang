package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.wanxiang.app.core.model.StorageMountBinding
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "storage_mount_bindings")
data class StorageMountBindingEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val hostPath: String,
    val guestPath: String,
    val enabled: Boolean,
    val isSystemDefault: Boolean,
)

@Dao
interface StorageMountBindingDao {
    @Query("SELECT * FROM storage_mount_bindings ORDER BY name ASC")
    fun observeAll(): Flow<List<StorageMountBindingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: StorageMountBindingEntity)

    @Query("UPDATE storage_mount_bindings SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM storage_mount_bindings WHERE id = :id AND isSystemDefault = 0")
    suspend fun deleteCustom(id: String)
}

@Singleton
class StorageMountBindingRepository @Inject constructor(
    private val dao: StorageMountBindingDao,
) {
    val bindings: Flow<List<StorageMountBinding>> = dao.observeAll().map { rows -> rows.map(StorageMountBindingEntity::toModel) }

    suspend fun add(binding: StorageMountBinding) = dao.upsert(binding.toEntity())
    suspend fun remove(id: String) = dao.deleteCustom(id)
    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)
}

private fun StorageMountBindingEntity.toModel() = StorageMountBinding(id, name, hostPath, guestPath, enabled, isSystemDefault)
private fun StorageMountBinding.toEntity() = StorageMountBindingEntity(id, name, hostPath, guestPath, enabled, isSystemDefault)
