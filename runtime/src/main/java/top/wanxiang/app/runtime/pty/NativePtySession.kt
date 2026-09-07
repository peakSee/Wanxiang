package top.wanxiang.app.runtime.pty

import top.wanxiang.app.runtime.shell.LinuxSession
import top.wanxiang.app.runtime.shell.SessionConfig
import top.wanxiang.app.runtime.shell.TerminalOutput
import top.wanxiang.app.runtime.shell.TerminalStream
import java.io.IOException
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
 * 真正的 PTY 会话：JNI forkpty 打开 master/slave，命令直接 exec 在 slave 上
 * （setsid + 控制终端），App 通过 master fd 读写。与 Termux 的 PTY 语义一致：
 * Ctrl+C / 任务控制 / SIGWINCH 缩放 / raw mode 全部可用。
 */
class NativePtySession(
    command: List<String>,
    hostEnvironment: Map<String, String>,
    private val config: SessionConfig,
    private val cleanupCallback: suspend () -> Unit = {},
) : LinuxSession {

    private val pair: IntArray = NativePty.openAndExec(
        command.toTypedArray(),
        hostEnvironment.entries.map { "${it.key}=${it.value}" }.toTypedArray(),
        "/",
        config.columns,
        config.rows,
    )
    private val masterFd: Int = pair[0]
    private val childPid: Int = pair[1]

    private val outputChannel = Channel<TerminalOutput>(OUTPUT_BUFFER_CAPACITY)
    private val closed = AtomicBoolean(false)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val utf8Decoder = IncrementalUtf8Decoder(8192)

    private val readerJob: Job = sessionScope.launch {
        val buffer = ByteArray(8192)
        try {
            while (!closed.get()) {
                val n = NativePty.readFd(masterFd, buffer)
                if (n < 0) break
                if (n > 0) {
                    val text = decodeUtf8(buffer, n)
                    if (text.isNotEmpty()) {
                        outputChannel.send(TerminalOutput(TerminalStream.STDOUT, text))
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (io: IOException) {
            // fd 在关闭时被另一线程 close，视为 EOF
        }
        decodeUtf8End().takeIf { it.isNotEmpty() }?.let {
            outputChannel.send(TerminalOutput(TerminalStream.STDOUT, it))
        }
        NativePty.waitPid(childPid)
        outputChannel.close()
    }

    /** 流式 UTF-8 解码，避免多字节字符跨 read 被截断。 */
    private fun decodeUtf8(buffer: ByteArray, length: Int): String {
        return utf8Decoder.decode(buffer, length)
    }

    private fun decodeUtf8End(): String = utf8Decoder.finish()

    override val output: Flow<TerminalOutput> = outputChannel.receiveAsFlow()

    override val pid: Long? get() = childPid.toLong()

    override val isAlive: Boolean
        get() = !closed.get() && NativePty.killPid(childPid, 0) == 0

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        if (!closed.get()) {
            var offset = 0
            while (offset < data.size) {
                val written = NativePty.writeFd(masterFd, data, offset, data.size - offset)
                if (written <= 0) break
                offset += written
            }
        }
    }

    override suspend fun resize(columns: Int, rows: Int) = withContext(Dispatchers.IO) {
        if (!closed.get()) {
            NativePty.resizeFd(masterFd, columns.coerceIn(20, 400), rows.coerceIn(5, 200))
        }
    }

    override suspend fun interrupt() = write(byteArrayOf(3))

    override suspend fun close() = withContext(Dispatchers.IO) {
        if (closed.compareAndSet(false, true)) {
            readerJob.cancel()
            sessionScope.cancel()
            // SIGHUP 让 shell 优雅退出；proot --kill-on-exit 负责整棵进程树。
            NativePty.killPid(childPid, 1)
            // 硬停止兜底：setsid 后 -pid 覆盖整个会话进程组。
            NativePty.killPid(childPid, 9)
            NativePty.waitPid(childPid)
            NativePty.closeFd(masterFd)
            outputChannel.close()
            runCatching { cleanupCallback() }
        }
    }

    companion object {
        // 输出缓冲：~8KB/帧 × 1024 ≈ 8MB 上限，配合 send 背压可在高负载下不丢数据。
        const val OUTPUT_BUFFER_CAPACITY = 1024
    }
}
