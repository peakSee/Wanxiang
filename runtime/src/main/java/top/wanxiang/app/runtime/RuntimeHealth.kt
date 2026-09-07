package top.wanxiang.app.runtime

enum class RuntimeHealthStatus {
    HEALTHY,
    UNHEALTHY,
}

data class RuntimeHealth(
    val status: RuntimeHealthStatus,
    val osRelease: String? = null,
    val architecture: String? = null,
    val workspaceWritable: Boolean = false,
    val detail: String? = null,
) {
    val isHealthy: Boolean get() = status == RuntimeHealthStatus.HEALTHY
}
