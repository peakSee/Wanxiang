package top.wanxiang.app.runtime.browser.cdp

/**
 * URL glob 匹配（`*` 任意串 / `?` 单字符）。
 *
 * 语义与 assets/hook_runtime.js 的 globToRegExp 完全一致：
 * 双层（注入层 / CDP 层）用同一匹配语义，同一规则命中结果一致。
 */
object CdpGlob {

    fun toRegex(pattern: String): Regex {
        val sb = StringBuilder()
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> {
                    // 非 glob 元字符全部按正则字面量转义
                    if (c.isLetterOrDigit() || c == '_') sb.append(c)
                    else sb.append('\\').append(c)
                }
            }
        }
        return Regex("^$sb$")
    }

    fun matches(pattern: String, url: String): Boolean = toRegex(pattern).matches(url)
}
