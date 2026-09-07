package top.wanxiang.app.runtime.shell

import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 交互式会话的无 JNI backend，底层由 Debian `script` 创建 PTY。
 *
 * 它提供 stdin/stdout 生命周期、ANSI 输出流和可注入的窗口调整回调；后续接入真正
 * forkpty backend 时无需修改 LinuxSession API。
 */
class ProcessLinuxSession(
    command: List<String>,
    hostEnvironment: Map<String, String> = emptyMap(),
    private val allowSttyResize: Boolean = true,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val resizeCallback: (suspend (columns: Int, rows: Int) -> Unit)? = null,
    private val cleanupCallback: suspend () -> Unit = {},
) : LinuxSession {
    private val process = ProcessBuilder(command)
        .directory(File("/"))
        .apply {
            environment().clear()
            environment().putAll(hostEnvironment)
        }
        .redirectErrorStream(true)
        .start()
    private val outputChannel = Channel<TerminalOutput>(OUTPUT_BUFFER_CAPACITY)
    private val closed = AtomicBoolean(false)
    private val sessionScope = CoroutineScope(SupervisorJob() + scope.coroutineContext)
    private val readerJob: Job = sessionScope.launch {
        try {
            InputStreamReader(process.inputStream, Charsets.UTF_8).use { input ->
                val buffer = CharArray(4096)
                while (!closed.get()) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        outputChannel.send(
                            TerminalOutput(
                                TerminalStream.STDOUT,
                                String(buffer, 0, read),
                            ),
                        )
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (io: IOException) {
            // close()/process.destroy() closes the stream from another thread while
            // this coroutine is blocked in read(); Android surfaces that as
            // InterruptedIOException. Treat it as EOF instead of crashing the app.
        }
        outputChannel.close()
    }

    override val output: Flow<TerminalOutput> = outputChannel.receiveAsFlow()

    // Android's Process API does not expose a portable PID accessor; keep this
    // nullable rather than parsing implementation-specific /proc state.
    override val pid: Long? get() = null

    override val isAlive: Boolean
        get() = !closed.get() && process.isAlive

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        if (!closed.get()) {
            process.outputStream.write(data)
            process.outputStream.flush()
        }
    }

    override suspend fun resize(columns: Int, rows: Int) {
        // The Script backend records the PTY slave path and updates it through a
        // separate Runtime command. This avoids injecting `stty` into a TUI's
        // stdin while keeping the LinuxSession contract backend-independent.
        if (resizeCallback != null) {
            resizeCallback.invoke(columns, rows)
        } else if (allowSttyResize) {
            write("stty cols $columns rows $rows\n".toByteArray())
        }
    }

    override suspend fun interrupt() = write(byteArrayOf(3))

    override suspend fun close() = withContext(Dispatchers.IO) {
        if (closed.compareAndSet(false, true)) {
            readerJob.cancel()
            sessionScope.cancel()
            process.destroy()
            if (!process.waitFor(PROCESS_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
            outputChannel.close()
            runCatching { cleanupCallback() }
        }
    }

    private companion object {
        const val PROCESS_SHUTDOWN_TIMEOUT_MS = 500L
        const val OUTPUT_BUFFER_CAPACITY = 1024
    }
}
