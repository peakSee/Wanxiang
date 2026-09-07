package top.wanxiang.app.core.common.logging

import android.util.Log
import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用日志：普通运行日志与智能体调试日志统一写入公共 Download/WanXiang 目录（自动创建），
 * 便于用户直接从文件管理器取走日志。未授予"所有文件访问"或公共目录写入失败时，
 * 回退到应用私有 files/logs 目录，保证日志永不静默丢失。
 */
@Singleton
class AppLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secretRedactor: SensitiveDataRedactor,
) {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Any()

    fun d(message: String, throwable: Throwable? = null) = log("D", message, throwable) { text, error ->
        Log.d(TAG, text, error)
    }

    fun i(message: String, throwable: Throwable? = null) = log("I", message, throwable) { text, error ->
        Log.i(TAG, text, error)
    }

    fun w(message: String, throwable: Throwable? = null) = log("W", message, throwable) { text, error ->
        Log.w(TAG, text, error)
    }

    fun e(message: String, throwable: Throwable? = null) = log("E", message, throwable) { text, error ->
        Log.e(TAG, text, error)
    }

    private fun log(
        level: String,
        message: String,
        throwable: Throwable?,
        androidLog: (String, Throwable?) -> Unit,
    ) {
        val safeMessage = safe(message)
        val safeThrowable = safe(throwable)
        androidLog(safeMessage, safeThrowable)
        val stack = throwable?.let { secretRedactor.redact(it.stackTraceToString()) }.orEmpty()
        ioScope.launch {
            runCatching {
                append(
                    fileName = GENERAL_LOG_FILE,
                    content = "${System.currentTimeMillis()} [$level] $safeMessage" +
                        if (stack.isBlank()) "\n" else "\n$stack\n",
                    maxBytes = MAX_LOG_BYTES,
                    rotateMarker = "",
                )
            }
        }
    }

    fun logAgent(sessionId: String, tag: String, message: String, throwable: Throwable? = null) {
        val safeMessage = safe(message)
        val safeTag = safe(tag)
        val safeSessionId = safe(sessionId)
        val stack = throwable?.let { secretRedactor.redact(it.stackTraceToString()) }.orEmpty()
        Log.d(TAG, "[$safeTag][$safeSessionId] $safeMessage", throwable)
        ioScope.launch {
            runCatching {
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(java.util.Date())
                append(
                    fileName = AGENT_LOG_FILE,
                    content = "[$timestamp][$safeTag][session:$safeSessionId] $safeMessage" +
                        if (stack.isBlank()) "\n" else "\n$stack\n",
                    maxBytes = MAX_AGENT_LOG_BYTES,
                    rotateMarker = "[LOG ROTATED at ${System.currentTimeMillis()}]\n",
                )
            }
        }
    }

    /** 当前智能体日志的实际落盘位置描述（含回退目录），供开发者界面展示。 */
    fun getAgentLogLocation(): String {
        val publicFile = publicLogFile(AGENT_LOG_FILE)
        return if (publicFile != null && publicFile.exists()) {
            publicFile.absolutePath
        } else if (Environment.isExternalStorageManager()) {
            "${publicFile?.absolutePath}（尚未创建）"
        } else {
            fallbackLogFile(AGENT_LOG_FILE).absolutePath + "（未授予所有文件访问）"
        }
    }

    fun readAgentLogs(maxChars: Int = 100_000): String = runCatching {
        val file = existingLogFile(AGENT_LOG_FILE)
            ?: return@runCatching "暂无智能体本地日志"
        val text = file.readText(Charsets.UTF_8)
        when {
            text.isEmpty() -> "暂无智能体本地日志"
            text.length > maxChars -> text.takeLast(maxChars)
            else -> text
        }
    }.getOrDefault("读取日志失败")

    fun getAgentLogSizeBytes(): Long = runCatching {
        existingLogFile(AGENT_LOG_FILE)?.length() ?: 0L
    }.getOrDefault(0L)

    fun clearAgentLogs(): Boolean = runCatching {
        var ok = true
        publicLogFile(AGENT_LOG_FILE)?.takeIf { it.exists() }?.let { ok = it.delete() && ok }
        fallbackLogFile(AGENT_LOG_FILE).takeIf { it.exists() }?.let { ok = it.delete() && ok }
        ok
    }.getOrDefault(false)

    /** 追加一行日志：优先公共目录，失败时回退私有目录。 */
    private fun append(fileName: String, content: String, maxBytes: Long, rotateMarker: String) {
        synchronized(writeLock) {
            val target = publicLogFile(fileName) ?: fallbackLogFile(fileName)
            try {
                writeTo(target, content, maxBytes, rotateMarker)
            } catch (t: Throwable) {
                if (target != fallbackLogFile(fileName)) {
                    writeTo(fallbackLogFile(fileName), content, maxBytes, rotateMarker)
                } else {
                    throw t
                }
            }
        }
    }

    private fun writeTo(file: File, content: String, maxBytes: Long, rotateMarker: String) {
        file.parentFile?.mkdirs()
        if (file.length() > maxBytes) file.writeText(rotateMarker)
        file.appendText(content)
    }

    /** 公共 Download/WanXiang 目录下的日志文件；未授予所有文件访问时返回 null。 */
    private fun publicLogFile(fileName: String): File? {
        if (!Environment.isExternalStorageManager()) return null
        return runCatching {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_LOG_DIR)
                .let { File(it, fileName) }
        }.getOrNull()
    }

    private fun fallbackLogFile(fileName: String): File = File(File(context.filesDir, INTERNAL_LOG_DIR), fileName)

    /** 读取/统计时优先取公共目录中已存在的文件，其次私有目录。 */
    private fun existingLogFile(fileName: String): File? =
        publicLogFile(fileName)?.takeIf { it.exists() }
            ?: fallbackLogFile(fileName).takeIf { it.exists() }

    private fun safe(value: String): String = secretRedactor.redact(value)

    private fun safe(throwable: Throwable?): Throwable? = throwable?.let {
        val message = it.message.orEmpty()
        if (safe(message) == message) it else IllegalStateException(safe(message))
    }

    private companion object {
        const val TAG = "WanXiang"
        const val PUBLIC_LOG_DIR = "WanXiang"
        const val INTERNAL_LOG_DIR = "logs"
        const val GENERAL_LOG_FILE = "runtime.log"
        const val AGENT_LOG_FILE = "agent_debug.log"
        const val MAX_LOG_BYTES = 1024L * 1024L
        const val MAX_AGENT_LOG_BYTES = 5 * 1024L * 1024L
    }
}
