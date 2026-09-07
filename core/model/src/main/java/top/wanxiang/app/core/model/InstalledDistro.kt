package top.wanxiang.app.core.model

/**
 * 万象支持的已安装 Linux 发行版实例数据模型。
 */
data class InstalledDistro(
    val id: String,
    val displayName: String,
    val sizeBytes: Long = 0L,
    val installedAt: Long = 0L,
    val isActive: Boolean = false,
    val packageManager: String = "apt",
    val statusText: String = "",
)
