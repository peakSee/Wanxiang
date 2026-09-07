package top.wanxiang.app.runtime.browser

import kotlinx.serialization.Serializable
import top.wanxiang.app.core.browser.BrowserFamily

/**
 * 一个浏览器 tab 的"逻辑身份"。Harness 工具的 args 中传 `tab: String`（字符串 ID），
 * registry 反查回 token。
 */
@Serializable
data class BrowserSessionToken(
    val tabId: String,
    val family: BrowserFamily,
    val title: String = "",
    val url: String = "about:blank",
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** 默认 tab id：用于 calls 中不传 tab 的情况。 */
        const val DEFAULT_TAB_ID = "wanxiang-default"
        fun defaultTab(family: BrowserFamily) = BrowserSessionToken(
            tabId = DEFAULT_TAB_ID, family = family
        )
    }
}
