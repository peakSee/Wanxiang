package top.wanxiang.app.core.common.logging

/** Minimal logging boundary that keeps the common module independent of security implementations. */
fun interface SensitiveDataRedactor {
    fun redact(value: String): String
}
