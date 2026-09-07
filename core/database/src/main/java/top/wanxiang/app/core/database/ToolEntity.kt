package top.wanxiang.app.core.database

import androidx.room.Entity

/**
 * 工具/插件的元数据与安装状态。
 *
 * 主键为 (distroId, id)：插件安装状态按发行版（系统）隔离，
 * 同一工具在不同系统下可以有不同的安装状态与版本。
 */
@Entity(
    tableName = "tools",
    primaryKeys = ["distroId", "id"],
)
data class ToolEntity(
    /** 所属发行版（系统）ID，如 ubuntu / debian / alpine。 */
    val distroId: String,
    val id: String,
    val name: String,
    val description: String,
    val dependencies: String,
    val launchType: String,
    val state: String,
    val manifestVersion: String = "0.1.0",
    val installedVersion: String? = null,
    val publisher: String = "",
    val category: String = "AI_AGENT",
    val permissions: String = "",
    val homepage: String? = null,
    val updateStrategy: String = "REINSTALL",
    val latestVersion: String? = null,
)
