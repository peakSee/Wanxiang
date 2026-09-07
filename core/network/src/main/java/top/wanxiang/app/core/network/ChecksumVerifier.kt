package top.wanxiang.app.core.network

import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChecksumVerifier @Inject constructor() {
    fun sha256(file: File): String {
        require(file.isFile) { "文件不存在：${file.absolutePath}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    fun verify(file: File, expectedSha256: String) {
        if (expectedSha256.isBlank()) return
        val actual = sha256(file)
        if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
            throw DownloadError.ChecksumMismatch(expectedSha256.trim(), actual)
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
