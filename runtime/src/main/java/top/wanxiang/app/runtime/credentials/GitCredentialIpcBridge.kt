package top.wanxiang.app.runtime.credentials

import android.content.Context
import android.os.FileObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.core.datastore.GitCredential
import top.wanxiang.app.core.datastore.GitPreferences
import top.wanxiang.app.runtime.RuntimePathManager
import java.util.UUID

/**
 * 万象 自定义 git credential helper（容器内 `/wanxiang-ipc/git-credential-wanxiang`）与 app 之间的
 * 文件 IPC 桥。三端（UI 按钮 / AI 里跑的 bash / 交互终端手敲）git 缺凭据时都走这条统一链，
 * 无需逐命令路径适配。
 *
 * **工作原理**：
 * - helper 通过 PRoot `-b` 与宿主 [RuntimePathManager.gitIpcDir] 绑定同一 inode；
 * - helper 未命中 store 时写 `cred-req-<id>`（含 host），app 侧 [FileObserver] 或 [fallbackPollLoop] 捕获；
 * - 上层 [top.wanxiang.app.ui.chat.GlobalCredentialDialogHost] 监听 [request] StateFlow → 弹全局对话框；
 * - 用户填完 → [respond] 写 `cred-resp-<id>`（含 username/password），helper 轮询读到 → cat 给 git → git 自动续跑；
 * - 用户取消 → [cancel] 写 `cancel=1`，helper 退出非零让 git 报认证失败（与无凭据行为一致）。
 *
 * **可靠性**：FileObserver 在部分机型 PRoot `-b` 目录上可能不可靠，另起 1s 兜底轮询；
 * 两路径都走 [handleRequest]，靠 [seen] 按 requestId 去重防双触发。启动时清 [cleanupStale] 上次残留文件。
 *
 * **原子写**：所有响应文件先写 `.tmp` 再 renameTo，避免 helper 读到半截。
 *
 * **生命周期**：`@Singleton`，App 级。由 [GitCredentialIpcInstaller] 在合适时机调 [start]。
 * FileObserver 需主线程创建与 startWatching；此处不严格约束线程（[handleRequest] 内部走 IO 协程）。
 */
@Singleton
class GitCredentialIpcBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pathManager: RuntimePathManager,
    private val preferences: GitPreferences,
    private val logger: AppLogger,
) {
    /** 当前待用户填的凭据请求（host 来自 git credential 协议）。null = 无待处理。 */
    data class CredentialRequest(
        val requestId: String,
        val host: String,
    )

    private val _request = MutableStateFlow<CredentialRequest?>(null)
    val request: StateFlow<CredentialRequest?> = _request.asStateFlow()

    private val started = AtomicBoolean(false)
    private var observer: FileObserver? = null
    private val seen = LinkedHashSetWithCap(SEEN_CAP)
    private val seenMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 主线程调用：建 FileObserver + 起兜底轮询协程 + 清理上次残留。幂等可重复。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val dir = ensureIpcDir()
        try {
            @Suppress("DEPRECATION")
            val obs = object : FileObserver(dir.absolutePath, CREATE or CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    val name = path ?: return
                    if (!name.startsWith(REQ_PREFIX) || name.endsWith(".tmp")) return
                    scope.launch { handleRequest(File(dir, name)) }
                }
            }
            obs.startWatching()
            observer = obs
            logger.i("GitIpc: FileObserver 启动，监听 ${dir.absolutePath}")
        } catch (t: Throwable) {
            logger.w("GitIpc: FileObserver 启动失败（走兜底轮询）: ${t.message}")
        }
        scope.launch {
            cleanupStale(dir)
            fallbackPollLoop(dir)
        }
    }

    /**
     * 用户填完确认：原子写响应文件（helper 会 cat 给 git），清 [request] 状态，
     * 并把这条凭据 upsert 到 [GitPreferences]（下次同 host 走 store 直命中，不再弹）。
     */
    fun respond(requestId: String, username: String, password: String) {
        val dir = ensureIpcDir()
        scope.launch {
            writeRespAtomically(dir, requestId, "username=$username\npassword=$password\n")
            val host = _request.value?.host.orEmpty()
            clearIfCurrent(requestId)
            if (host.isNotBlank()) {
                runCatching {
                    val list = preferences.credentials.first()
                    val existing = list.firstOrNull { it.host.equals(host, ignoreCase = true) }
                    val updated = if (existing != null) {
                        list.map { if (it === existing) it.copy(username = username, token = password) else it }
                    } else {
                        list + GitCredential(
                            id = UUID.randomUUID().toString(),
                            name = "$host (auto)",
                            host = host,
                            username = username,
                            token = password,
                            createdAtMillis = System.currentTimeMillis(),
                        )
                    }
                    preferences.setCredentials(updated)
                }.onFailure { logger.w("GitIpc: upsert 凭据到 DataStore 失败: ${it.message}") }
            }
            logger.i("GitIpc: 已回填凭据 id=$requestId user=$username")
        }
    }

    /** 用户取消：写 `cancel=1`（helper 退出非零让 git 报认证失败）。 */
    fun cancel(requestId: String) {
        val dir = ensureIpcDir()
        scope.launch {
            writeRespAtomically(dir, requestId, "cancel=1\n")
            clearIfCurrent(requestId)
            logger.i("GitIpc: 用户取消弹窗 id=$requestId")
        }
    }

    private fun ensureIpcDir(): File = pathManager.gitIpcDir.apply { mkdirs() }

    private suspend fun handleRequest(file: File) {
        val name = file.name
        val requestId = name.removePrefix(REQ_PREFIX)
        val fresh = seenMutex.withLock { seen.addCapped(requestId) }
        if (!fresh) return
        if (!file.exists() || file.length() == 0L) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val host = text.lineSequence()
            .firstOrNull { it.startsWith("host=") }
            ?.removePrefix("host=")
            ?.trim()
            ?.lowercase()
            ?: ""
        if (host.isBlank()) return
        logger.i("GitIpc: 收到凭据请求 host=$host id=$requestId")
        _request.value = CredentialRequest(requestId, host)
    }

    private suspend fun fallbackPollLoop(dir: File) {
        while (true) {
            delay(FALLBACK_POLL_MS)
            runCatching {
                dir.listFiles { f -> f.name.startsWith(REQ_PREFIX) && !f.name.endsWith(".tmp") }
                    ?.forEach { handleRequest(it) }
            }
        }
    }

    private fun cleanupStale(dir: File) {
        runCatching {
            dir.listFiles { f ->
                f.name.startsWith(REQ_PREFIX) || f.name.startsWith(RESP_PREFIX)
            }?.forEach { it.delete() }
        }
    }

    private fun writeRespAtomically(dir: File, requestId: String, content: String) {
        val target = File(dir, RESP_PREFIX + requestId)
        val tmp = File(dir, "$RESP_PREFIX$requestId.tmp")
        runCatching {
            tmp.writeText(content)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        }.onFailure { logger.w("GitIpc: 写凭据响应失败: ${it.message}") }
    }

    private fun clearIfCurrent(requestId: String) {
        if (_request.value?.requestId == requestId) _request.value = null
    }

    private companion object {
        const val REQ_PREFIX = "cred-req-"
        const val RESP_PREFIX = "cred-resp-"
        const val FALLBACK_POLL_MS = 1_000L
        const val SEEN_CAP = 64
    }
}

/** 有容量上限的 LinkedHashSet，超过 cap 淘汰最旧；给 [GitCredentialIpcBridge.seen] 用。 */
private class LinkedHashSetWithCap(private val cap: Int) : LinkedHashSet<String>() {
    fun addCapped(id: String): Boolean {
        if (contains(id)) return false
        add(id)
        if (size > cap) {
            iterator().next().let { remove(it) }
        }
        return true
    }
}
