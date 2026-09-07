package top.wanxiang.app.runtime.tools

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
import top.wanxiang.app.core.datastore.ToolPreferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

@Singleton
class OpenClawToolInstaller @Inject constructor(
    private val linuxRuntime: LinuxRuntime,
    private val dependencyManager: DependencyManager,
    private val providerManager: ProviderManager,
    private val remoteScriptRunner: RemoteScriptRunner,
    private val toolCommandLinker: ToolCommandLinker,
    private val settingsDataStore: ToolPreferences,
) : ToolRuntimeAdapter {
    override val toolId: String = "openclaw"

    override fun install(): Flow<InstallEvent> = flow {
        emit(InstallEvent.Started(toolId))
        try {
            checkReady()
            emit(InstallEvent.Progress(toolId, "准备 Node.js 运行时", 0.08f, InstallEvent.Phase.INSTALLING_DEPENDENCY))
            acquire(RuntimeName.NODE, toolId, ">=22.22.3")
            emit(InstallEvent.Progress(toolId, "准备 curl 和 CA 证书", 0.15f, InstallEvent.Phase.INSTALLING_DEPENDENCY))
            acquire(RuntimeName.CURL, toolId)
            acquire(RuntimeName.CA_CERTIFICATES, toolId)
            acquire(RuntimeName.GIT, toolId)
            emit(InstallEvent.Progress(toolId, "运行 OpenClaw 官方安装脚本", 0.45f, InstallEvent.Phase.RUNNING_INSTALLER))
            val installEnvironment = providerManager.environment() + mapOf(
                "OPENCLAW_HOME" to ToolLayout.toolDataDirectory(toolId),
                "XDG_CONFIG_HOME" to ToolLayout.toolDataDirectory(toolId),
                "OPENCLAW_INSTALL_METHOD" to "npm",
                "OPENCLAW_NO_PROMPT" to "1",
                "OPENCLAW_NO_ONBOARD" to "1",
                "npm_config_prefix" to ToolLayout.toolDirectory(toolId),
                "NPM_CONFIG_PREFIX" to ToolLayout.toolDirectory(toolId),
            )
            val result = executeAndReport(
                remoteScriptRunner.run(
                    RemoteScriptSpec(
                        name = "openclaw",
                        url = "https://openclaw.ai/install.sh",
                        arguments = listOf("--install-method", "npm", "--no-prompt", "--no-onboard"),
                    ),
                    installEnvironment,
                ),
            )
            if (!result.isSuccess) error(result.stderr.ifBlank { "OpenClaw 安装失败" })
            val link = toolCommandLinker.link(
                command = "openclaw",
                target = "${ToolLayout.toolDirectory(toolId)}/bin/openclaw",
                environment = providerManager.environment(),
            )
            if (!link.isSuccess) error(link.stderr.ifBlank { "无法创建 openclaw 命令入口" })
            emit(InstallEvent.Progress(toolId, "验证 OpenClaw 命令", 0.85f, InstallEvent.Phase.VERIFYING_INSTALLATION))
            val version = executeAndReport("openclaw --version")
            if (!version.isSuccess) error(version.stderr.ifBlank { "找不到 openclaw 命令" })
            emit(InstallEvent.Completed(toolId, version.stdout.trim().lineSequence().firstOrNull()))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            emit(InstallEvent.RolledBack(toolId))
            emit(InstallEvent.Failed(toolId, throwable.message ?: "OpenClaw 安装失败"))
        }
    }

    override suspend fun launch(): CommandResult {
        val dataDir = ToolLayout.toolDataDirectory(toolId)
        return execute(
            command = "mkdir -p $dataDir && openclaw gateway --allow-unconfigured --bind lan --port 18789",
            environment = mapOf(
                "OPENCLAW_GATEWAY_TOKEN" to resolveAccessToken(),
                "OPENCLAW_STATE_DIR" to dataDir,
                "OPENCLAW_CONFIG_PATH" to "$dataDir/openclaw.json",
            ),
        )
    }

    override suspend fun verify(): CommandResult = execute("openclaw --version")

    override suspend fun interactiveSessionConfig(): SessionConfig = SessionConfig(
        commandLine = "exec openclaw",
        environment = providerManager.environment(),
        allowSttyResize = false,
    )

    override suspend fun startService(): ManagedProcess {
        val token = resolveAccessToken()
        val tokenEnv = mapOf(
            "OPENCLAW_TOKEN" to token,
            "OPENCLAW_GATEWAY_TOKEN" to token,
        )

        val dataDir = ToolLayout.toolDataDirectory(toolId)
        val toolDir = ToolLayout.toolDirectory(toolId)

        return linuxRuntime.startBackground(
            "openclaw-gateway",
            ShellCommand(
                commandLine = "mkdir -p $dataDir && " +
                    "exec openclaw gateway --allow-unconfigured --bind lan --port 18789",
                environment = providerManager.environment() + mapOf(
                    "HOST" to "0.0.0.0",
                    "PORT" to "18789",
                    "OPENCLAW_PORT" to "18789",
                    "OPENCLAW_HOST" to "0.0.0.0",
                    "OPENCLAW_GATEWAY_HOST" to "0.0.0.0",
                    "OPENCLAW_GATEWAY_PORT" to "18789",
                    "OPENCLAW_HOME" to dataDir,
                    "OPENCLAW_STATE_DIR" to dataDir,
                    "OPENCLAW_CONFIG_PATH" to "$dataDir/openclaw.json",
                    "XDG_CONFIG_HOME" to dataDir,
                    "npm_config_prefix" to toolDir,
                    "NPM_CONFIG_PREFIX" to toolDir,
                    "PATH" to "$toolDir/bin:/opt/wanxiang/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "NODE_ENV" to "production",
                ) + tokenEnv,
            ),
            toolId = toolId,
            type = ProcessType.SERVICE,
        )
    }

    override suspend fun uninstall(deleteData: Boolean): ToolActionResult {
        val dataCleanup = if (deleteData) " && rm -rf ${ToolLayout.toolDataDirectory(toolId)}" else ""
        val link = toolCommandLinker.remove("openclaw", providerManager.environment())
        val directory = execute("rm -rf ${ToolLayout.toolDirectory(toolId)}$dataCleanup")
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

    private suspend fun execute(
        command: String,
        environment: Map<String, String> = emptyMap(),
    ) = linuxRuntime.execute(
        ShellCommand(command, environment = providerManager.environment() + environment),
    )

    private suspend fun resolveAccessToken(): String =
        runCatching { settingsDataStore.toolAccessToken(linuxRuntime.activeDistroId.value, toolId).first() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().replace("-", "").also {
                settingsDataStore.setToolAccessToken(linuxRuntime.activeDistroId.value, toolId, it)
            }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.executeAndReport(
        result: CommandResult,
    ): CommandResult {
        result.stdout.lineSequence().filter { it.isNotBlank() }.forEach { emit(InstallEvent.Output("openclaw", it)) }
        result.stderr.lineSequence().filter { it.isNotBlank() }.forEach { emit(InstallEvent.Output("openclaw", it)) }
        return result
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.executeAndReport(
        command: String,
    ): CommandResult = executeAndReport(execute(command))
    private fun checkReady() = check(linuxRuntime.state.value is top.wanxiang.app.core.model.RuntimeState.Ready) {
        "Linux Runtime 未就绪，请先初始化 Linux"
    }

    private fun CommandResult.toActionResult() = ToolActionResult(
        success = isSuccess,
        message = stderr.ifBlank { stdout }.trim().ifBlank { if (isSuccess) "卸载完成" else "命令退出码 $exitCode" },
    )
}
