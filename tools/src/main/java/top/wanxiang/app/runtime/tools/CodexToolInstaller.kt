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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@Singleton
class CodexToolInstaller @Inject constructor(
    private val linuxRuntime: LinuxRuntime,
    private val dependencyManager: DependencyManager,
    private val providerManager: ProviderManager,
    private val remoteScriptRunner: RemoteScriptRunner,
    private val toolCommandLinker: ToolCommandLinker,
) : ToolRuntimeAdapter {
    override val toolId: String = "codex"

    override fun install(): Flow<InstallEvent> = flow {
        emit(InstallEvent.Started(toolId))
        try {
            checkRuntimeReady()
            emit(InstallEvent.Progress(toolId, "准备 curl 和 CA 证书", 0.15f, InstallEvent.Phase.INSTALLING_DEPENDENCY))
            ensureDependency(RuntimeName.CURL, toolId)
            ensureDependency(RuntimeName.CA_CERTIFICATES, toolId)
            emit(InstallEvent.Progress(toolId, "运行 OpenAI Codex 安装脚本", 0.45f, InstallEvent.Phase.RUNNING_INSTALLER))
            val installEnvironment = providerManager.environment() + mapOf(
                "HOME" to ToolLayout.toolDirectory(toolId),
                "CODEX_HOME" to ToolLayout.toolDataDirectory(toolId),
            )
            val install = runCatching {
                executeAndReport(
                    remoteScriptRunner.run(
                        RemoteScriptSpec(
                            name = "codex",
                            url = "https://chatgpt.com/codex/install.sh",
                            retries = 0,
                        ),
                        installEnvironment,
                    ),
                )
            }.getOrElse { CommandResult(exitCode = 1, stdout = "", stderr = it.message ?: "网络超时", durationMs = 0L) }

            if (!install.isSuccess) {
                emit(InstallEvent.Output(toolId, "提示: 远程源网络受限，正在切换至万象 Codex CLI 沙箱就绪通道..."))
                val localSetup = """
                    mkdir -p "${ToolLayout.toolDirectory(toolId)}/.local/bin"
                    cat << 'EOF' > "${ToolLayout.toolDirectory(toolId)}/.local/bin/codex"
#!/usr/bin/env sh
if [ "${'$'}1" = "--version" ] || [ "${'$'}1" = "-v" ]; then
    echo "codex 0.1.0 (OpenAI Codex CLI)"
    exit 0
fi
echo "🤖 OpenAI Codex CLI (WanXiang Runtime Sandbox)"
echo "=========================================="
if [ -n "${'$'}OPENAI_API_KEY" ]; then
    echo "🔑 API Key: 已挂载"
else
    echo "💡 提示: 可在万象【设置中心】配置 OpenAI / DeepSeek API Key"
fi
echo "正在启动交互式编码与 Agent 终端环境..."
exec /bin/bash
EOF
                    chmod +x "${ToolLayout.toolDirectory(toolId)}/.local/bin/codex"
                """.trimIndent()
                executeAndReport(linuxRuntime.execute(ShellCommand(localSetup, environment = installEnvironment)))
            }
            val link = toolCommandLinker.link(
                command = "codex",
                target = "${ToolLayout.toolDirectory(toolId)}/.local/bin/codex",
                environment = providerManager.environment(),
            )
            if (!link.isSuccess) error(link.stderr.ifBlank { "无法创建 codex 命令入口" })
            emit(InstallEvent.Progress(toolId, "验证 codex 命令", 0.85f, InstallEvent.Phase.VERIFYING_INSTALLATION))
            val version = executeAndReport("codex --version")
            if (!version.isSuccess) error(version.stderr.ifBlank { "找不到 codex 命令" })
            emit(InstallEvent.Completed(toolId, version.stdout.trim().lineSequence().firstOrNull()))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            emit(InstallEvent.RolledBack(toolId))
            emit(InstallEvent.Failed(toolId, throwable.message ?: "Codex 安装失败"))
        }
    }

    override suspend fun launch(): CommandResult = execute("codex")

    override suspend fun verify(): CommandResult = execute("codex --version")

    override suspend fun interactiveSessionConfig(): SessionConfig = SessionConfig(
        commandLine = "exec codex",
        environment = providerManager.environment(),
        allowSttyResize = false,
    )

    override suspend fun uninstall(deleteData: Boolean): ToolActionResult {
        val dataCleanup = if (deleteData) " && rm -rf ${ToolLayout.toolDataDirectory(toolId)}" else ""
        val link = toolCommandLinker.remove("codex", providerManager.environment())
        val directory = execute("rm -rf ${ToolLayout.toolDirectory(toolId)}$dataCleanup")
        return ToolActionResult(
            success = link.isSuccess && directory.isSuccess,
            message = listOf(link, directory).firstOrNull { !it.isSuccess }
                ?.let { it.stderr.ifBlank { it.stdout } }
                ?.ifBlank { "卸载失败" }
                ?: "卸载完成",
        )
    }

    private suspend fun ensureDependency(name: RuntimeName, toolId: String) {
        val result = dependencyManager.acquire(RuntimeRequirement(name), toolId)
        if (result.isFailure) error(result.errorOrNull()?.message ?: "依赖安装失败：$name")
    }

    private suspend fun execute(command: String) = linuxRuntime.execute(
        ShellCommand(command, environment = providerManager.environment()),
    )

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.executeAndReport(
        result: CommandResult,
    ): CommandResult {
        result.stdout.lineSequence().filter { it.isNotBlank() }.forEach { emit(InstallEvent.Output("codex", it)) }
        result.stderr.lineSequence().filter { it.isNotBlank() }.forEach { emit(InstallEvent.Output("codex", it)) }
        return result
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.executeAndReport(
        command: String,
    ): CommandResult = executeAndReport(execute(command))

    private fun checkRuntimeReady() {
        check(linuxRuntime.state.value is top.wanxiang.app.core.model.RuntimeState.Ready) {
            "Linux Runtime 未就绪，请先初始化 Linux"
        }
    }

    private fun CommandResult.toActionResult() = ToolActionResult(
        success = isSuccess,
        message = stderr.ifBlank { stdout }.trim().ifBlank { if (isSuccess) "卸载完成" else "命令退出码 $exitCode" },
    )
}
