package top.wanxiang.app.harness

import top.wanxiang.app.core.common.result.AppError
import top.wanxiang.app.core.common.result.AppResult
import top.wanxiang.app.core.common.result.ErrorCode
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WorkspaceEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

/**
 * Harness 文件工具的受控访问层。
 *
 * 只允许在工作区根目录内操作：
 * - 拒绝 `..` 段、绝对路径逃逸、符号链接逃逸（canonical 校验）
 * - 写入使用临时文件 + 原子 rename，避免半写文件
 * - 读取限制单文件大小，防止把巨型文件灌进 LLM 上下文
 */
class WorkspaceFileAccess(
    private val root: File,
    private val globalRootCanonical: File? = null,
) {
    private val rootCanonical: File = root.absoluteFile.canonicalFile

    suspend fun list(path: String): AppResult<List<WorkspaceEntry>> = withContext(Dispatchers.IO) {
        try {
            val directory = resolveRequired(path)
            check(directory.isDirectory) { "不是目录：${display(path)}" }
            val entries = Files.newDirectoryStream(directory.toPath()).use { stream ->
                val iterator = stream.iterator()
                buildList {
                    while (iterator.hasNext() && size < MAX_LIST_ENTRIES) {
                        val file = iterator.next().toFile()
                        add(WorkspaceEntry(file.name, file.isDirectory, if (file.isFile) file.length() else 0L))
                    }
                }
            }
                .sortedWith(compareBy<WorkspaceEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
            AppResult.Success(entries)
        } catch (throwable: Throwable) {
            failure(path, throwable)
        }
    }

    /**
     * 读取文件内容。支持行级分页（pi 的 read 工具设计）：
     * [offset] 为 1 起始的行号，[limit] 为最多读取的行数（上限 [DEFAULT_READ_LINES]）；
     * 分页时在内容前加范围头，让模型知道文件总行数与当前窗口，便于继续翻页或精确定位。
     * 无参调用对小文件返回全文；超过 [DEFAULT_READ_LINES] 行的大文件自动限窗，
     * 并在范围头里给出续读偏移，避免模型在不知道截断的情况下基于半份内容做分析。
     */
    suspend fun read(path: String, offset: Int? = null, limit: Int? = null): AppResult<String> = withContext(Dispatchers.IO) {
        try {
            val file = resolveRequired(path)
            check(file.isFile) { "不是文件：${display(path)}" }
            check(file.length() <= MAX_READ_BYTES) {
                "文件过大（${file.length()} 字节，上限 ${MAX_READ_BYTES}）。" +
                    "请用 base 执行 grep -n 定位关键行号，再用 read(path, offset, limit) 分段精读。"
            }
            val content = file.readText(Charsets.UTF_8)
            val lines = content.split('\n')
            val totalLines = if (content.endsWith("\n")) lines.size - 1 else lines.size
            if (offset == null && limit == null && totalLines <= DEFAULT_READ_LINES) {
                AppResult.Success(content)
            } else {
                val effectiveLimit = (limit ?: DEFAULT_READ_LINES).coerceIn(1, DEFAULT_READ_LINES)
                val start = ((offset ?: 1) - 1).coerceIn(0, totalLines)
                val end = minOf(start + effectiveLimit, totalLines)
                val selected = lines.subList(start, end)
                val header = buildString {
                    append("[文件 ${display(path)} 共 $totalLines 行，当前显示第 ${start + 1}-$end 行")
                    if (end < totalLines) append("；内容未完，继续读取请用 offset=${end + 1}")
                    append("]\n")
                }
                AppResult.Success(header + selected.joinToString("\n"))
            }
        } catch (throwable: Throwable) {
            failure(path, throwable)
        }
    }

    suspend fun write(path: String, content: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val contentBytes = content.toByteArray(Charsets.UTF_8).size
            require(contentBytes <= MAX_WRITE_BYTES) { "内容过长（$contentBytes 字节，上限 $MAX_WRITE_BYTES）" }
            val file = resolveWritable(path)
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
            try {
                temporary.writeText(content, Charsets.UTF_8)
                if (!temporary.renameTo(file)) {
                    temporary.copyTo(file, overwrite = true)
                    temporary.delete()
                }
            } finally {
                temporary.delete()
            }
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            failure(path, throwable)
        }
    }

    suspend fun edit(path: String, oldText: String, newText: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            require(oldText.isNotEmpty()) { "oldText 不能为空" }
            val file = resolveWritable(path)
            check(file.isFile) { "不是文件：${display(path)}" }
            check(file.length() <= MAX_EDIT_BYTES) {
                "文件过大（${file.length()} 字节，上限 $MAX_EDIT_BYTES），请使用 base 的流式文本工具进行定点修改"
            }
            val content = file.readText(Charsets.UTF_8)
            val first = content.indexOf(oldText)
            check(first >= 0) { "oldText 未找到" }
            check(first == content.lastIndexOf(oldText)) { "oldText 匹配多处，请提供更精确的上下文" }
            write(path, content.replaceRange(first, first + oldText.length, newText))
                .errorOrNull()?.let { throw it.cause ?: IllegalStateException(it.message) }
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            failure(path, throwable)
        }
    }

    /** 解析路径并强制其位于工作区内；目录可以不存在（用于写入）。 */
    private fun resolveWritable(path: String): File {
        val resolved = resolve(path)
            ?: throw IllegalArgumentException("路径越界：${display(path)}")
        if (resolved.exists() && resolved.isDirectory) {
            throw IllegalArgumentException("是目录：${display(path)}")
        }
        return resolved
    }

    /** 解析路径，且要求目标存在。 */
    private fun resolveRequired(path: String): File {
        val resolved = resolve(path)
            ?: throw IllegalArgumentException("路径越界：${display(path)}")
        check(resolved.exists()) { "不存在：${display(path)}" }
        return resolved
    }

    /** Resolve a download destination while preserving the workspace boundary checks. */
    fun resolveDownloadDestination(path: String): File = resolveWritable(path)

    /**
     * 读取指定路径当前内容；文件不存在或无权限读取时返回 null（不抛异常）。
     * 供 checkpoint 快照在写工具触碰前捕获轮初内容。
     */
    suspend fun previewOrNull(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val file = resolveRequired(path)
            if (!file.isFile) null else file.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    /** 文件字节数（路径越界/非文件/不存在返回 null）；供 checkpoint 预判是否跳过超大文件快照。 */
    suspend fun fileSizeOrNull(path: String): Long? = withContext(Dispatchers.IO) {
        try {
            val file = resolveRequired(path)
            if (file.isFile) file.length() else null
        } catch (_: Throwable) {
            null
        }
    }

    /** 在工作区边界内删除文件（路径逃逸或删除失败返回 false）；文件不存在视为成功。 */
    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = resolve(path) ?: return@withContext false
            if (!file.exists()) return@withContext true
            if (file.isDirectory) return@withContext false
            file.delete()
        } catch (_: Throwable) {
            false
        }
    }

    /** Cheap cache stamp for prompt-relevant workspace metadata without reading file bodies. */
    suspend fun changeStamp(path: String, relativePaths: List<String>): Long = withContext(Dispatchers.IO) {
        val base = resolveRequired(path)
        var stamp = base.lastModified()
        relativePaths.forEach { relative ->
            val child = File(base, relative).canonicalFile
            if (isInside(base, child) && child.exists()) {
                stamp = stamp * 31L + child.lastModified()
                stamp = stamp * 31L + child.length()
            }
        }
        stamp
    }

    fun withBase(workspaceBase: String): WorkspaceFileAccess {
        val clean = workspaceBase.trim().removePrefix("/workspace/").removePrefix("/workspace").removePrefix("/")
        if (clean.isBlank()) return this
        val target = File(root, clean)
        val canonical = target.canonicalFile
        return if (isInside(rootCanonical, canonical)) WorkspaceFileAccess(canonical, rootCanonical) else this
    }

    private fun resolve(path: String): File? {
        val trimmed = path.trim().replace('\\', '/')
        val segments = trimmed.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        if (trimmed.startsWith("/") && segments.isNotEmpty() && segments.first() != "workspace") return null

        val isWorkspacePrefixed = segments.isNotEmpty() && segments.first() == "workspace"
        val baseRoot = if (isWorkspacePrefixed) (globalRootCanonical ?: rootCanonical) else root
        val relative = if (isWorkspacePrefixed) segments.drop(1) else segments
        var candidate = baseRoot
        for (segment in relative) {
            candidate = File(candidate, segment)
        }
        if (candidate == baseRoot) return baseRoot
        val canonical = candidate.canonicalFile
        val allowedRoot = globalRootCanonical ?: rootCanonical
        return canonical.takeIf { isInside(allowedRoot, it) }
    }

    private fun isInside(root: File, candidate: File): Boolean =
        candidate.absolutePath == root.absolutePath ||
            candidate.absolutePath.startsWith(root.absolutePath + File.separator)

    private fun display(path: String): String =
        if (path.trim().startsWith("/")) path.trim() else "/workspace/${path.trim().trimStart('/')}"

    private fun failure(path: String, throwable: Throwable): AppResult<Nothing> =
        AppResult.Failure(AppError(ErrorCode.IO, "${display(path)}：${throwable.message}", throwable))

    companion object {
        const val MAX_READ_BYTES = 1 * 1024 * 1024L
        const val MAX_WRITE_BYTES = 1 * 1024 * 1024
        const val MAX_EDIT_BYTES = 1 * 1024 * 1024L
        const val MAX_LIST_ENTRIES = 5_000

        /** 无参 read 的默认行窗口；单次 limit 也以此为上限，避免单次读取灌爆工具输出。 */
        const val DEFAULT_READ_LINES = 2000
    }
}
