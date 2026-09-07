package top.wanxiang.app.runtime.browser.secret

import top.wanxiang.app.core.security.SecretRedactor

/**
 * 把浏览器侧产出的字符串统一过 [SecretRedactor]，避免带 cookie/Authorization 的页面源码、
 * network headers、console message 直接回灌到 LLM。
 */
object SecretRedactingInterceptor {

    private val redactor by lazy { SecretRedactor() }

    fun apply(input: String): String = redactCookieValues(redactor.redact(input))

    /**
     * 对 headers 字典做敏感字段处理：对 name 命中（authorization / cookie / set-cookie）值打码，
     * 其余原样返回；非命中也走一次 [SecretRedactor.redact]，以防 value 里夹带密钥。
     */
    fun apply(headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return headers
        val sensitiveKeys = setOf("authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token")
        return headers.mapValues { (k, v) ->
            val redacted = redactor.redact(v)
            if (k.lowercase() in sensitiveKeys) "[REDACTED_${k.uppercase()}]" else redacted
        }
    }

    /** 常见会话类 cookie 名（忽略大小写；名字等于或以后缀形式命中，如 csrftoken / connect.sid / JSESSIONID）。 */
    private val SESSION_COOKIE_NAMES = setOf("sid", "sess", "session", "sessionid", "token", "auth", "jwt", "csrf")

    /** 长随机值特征：cookie value 达到该长度即视为 token 打码。 */
    private const val COOKIE_VALUE_REDACT_MIN = 32

    /** cookie 串形态：至少两个 `name=value` 对以 `;` 分隔（如 `a=b; c=d`）；限定单行，避免误吞普通文本。 */
    private val COOKIE_LIST = Regex(
        "[A-Za-z0-9_.-]{1,64}\\s*=\\s*[^;\\r\\n]+(?:\\s*;\\s*[A-Za-z0-9_.-]{1,64}\\s*=\\s*[^;\\r\\n]+)+",
    )

    /** 单个 `name=value` 对（`;` 之前、单行之内）。 */
    private val COOKIE_PAIR = Regex("([A-Za-z0-9_.-]{1,64})\\s*=\\s*([^;\\r\\n]+)")

    /**
     * 补齐 [SecretRedactor] 覆盖不到的 cookie 表单值（`session=abc123; theme=dark`）：
     * 1) 名字命中会话类 cookie（`session=abc` 即使不在完整 cookie 串中也视作敏感）；
     * 2) cookie 串（`;` 分隔多对）里 value ≥32 字符的长随机值同样打码。
     */
    private fun redactCookieValues(input: String): String {
        if (!input.contains('=')) return input
        val nameSensitized = COOKIE_PAIR.replace(input) { pair ->
            val (name, _) = pair.destructured
            if (isSessionCookieName(name)) "${pair.groupValues[1]}=[REDACTED]" else pair.value
        }
        return COOKIE_LIST.replace(nameSensitized) { list ->
            COOKIE_PAIR.replace(list.value) { pair ->
                val (name, value) = pair.destructured
                if (isSessionCookieName(name) || value.trim().length >= COOKIE_VALUE_REDACT_MIN) {
                    "${pair.groupValues[1]}=[REDACTED]"
                } else {
                    pair.value
                }
            }
        }
    }

    private fun isSessionCookieName(name: String): Boolean {
        val normalized = name.lowercase().removePrefix("__host-").removePrefix("__secure-")
        if (normalized in SESSION_COOKIE_NAMES) return true
        return SESSION_COOKIE_NAMES.any { normalized.endsWith(it) }
    }
}
