package top.wanxiang.app.runtime

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.wanxiang.app.core.datastore.SshPreferences
import top.wanxiang.app.core.model.RuntimeState
import top.wanxiang.app.runtime.service.LocalServiceLauncher
import top.wanxiang.app.runtime.service.LocalServiceSpec
import top.wanxiang.app.runtime.shell.ManagedProcess
import top.wanxiang.app.runtime.shell.ProcessType
import top.wanxiang.app.runtime.shell.ShellCommand

data class SshRuntimeConfig(
    val port: Int = DEFAULT_SSH_PORT,
    val authorizedKeys: String = "",
    val passwordAuthEnabled: Boolean = false,
) {
    init {
        require(port in 1024..65535) { "SSH 端口必须在 1024..65535 之间" }
    }

    companion object {
        const val DEFAULT_SSH_PORT = 8022
    }
}

sealed interface SshServiceState {
    data class Stopped(val distroId: String, val installed: Boolean = false) : SshServiceState
    data class Installing(val distroId: String) : SshServiceState
    data class Starting(val distroId: String) : SshServiceState
    data class Running(
        val distroId: String,
        val port: Int,
        val host: String,
    ) : SshServiceState
    data class Failed(val distroId: String, val message: String) : SshServiceState
}

/** Owns the built-in OpenSSH server for the currently active Linux distribution. */
@Singleton
class SshServiceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val linuxRuntime: LinuxRuntime,
    private val preferences: SshPreferences,
    private val serviceLauncher: LocalServiceLauncher,
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceMutex = Mutex()
    private val observing = AtomicBoolean(false)
    private val _state = MutableStateFlow<SshServiceState>(SshServiceState.Stopped("ubuntu"))
    val state: StateFlow<SshServiceState> = _state.asStateFlow()

    private var serviceProcess: ManagedProcess? = null
    private var serviceDistroId: String? = null
    private var monitorJob: Job? = null
    private var activePort: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Idempotently restores SSH when the runtime becomes ready or the active distro changes. */
    fun startObserving() {
        if (!observing.compareAndSet(false, true)) return
        managerScope.launch {
            combine(linuxRuntime.state, linuxRuntime.activeDistroId) { runtimeState, distroId ->
                (runtimeState is RuntimeState.Ready) to distroId
            }
                .distinctUntilChanged()
                .collectLatest { (ready, distroId) ->
                    serviceMutex.withLock {
                        if (serviceDistroId != null && serviceDistroId != distroId) stopLocked()
                    }
                    if (!ready) {
                        _state.value = SshServiceState.Stopped(distroId)
                        return@collectLatest
                    }
                    refresh(distroId)
                    if (preferences.enabled(distroId).first()) {
                        try {
                            start(distroId)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            // start() publishes the actionable failure through state.
                        }
                    }
                }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        val distroId = linuxRuntime.activeDistroId.value
        if (enabled) {
            start(distroId)
            preferences.setEnabled(distroId, true)
        } else {
            preferences.setEnabled(distroId, false)
            stop()
        }
    }

    suspend fun setPort(port: Int) {
        require(port in 1024..65535) { "SSH 端口必须在 1024..65535 之间" }
        val distroId = linuxRuntime.activeDistroId.value
        preferences.setPort(distroId, port)
        restartIfRunning(distroId)
    }

    suspend fun setAuthorizedKeys(keys: String) {
        val distroId = linuxRuntime.activeDistroId.value
        val normalized = SshCommandFactory.normalizeAuthorizedKeys(keys)
        preferences.setAuthorizedKeys(distroId, normalized)
        val passwordAvailable = preferences.passwordAuthEnabled(distroId).first() &&
            preferences.passwordConfigured(distroId).first()
        if (normalized.isBlank() && !passwordAvailable) {
            preferences.setEnabled(distroId, false)
            if (serviceDistroId == distroId) stop()
        } else {
            restartIfRunning(distroId)
        }
    }

    suspend fun setPassword(password: String) {
        val distroId = linuxRuntime.activeDistroId.value
        val normalized = SshCommandFactory.normalizePassword(password)
        preferences.setPassword(distroId, normalized)
        preferences.setPasswordAuthEnabled(distroId, true)
        restartIfRunning(distroId)
    }

    suspend fun setPasswordAuthEnabled(enabled: Boolean) {
        val distroId = linuxRuntime.activeDistroId.value
        if (enabled) {
            check(preferences.passwordConfigured(distroId).first()) { "请先设置 SSH 登录密码" }
        } else {
            check(preferences.authorizedKeys(distroId).first().isNotBlank() || !preferences.enabled(distroId).first()) {
                "请先添加 SSH 公钥，或关闭 SSH 服务后再停用密码登录"
            }
        }
        preferences.setPasswordAuthEnabled(distroId, enabled)
        restartIfRunning(distroId)
    }

    suspend fun clearPassword() {
        val distroId = linuxRuntime.activeDistroId.value
        preferences.setPasswordAuthEnabled(distroId, false)
        preferences.setPassword(distroId, null)
        if (preferences.authorizedKeys(distroId).first().isBlank()) {
            preferences.setEnabled(distroId, false)
            if (serviceDistroId == distroId) stop()
        } else {
            restartIfRunning(distroId)
        }
    }

    suspend fun refresh(distroId: String = linuxRuntime.activeDistroId.value) {
        if (serviceDistroId == distroId && serviceProcess?.session?.isAlive == true) return
        val installed = linuxRuntime.execute(
            ShellCommand(
                commandLine = "command -v sshd >/dev/null 2>&1",
                timeoutMs = PROBE_TIMEOUT_MS,
            ),
            distroId,
        ).isSuccess
        _state.value = SshServiceState.Stopped(distroId, installed)
    }

    suspend fun start(distroId: String = linuxRuntime.activeDistroId.value) = serviceMutex.withLock {
        check(linuxRuntime.state.value is RuntimeState.Ready) { "Linux 运行时尚未就绪" }
        val current = serviceProcess
        if (serviceDistroId == distroId && current?.session?.isAlive == true) return@withLock
        stopLocked()

        val config = readConfig(distroId)
        val authorizedKeys = SshCommandFactory.normalizeAuthorizedKeys(config.authorizedKeys)
        val password = if (config.passwordAuthEnabled) {
            preferences.readPassword(distroId)?.let(SshCommandFactory::normalizePassword)
                ?: error("请先设置 SSH 登录密码")
        } else {
            null
        }
        check(authorizedKeys.isNotBlank() || password != null) {
            "请先设置 SSH 登录密码或添加至少一个公钥"
        }

        try {
            val installed = linuxRuntime.execute(
                ShellCommand("command -v sshd >/dev/null 2>&1", timeoutMs = PROBE_TIMEOUT_MS),
                distroId,
            ).isSuccess
            if (!installed) {
                _state.value = SshServiceState.Installing(distroId)
                val install = linuxRuntime.execute(
                    ShellCommand(
                        commandLine = SshCommandFactory.installCommand,
                        timeoutMs = INSTALL_TIMEOUT_MS,
                    ),
                    distroId,
                )
                check(install.isSuccess) {
                    (install.stderr.ifBlank { install.stdout }).trim().ifBlank { "OpenSSH 安装失败" }
                }
            }

            _state.value = SshServiceState.Starting(distroId)
            val configure = linuxRuntime.execute(
                ShellCommand(
                    commandLine = SshCommandFactory.configureCommand(
                        config.copy(authorizedKeys = authorizedKeys),
                        password,
                        // 始终监听所有 IPv4 接口：本机 127.0.0.1 与局域网设备都能连接，
                        // 且不随 Wi-Fi IP 变化失效；就绪探测走 127.0.0.1 即可命中。
                        listenAddress = "0.0.0.0",
                    ),
                    timeoutMs = CONFIGURE_TIMEOUT_MS,
                ),
                distroId,
            )
            check(configure.isSuccess) {
                (configure.stderr.ifBlank { configure.stdout }).trim().ifBlank { "SSH 配置校验失败" }
            }

            // 停止可能残留、仍占用端口的旧 sshd（主机 proot 被杀后，沙盒内的
            // sshd 可能成为孤儿继续监听），待端口真正释放后再绑定，避免
            // “Address already in use”。
            cleanupStaleSshd(distroId, config.port)

            val handle = serviceLauncher.start(
                LocalServiceSpec(
                    serviceId = SERVICE_ID,
                    port = config.port,
                    startupTimeoutMs = STARTUP_TIMEOUT_MS,
                ),
            ) {
                linuxRuntime.startBackground(
                    id = PROCESS_ID,
                    toolId = LOG_ID,
                    type = ProcessType.SERVICE,
                    distroId = distroId,
                    command = ShellCommand(
                        commandLine = SshCommandFactory.startCommand,
                        timeoutMs = Long.MAX_VALUE,
                    ),
                )
            }
            serviceProcess = handle.process
            serviceDistroId = distroId
            activePort = config.port
            _state.value = SshServiceState.Running(
                distroId,
                config.port,
                host = localIpv4Address() ?: "设备局域网 IP",
            )
            acquireWakeLock()
            acquireWifiLock()
            monitor(handle.process, distroId)
        } catch (cancellation: CancellationException) {
            _state.value = SshServiceState.Stopped(distroId)
            throw cancellation
        } catch (throwable: Throwable) {
            serviceProcess = null
            serviceDistroId = null
            _state.value = SshServiceState.Failed(distroId, throwable.message ?: "SSH 服务启动失败")
            throw throwable
        }
    }

    suspend fun stop() = serviceMutex.withLock {
        val distroId = serviceDistroId ?: linuxRuntime.activeDistroId.value
        stopLocked()
        refresh(distroId)
    }

    fun logs(): Flow<List<String>> = linuxRuntime.observeBackgroundLogs(LOG_ID)

    private suspend fun restartIfRunning(distroId: String) {
        if (serviceDistroId == distroId && serviceProcess?.session?.isAlive == true) {
            serviceMutex.withLock { stopLocked() }
            start(distroId)
        }
    }

    private suspend fun stopLocked() {
        monitorJob?.cancel()
        monitorJob = null
        val distroId = serviceDistroId
        val port = activePort
        try {
            serviceLauncher.stop(SERVICE_ID)
            if (distroId != null && port != null) cleanupStaleSshd(distroId, port)
        } finally {
            serviceProcess = null
            serviceDistroId = null
            activePort = null
            releaseWakeLock()
        }
    }

    private suspend fun readConfig(distroId: String) = SshRuntimeConfig(
        port = preferences.port(distroId).first(),
        authorizedKeys = preferences.authorizedKeys(distroId).first(),
        passwordAuthEnabled = preferences.passwordAuthEnabled(distroId).first(),
    )

    private fun monitor(process: ManagedProcess, distroId: String) {
        monitorJob?.cancel()
        monitorJob = managerScope.launch {
            while (isActive && process.session.isAlive) delay(PROCESS_POLL_INTERVAL_MS)
            if (isActive && serviceProcess === process) {
                serviceProcess = null
                serviceDistroId = null
                releaseWakeLock()
                val detail = linuxRuntime.getBackgroundLogs(LOG_ID).takeLast(3).joinToString("\n")
                _state.value = SshServiceState.Failed(
                    distroId,
                    detail.ifBlank { "SSH 服务进程已退出" },
                )
            }
        }
    }

    /** 清除可能残留、仍占用端口的旧 sshd，并等待端口真正释放。 */
    private suspend fun cleanupStaleSshd(distroId: String, port: Int) {
        try {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = KILL_STALE_SSHD_COMMAND,
                    timeoutMs = KILL_STALE_TIMEOUT_MS,
                ),
                distroId,
            )
        } catch (_: Throwable) {
            // 运行时可正处于关闭中；尽力而为，下面的端口释放等待兜底。
        }
        val freed = withTimeoutOrNull(PORT_FREE_TIMEOUT_MS) {
            while (isPortBusy(port)) delay(PORT_POLL_INTERVAL_MS)
            true
        }
        check(freed == true) { "SSH 端口 $port 仍被旧进程占用，无法启动" }
    }

    private suspend fun isPortBusy(port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), PORT_CONNECT_TIMEOUT_MS)
            }
        }.isSuccess
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = runCatching {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull() ?: return
        val lock = wakeLock ?: pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Wanxiang::LinuxRuntime",
        ).also { it.setReferenceCounted(false) }
        wakeLock = lock
        runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifi = runCatching {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull() ?: return
        val lock = wifiLock ?: wifi.createWifiLock(
            // WIFI_MODE_FULL_HIGH_PERF 在 API 34+ 标记弃用，但 FULL_LOW_LATENCY 语义不同（低延迟≠高吞吐），
            // 保持原模式以不改变行为；系统在 Android 13+ 会自行优化调度。
            @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "Wanxiang::LinuxRuntime",
        ).also { it.setReferenceCounted(false) }
        wifiLock = lock
        runCatching { lock.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wakeLock = null
        wifiLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wifiLock = null
    }

    companion object {
        const val LOG_ID = "system-ssh"
        private const val SERVICE_ID = "wanxiang-ssh"
        private const val PROCESS_ID = "wanxiang-sshd"
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val INSTALL_TIMEOUT_MS = 10 * 60 * 1000L
        private const val CONFIGURE_TIMEOUT_MS = 30_000L
        // PRoot 首次拉起 sshd 在慢速设备上可能超过 15 秒；进程提前退出时
        // LocalServiceLauncher 仍会立即失败，因此放宽端口就绪等待不会掩盖崩溃。
        private const val STARTUP_TIMEOUT_MS = 60_000L
        private const val PROCESS_POLL_INTERVAL_MS = 1_000L
        private const val KILL_STALE_TIMEOUT_MS = 5_000L
        private const val PORT_FREE_TIMEOUT_MS = 5_000L
        private const val PORT_POLL_INTERVAL_MS = 100L
        private const val PORT_CONNECT_TIMEOUT_MS = 250
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L

        // 结束一个 sshd 会话：先优雅终止，稍等回落，再强制终止；同时按 pidfile 兜底。
        val KILL_STALE_SSHD_COMMAND = """
            if command -v pkill >/dev/null 2>&1; then
              pkill -f 'ssh[d] .*wanxiang_sshd_config' 2>/dev/null || true
            fi
            if test -f /tmp/wanxiang-sshd.pid; then
              kill "${'$'}(cat /tmp/wanxiang-sshd.pid 2>/dev/null)" 2>/dev/null || true
            fi
            sleep 0.2
            if command -v pkill >/dev/null 2>&1; then
              pkill -9 -f 'ssh[d] .*wanxiang_sshd_config' 2>/dev/null || true
            fi
        """.trimIndent()

        fun localIpv4Address(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}

internal object SshCommandFactory {
    val installCommand: String = """
        set -eu
        if command -v sshd >/dev/null 2>&1; then exit 0; fi
        if command -v apt-get >/dev/null 2>&1; then
          export DEBIAN_FRONTEND=noninteractive
          apt-get update -y
          apt-get install -y --no-install-recommends openssh-server
        elif command -v apk >/dev/null 2>&1; then
          apk add --no-cache openssh
        elif command -v pacman >/dev/null 2>&1; then
          pacman -Sy --noconfirm --needed openssh
        elif command -v dnf >/dev/null 2>&1; then
          dnf install -y openssh-server
        elif command -v zypper >/dev/null 2>&1; then
          zypper --non-interactive install openssh
        elif command -v xbps-install >/dev/null 2>&1; then
          xbps-install -Sy openssh
        else
          echo '当前发行版没有受支持的包管理器' >&2
          exit 65
        fi
        command -v sshd >/dev/null 2>&1
    """.trimIndent()

    const val startCommand: String =
        "SSHD=\$(command -v sshd); exec \"\$SSHD\" -D -e -f /etc/ssh/wanxiang_sshd_config"

    fun configureCommand(
        config: SshRuntimeConfig,
        password: String? = null,
        listenAddress: String? = null,
    ): String {
        val keys = normalizeAuthorizedKeys(config.authorizedKeys)
        val normalizedPassword = if (config.passwordAuthEnabled) {
            normalizePassword(password ?: error("请先设置 SSH 登录密码"))
        } else {
            null
        }
        check(keys.isNotBlank() || normalizedPassword != null) { "请先设置 SSH 登录密码或添加至少一个公钥" }
        val resolvedListenAddress = listenAddress ?: "0.0.0.0"
        val passwordAuth = if (normalizedPassword != null) "yes" else "no"
        val permitRootLogin = if (normalizedPassword != null) "yes" else "prohibit-password"
        val sshdConfig = """
            Port ${config.port}
            ListenAddress $resolvedListenAddress
            Protocol 2
            HostKey /etc/ssh/ssh_host_ed25519_key
            HostKey /etc/ssh/ssh_host_rsa_key
            PermitRootLogin $permitRootLogin
            PubkeyAuthentication yes
            AuthorizedKeysFile .ssh/authorized_keys
            PasswordAuthentication $passwordAuth
            KbdInteractiveAuthentication no
            ChallengeResponseAuthentication no
            PermitEmptyPasswords no
            UsePAM no
            X11Forwarding no
            AllowAgentForwarding no
            AllowTcpForwarding yes
            GatewayPorts no
            PermitUserEnvironment no
            UseDNS no
            TCPKeepAlive yes
            PidFile /tmp/wanxiang-sshd.pid
            Subsystem sftp internal-sftp
            ClientAliveInterval 60
            ClientAliveCountMax 3
            LoginGraceTime 30
            MaxAuthTries 5
            LogLevel VERBOSE
        """.trimIndent() + "\n"
        val keysBase64 = Base64.getEncoder().encodeToString((keys + "\n").toByteArray())
        val configBase64 = Base64.getEncoder().encodeToString(sshdConfig.toByteArray())
        val passwordCommand = normalizedPassword?.let {
            val credentialBase64 = Base64.getEncoder().encodeToString("root:$it\n".toByteArray())
            "printf '%s' '$credentialBase64' | base64 -d | chpasswd"
        }.orEmpty()
        return """
            set -eu
            umask 077
            mkdir -p /root/.ssh /run/sshd /etc/ssh
            printf '%s' '$keysBase64' | base64 -d > /root/.ssh/authorized_keys
            printf '%s' '$configBase64' | base64 -d > /etc/ssh/wanxiang_sshd_config
            chmod 700 /root/.ssh
            chmod 600 /root/.ssh/authorized_keys /etc/ssh/wanxiang_sshd_config
            $passwordCommand
            ssh-keygen -A
            SSHD=${'$'}(command -v sshd)
            "${'$'}SSHD" -t -f /etc/ssh/wanxiang_sshd_config
        """.trimIndent()
    }

    fun normalizeAuthorizedKeys(value: String): String {
        require(value.length <= MAX_AUTHORIZED_KEYS_BYTES) { "SSH 公钥内容过长" }
        val lines = value.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.size <= MAX_AUTHORIZED_KEYS) { "最多允许 $MAX_AUTHORIZED_KEYS 个 SSH 公钥" }
        lines.forEach { line ->
            require(AUTHORIZED_KEY.matches(line)) { "SSH 公钥格式无效：${line.take(32)}" }
        }
        return lines.distinct().joinToString("\n")
    }

    fun normalizePassword(value: String): String {
        require(value.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
            "SSH 密码长度必须在 $MIN_PASSWORD_LENGTH..$MAX_PASSWORD_LENGTH 个字符之间"
        }
        require(value.none(Char::isISOControl) && ':' !in value) { "SSH 密码不能包含控制字符或冒号" }
        return value
    }

    private const val MAX_AUTHORIZED_KEYS = 20
    private const val MAX_AUTHORIZED_KEYS_BYTES = 16 * 1024
    private const val MIN_PASSWORD_LENGTH = 8
    private const val MAX_PASSWORD_LENGTH = 128
    private val AUTHORIZED_KEY = Regex(
        "^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(?:256|384|521)|sk-ssh-ed25519@openssh\\.com|" +
            "sk-ecdsa-sha2-nistp256@openssh\\.com) [A-Za-z0-9+/]+={0,3}(?: [^\\r\\n]*)?$",
    )
}
