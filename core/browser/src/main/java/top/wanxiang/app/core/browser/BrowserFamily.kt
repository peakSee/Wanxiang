package top.wanxiang.app.core.browser

import kotlinx.serialization.Serializable

@Serializable
enum class BrowserFamily {
    IN_APP,
    EXTERNAL_CT,
    REMOTE_CDP;

    val isProgrammable: Boolean get() = this == IN_APP || this == REMOTE_CDP

    companion object {
        fun fromRaw(raw: String?): BrowserFamily = when (raw?.lowercase()) {
            "external_ct", "external-ct", "chrome_ct" -> EXTERNAL_CT
            "remote_cdp", "remote-cdp", "cdp" -> REMOTE_CDP
            else -> IN_APP
        }
    }
}
