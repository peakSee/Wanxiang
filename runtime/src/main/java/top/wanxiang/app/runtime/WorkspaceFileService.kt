package top.wanxiang.app.runtime

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.wanxiang.app.core.common.files.SafeFileTree
import top.wanxiang.app.core.common.result.AppError
import top.wanxiang.app.core.common.result.AppResult
import top.wanxiang.app.core.common.result.ErrorCode
import top.wanxiang.app.core.database.WorkspaceRepository

/** Boundary-safe file operations for a registered workspace. */
@Singleton
class WorkspaceFileService @Inject constructor(
    private val pathManager: RuntimePathManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    suspend fun listFiles(projectName: String, relativePath: String = ""): AppResult<List<WorkspaceFileItem>> = ioResult("读取文件列表失败") {
        val directory = resolve(projectName, relativePath)
        check(directory.isDirectory) { "不是目录：${displayPath(projectName, relativePath)}" }
        val root = projectRoot(projectName)
        directory.listFiles().orEmpty().map { file ->
            WorkspaceFileItem(
                name = file.name,
                relativePath = file.toRelativeString(root).replace(File.separatorChar, '/'),
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isFile) file.length() else 0L,
                lastModified = file.lastModified(),
                extension = if (file.isFile) file.extension.lowercase() else "",
            )
        }.sortedWith(compareBy<WorkspaceFileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun readFile(projectName: String, relativePath: String): AppResult<String> = ioResult("读取文件失败") {
        val file = resolve(projectName, relativePath)
        check(file.isFile) { "不是文件：${displayPath(projectName, relativePath)}" }
        check(file.length() <= WorkspaceManager.MAX_FILE_READ_BYTES) {
            "文件过大（${file.length()} 字节，上限 ${WorkspaceManager.MAX_FILE_READ_BYTES / 1024 / 1024} MB）"
        }
        file.readText(Charsets.UTF_8)
    }

    suspend fun writeFile(projectName: String, relativePath: String, content: String): AppResult<Unit> = ioResult("保存文件失败") {
        require(content.length <= WorkspaceManager.MAX_FILE_WRITE_CHARS) {
            "内容过长（${content.length} 字符，上限 ${WorkspaceManager.MAX_FILE_WRITE_CHARS}）"
        }
        val file = resolve(projectName, relativePath, allowMissing = true)
        require(!file.isDirectory) { "目标是目录：${displayPath(projectName, relativePath)}" }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            temporary.writeText(content, Charsets.UTF_8)
            if (!temporary.renameTo(file)) temporary.copyTo(file, overwrite = true)
        } finally {
            temporary.delete()
        }
    }

    suspend fun createFile(projectName: String, relativePath: String): AppResult<Unit> = ioResult("创建文件失败") {
        val file = resolve(projectName, relativePath, allowMissing = true)
        check(!file.exists()) { "文件已存在：${file.name}" }
        file.parentFile?.mkdirs()
        check(file.createNewFile()) { "无法创建文件" }
    }

    suspend fun createDirectory(projectName: String, relativePath: String): AppResult<Unit> = ioResult("创建目录失败") {
        val directory = resolve(projectName, relativePath, allowMissing = true)
        check(!directory.exists()) { "目录已存在：${directory.name}" }
        check(directory.mkdirs()) { "无法创建目录" }
    }

    suspend fun renameItem(projectName: String, oldRelativePath: String, newName: String): AppResult<Unit> = ioResult("重命名失败") {
        val safeName = newName.trim()
        require(safeName.isNotBlank() && '/' !in safeName && '\\' !in safeName) { "新名称不合法" }
        val file = resolve(projectName, oldRelativePath)
        val target = File(file.parentFile, safeName)
        check(!target.exists()) { "目标已存在：$safeName" }
        check(file.renameTo(target)) { "重命名失败" }
    }

    suspend fun deleteItem(projectName: String, relativePath: String): AppResult<Unit> = ioResult("删除失败") {
        val file = resolve(projectName, relativePath)
        check(file.canonicalFile != projectRoot(projectName).canonicalFile) { "不能通过此接口删除工作区根目录" }
        if (file.isDirectory) SafeFileTree.delete(file) else check(file.delete()) { "删除文件失败" }
    }

    private suspend fun projectRoot(projectName: String): File {
        if (projectName == "sdcard") File("/storage/emulated/0").takeIf { it.exists() }?.let { return it }
        require(isValidProjectName(projectName)) { "项目名称无效：$projectName" }
        val entity = workspaceRepository.findByName(projectName) ?: error("项目不存在：$projectName")
        return File(entity.path).also { check(it.isDirectory) { "关联目录不存在：$projectName" } }
    }

    private suspend fun resolve(projectName: String, relativePath: String, allowMissing: Boolean = false): File {
        val root = projectRoot(projectName)
        val trimmed = relativePath.trim().removePrefix("/workspace/$projectName").removePrefix("/sdcard").removePrefix("/")
        val segments = trimmed.split('/', '\\').filter { it.isNotEmpty() && it != "." }
        require(segments.none { it == ".." }) { "路径包含越界操作符 (..)" }
        val candidate = segments.fold(root) { parent, segment -> File(parent, segment) }
        val canonical = candidate.canonicalFile
        require(isInside(root.canonicalFile, canonical)) { "路径越界：$relativePath" }
        if (!allowMissing) require(candidate.exists()) { "目标不存在：${displayPath(projectName, relativePath)}" }
        return candidate
    }

    private suspend fun displayPath(projectName: String, relativePath: String): String =
        if (projectName == "sdcard") "/sdcard/${relativePath.trimStart('/')}"
        else "${linuxPathFor(projectRoot(projectName))}/${relativePath.trimStart('/')}"

    private fun linuxPathFor(directory: File): String {
        val canonical = directory.canonicalFile
        val internal = pathManager.workspaceDir.canonicalFile
        val shared = WorkspaceManager.SHARED_STORAGE_ROOT.canonicalFile
        return when {
            isInside(internal, canonical) -> "/workspace/${canonical.toRelativeString(internal).replace(File.separatorChar, '/')}"
            isInside(shared, canonical) -> "/sdcard/${canonical.toRelativeString(shared).replace(File.separatorChar, '/')}"
            else -> error("目录不在可关联空间内")
        }.trimEnd('/')
    }

    private fun isInside(root: File, candidate: File): Boolean =
        candidate == root || candidate.absolutePath.startsWith(root.absolutePath + File.separator)

    private fun isValidProjectName(name: String): Boolean = name.isNotEmpty() &&
        name.length <= WorkspaceManager.MAX_PROJECT_NAME_LENGTH && name.first().isLetterOrDigit() &&
        name.drop(1).all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

    private suspend fun <T> ioResult(fallback: String, block: suspend () -> T): AppResult<T> = withContext(Dispatchers.IO) {
        try {
            AppResult.Success(block())
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: fallback, throwable))
        }
    }
}
