package top.wanxiang.app.core.browser

object BrowserSelectionPolicy {
    data class Selection(
        val family: BrowserFamily,
        val reason: String,
        val fallbackChain: List<BrowserFamily>
    )

    fun decide(
        requested: BrowserFamily?,
        urlHint: String?,
        prefs: BrowserPreferences,
        families: Map<BrowserFamily, Boolean>
    ): Selection {
        if (requested != null) {
            val ok = families[requested] != false
            return if (ok) Selection(requested, "user-explicit", listOf(requested))
            else {
                val fallback = pickFallback(families, prefs)
                Selection(fallback, "user-explicit[unavailable:${requested}] -> fallback", listOf(fallback))
            }
        }
        val urlPick = urlHint?.let { pickByUrl(it, families, prefs) }
        if (urlPick != null) return urlPick
        val preferred = prefs.resolvedFamily
        val ok = families[preferred] != false
        return if (ok) Selection(preferred, "default-by-prefs", listOf(preferred))
        else {
            val fallback = pickFallback(families, prefs, exclude = preferred)
            Selection(fallback, "default-by-prefs unavailable ${preferred} -> fallback", listOf(fallback))
        }
    }

    private fun pickByUrl(
        url: String,
        families: Map<BrowserFamily, Boolean>,
        prefs: BrowserPreferences
    ): Selection? {
        val t = url.trim().lowercase()
        if (t.startsWith("about:") || t.startsWith("file:") || t.startsWith("data:")) {
            return families[BrowserFamily.IN_APP]?.let {
                Selection(BrowserFamily.IN_APP, "url-scheme-local", listOf(BrowserFamily.IN_APP))
            }
        }
        if (t.startsWith("http://127.0.0.1") || t.startsWith("http://localhost")) {
            return families[BrowserFamily.IN_APP]?.let {
                Selection(BrowserFamily.IN_APP, "url-host-loopback", listOf(BrowserFamily.IN_APP))
            }
        }
        if (t.startsWith("http://") || t.startsWith("https://")) {
            val order = if (prefs.allowRemoteConnect)
                listOf(BrowserFamily.IN_APP, BrowserFamily.EXTERNAL_CT)
            else listOf(BrowserFamily.IN_APP)
            val pick = order.firstOrNull { families[it] != false } ?: BrowserFamily.IN_APP
            return Selection(pick, "url-scheme-http(s)", listOf(pick))
        }
        return null
    }

    private fun pickFallback(
        families: Map<BrowserFamily, Boolean>,
        prefs: BrowserPreferences,
        exclude: BrowserFamily? = null
    ): BrowserFamily {
        val order = listOfNotNull(
            BrowserFamily.IN_APP.takeIf { it != exclude && families[it] != false },
            BrowserFamily.EXTERNAL_CT.takeIf { it != exclude && families[it] != false },
            BrowserFamily.REMOTE_CDP.takeIf { it != exclude && families[it] != false }
        )
        return order.firstOrNull() ?: BrowserFamily.IN_APP
    }
}

