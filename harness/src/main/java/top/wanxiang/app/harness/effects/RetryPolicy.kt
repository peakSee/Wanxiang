package top.wanxiang.app.harness.effects

data class RetryPolicy(
    val enabled: Boolean,
    val maxRetries: Int,
    val baseDelayMs: Long,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be non-negative" }
        require(baseDelayMs >= 0) { "baseDelayMs must be non-negative" }
    }

    val maxAttempts: Int get() = if (enabled) maxRetries + 1 else 1

    fun delayForRetry(retryNumber: Int): Long {
        require(retryNumber >= 1) { "retryNumber starts at one" }
        if (!enabled) return 0
        val shift = (retryNumber - 1).coerceAtMost(62)
        val multiplier = 1L shl shift
        return if (baseDelayMs > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else baseDelayMs * multiplier
    }

    companion object {
        val NETWORK_DEFAULT = RetryPolicy(enabled = true, maxRetries = 3, baseDelayMs = 1_500)
    }
}
