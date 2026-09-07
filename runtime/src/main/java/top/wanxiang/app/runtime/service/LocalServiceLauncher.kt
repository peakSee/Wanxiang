package top.wanxiang.app.runtime.service

import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.shell.ManagedProcess
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds

data class LocalServiceSpec(
    val serviceId: String,
    val port: Int,
    val path: String = "/",
    val startupTimeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS,
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    init {
        require(SERVICE_ID.matches(serviceId)) { "本地服务 ID 无效：$serviceId" }
    }
    init {
        require(port in 1..65535) { "本地服务端口无效：$port" }
        require(path.startsWith('/')) { "本地服务路径必须以 / 开头" }
        require(path.length <= 512 && path.none(Char::isISOControl)) { "本地服务路径无效" }
        require(startupTimeoutMs > 0) { "本地服务启动超时必须大于 0" }
        require(pollIntervalMs > 0) { "本地服务轮询间隔必须大于 0" }
    }

    companion object {
        // 网关启动等待：首次启动可能需编译/下载依赖，20 秒在慢速真机上经常不够，
        // 加长到 10 分钟；若进程真正崩溃退出，等待逻辑会立即感知并报错，无需等满超时。
        const val DEFAULT_STARTUP_TIMEOUT_MS = 10 * 60 * 1000L
        const val DEFAULT_POLL_INTERVAL_MS = 200L
        private val SERVICE_ID = Regex("[a-z0-9][a-z0-9-]{1,63}")
    }
}

data class LocalServiceHandle(
    val spec: LocalServiceSpec,
    val process: ManagedProcess,
) {
    val url: String get() = "http://localhost:${spec.port}${spec.path}"
}

class LocalServiceStartException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

interface LocalServiceLauncher {
    suspend fun start(
        spec: LocalServiceSpec,
        startProcess: suspend () -> ManagedProcess,
    ): LocalServiceHandle

    suspend fun stop(serviceId: String): Boolean
    suspend fun stopAll()
}

@Singleton
class LocalServiceLauncherImpl @Inject constructor(
    private val linuxRuntime: LinuxRuntime,
) : LocalServiceLauncher {
    private val mutex = Mutex()
    private val services = LinkedHashMap<String, LocalServiceHandle>()

    override suspend fun start(
        spec: LocalServiceSpec,
        startProcess: suspend () -> ManagedProcess,
    ): LocalServiceHandle = mutex.withLock {
        stopLocked(spec.serviceId)
        val process = try {
            startProcess()
        } catch (throwable: Throwable) {
            throw LocalServiceStartException("无法启动本地服务：${spec.serviceId}", throwable)
        }

        try {
            awaitPort(spec, process)
            LocalServiceHandle(spec, process).also { services[spec.serviceId] = it }
        } catch (timeout: TimeoutCancellationException) {
            linuxRuntime.stopBackground(process.id)
            throw LocalServiceStartException(
                "本地服务未在 ${spec.startupTimeoutMs / 1000} 秒内就绪：${spec.serviceId}",
                timeout,
            )
        } catch (cancellation: CancellationException) {
            linuxRuntime.stopBackground(process.id)
            throw cancellation
        } catch (throwable: Throwable) {
            linuxRuntime.stopBackground(process.id)
            if (throwable is LocalServiceStartException) throw throwable
            throw LocalServiceStartException(
                "本地服务未在 ${spec.startupTimeoutMs / 1000} 秒内就绪：${spec.serviceId}",
                throwable,
            )
        }
    }

    override suspend fun stop(serviceId: String): Boolean = mutex.withLock {
        stopLocked(serviceId)
    }

    override suspend fun stopAll() = mutex.withLock {
        services.values.forEach { handle -> linuxRuntime.stopBackground(handle.process.id) }
        services.clear()
    }

    private suspend fun stopLocked(serviceId: String): Boolean {
        val handle = services.remove(serviceId) ?: return false
        linuxRuntime.stopBackground(handle.process.id)
        return true
    }

    private suspend fun awaitPort(spec: LocalServiceSpec, process: ManagedProcess) {
        withTimeout(spec.startupTimeoutMs.milliseconds) {
            while (true) {
                coroutineContext.ensureActive()
                if (!process.session.isAlive) {
                    throw LocalServiceStartException("本地服务进程已退出：${spec.serviceId}")
                }
                if (isPortOpen(spec.port)) return@withTimeout
                delay(spec.pollIntervalMs.milliseconds)
            }
        }
    }

    private suspend fun isPortOpen(port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), SOCKET_CONNECT_TIMEOUT_MS.toInt())
            }
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val SOCKET_CONNECT_TIMEOUT_MS = 250L
    }
}
