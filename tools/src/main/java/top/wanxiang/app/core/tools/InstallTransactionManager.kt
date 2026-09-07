package top.wanxiang.app.core.tools

import top.wanxiang.app.core.common.files.SafeFileTree
import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.runtime.RuntimePathManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides a recoverable program-directory transaction for tool install/update.
 * Tool data is intentionally outside this snapshot and survives program updates.
 *
 * 目录清理属于尽力而为：残留文件由下次 begin / recover / cleanupOrphans 兜底，
 * 任何清理失败都只记录日志，绝不允许让安装/回滚流程崩溃。
 */
@Singleton
class InstallTransactionManager @Inject constructor(
    private val pathManager: RuntimePathManager,
    private val logger: AppLogger,
) {
    suspend fun begin(distroId: String, toolId: String, preserveExisting: Boolean): InstallTransaction =
        withContext(Dispatchers.IO) {
            require(SAFE_TOOL_ID.matches(toolId)) { "工具 ID 无效：$toolId" }
            val safeDistro = distroId.lowercase().trim()
            pathManager.ensureDistroDirectories(safeDistro)
            val target = File(pathManager.wanxiangToolsDir(safeDistro), toolId)
            val transactionRoot = File(pathManager.wanxiangRootDir(safeDistro), ".transactions")
            transactionRoot.mkdirs()
            val snapshot = File(transactionRoot, "$toolId-${System.nanoTime()}")
            if (preserveExisting && target.isDirectory) {
                SafeFileTree.copy(target, snapshot)
            } else {
                safeDelete(target, "begin($safeDistro:$toolId)")
            }
            InstallTransaction(target = target, snapshot = snapshot.takeIf { it.exists() })
        }

    suspend fun commit(transaction: InstallTransaction) = withContext(Dispatchers.IO) {
        transaction.snapshot?.let { safeDelete(it, "commit(${transaction.target.name})") }
    }

    /** Restores the newest orphaned transaction after an app-process crash. */
    suspend fun recover(distroId: String, toolId: String, preserveExisting: Boolean): Boolean = withContext(Dispatchers.IO) {
        require(SAFE_TOOL_ID.matches(toolId)) { "工具 ID 无效：$toolId" }
        val safeDistro = distroId.lowercase().trim()
        val transactionRoot = File(pathManager.wanxiangRootDir(safeDistro), ".transactions")
        val candidates = transactionRoot.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("$toolId-") }
            .sortedByDescending { it.lastModified() }
        val target = File(pathManager.wanxiangToolsDir(safeDistro), toolId)
        if (preserveExisting && candidates.isNotEmpty()) {
            safeDelete(target, "recover($safeDistro:$toolId) target")
            SafeFileTree.copy(candidates.first(), target)
        } else if (!preserveExisting) {
            safeDelete(target, "recover($safeDistro:$toolId) target")
        }
        candidates.forEach { safeDelete(it, "recover($safeDistro:$toolId) snapshot") }
        candidates.isNotEmpty() || !preserveExisting
    }

    suspend fun cleanupOrphans(activeToolIds: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        val distroIds = pathManager.listInstalledDistroIds().ifEmpty { listOf("ubuntu") }
        distroIds.forEach { distroId ->
            val safeDistro = distroId.lowercase().trim()
            File(pathManager.wanxiangRootDir(safeDistro), ".transactions")
                .listFiles()
                .orEmpty()
                .filter { file -> activeToolIds.none { file.name.startsWith("$it-") } }
                .forEach { safeDelete(it, "cleanupOrphans($safeDistro)") }
        }
    }

    suspend fun rollback(transaction: InstallTransaction) = withContext(Dispatchers.IO) {
        safeDelete(transaction.target, "rollback(${transaction.target.name}) target")
        transaction.snapshot?.let { snapshot ->
            runCatching {
                SafeFileTree.copy(snapshot, transaction.target)
                check(transaction.target.exists()) { "无法恢复工具程序目录：${transaction.target.name}" }
            }.onFailure { logger.e("回滚恢复目录失败：${transaction.target.name}", it) }
            safeDelete(snapshot, "rollback(${transaction.target.name}) snapshot")
        }
    }

    private fun safeDelete(file: File, what: String) {
        runCatching { SafeFileTree.delete(file) }
            .onFailure { logger.e("清理失败（$what）：$file", it) }
    }

    private companion object {
        val SAFE_TOOL_ID = Regex("[a-z0-9][a-z0-9-]{1,63}")
    }
}

data class InstallTransaction(
    val target: File,
    val snapshot: File?,
)
