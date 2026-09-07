package top.wanxiang.app.runtime.tools

import top.wanxiang.app.runtime.FakeLinuxRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteScriptRunnerTest {
    @Test
    fun downloadsToPrivateTempFileAndDoesNotPipeToShell() = runBlocking {
        val runtime = FakeLinuxRuntime()
        val runner = RemoteScriptRunner(runtime)

        runner.run(
            RemoteScriptSpec(
                name = "openclaw",
                url = "https://openclaw.ai/install.sh",
                arguments = listOf("--no-onboard"),
            ),
        )

        val command = runtime.executedCommands.single()
        assertTrue(command.contains("curl -fsSL"))
        assertTrue(command.contains("-o \"\$script_path\""))
        assertTrue(command.contains("trap 'rm -f \"\$script_path\"'"))
        assertTrue(command.contains("bash \"\$script_path\" '--no-onboard'"))
        assertFalse(command.contains("| bash"))
        assertFalse(command.contains("| sh"))
    }

    @Test
    fun optionalSha256IsVerifiedBeforeExecution() = runBlocking {
        val runtime = FakeLinuxRuntime()
        RemoteScriptRunner(runtime).run(
            RemoteScriptSpec(
                name = "codex",
                url = "https://chatgpt.com/codex/install.sh",
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ),
        )

        assertTrue(runtime.executedCommands.single().contains("sha256sum -c -"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedSha256() {
        runBlocking {
        RemoteScriptRunner(FakeLinuxRuntime()).run(
            RemoteScriptSpec(
                name = "codex",
                url = "https://chatgpt.com/codex/install.sh",
                sha256 = "not-a-sha256",
            ),
        )
        }
    }

    @Test
    fun rejectsUnregisteredScriptHost() = runBlocking {
        val runner = RemoteScriptRunner(FakeLinuxRuntime())

        var rejected = false
        try {
            runner.run(RemoteScriptSpec("demo", "https://example.com/install.sh"))
        } catch (expected: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun rejectsNonHttpsScript() = runBlocking {
        val runner = RemoteScriptRunner(FakeLinuxRuntime())

        var rejected = false
        try {
            runner.run(RemoteScriptSpec("codex", "http://chatgpt.com/codex/install.sh"))
        } catch (expected: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun rejectsScriptUrlWithUserInfoOrNonStandardPort() = runBlocking {
        val runner = RemoteScriptRunner(FakeLinuxRuntime())

        val userInfo = runCatching {
            runner.run(RemoteScriptSpec("codex", "https://user@chatgpt.com/codex/install.sh"))
        }.exceptionOrNull()
        val nonStandardPort = runCatching {
            runner.run(RemoteScriptSpec("codex", "https://chatgpt.com:8443/codex/install.sh"))
        }.exceptionOrNull()

        assertTrue(userInfo is IllegalArgumentException)
        assertTrue(nonStandardPort is IllegalArgumentException)
    }
}
