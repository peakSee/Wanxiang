package top.wanxiang.app.runtime.tools

import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

data class LoginUrl(
    val url: String,
    val host: String,
)

/** Finds explicit browser-login links in CLI output without opening them automatically. */
@Singleton
class AuthUrlDetector @Inject constructor() {
    fun find(text: String): List<LoginUrl> = URL_PATTERN.findAll(text)
        .mapNotNull { match ->
            val candidate = match.value.trimEnd('.', ',', ';', ':', ')', ']', '}')
            val uri = runCatching { URI(candidate) }.getOrNull() ?: return@mapNotNull null
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase().orEmpty()
            val hint = listOf(uri.host.orEmpty(), uri.path.orEmpty(), uri.query.orEmpty())
                .joinToString(" ")
                .lowercase()
            if (scheme != "https" || host.isBlank() || AUTH_HINTS.none(hint::contains)) {
                return@mapNotNull null
            }
            LoginUrl(candidate, host)
        }
        .distinctBy { it.url }
        .toList()

    private companion object {
        val URL_PATTERN = Regex("https://[^\\s<>\\\"']+")
        val AUTH_HINTS = setOf(
            "oauth",
            "authorize",
            "authorization",
            "login",
            "signin",
            "sign-in",
            "device",
            "consent",
        )
    }
}
