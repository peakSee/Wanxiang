package top.wanxiang.app.core.common.result

data class AppError(
    val code: ErrorCode,
    val message: String,
    val cause: Throwable? = null,
)

enum class ErrorCode {
    UNKNOWN,
    IO,
    NETWORK,
    DOWNLOAD,
    CHECKSUM,
    RUNTIME_NOT_INITIALIZED,
    UNSUPPORTED_ARCHITECTURE,
    INSUFFICIENT_STORAGE,
    INSTALLATION_FAILED,
    DATABASE,
    SECURITY,
}