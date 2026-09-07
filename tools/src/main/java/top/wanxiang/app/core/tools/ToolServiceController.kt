package top.wanxiang.app.core.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.service.LocalServiceSpec
import top.wanxiang.app.runtime.shell.ManagedProcess
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Owns background-tool lifecycle and readiness probing independently from install transactions. */
@Singleton
class ToolServiceController @Inject constructor(
    private val linuxRuntime: LinuxRuntime,
) {
    fun isRunning(toolId: String, spec: LocalServiceSpec?): Boolean =
        linuxRuntime.listBackground().any {
            it.toolId == toolId && it.session.isAlive && (spec == null || isPortOpen(spec.port))
        }

    suspend fun stop(toolId: String) {
        linuxRuntime.listBackground()
            .filter { it.toolId == toolId }
            .forEach { linuxRuntime.stopBackground(it.id) }
    }

    suspend fun restart(
        toolId: String,
        adapter: ToolRuntimeAdapter,
        spec: LocalServiceSpec?,
    ): ManagedProcess {
        stop(toolId)
        return start(toolId, adapter, spec)
    }

    suspend fun start(
        toolId: String,
        adapter: ToolRuntimeAdapter,
        spec: LocalServiceSpec?,
    ): ManagedProcess {
        val process = requireNotNull(adapter.startService()) { "工具不提供后台服务：$toolId" }
        if (spec != null) awaitPortOrThrow(toolId, process, spec)
        return process
    }

    fun observeLogs(toolId: String): Flow<List<String>> = linuxRuntime.observeBackgroundLogs(toolId)

    fun getLogs(toolId: String): List<String> = linuxRuntime.getBackgroundLogs(toolId)

    fun clearLogs(toolId: String) = linuxRuntime.clearBackgroundLogs(toolId)

    private suspend fun awaitPortOrThrow(
        toolId: String,
        process: ManagedProcess,
        spec: LocalServiceSpec,
    ) {
        val deadline = System.currentTimeMillis() + spec.startupTimeoutMs
        while (true) {
            coroutineContext.ensureActive()
            if (!process.session.isAlive) {
                linuxRuntime.stopBackground(process.id)
                throw IllegalStateException("网关进程启动后立即退出，请查看服务日志：$toolId")
            }
            if (isPortOpen(spec.port)) return
            if (System.currentTimeMillis() > deadline) break
            delay(spec.pollIntervalMs)
        }
        linuxRuntime.stopBackground(process.id)
        throw IllegalStateException(
            "网关未在 ${spec.startupTimeoutMs / 1000} 秒内就绪（端口 ${spec.port} 未监听），已自动停止：$toolId",
        )
    }

    private fun isPortOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), PORT_PROBE_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    private companion object {
        const val PORT_PROBE_TIMEOUT_MS = 250
    }
}
