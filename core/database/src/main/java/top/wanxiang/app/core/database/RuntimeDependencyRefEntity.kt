package top.wanxiang.app.core.database

import androidx.room.Entity

@Entity(tableName = "runtime_dependency_ref", primaryKeys = ["toolId", "runtimeId"])
data class RuntimeDependencyRefEntity(
    val toolId: String,
    val runtimeId: String,
)
