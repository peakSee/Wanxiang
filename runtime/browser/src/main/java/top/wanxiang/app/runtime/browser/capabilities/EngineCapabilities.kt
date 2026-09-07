package top.wanxiang.app.runtime.browser.capabilities

import top.wanxiang.app.core.browser.BrowserCapability

/**
 * in-app WebView 引擎声明的能力集合（与 [top.wanxiang.app.core.browser.BrowserDescriptor.capabilities] 一致）。
 */
object EngineCapabilities {
    val IN_APP: Set<BrowserCapability> = setOf(
        BrowserCapability.OPEN,
        BrowserCapability.NAVIGATE,
        BrowserCapability.CLOSE_TAB,
        BrowserCapability.LIST_TABS,
        BrowserCapability.SNAPSHOT,
        BrowserCapability.CLICK,
        BrowserCapability.TYPE,
        BrowserCapability.PRESS,
        BrowserCapability.SCROLL,
        BrowserCapability.SCREENSHOT,
        BrowserCapability.EVALUATE_JS,
        BrowserCapability.PAGE_SOURCE,
        BrowserCapability.CONSOLE_READ,
        BrowserCapability.NETWORK_INTERCEPT,
        BrowserCapability.COOKIES_RW,
        BrowserCapability.LOCAL_RW,
        BrowserCapability.SESSION_RW,
        BrowserCapability.FILE_FS,
        BrowserCapability.INSTALL_HOOK,
        BrowserCapability.CDP_DEBUG,
    )
}
