package top.wanxiang.app.core.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * BrowserPreferences DataStore 字段 —— 单纯键常量，避免在 BrowserPreferences facade 里内联散落。
 */
object BrowserPreferencesKeys {
    val DefaultFamily = stringPreferencesKey("browser_default_family")
    val HomeUrl = stringPreferencesKey("browser_home_url")
    val CoBrowsingEnabled = booleanPreferencesKey("browser_co_browsing_enabled")
    val AllowRemoteConnect = booleanPreferencesKey("browser_allow_remote_connect")
    val AllowEvalJs = booleanPreferencesKey("browser_allow_eval_js")
    val AllowHooks = booleanPreferencesKey("browser_allow_hooks")
    val AllowCdp = booleanPreferencesKey("browser_allow_cdp")
    val DesktopUserAgent = booleanPreferencesKey("browser_desktop_user_agent")
    val MaxCaptureBytes = intPreferencesKey("browser_max_capture_bytes")
}
