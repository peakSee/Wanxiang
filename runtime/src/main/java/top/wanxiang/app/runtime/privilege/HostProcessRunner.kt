package top.wanxiang.app.runtime.privilege

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Root 与 Shizuku UserService 共用的有界、可取消宿主进程执行器。 */
internal class HostProcessRunner(
    private val processFactory: (String) -> Process,
) {
    private val running = ConcurrentHashMap<String, Process>()

    fun execute(operationId: String, command: String): ShellExecResult {
        if (command.isBlank() || command.length > MAX_COMMAND_LENGTH) {
            return ShellExecResult(false, -1, "", "宿主命令为空或超过 $MAX_COMMAND_LENGTH 字符")
        }
        val id = operationId.ifBlank { UUID.randomUUID().toString() }
        return try {
            val process = processFactory(command)
            if (running.putIfAbsent(id, process) != null) {
                stopProcess(process)
                error("宿主操作 $id 已在执行")
            }
            val stdoutFuture = IO_EXECUTOR.submit<String> { process.inputStream.readBoundedText() }
            val stderrFuture = IO_EXECUTOR.submit<String> { process.errorStream.readBoundedText() }
            try {
                val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!completed) stopProcess(process)
                val stdout = stdoutFuture.awaitOutput(process.inputStream)
                val stderr = stderrFuture.awaitOutput(process.errorStream)
                if (completed) {
                    val exitCode = process.exitValue()
                    ShellExecResult(exitCode == 0, exitCode, stdout, stderr)
                } else {
                    ShellExecResult(false, -1, stdout, stderr.ifBlank { "命令执行超时 (${COMMAND_TIMEOUT_SECONDS}s)" })
                }
            } finally {
                running.remove(id, process)
                if (process.isAlive) stopProcess(process)
            }
        } catch (throwable: Throwable) {
            ShellExecResult(false, -1, "", "宿主命令执行失败: ${throwable.message ?: throwable::class.java.simpleName}")
        }
    }

    fun cancel(operationId: String): Boolean {
        val process = running[operationId] ?: return false
        stopProcess(process)
        return true
    }

    private fun stopProcess(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.destroy() }
        if (runCatching { process.isAlive }.getOrDefault(false)) runCatching { process.destroyForcibly() }
    }

    private fun InputStream.readBoundedText(): String {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = ByteArrayOutputStream()
        var truncated = false
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            val remaining = MAX_STREAM_BYTES - output.size()
            if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
            if (count > remaining) truncated = true
        }
        return output.toString(Charsets.UTF_8.name()) + if (truncated) "\n[宿主输出已截断]" else ""
    }

    private fun Future<String>.awaitOutput(stream: InputStream): String = try {
        get(OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (_: Throwable) {
        runCatching { stream.close() }
        cancel(true)
        "[宿主输出读取超时或已取消]"
    }

    companion object {
        const val MAX_COMMAND_LENGTH = 32 * 1024
        private const val MAX_STREAM_BYTES = 64 * 1024
        private const val COMMAND_TIMEOUT_SECONDS = 30L
        private const val OUTPUT_DRAIN_TIMEOUT_SECONDS = 3L
        private val IO_EXECUTOR = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "wanxiang-host-output").apply { isDaemon = true }
        }
    }
}
