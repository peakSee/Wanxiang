package top.wanxiang.app.runtime

import top.wanxiang.app.runtime.shell.CommandResult
import top.wanxiang.app.runtime.shell.ShellCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeLinuxRuntimeTest {
    @Test
    fun returnsConfiguredCommandResultAndRecordsCommand() = runBlocking {
        val runtime = FakeLinuxRuntime()
        runtime.commandResults["echo hello"] = CommandResult(0, "hello\n", "", 1)

        val result = runtime.execute(ShellCommand("echo hello"))

        assertEquals("hello\n", result.stdout)
        assertEquals(listOf("echo hello"), runtime.executedCommands)
    }
}
