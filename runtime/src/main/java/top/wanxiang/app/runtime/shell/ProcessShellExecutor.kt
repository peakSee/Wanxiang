package top.wanxiang.app.runtime.shell

import top.wanxiang.app.runtime.RuntimePathManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProcessShellExecutor @Inject constructor(
    private val pathManager: RuntimePathManager,
) : ShellExecutor {

    override suspend fun execute(
        command: List<String>,
        workingDirectory: File?,
        environment: Map<String, String>,
        timeoutMs: Long,
        onOutput: ((String) -> Unit)?,
    ): CommandResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val process = ProcessBuilder(command)
            .apply {
                workingDirectory?.let { directory(it) }
                // Keep the PRoot host environment deterministic. In particular,
                // never inherit Termux's LD_PRELOAD or a stale PROOT_LOADER.
                environment().clear()
                environment().putAll(pathManager.hostProcessEnvironment())
                environment().putAll(environment)
            }
            .redirectErrorStream(false)
            .start()

        // 🌟 关键修复：主动关闭子进程的标准输入（stdin），防止任何交互式脚本/命令无限阻塞在等待键盘输入上
        runCatching { process.outputStream.close() }

        val stdoutDeferred = async(Dispatchers.IO) {
            readFully(process.inputStream, onOutput)
        }
        val stderrDeferred = async(Dispatchers.IO) {
            readFully(process.errorStream, onOutput)
        }

        try {
            val exitCode = withTimeout(timeoutMs) {
                runInterruptible(Dispatchers.IO) { process.waitFor() }
            }
            val (stdout, stderr) = listOf(stdoutDeferred, stderrDeferred).awaitAll().let { values ->
                values[0] to values[1]
            }
            CommandResult(
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            process.destroyForcibly()
            // 等进程树真正消亡：PRoot 被强杀后，被 ptrace 的 npm/node 由内核
            // 异步清除，若立刻开始回滚删除目录，可能撞上仍在写入的残留进程。
            runInterruptible(Dispatchers.IO) {
                process.waitFor(PROCESS_TEARDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            // Preserve output produced before the timeout. Cancelling the
            // readers here discarded the only useful diagnostic from long
            // setup scripts (APT/Gradle/Flutter), leaving just the timeout
            // sentence in the installation dialog.
            val partialStdout = runCatching {
                withTimeoutOrNull(PROCESS_TEARDOWN_TIMEOUT_MS) { stdoutDeferred.await() }.orEmpty()
            }.getOrDefault("")
            val partialStderr = runCatching {
                withTimeoutOrNull(PROCESS_TEARDOWN_TIMEOUT_MS) { stderrDeferred.await() }.orEmpty()
            }.getOrDefault("")
            CommandResult(
                exitCode = TIMEOUT_EXIT_CODE,
                stdout = partialStdout,
                stderr = buildString {
                    if (partialStderr.isNotBlank()) {
                        append(partialStderr.trimEnd())
                        append('\n')
                    }
                    append("Command timed out after ${timeoutMs}ms")
                },
                durationMs = System.currentTimeMillis() - startedAt,
            )
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // 用户主动取消编译：强杀 PRoot 进程树，避免 Gradle 后台继续跑
            process.destroyForcibly()
            throw cancellation
        } finally {
            process.destroy()
        }
    }

    private suspend fun readFully(
        stream: java.io.InputStream,
        onOutput: ((String) -> Unit)? = null,
    ): String = try {
        stream.use { input ->
            val kept = ByteArrayOutputStream(MAX_CAPTURE_BYTES)
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var totalBytes = 0L
            // 🔒 排水循环绝不能被消费端异常杀死：stdout/stderr 两个读取协程并发调用
            // 同一个 onOutput，若回调内部抛出未捕获异常（例如共享 StringBuilder 的数据
            // 竞争），本协程死亡后宿主管道无人读取，子进程写满内核管道缓冲（约 64KB）
            // 后 write() 永久阻塞，整棵构建进程树随之挂死、日志冻结在一半。
            // 因此回调异常在此兜底捕获：连续失败仅停用回调，排水必须继续。
            var callback = onOutput
            var consecutiveCallbackFailures = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                totalBytes += read
                val remaining = MAX_CAPTURE_BYTES - kept.size()
                if (remaining > 0) kept.write(buffer, 0, minOf(read, remaining))
                if (callback != null && read > 0) {
                    val chunk = String(buffer, 0, read, Charsets.UTF_8)
                    try {
                        callback(chunk)
                        consecutiveCallbackFailures = 0
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        throw cancellation
                    } catch (t: Throwable) {
                        consecutiveCallbackFailures++
                        if (consecutiveCallbackFailures >= MAX_CALLBACK_FAILURES) {
                            // 回调持续崩溃时放弃流式转发（CommandResult 仍保留完整输出），
                            // 但绝不能停止读管道——那会把正在运行的 Gradle/Flutter 卡死。
                            callback = null
                        }
                    }
                }
            }
            buildString {
                append(kept.toByteArray().toString(Charsets.UTF_8))
                if (totalBytes > kept.size()) {
                    append("\n\n[进程输出已截断：共 ")
                    append(totalBytes)
                    append(" 字节，仅保留前 ")
                    append(kept.size())
                    append(" 字节]")
                }
            }
        }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (io: java.io.IOException) {
        // Timeout teardown closes the process streams from this thread while a
        // reader is blocked in read(); Android surfaces that as
        // InterruptedIOException. Treat it as EOF: the timeout already produced
        // the authoritative CommandResult, and the reader failure must not
        // override it through structured-concurrency propagation.
        ""
    }

    private companion object {
        const val TIMEOUT_EXIT_CODE = 124
        const val PROCESS_TEARDOWN_TIMEOUT_MS = 1_000L
        const val MAX_CAPTURE_BYTES = 4 * 1024 * 1024
        const val READ_BUFFER_BYTES = 16 * 1024

        /** onOutput 连续抛异常达到该次数后停用流式回调，仅保留排水与完整输出捕获。 */
        const val MAX_CALLBACK_FAILURES = 8
    }
}
