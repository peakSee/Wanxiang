package top.wanxiang.app.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shared_runtimes")
data class RuntimeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String? = null,
    val executablePath: String,
    val state: String,
)
