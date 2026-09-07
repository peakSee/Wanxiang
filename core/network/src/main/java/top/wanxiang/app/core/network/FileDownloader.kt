package top.wanxiang.app.core.network

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.utils.io.readAvailable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

private const val DEFAULT_DOWNLOAD_MAX_BYTES = 1024L * 1024L * 1024L

data class DownloadRequest(
    val url: String,
    val destination: File,
    val partialFile: File = File("${destination.absolutePath}.part"),
    val sha256: String? = null,
    val maxAttempts: Int = 3,
    val maxBytes: Long = DEFAULT_DOWNLOAD_MAX_BYTES,
)

sealed interface DownloadEvent {
    data object Started : DownloadEvent
    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long?,
    ) : DownloadEvent
    data object Verifying : DownloadEvent
    data class Completed(val file: File) : DownloadEvent
}

interface FileDownloader {
    fun download(request: DownloadRequest): Flow<DownloadEvent>
}

@Singleton
class ResumableFileDownloader @Inject constructor(
    private val httpClient: HttpClient,
    private val checksumVerifier: ChecksumVerifier,
) : FileDownloader {
    override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        if (!request.url.startsWith("https://", ignoreCase = true)) {
            throw DownloadError.Security("下载地址必须使用 HTTPS：${request.url}")
        }
        require(request.maxAttempts > 0) { "maxAttempts 必须大于 0" }
        require(request.maxBytes > 0) { "maxBytes 必须大于 0" }
        val destinationParent = request.destination.parentFile
        if (destinationParent != null && !destinationParent.exists() && !destinationParent.mkdirs()) {
            throw DownloadError.Storage("无法创建下载目录：${destinationParent.absolutePath}")
        }
        val partialParent = request.partialFile.parentFile
        if (partialParent != null && !partialParent.exists() && !partialParent.mkdirs()) {
            throw DownloadError.Storage("无法创建临时下载目录：${partialParent.absolutePath}")
        }
        emit(DownloadEvent.Started)

        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < request.maxAttempts) {
            attempt += 1
            try {
                downloadOnce(request) { downloaded, total ->
                    emit(DownloadEvent.Progress(downloaded, total))
                }
                if (!request.sha256.isNullOrBlank()) {
                    emit(DownloadEvent.Verifying)
                    try {
                        checksumVerifier.verify(request.destination, request.sha256)
                    } catch (checksum: DownloadError.ChecksumMismatch) {
                        request.destination.delete()
                        request.partialFile.delete()
                        throw checksum
                    }
                }
                emit(DownloadEvent.Completed(request.destination))
                return@flow
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (restart: RestartFromZeroException) {
                request.partialFile.delete()
                lastError = restart
                if (attempt < request.maxAttempts) delay(RETRY_DELAY_MS * attempt)
            } catch (nonRetryable: DownloadError.Security) {
                throw nonRetryable
            } catch (nonRetryable: DownloadError.ChecksumMismatch) {
                throw nonRetryable
            } catch (nonRetryable: DownloadError.Storage) {
                throw nonRetryable
            } catch (http: DownloadError.Http) {
                if (http.statusCode in 400..499 && http.statusCode !in setOf(408, 429)) {
                    throw http
                }
                lastError = http
                if (attempt < request.maxAttempts) delay(RETRY_DELAY_MS * attempt)
            } catch (throwable: Throwable) {
                lastError = throwable
                if (attempt < request.maxAttempts) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            }
        }
        throw DownloadError.RetryExhausted(
            "下载失败（${request.url}）：${lastError?.message ?: "未知错误"}",
            lastError,
        )
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadOnce(
        request: DownloadRequest,
        onProgress: suspend (Long, Long?) -> Unit,
    ) {
        val existingBytes = request.partialFile.takeIf { it.isFile }?.length() ?: 0L
        if (existingBytes > request.maxBytes) {
            throw DownloadError.Storage("临时下载文件超过大小限制：${request.maxBytes} bytes")
        }
        httpClient.prepareGet(request.url) {
            // 图床/CDN 普遍对无 UA 的裸请求做反爬（返回统一占位图），模拟浏览器并带上站点根 Referer。
            header(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
            header("Referer", refererRootOf(request.url))
            if (existingBytes > 0L) {
                header(HttpHeaders.Range, "bytes=$existingBytes-")
            }
        }.execute { response ->
            if (response.request.url.protocol != URLProtocol.HTTPS) {
                throw DownloadError.Security("下载被重定向到非 HTTPS 地址")
            }
            if (response.status.value == 416 && existingBytes > 0L) {
                throw RestartFromZeroException()
            }
            if (response.status.value !in 200..299) {
                throw DownloadError.Http(response.status.value, "HTTP ${response.status.value}")
            }

            val body = response.bodyAsChannel()
            val append = existingBytes > 0L && response.status.value == 206
            if (append) {
                val contentRange = response.headers[HttpHeaders.ContentRange].orEmpty()
                if (!contentRange.startsWith("bytes $existingBytes-")) {
                    throw RestartFromZeroException()
                }
            }
            val baseBytes = if (append) existingBytes else 0L
            if (!append) request.partialFile.delete()
            val totalBytes = response.headers[HttpHeaders.ContentLength]
                ?.toLongOrNull()
                ?.let { advertised ->
                if (advertised > request.maxBytes - baseBytes) request.maxBytes + 1 else advertised + baseBytes
            }
            if (totalBytes != null && totalBytes > request.maxBytes) {
                throw DownloadError.Storage("下载文件超过大小限制：${request.maxBytes} bytes")
            }
            var downloadedBytes = baseBytes
            onProgress(downloadedBytes, totalBytes)

            FileOutputStream(request.partialFile, append).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = body.readAvailable(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    if (downloadedBytes > request.maxBytes) {
                        throw DownloadError.Storage("下载文件超过大小限制：${request.maxBytes} bytes")
                    }
                    onProgress(downloadedBytes, totalBytes)
                }
                output.flush()
            }
        }

        try {
            runCatching {
                Files.move(
                    request.partialFile.toPath(),
                    request.destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(
                    request.partialFile.toPath(),
                    request.destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (throwable: Throwable) {
            throw DownloadError.Storage(
                "无法提交下载文件：${request.destination.absolutePath}",
                throwable,
            )
        }
    }

    private fun refererRootOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return url
        val hostStart = schemeEnd + 3
        if (hostStart >= url.length) return url
        val host = url.substring(hostStart).substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.isEmpty()) return url
        return url.substring(0, hostStart) + host + "/"
    }

    private class RestartFromZeroException : IOException()

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val RETRY_DELAY_MS = 500L
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) 122.0.0.0 Mobile Safari/537.36"
    }
}
