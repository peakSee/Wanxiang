package top.wanxiang.app.runtime

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { downloadedBytes.toFloat() / it }

    val downloadedMegabytes: Long
        get() = downloadedBytes / BYTES_PER_MB

    val totalMegabytes: Long?
        get() = totalBytes?.let { it / BYTES_PER_MB }

    companion object {
        private const val BYTES_PER_MB = 1024L * 1024L
    }
}
