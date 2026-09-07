package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 工作区元数据（程序目录在文件系统，这里存路径与创建时间等元信息）。 */
@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey val name: String,
    val path: String,
    val createdAt: Long,
    /** 仅应用新建的私有目录为 true；关联的现有/共享目录删除工程时不得删除原文件。 */
    val ownsDirectory: Boolean = true,
)

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces ORDER BY createdAt ASC")
    suspend fun listAll(): List<WorkspaceEntity>

    @Query("SELECT * FROM workspaces WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): WorkspaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workspace: WorkspaceEntity)

    @Query("DELETE FROM workspaces WHERE name = :name")
    suspend fun delete(name: String)
}
