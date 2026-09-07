package top.wanxiang.app.core.browser

import kotlinx.serialization.Serializable

@Serializable
data class BrowserDescriptor(
    val family: BrowserFamily,
    val displayName: String,
    val healthy: Boolean,
    val capabilities: Set<BrowserCapability>,
    val versionTag: String = "",
    val notes: String = ""
)
