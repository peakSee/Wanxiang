package top.wanxiang.app.core.model

import kotlinx.serialization.Serializable

/**
 * 云端版本信息——配置键 `wanxiang.latest_version` 的值结构（JSON 字符串，客户端二次解析）。
 * versionCode 用于与 GitHub Releases 检查结果取「更新者」；forceUpdate=true 时强制更新。
 */
@Serializable
data class CloudLatestVersion(
    val versionName: String = "",
    val versionCode: Int = 0,
    val apkUrl: String = "",
    val changelog: String = "",
    val forceUpdate: Boolean = false,
)

/**
 * 云端公告——`/app-api/wanxiang/announcement/list` 的数组元素。
 * type：1=通知 2=公告；content 为富文本 HTML；createTime 为毫秒时间戳。
 */
@Serializable
data class CloudAnnouncement(
    val id: Long = 0,
    val title: String = "",
    val type: Int = 1,
    val content: String = "",
    val createTime: Long = 0,
)
