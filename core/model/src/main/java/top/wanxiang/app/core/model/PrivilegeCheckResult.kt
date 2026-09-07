package top.wanxiang.app.core.model

/**
 * 特权探测与授权结果。
 */
sealed interface PrivilegeCheckResult {
    val mode: ExecutionMode

    data class Authorized(
        override val mode: ExecutionMode,
        val details: String,
    ) : PrivilegeCheckResult

    data class Unauthorized(
        override val mode: ExecutionMode,
        val reason: String,
    ) : PrivilegeCheckResult

    data class ServiceNotRunning(
        override val mode: ExecutionMode,
        val guidance: String,
    ) : PrivilegeCheckResult
}
