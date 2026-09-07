package top.wanxiang.app.runtime.service

import top.wanxiang.app.core.common.result.AppResult
import top.wanxiang.app.core.model.RuntimeState
import top.wanxiang.app.runtime.RuntimeHealth
import top.wanxiang.app.runtime.RuntimeInstallRequest
import top.wanxiang.app.runtime.shell.CommandResult
import top.wanxiang.app.runtime.shell.LinuxSession
import top.wanxiang.app.runtime.shell.ManagedProcess
import top.wanxiang.app.runtime.shell.SessionConfig
import top.wanxiang.app.runtime.shell.ShellCommand
import top.wanxiang.app.runtime.shell.TerminalOutput
import java.net.ServerSocket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalServiceLauncherTest {
    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeServiceId() {
        LocalServiceSpec("../service", 8080)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsControlCharactersInPath() {
        LocalServiceSpec("demo", 8080, "/dashboard\u0000")
    }

    @Test
    fun waitsUntilLocalPortIsReadyAndStopsProcess() = runBlocking {
        val runtime = FakeRuntime()
        val launcher = LocalServiceLauncherImpl(runtime)
        val server = ServerSocket(0)
        val session = FakeSession(server)
        val process = ManagedProcess("demo-process", 1L, session)
        runtime.sessionToStop = session

        try {
            val handle = launcher.start(
                LocalServiceSpec("demo", server.localPort, startupTimeoutMs = 2_000, pollIntervalMs = 10),
            ) { process }

            assertEquals("http://localhost:${server.localPort}/", handle.url)
            assertTrue(session.isAlive)
            assertTrue(launcher.stop("demo"))
            assertTrue(!session.isAlive)
            assertEquals("demo-process", runtime.stoppedProcessId)
        } finally {
            server.close()
        }
    }

    @Test
    fun failsFastWhenProcessExitsBeforePortIsReady() = runBlocking {
        val runtime = FakeRuntime()
        val launcher = LocalServiceLauncherImpl(runtime)
        val session = FakeSession(null)
        session.close()

        val error = runCatching {
            launcher.start(LocalServiceSpec("demo", findUnusedPort(), startupTimeoutMs = 500, pollIntervalMs = 10)) {
                ManagedProcess("demo-process", 1L, session)
            }
        }.exceptionOrNull()

        assertTrue(error is LocalServiceStartException)
        assertEquals("demo-process", runtime.stoppedProcessId)
    }

    private fun findUnusedPort(): Int = ServerSocket(0).use { it.localPort }

    private class FakeRuntime : top.wanxiang.app.runtime.LinuxRuntime {
        override val state = MutableStateFlow<RuntimeState>(RuntimeState.Ready)
        override val activeDistroId = MutableStateFlow("ubuntu")
        override val installedDistros = MutableStateFlow<List<top.wanxiang.app.core.model.InstalledDistro>>(emptyList())
        var stoppedProcessId: String? = null
        var sessionToStop: LinuxSession? = null

        override fun refreshInstalledDistros() = Unit
        override suspend fun switchActiveDistro(distroId: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun installDistro(request: RuntimeInstallRequest, onProgress: suspend (top.wanxiang.app.runtime.DownloadProgress) -> Unit): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun uninstallDistro(distroId: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun resetSandbox(distroId: String?): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun initialize(request: RuntimeInstallRequest): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun restoreInstalledState(): Boolean = false
        override suspend fun updateRootfs(distroId: String?): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun checkRootfsUpdate(distroId: String?): AppResult<top.wanxiang.app.runtime.RootfsUpdateInfo> =
            error("unused")
        override suspend fun healthCheck(distroId: String?): RuntimeHealth = error("unused")
        override suspend fun execute(command: ShellCommand, distroId: String?): CommandResult = error("unused")
        override suspend fun startSession(config: SessionConfig, distroId: String?): LinuxSession = error("unused")
        override suspend fun startBackground(
            id: String,
            command: ShellCommand,
            toolId: String?,
            type: top.wanxiang.app.runtime.shell.ProcessType,
            distroId: String?,
        ): ManagedProcess = error("unused")
        override fun listBackground(): List<ManagedProcess> = emptyList()
        override suspend fun cleanupDeadBackground(): Int = 0
        override suspend fun stopBackground(id: String): Boolean {
            stoppedProcessId = id
            sessionToStop?.close()
            return true
        }
        override suspend fun shutdown() = Unit
        override fun rootfsPath(distroId: String?) = error("unused")
        override fun workspacePath() = error("unused")
    }

    private class FakeSession(private val server: ServerSocket?) : LinuxSession {
        private var closed = false
        override val isAlive: Boolean get() = !closed
        override val output: Flow<TerminalOutput> = emptyFlow()
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun resize(columns: Int, rows: Int) = Unit
        override suspend fun interrupt() = Unit
        override suspend fun close() {
            closed = true
            server?.close()
        }
    }
}
