package top.wanxiang.app.core.common.logging

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Stores redacted crash reports locally; no network upload is performed. */
@Singleton
class CrashReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secretRedactor: SensitiveDataRedactor,
) {
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(thread, throwable)
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    fun latestReport(): String? = File(context.filesDir, REPORT_PATH)
        .takeIf { it.isFile }
        ?.readText()

    /** Copies reports to Download/WanXiang/crash-reports after the next successful launch. */
    fun exportPendingReports(): Int {
        val reportDir = reportDirectory()
        val pending = reportDir.listFiles { file ->
            file.isFile && file.name.startsWith(REPORT_FILE_PREFIX) && file.extension == "txt" &&
                !File(reportDir, "${file.name}$EXPORTED_MARKER_SUFFIX").exists()
        }.orEmpty()

        var exported = 0
        pending.sortedBy { it.lastModified() }.forEach { report ->
            if (exportToDownloads(report)) {
                runCatching {
                    File(reportDir, "${report.name}$EXPORTED_MARKER_SUFFIX").writeText("")
                }
                exported++
            }
        }
        return exported
    }

    private fun write(thread: Thread, throwable: Throwable) {
        runCatching {
            val timestamp = System.currentTimeMillis()
            val rawReport = buildString {
                appendLine("WanXiang crash report")
                appendLine("reportTime=${formatTimestamp(timestamp)}")
                appendLine("reportTimeMillis=$timestamp")
                appendLine("package=${context.packageName}")
                appendLine("version=${appVersion()}")
                appendLine("processId=${Process.myPid()}")
                appendLine("thread=${thread.name} (${thread.id})")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("exception=${throwable.javaClass.name}")
                appendLine("message=${throwable.message.orEmpty()}")
                appendLine()
                appendLine(throwable.stackTraceToString())
            }
            val report = runCatching { secretRedactor.redact(rawReport) }
                .getOrElse { rawReport }
                .take(MAX_REPORT_CHARS)
            val reportDir = reportDirectory().also(File::mkdirs)
            File(context.filesDir, REPORT_PATH).apply {
                parentFile?.mkdirs()
                writeText(report)
            }
            File(reportDir, "$REPORT_FILE_PREFIX${fileTimestamp(timestamp)}.txt").writeText(report)
            pruneOldReports(reportDir)
        }
    }

    private fun exportToDownloads(report: File): Boolean = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, report.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, EXPORT_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                report.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Unable to open crash report destination")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            true
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun appVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName.orEmpty()} (${info.longVersionCode})"
    }.getOrDefault("unknown")

    private fun reportDirectory(): File = File(context.filesDir, REPORT_DIRECTORY)

    private fun pruneOldReports(directory: File) {
        val reports = directory.listFiles { file ->
            file.isFile && file.name.startsWith(REPORT_FILE_PREFIX) && file.extension == "txt"
        }.orEmpty().sortedByDescending { it.lastModified() }
        reports.drop(MAX_REPORT_FILES).forEach { report ->
            runCatching { report.delete() }
            runCatching { File(directory, "${report.name}$EXPORTED_MARKER_SUFFIX").delete() }
        }
    }

    private fun formatTimestamp(value: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(value))

    private fun fileTimestamp(value: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(value))

    private companion object {
        const val REPORT_PATH = "logs/crash/latest.txt"
        const val REPORT_DIRECTORY = "logs/crash/reports"
        const val REPORT_FILE_PREFIX = "wanxiang-crash-"
        const val EXPORTED_MARKER_SUFFIX = ".exported"
        val EXPORT_RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/WanXiang/crash-reports"
        const val MAX_REPORT_CHARS = 256 * 1024
        const val MAX_REPORT_FILES = 20
    }
}
