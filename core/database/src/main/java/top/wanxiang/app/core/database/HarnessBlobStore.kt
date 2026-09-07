package top.wanxiang.app.core.database

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🌟 万象大载荷离线沙箱持久化存储 (Harness Large Payload Storage)
 *
 * 彻底解决 Android SQLite CursorWindow (1MB~2MB) 游标单行大小物理上限问题。
 * - 普通小载荷 (< 48KB): 原样内联存储在 SQLite `harness_entries.payloadJson` 中，零额外文件 IO 开销。
 * - 大载荷 (>= 48KB): 自动外置化存储至应用私有沙箱文件 (`files/harness_blobs/{sessionId}/{entryId}.json`)，
 *   数据库内仅记录引用标识 (`@@WANXIANG_BLOB@@:harness_blobs/...`)。
 * - 会话删除时：自动级联清理该会话名下的所有外置沙箱文件。
 */
@Singleton
class HarnessBlobStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val blobBaseDir by lazy {
        File(context.filesDir, "harness_blobs").apply { mkdirs() }
    }

    /**
     * 写入检查：如果 [payloadJson] 超过阈值，则外置化存入沙箱文件并返回指向文件的引用指针；
     * 否则直接返回原 [payloadJson]。
     */
    fun storeIfLarge(sessionId: String, entryId: String, payloadJson: String): String {
        if (payloadJson.length < MAX_INLINE_PAYLOAD_CHARS) {
            return payloadJson
        }
        return runCatching {
            val sessionDir = File(blobBaseDir, sanitizeFileName(sessionId)).apply { mkdirs() }
            val blobFile = File(sessionDir, "${sanitizeFileName(entryId)}.json")
            blobFile.writeText(payloadJson, Charsets.UTF_8)
            BLOB_PREFIX + blobFile.relativeTo(context.filesDir).path
        }.getOrElse {
            payloadJson
        }
    }

    /**
     * 读取还原：如果 [payloadOrPointer] 是外置引用指针，则从沙箱文件中读取还原；
     * 否则原样返回。
     */
    fun read(payloadOrPointer: String): String {
        if (!payloadOrPointer.startsWith(BLOB_PREFIX)) {
            return payloadOrPointer
        }
        val relativePath = payloadOrPointer.removePrefix(BLOB_PREFIX)
        val file = File(context.filesDir, relativePath)
        return if (file.exists() && file.isFile) {
            runCatching { file.readText(Charsets.UTF_8) }.getOrDefault(payloadOrPointer)
        } else {
            payloadOrPointer
        }
    }

    /**
     * 清理指定会话的外置沙箱大文件。
     */
    fun deleteSessionBlobs(sessionId: String) {
        runCatching {
            val sessionDir = File(blobBaseDir, sanitizeFileName(sessionId))
            if (sessionDir.exists()) {
                sessionDir.deleteRecursively()
            }
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    companion object {
        const val BLOB_PREFIX = "@@WANXIANG_BLOB@@:"
        const val MAX_INLINE_PAYLOAD_CHARS = 48 * 1024 // 48 KB
    }
}