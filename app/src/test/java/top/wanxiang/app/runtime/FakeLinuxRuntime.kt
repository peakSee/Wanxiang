package top.wanxiang.app.runtime

import top.wanxiang.app.core.common.result.AppResult
import top.wanxiang.app.core.model.InstalledDistro
import top.wanxiang.app.core.model.RuntimeState
import top.wanxiang.app.runtime.shell.CommandResult
import top.wanxiang.app.runtime.shell.LinuxSession
import top.wanxiang.app.runtime.shell.ManagedProcess
import top.wanxiang.app.runtime.shell.ProcessType
import top.wanxiang.app.runtime.shell.SessionConfig
import top.wanxiang.app.runtime.shell.ShellCommand
import top.wanxiang.app.runtime.shell.TerminalOutput
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Small deterministic runtime double for JVM tool-contract tests. */
class FakeLinuxRuntime : LinuxRuntime {
    override val state = MutableStateFlow<RuntimeState>(RuntimeState.Ready)
    override val activeDistroId = MutableStateFlow("ubuntu")
    override val installedDistros = MutableStateFlow<List<InstalledDistro>>(
        listOf(
            InstalledDistro("ubuntu", "Ubuntu 24.04 LTS", 1024L * 1024L * 200L, System.currentTimeMillis(), true, "apt", "当前主系统"),
        ),
    )

    val commandResults = mutableMapOf<String, CommandResult>()
    val executedCommands = mutableListOf<String>()
    val executedShellCommands = mutableListOf<ShellCommand>()
    val sessions = mutableListOf<SessionConfig>()
    val backgroundProcesses = linkedMapOf<String, ManagedProcess>()
    val backgroundLogs = mutableMapOf<String, List<String>>()

    override fun refreshInstalledDistros() = Unit
    override suspend fun switchActiveDistro(distroId: String): AppResult<Unit> {
        activeDistroId.value = distroId
        return AppResult.Success(Unit)
    }
    override suspend fun installDistro(request: RuntimeInstallRequest, onProgress: suspend (DownloadProgress) -> Unit): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun uninstallDistro(distroId: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun resetSandbox(distroId: String?): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun initialize(request: RuntimeInstallRequest): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun restoreInstalledState(): Boolean = false
    override suspend fun updateRootfs(distroId: String?): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun checkRootfsUpdate(distroId: String?): AppResult<RootfsUpdateInfo> =
        AppResult.Success(
            RootfsUpdateInfo(
                distroId = distroId ?: "ubuntu",
                imageReference = "buildpack-deps:noble-scm",
                currentVersion = "test",
                currentDigest = "sha256:test",
                latestDigest = "sha256:test",
                hasUpdate = false,
            ),
        )

    override suspend fun healthCheck(distroId: String?): RuntimeHealth = RuntimeHealth(
        status = RuntimeHealthStatus.HEALTHY,
        osRelease = "Debian GNU/Linux",
        architecture = "aarch64",
        workspaceWritable = true,
    )

    override suspend fun execute(command: ShellCommand, distroId: String?): CommandResult {
        executedCommands += command.commandLine
        executedShellCommands += command
        return commandResults[command.commandLine] ?: CommandResult(
            exitCode = 0,
            stdout = "",
            stderr = "",
            durationMs = 0,
        )
    }

    override suspend fun startSession(config: SessionConfig, distroId: String?): LinuxSession {
        sessions += config
        return FakeSession()
    }

    override suspend fun startBackground(
        id: String,
        command: ShellCommand,
        toolId: String?,
        type: ProcessType,
        distroId: String?,
    ): ManagedProcess = ManagedProcess(
        id,
        System.currentTimeMillis(),
        FakeSession(),
        toolId,
        null,
        type,
    ).also { backgroundProcesses[id] = it }

    override suspend fun stopBackground(id: String): Boolean = backgroundProcesses.remove(id) != null
    override fun listBackground(): List<ManagedProcess> = backgroundProcesses.values.toList()
    override suspend fun cleanupDeadBackground(): Int = 0
    override fun getBackgroundLogs(idOrToolId: String): List<String> = backgroundLogs[idOrToolId].orEmpty()
    override suspend fun shutdown() = Unit
    override fun rootfsPath(distroId: String?): File = File("/fake/rootfs")
    override fun workspacePath(): File = File("/fake/workspace")

    private class FakeSession : LinuxSession {
        override val isAlive: Boolean = true
        override val output: Flow<TerminalOutput> = emptyFlow()
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun resize(columns: Int, rows: Int) = Unit
        override suspend fun interrupt() = Unit
        override suspend fun close() = Unit
    }
}
