package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A reusable workshop build script. [projectType] uses the ProjectType enum name. */
@Entity(tableName = "build_scripts")
data class BuildScriptEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val projectType: String,
    val content: String,
    val isBuiltin: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Explicit project-to-script selection. Projects without a row use the standard build flow. */
@Entity(
    tableName = "project_build_script_bindings",
    indices = [Index("scriptId")],
)
data class ProjectBuildScriptBindingEntity(
    @PrimaryKey val projectName: String,
    val scriptId: String,
    val updatedAt: Long,
)

@Dao
interface BuildScriptDao {
    @Query("SELECT * FROM build_scripts ORDER BY isBuiltin DESC, updatedAt DESC, name COLLATE NOCASE ASC")
    fun observeScripts(): Flow<List<BuildScriptEntity>>

    @Query("SELECT * FROM build_scripts ORDER BY isBuiltin DESC, updatedAt DESC, name COLLATE NOCASE ASC")
    suspend fun listScripts(): List<BuildScriptEntity>

    @Query("SELECT * FROM build_scripts WHERE id = :id LIMIT 1")
    suspend fun findScript(id: String): BuildScriptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScript(script: BuildScriptEntity)

    @Query("DELETE FROM build_scripts WHERE id = :id AND isBuiltin = 0")
    suspend fun deleteUserScript(id: String): Int

    @Query("SELECT * FROM project_build_script_bindings ORDER BY projectName COLLATE NOCASE ASC")
    fun observeBindings(): Flow<List<ProjectBuildScriptBindingEntity>>

    @Query("SELECT * FROM project_build_script_bindings WHERE projectName = :projectName LIMIT 1")
    suspend fun findBinding(projectName: String): ProjectBuildScriptBindingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBinding(binding: ProjectBuildScriptBindingEntity)

    @Query("DELETE FROM project_build_script_bindings WHERE projectName = :projectName")
    suspend fun deleteBinding(projectName: String)

    @Query("DELETE FROM project_build_script_bindings WHERE scriptId = :scriptId")
    suspend fun deleteBindingsForScript(scriptId: String)

    @Transaction
    suspend fun deleteScriptAndBindings(id: String): Boolean {
        val deleted = deleteUserScript(id) > 0
        if (deleted) deleteBindingsForScript(id)
        return deleted
    }
}
