package top.wanxiang.app.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "install_logs")
data class InstallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val distroId: String = "ubuntu",
    val toolId: String,
    val event: String,
    val message: String,
    val createdAt: Long = System.currentTimeMillis(),
)

