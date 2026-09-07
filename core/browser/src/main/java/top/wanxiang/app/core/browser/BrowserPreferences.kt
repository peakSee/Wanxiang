package top.wanxiang.app.core.browser

import kotlinx.serialization.Serializable

@Serializable
data class BrowserPreferences(
    val defaultFamily: String = BrowserFamily.IN_APP.name,
    val homeUrl: String = "about:blank",
    val coBrowsingEnabled: Boolean = true,
    val allowRemoteConnect: Boolean = false,
    val allowEvalJs: Boolean = false,
    val allowHooks: Boolean = false,
    val allowCdp: Boolean = false,
    val desktopUserAgent: Boolean = false,
    val maxCaptureBytes: Int = 6 * 1024 * 1024
) {
    val resolvedFamily: BrowserFamily
        get() = BrowserFamily.fromRaw(defaultFamily)

    companion object {
        val DEFAULT = BrowserPreferences()
    }
}
