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
import java.io.File
import kotlinx.coroutines.flow.StateFlow

interface LinuxRuntime {
    val state: StateFlow<RuntimeState>
    val activeDistroId: StateFlow<String>
    val installedDistros: StateFlow<List<InstalledDistro>>

    suspend fun initialize(request: RuntimeInstallRequest = RuntimeInstallRequest("ubuntu")): AppResult<Unit>
    suspend fun restoreInstalledState(): Boolean
    suspend fun updateRootfs(distroId: String? = null): AppResult<Unit>
    suspend fun checkRootfsUpdate(distroId: String? = null): AppResult<RootfsUpdateInfo>
    suspend fun healthCheck(distroId: String? = null): RuntimeHealth

    suspend fun switchActiveDistro(distroId: String): AppResult<Unit>
    suspend fun installDistro(request: RuntimeInstallRequest, onProgress: suspend (DownloadProgress) -> Unit = {}): AppResult<Unit>
    suspend fun importDistro(request: RuntimeInstallRequest, archive: File): AppResult<Unit> =
        AppResult.Failure(top.wanxiang.app.core.common.result.AppError(
            top.wanxiang.app.core.common.result.ErrorCode.INSTALLATION_FAILED,
            "当前运行时不支持导入 RootFS",
        ))
    suspend fun uninstallDistro(distroId: String): AppResult<Unit>
    /** Remove the active distro and its installed tools while preserving /workspace. */
    suspend fun resetSandbox(distroId: String? = null): AppResult<Unit>
    fun refreshInstalledDistros()

    suspend fun execute(command: ShellCommand, distroId: String? = null): CommandResult
    suspend fun startSession(config: SessionConfig = SessionConfig(), distroId: String? = null): LinuxSession

    suspend fun startBackground(
        id: String,
        command: ShellCommand,
        toolId: String? = null,
        type: ProcessType = ProcessType.SERVICE,
        distroId: String? = null,
    ): ManagedProcess

    suspend fun stopBackground(id: String): Boolean
    fun listBackground(): List<ManagedProcess>
    suspend fun cleanupDeadBackground(): Int
    fun observeBackgroundLogs(idOrToolId: String): kotlinx.coroutines.flow.Flow<List<String>> = kotlinx.coroutines.flow.emptyFlow()
    fun getBackgroundLogs(idOrToolId: String): List<String> = emptyList()
    fun clearBackgroundLogs(idOrToolId: String) = Unit

    suspend fun shutdown()
    fun rootfsPath(distroId: String? = null): File
    fun rootfsVersion(distroId: String? = null): String? = null
    fun workspacePath(): File
}
