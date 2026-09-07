package top.wanxiang.app.runtime.tools

import top.wanxiang.app.core.datastore.ToolPreferences
import top.wanxiang.app.core.model.RuntimeName
import top.wanxiang.app.core.model.RuntimeRequirement
import top.wanxiang.app.core.tools.DependencyManager
import top.wanxiang.app.core.tools.ProviderManager
import top.wanxiang.app.core.tools.ToolActionResult
import top.wanxiang.app.core.tools.ToolRuntimeAdapter
import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.shell.CommandResult
import top.wanxiang.app.runtime.shell.ShellCommand
import top.wanxiang.app.runtime.shell.SessionConfig
import top.wanxiang.app.runtime.shell.ManagedProcess
import top.wanxiang.app.runtime.shell.ProcessType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

@Singleton
class HermesToolInstaller @Inject constructor(
    private val linuxRuntime: LinuxRuntime,
    private val dependencyManager: DependencyManager,
    private val providerManager: ProviderManager,
    private val remoteScriptRunner: RemoteScriptRunner,
    private val toolCommandLinker: ToolCommandLinker,
    private val settingsDataStore: ToolPreferences,
) : ToolRuntimeAdapter {
    override val toolId: String = "hermes-agent"

    override fun install(): Flow<InstallEvent> = flow {
        emit(InstallEvent.Started(toolId))
        try {
            check(linuxRuntime.state.value is top.wanxiang.app.core.model.RuntimeState.Ready) {
                "Linux Runtime 未就绪，请先初始化 Linux"
            }
            emit(InstallEvent.Progress(toolId, "准备 Python 运行时", 0.08f, InstallEvent.Phase.INSTALLING_DEPENDENCY))
            acquire(RuntimeName.PYTHON, toolId, ">=3.11")
            emit(InstallEvent.Progress(toolId, "准备 curl 和 CA 证书", 0.15f, InstallEvent.Phase.INSTALLING_DEPENDENCY))
            acquire(RuntimeName.CURL, toolId)
            acquire(RuntimeName.CA_CERTIFICATES, toolId)
            acquire(RuntimeName.GIT, toolId)
            emit(InstallEvent.Progress(toolId, "运行 Hermes 官方安装脚本", 0.45f, InstallEvent.Phase.RUNNING_INSTALLER))
            val result = executeAndReport(
                remoteScriptRunner.run(
                    RemoteScriptSpec(
                        name = "hermes-agent",
                        url = "https://hermes-agent.nousresearch.com/install.sh",
                        arguments = listOf(
                            "--skip-setup",
                            "--skip-browser",
                            "--non-interactive",
                            "--dir",
                            ToolLayout.toolDirectory(toolId),
                            "--hermes-home",
                            ToolLayout.toolDataDirectory(toolId),
                        ),
                        retries = 1,
                    ),
                    providerManager.environment(),
                ),
            )
            if (!result.isSuccess) error(result.stderr.ifBlank { "Hermes 安装失败" })
            val link = toolCommandLinker.link(
                command = "hermes",
                target = "/root/.local/bin/hermes",
                environment = providerManager.environment(),
            )
            if (!link.isSuccess) error(link.stderr.ifBlank { "无法创建 hermes 命令入口" })
            emit(InstallEvent.Progress(toolId, "验证 Hermes", 0.80f, InstallEvent.Phase.VERIFYING_INSTALLATION))
            val version = executeAndReport("hermes --version")
            if (!version.isSuccess) error(version.stderr.ifBlank { "找不到 hermes 命令" })
            emit(InstallEvent.Completed(toolId, version.stdout.trim().lineSequence().firstOrNull()))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            emit(InstallEvent.RolledBack(toolId))
            emit(InstallEvent.Failed(toolId, throwable.message ?: "Hermes 安装失败"))
        }
    }

    override suspend fun launch(): CommandResult = execute("hermes")
    override suspend fun verify(): CommandResult = execute("hermes --version")

    override suspend fun interactiveSessionConfig(): SessionConfig = SessionConfig(
        commandLine = "exec hermes",
        environment = providerManager.environment(),
        allowSttyResize = false,
    )

    override suspend fun startService(): ManagedProcess {
        val token = resolveAccessToken()
        return linuxRuntime.startBackground(
            "hermes-dashboard",
            ShellCommand(
                commandLine = "hermes dashboard --no-open --host 0.0.0.0 --port 9119",
                environment = providerManager.environment() + mapOf(
                    "HOST" to "0.0.0.0",
                    // 与 OpenClaw 对齐：网关启动时注入访问 Token，供 Dashboard 鉴权使用
                    "HERMES_DASHBOARD_TOKEN" to token,
                    "WANXIANG_GATEWAY_TOKEN" to token,
                ),
            ),
            toolId = toolId,
            type = ProcessType.SERVICE,
        )
    }

    private suspend fun resolveAccessToken(): String =
        runCatching { settingsDataStore.toolAccessToken(linuxRuntime.activeDistroId.value, toolId).first() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().replace("-", "").also {
                settingsDataStore.setToolAccessToken(linuxRuntime.activeDistroId.value, toolId, it)
            }
    override suspend fun uninstall(deleteData: Boolean): ToolActionResult {
        val dataCleanup = if (deleteData) " && rm -rf ${ToolLayout.toolDataDirectory(toolId)}" else ""
        val link = toolCommandLinker.remove("hermes", providerManager.environment())
        val directory = execute(
            "rm -f /root/.local/bin/hermes /usr/local/bin/hermes && " +
                "rm -rf ${ToolLayout.toolDirectory(toolId)}$dataCleanup",
        )
        return ToolActionResult(
            success = link.isSuccess && directory.isSuccess,
            message = listOf(link, directory).firstOrNull { !it.isSuccess }
                ?.let { it.stderr.ifBlank { it.stdout } }
                ?.ifBlank { "卸载失败" }
                ?: "卸载完成",
        )
    }

    private suspend fun acquire(name: RuntimeName, toolId: String, constraint: String? = null) {
        val result = dependencyManager.acquire(RuntimeRequirement(name, constraint), toolId)
        if (result.isFailure) error(result.errorOrNull()?.message ?: "依赖安装失败：$name")
    }

    private suspend fun execute(command: String) = linuxRuntime.execute(
        ShellCommand(command, environment = providerManager.environment()),
    )

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.executeAndReport(
        result: CommandResult,
    ): CommandResult {
        result.stdout.lineSequence().filter { it.isNotBlank() }.forEach { emit(InstallEvent.Output("hermes-agent", it)) }
        result.stderr.lineSequence().filter { it.isNotBlank() }.forEach { emit(InstallEvent.Output("hermes-agent", it)) }
        return result
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.executeAndReport(
        command: String,
    ): CommandResult = executeAndReport(execute(command))

    private fun CommandResult.toActionResult() = ToolActionResult(
        success = isSuccess,
        message = stderr.ifBlank { stdout }.trim().ifBlank { if (isSuccess) "卸载完成" else "命令退出码 $exitCode" },
    )
}
