package top.wanxiang.app.runtime.privilege

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostProcessRunnerTest {
    @Test
    fun `large output is drained concurrently and bounded`() {
        val process = FakeProcess(stdout = "x".repeat(200_000), completed = true)
        val result = HostProcessRunner { process }.execute("large-output", "test")

        assertTrue(result.success)
        assertTrue(result.stdout.contains("输出已截断"))
        assertTrue(result.stdout.length < 70_000)
    }

    @Test
    fun `operation can be cancelled by id`() {
        val process = FakeProcess(completed = false)
        val runner = HostProcessRunner { process }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<ShellExecResult> { runner.execute("cancel-me", "test") }
            var cancelled = false
            for (i in 0 until 100) {
                if (runner.cancel("cancel-me")) {
                    cancelled = true
                    break
                }
                Thread.sleep(10)
            }
            assertTrue("Expected runner.cancel to succeed", cancelled)
            val result = future.get(2, TimeUnit.SECONDS)
            assertFalse(result.success)
            assertFalse(process.isAlive)
        } finally {
            executor.shutdownNow()
        }
    }

    private class FakeProcess(
        stdout: String = "",
        stderr: String = "",
        completed: Boolean,
    ) : Process() {
        private val done = CountDownLatch(if (completed) 0 else 1)
        private val stdoutStream = ByteArrayInputStream(stdout.toByteArray())
        private val stderrStream = ByteArrayInputStream(stderr.toByteArray())
        private val stdinStream = ByteArrayOutputStream()
        @Volatile private var alive = !completed
        @Volatile private var code = if (completed) 0 else 143

        override fun getOutputStream(): OutputStream = stdinStream
        override fun getInputStream(): InputStream = stdoutStream
        override fun getErrorStream(): InputStream = stderrStream
        override fun waitFor(): Int {
            done.await()
            return code
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)
        override fun exitValue(): Int {
            check(!alive) { "process still alive" }
            return code
        }
        override fun destroy() {
            alive = false
            done.countDown()
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
        override fun isAlive(): Boolean = alive
    }
}
