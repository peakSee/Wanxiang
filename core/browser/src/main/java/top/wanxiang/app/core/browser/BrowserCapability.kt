package top.wanxiang.app.core.browser

import kotlinx.serialization.Serializable

@Serializable
enum class BrowserCapability {
    OPEN, NAVIGATE, CLOSE_TAB, LIST_TABS,
    SNAPSHOT, CLICK, TYPE, PRESS, SCROLL,
    SCREENSHOT,
    EVALUATE_JS,
    PAGE_SOURCE,
    CONSOLE_READ,
    NETWORK_INTERCEPT,
    COOKIES_RW, LOCAL_RW, SESSION_RW,
    FILE_FS,
    INSTALL_HOOK,
    CDP_DEBUG
}
