package top.wanxiang.app.core.network

import java.io.IOException

sealed class DownloadError(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    class Security(message: String) : DownloadError(message)
    class Http(val statusCode: Int, message: String) : DownloadError(message)
    class EmptyResponse : DownloadError("服务器返回空内容")
    class ChecksumMismatch(
        val expected: String,
        val actual: String,
    ) : DownloadError("SHA-256 校验失败：expected=$expected actual=$actual")
    class Storage(message: String, cause: Throwable? = null) : DownloadError(message, cause)
    class RetryExhausted(message: String, cause: Throwable? = null) : DownloadError(message, cause)
}
