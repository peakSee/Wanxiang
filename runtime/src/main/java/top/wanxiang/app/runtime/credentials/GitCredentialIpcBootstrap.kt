package top.wanxiang.app.runtime.credentials

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.core.datastore.GitPreferences
import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.RuntimePathManager
import top.wanxiang.app.runtime.shell.ShellCommand
import java.net.URLEncoder

/**
 * 万象 Git 凭证 IPC 装配器：负责 3 件事，都幂等：
 *
 * 1. **提取 helper**：从 `assets/wanxiang/git-credential-wanxiang` 拷到 `<ipcDir>/git-credential-wanxiang` + 可执行位。
 * 2. **注册 git 全局 credential.helper**：向容器 `/root/.gitconfig` 追加两行
 *    （`store --file=/wanxiang-ipc/git-credentials` 排在前，`/wanxiang-ipc/git-credential-wanxiang` 排在后）。
 * 3. **镜像凭据到 git-credentials 文件**：监听 [GitPreferences.credentials]，任何变更立即重写文件，
 *    UI 和容器 git 共用同一份。
 *
 * 4. **启动桥**：[GitCredentialIpcBridge.start]（FileObserver + 兜底轮询）。
 *
 * 通过 `LinuxRuntime.execute` 跑 `git config --global credential.helper <value>` 完成注册（幂等：
 * git config 允许多值 append，所以每次都写会重复。改为**先 --unset-all 再 add**，保持恰好两条）。
 */
@Singleton
class GitCredentialIpcBootstrap @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pathManager: RuntimePathManager,
    private val preferences: GitPreferences,
    private val bridge: GitCredentialIpcBridge,
    private val linuxRuntime: LinuxRuntime,
    private val logger: AppLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    /** App 启动或沙箱 ready 后调；幂等。 */
    fun start() {
        if (started) return
        started = true
        val ipcDir = pathManager.gitIpcDir.apply { mkdirs() }
        installHelperScript(ipcDir)
        observeAndMirrorCredentials(File(ipcDir, "git-credentials"))
        bridge.start()
        // git config 需要沙箱在跑。旧版是 20×5s 盲重试：沙箱没就绪时白烧 100 秒还刷日志。
        // 现在挂起等 RuntimeState.Ready 再注册（零空转）；就绪后仍失败才短重试几次
        // （冷启动瞬间 apt/dpkg 锁竞争等）。永不 Ready（用户没开沙箱）则安静等待，无副作用。
        scope.launch {
            linuxRuntime.state.first { it is top.wanxiang.app.core.model.RuntimeState.Ready }
            var attempts = 0
            while (!configured.get() && attempts < 6) {
                runCatching { ensureGitConfigured() }
                if (configured.get()) break
                delay(3_000L)
                attempts++
            }
            if (!configured.get()) logger.w("credential.helper 注册失败（沙箱就绪后 6 次重试仍不通）；UI git 命令会走手动 wrap 兜底")
        }
    }

    /** 由外部（如 ChatViewModel）在跑 git 前触发一次确保已注册；未就绪时非阻塞返回。 */
    fun ensureConfiguredAsync() {
        if (configured.get()) return
        scope.launch { runCatching { ensureGitConfigured() } }
    }

    private val configured = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 从 assets 抽 helper 脚本到 ipc 目录 + chmod +x。失败静默（不影响主流程）。 */
    private fun installHelperScript(ipcDir: File) {
        runCatching {
            val dest = File(ipcDir, HELPER_NAME)
            context.assets.open("wanxiang/$HELPER_NAME").use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            dest.setExecutable(true, false)
            dest.setReadable(true, false)
            logger.i("helper 已装到 ${dest.absolutePath} (${dest.length()}B)")
        }.onFailure { logger.w("装 helper 失败: ${it.message}") }
    }

    /**
     * 把已保存的凭据以 git-credential-store 格式写到 `<ipcDir>/git-credentials`，
     * 供容器内 `credential.helper=store --file=...` 直接读取；UI 与 git 共用同一份文件真源。
     */
    private fun observeAndMirrorCredentials(target: File) {
        scope.launch {
            preferences.credentials
                .map { list -> list.sortedBy { it.host.lowercase() } }
                .distinctUntilChanged()
                .collect { list -> writeCredentialsFile(target, list) }
        }
    }

    private fun writeCredentialsFile(target: File, list: List<top.wanxiang.app.core.datastore.GitCredential>) {
        runCatching {
            val sb = StringBuilder()
            list.forEach { c ->
                sb.append("https://")
                    .append(enc(c.username)).append(':').append(enc(c.token))
                    .append('@').append(c.host.lowercase())
                    .append('\n')
            }
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(sb.toString())
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(sb.toString())
                tmp.delete()
            }
            logger.i("同步 ${list.size} 条凭据到 git-credentials 文件")
        }.onFailure { logger.w("写 git-credentials 失败: ${it.message}") }
    }

    /**
     * 在沙箱里注册 credential.helper：先 unset-all（幂等），再依次加两条，最后 --get-all 回读校验；
     * 校验通过（stdout 含 git-credential-wanxiang）才把 [configured] 置位。
     * 沙箱未就绪时 execute 会返回非零或空，等下轮重试。
     */
    suspend fun ensureGitConfigured() {
        aptOrConfigMutex.withLock {
            // 沙箱镜像（buildpack-deps:noble-scm）默认不带 git；缺则 apt 装（TUNA 源已在 provision 里配好）。
            val cmd = "command -v git >/dev/null 2>&1 || (apt-get update -qq >/dev/null 2>&1 && apt-get install -y -qq git >/dev/null 2>&1); " +
                "git --version 2>&1; " +
                "git config --global --unset-all credential.helper 2>/dev/null; " +
                "git config --global credential.helper 'store --file=$SANDBOX_IPC/git-credentials' 2>&1; " +
                "git config --global --add credential.helper '$SANDBOX_IPC/$HELPER_NAME' 2>&1; " +
                "echo '---verify---'; git config --global --get-all credential.helper 2>&1"
            runCatching {
                linuxRuntime.execute(
                    ShellCommand(
                        commandLine = cmd,
                        workingDirectory = "/root",
                        timeoutMs = 180_000L,
                    ),
                )
            }.onSuccess {
                val combined = it.stdout + "\n" + it.stderr
                if (combined.contains("git-credential-wanxiang") && combined.contains("store --file=")) {
                    configured.set(true)
                    logger.i("credential.helper 已注册 (verify 通过)")
                } else {
                    logger.w("credential.helper 注册失败 exit=${it.exitCode} out='${combined.take(400)}'")
                }
            }.onFailure { logger.w("git config 命令失败（沙箱未就绪？）: ${it.message}") }
        }
    }

    private val aptOrConfigMutex = Mutex()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object {
        const val TAG = "GitIpcBootstrap"
        const val HELPER_NAME = "git-credential-wanxiang"
        const val SANDBOX_IPC = "/wanxiang-ipc"
    }
}
