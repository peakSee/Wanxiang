package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 终端会话元数据（仅持久化配置，不存输出；PTY 进程重启后重建空白 shell）。
 */
@Entity(tableName = "terminal_sessions", indices = [Index(value = ["sortOrder"])])
data class TerminalSessionEntity(
    @PrimaryKey val id: String,
    val label: String,
    val workingDirectory: String,
    val createdAt: Long,
    val sortOrder: Int,
    val distributionId: String = "ubuntu",
)

@Dao
interface TerminalSessionDao {
    @Query("SELECT * FROM terminal_sessions ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<TerminalSessionEntity>>

    @Query("SELECT * FROM terminal_sessions ORDER BY sortOrder ASC")
    suspend fun listAll(): List<TerminalSessionEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM terminal_sessions")
    suspend fun nextOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: TerminalSessionEntity)

    @Query("DELETE FROM terminal_sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM terminal_sessions")
    suspend fun deleteAll()
}
