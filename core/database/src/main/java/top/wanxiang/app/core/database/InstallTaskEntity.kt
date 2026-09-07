package top.wanxiang.app.core.database

import androidx.room.Entity

/** Durable transaction state used to recover after an Android process kill. */
@Entity(
    tableName = "install_tasks",
    primaryKeys = ["distroId", "toolId"],
)
data class InstallTaskEntity(
    val distroId: String,
    val toolId: String,
    val operation: String,
    val state: String,
    val message: String,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

