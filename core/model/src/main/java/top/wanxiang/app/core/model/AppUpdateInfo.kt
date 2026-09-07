package top.wanxiang.app.core.model

import kotlinx.serialization.Serializable

/**
 * 万象 · 版本更新信息模型 (GitHub Releases API)
 */
@Serializable
data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val hasUpdate: Boolean,
    val releaseTitle: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val apkDownloadUrl: String? = null,
    val apkSizeBytes: Long? = null,
    val publishedAt: String = "",
    /** 最新版本号（云端 source 提供，用于与 GitHub tag 取「更新者」）。 */
    val versionCode: Int = 0,
    /** 云端强制更新标记：true 时弹不可关闭的更新弹窗。 */
    val forceUpdate: Boolean = false,
)

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Success(val info: AppUpdateInfo) : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}
