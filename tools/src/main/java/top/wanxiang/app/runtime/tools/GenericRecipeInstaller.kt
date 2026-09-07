package top.wanxiang.app.runtime.tools

import top.wanxiang.app.core.model.RuntimeName
import top.wanxiang.app.core.model.RuntimeRequirement
import top.wanxiang.app.core.model.ToolManifest
import top.wanxiang.app.core.tools.DependencyManager
import top.wanxiang.app.core.tools.ManifestDependencyParser
import top.wanxiang.app.core.tools.ProviderManager
import top.wanxiang.app.core.tools.ToolActionResult
import top.wanxiang.app.core.tools.ToolRuntimeAdapter
import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.shell.CommandResult
import top.wanxiang.app.runtime.shell.ManagedProcess
import top.wanxiang.app.runtime.shell.ProcessType
import top.wanxiang.app.runtime.shell.SessionConfig
import top.wanxiang.app.runtime.shell.ShellCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * 通用声明式配方执行引擎 (Generic Recipe Installer)
 * 根据 ToolManifest 声明的脚本、依赖和环境变量，动态驱动任意工具的安装、软链接、校验与生命周期管理。
 */
class GenericRecipeInstaller(
    private val manifest: ToolManifest,
    private val linuxRuntime: LinuxRuntime,
    private val dependencyManager: DependencyManager,
    private val providerManager: ProviderManager,
    private val toolCommandLinker: ToolCommandLinker,
    private val localPluginPayloadManager: top.wanxiang.app.core.tools.LocalPluginPayloadManager? = null,
) : ToolRuntimeAdapter {
    override val toolId: String = manifest.id

    override fun install(): Flow<InstallEvent> = flow {
        emit(InstallEvent.Started(toolId))
        try {
            checkReady()

            val localPayload = if (manifest.source == "LOCAL") {
                emit(InstallEvent.Progress(toolId, "正在装载本地插件资源", 0.05f, InstallEvent.Phase.PREPARING))
                var preparedPath: String? = null
                localPluginPayloadManager?.prepare(toolId, linuxRuntime.activeDistroId.value)?.collect { event ->
                    when (event) {
                        is top.wanxiang.app.core.tools.LocalPluginPreparationEvent.Copying -> emit(
                            InstallEvent.Progress(
                                toolId = toolId,
                                message = event.message,
                                progress = LOCAL_COPY_PROGRESS_START + event.fraction * LOCAL_COPY_PROGRESS_SPAN,
                                phase = InstallEvent.Phase.PREPARING,
                            ),
                        )
                        is top.wanxiang.app.core.tools.LocalPluginPreparationEvent.Ready -> preparedPath = event.payloadPath
                    }
                } ?: error("本地插件 payload 管理器不可用")
                preparedPath ?: error("本地插件 payload 不存在")
            } else null

            // 1. 准备前置依赖
            emit(InstallEvent.Progress(toolId, "正在解析并准备工具依赖...", 0.15f, InstallEvent.Phase.INSTALLING_DEPENDENCY))
            for (depString in manifest.dependencies.takeUnless { manifest.offlineOnly }.orEmpty()) {
                val parsed = ManifestDependencyParser.parse(depString)
                if (parsed != null) {
                    val runtimeName = when (parsed.name.lowercase()) {
                        "node" -> RuntimeName.NODE
                        "python" -> RuntimeName.PYTHON
                        "git" -> RuntimeName.GIT
                        "curl" -> RuntimeName.CURL
                        "ca-certificates" -> RuntimeName.CA_CERTIFICATES
                        else -> null
                    }
                    if (runtimeName != null) {
                        acquire(runtimeName, toolId, parsed.constraint)
                    }
                }
            }

            // 2. 准备隔离目录与环境变量
            emit(InstallEvent.Progress(toolId, "正在配置沙箱隔离环境...", 0.35f, InstallEvent.Phase.RUNNING_INSTALLER))
            val toolDir = ToolLayout.toolDirectory(toolId)
            val toolDataDir = ToolLayout.toolDataDirectory(toolId)
            // The framework may install compatibility helpers before the plugin recipe runs,
            // so the conventional bin directory must already exist here.
            executeAndReport("mkdir -p $toolDir/bin $toolDataDir")

            val baseEnvironment = runtimeEnvironment(localPayload)

            // Older offline Android-suite packages called `unzip` directly before
            // the package itself had a chance to install a ZIP extractor. Provide
            // a compatibility shim from the already-installed JDK so those
            // packages remain installable on minimal rootfs images. New packages
            // use the same fallback internally, so this is harmless when unused.
            if (manifest.source == "LOCAL") {
                val unzipShim = localUnzipCompatibilityCommand()
                val shimResult = linuxRuntime.execute(
                    ShellCommand(commandLine = unzipShim, environment = baseEnvironment),
                )
                if (!shimResult.isSuccess) {
                    error(shimResult.stderr.ifBlank { shimResult.stdout }.ifBlank { "无法准备 ZIP 解压兼容层" })
                }
            }

            // 2.5 预检与自愈基础系统包管理状态 (清理残留锁、已损坏的 updates 事务与未配置的 dpkg 状态)
            val preflightCmd = "rm -rf /var/lib/dpkg/updates/* /var/lib/dpkg/lock* /var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null || true; DEBIAN_FRONTEND=noninteractive dpkg --configure -a 2>/dev/null || true"
            linuxRuntime.execute(ShellCommand(commandLine = preflightCmd, environment = baseEnvironment))

            // 3. 执行安装配方脚本
            emit(InstallEvent.Progress(toolId, "正在执行 ${manifest.name} 安装配方...", 0.55f, InstallEvent.Phase.RUNNING_INSTALLER))
            val script = manifest.installScript?.trimIndent()
                ?: error("工具 ${manifest.id} 未配置有效安装步骤 (installSteps)")

            var result = executeAndReport(
                linuxRuntime.execute(
                    ShellCommand(
                        commandLine = script,
                        environment = baseEnvironment,
                        timeoutMs = 15 * 60 * 1000L,
                    ),
                ),
            )

            // 若遇到 dpkg 中断、updates 损坏或锁问题，自动深度清理并重试一次
            if (!result.isSuccess && (
                result.stderr.contains("dpkg was interrupted") ||
                result.stdout.contains("dpkg was interrupted") ||
                result.stderr.contains("parsing file '/var/lib/dpkg/updates") ||
                result.stdout.contains("parsing file '/var/lib/dpkg/updates") ||
                result.stderr.contains("Could not get lock") ||
                result.stderr.contains("is locked")
            )) {
                emit(InstallEvent.Progress(toolId, "检测到 dpkg 事务损坏或残留锁，正在自愈修复并重试...", 0.60f, InstallEvent.Phase.RUNNING_INSTALLER))
                val fixCmd = "rm -rf /var/lib/dpkg/updates/* /var/lib/dpkg/lock* /var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null || true; DEBIAN_FRONTEND=noninteractive dpkg --configure -a; DEBIAN_FRONTEND=noninteractive apt-get --fix-broken install -y 2>/dev/null || true"
                executeAndReport(linuxRuntime.execute(ShellCommand(commandLine = fixCmd, environment = baseEnvironment, timeoutMs = 120_000L)))
                result = executeAndReport(
                    linuxRuntime.execute(
                        ShellCommand(
                            commandLine = script,
                            environment = baseEnvironment,
                            timeoutMs = 15 * 60 * 1000L,
                        ),
                    ),
                )
            }

            if (!result.isSuccess) {
                error(result.stderr.ifBlank { result.stdout }.ifBlank { "安装配方执行失败" })
            }

            // 4. 创建命令入口软链接
            emit(InstallEvent.Progress(toolId, "正在生成命令入口链接...", 0.80f, InstallEvent.Phase.VERIFYING_INSTALLATION))
            val links = if (manifest.commandLinks.isNotEmpty()) {
                manifest.commandLinks
            } else {
                listOf(manifest.id)
            }

            for (linkName in links) {
                val targetPath = "$toolDir/bin/$linkName"
                val linkRes = toolCommandLinker.link(linkName, targetPath, baseEnvironment)
                if (!linkRes.isSuccess) {
                    val fallbackTarget = "/usr/bin/$linkName"
                    toolCommandLinker.link(linkName, fallbackTarget, baseEnvironment)
                }
            }

            // 5. 验证安装
            emit(InstallEvent.Progress(toolId, "正在验证安装结果...", 0.90f, InstallEvent.Phase.VERIFYING_INSTALLATION))
            val verifyCmd = manifest.verifyCommand ?: "${links.first()} --version"
            val versionResult = executeAndReport(
                linuxRuntime.execute(ShellCommand(commandLine = verifyCmd, environment = baseEnvironment, timeoutMs = 60_000L)),
            )
            if (!versionResult.isSuccess) {
                // 验证命令超时挂死（退出码 124）意味着工具链本身可能已损坏——
                // 例如 java 启动器陷入 exec 回环：进程只烧 CPU、零输出，60 秒
                // 后被超时强杀。此时绝不能拿“文件存在/可执行位”当安装成功，
                // 否则 poisoned 状态会被标记为 INSTALLED 并反复触发重装。
                if (versionResult.exitCode == VERIFY_TIMEOUT_EXIT_CODE) {
                    error(
                        "验证命令超时挂起 ($verifyCmd)，疑似安装产物损坏（如启动器 exec 回环），已回滚整个事务",
                    )
                }
                // 兜底检查：验证命令以真实退出码快速失败（如工具不认识 --version
                // 参数）时，若主要二进制已建立，判定为安装成功并记录告警，
                // 避免因单次命令失败粗暴回滚整个事务。
                val anyBinaryExists = links.any { link ->
                    val checkRes = linuxRuntime.execute(ShellCommand("test -x $toolDir/bin/$link || test -x /opt/wanxiang/bin/$link || test -x /usr/bin/$link", environment = baseEnvironment, timeoutMs = 5_000L))
                    checkRes.isSuccess
                }
                if (!anyBinaryExists) {
                    error("验证命令失败 ($verifyCmd): ${versionResult.stderr.ifBlank { versionResult.stdout }}")
                }
            }

            // 6. 安装验证成功后自动清理沙箱内的重量级安装包暂存 (/opt/wanxiang/imports/<toolId>/archives)，即时释放 1~2GB 存储
            if (manifest.source == "LOCAL") {
                emit(InstallEvent.Progress(toolId, "正在清理安装包暂存...", 0.95f, InstallEvent.Phase.VERIFYING_INSTALLATION))
                linuxRuntime.execute(ShellCommand("rm -rf /opt/wanxiang/imports/$toolId/archives 2>/dev/null || true", environment = baseEnvironment))
            }

            val versionOutput = versionResult.stdout.trim().lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: manifest.version
            emit(InstallEvent.Completed(toolId, versionOutput))
        } catch (cancellation: CancellationException) {
            if (manifest.source == "LOCAL") {
                runCatching {
                    linuxRuntime.execute(ShellCommand("rm -rf /opt/wanxiang/imports/$toolId 2>/dev/null || true"))
                }
            }
            throw cancellation
        } catch (throwable: Throwable) {
            if (manifest.source == "LOCAL") {
                runCatching {
                    linuxRuntime.execute(ShellCommand("rm -rf /opt/wanxiang/imports/$toolId 2>/dev/null || true"))
                }
            }
            emit(InstallEvent.RolledBack(toolId))
            emit(InstallEvent.Failed(toolId, throwable.message ?: "安装失败"))
        }
    }

    override suspend fun launch(): CommandResult = execute(manifest.launchCommand ?: manifest.id)

    override suspend fun verify(): CommandResult = execute(manifest.verifyCommand ?: "${manifest.id} --version")

    override suspend fun interactiveSessionConfig(): SessionConfig = SessionConfig(
        commandLine = "exec ${manifest.launchCommand ?: manifest.id}",
        environment = runtimeEnvironment(),
        allowSttyResize = false,
    )

    override suspend fun startService(): ManagedProcess = linuxRuntime.startBackground(
        id = "${toolId}-service",
        command = ShellCommand(
            commandLine = manifest.launchCommand ?: manifest.id,
            environment = runtimeEnvironment(),
        ),
        toolId = toolId,
        type = ProcessType.SERVICE,
    )

    override suspend fun uninstall(deleteData: Boolean): ToolActionResult {
        val toolDir = ToolLayout.toolDirectory(toolId)
        val toolDataDir = ToolLayout.toolDataDirectory(toolId)
        val links = if (manifest.commandLinks.isNotEmpty()) manifest.commandLinks else listOf(manifest.id)

        for (link in links) {
            toolCommandLinker.remove(link, runtimeEnvironment())
        }

        val customUninstall = manifest.uninstallScript
        if (!customUninstall.isNullOrBlank()) {
            linuxRuntime.execute(ShellCommand(customUninstall, environment = runtimeEnvironment()))
        }

        val dataCleanup = if (deleteData) " && rm -rf $toolDataDir" else ""
        val directoryResult = linuxRuntime.execute(ShellCommand("rm -rf $toolDir$dataCleanup"))

        return ToolActionResult(
            success = directoryResult.isSuccess,
            message = if (directoryResult.isSuccess) "卸载完成" else directoryResult.stderr.ifBlank { "卸载失败" },
        )
    }

    private suspend fun acquire(name: RuntimeName, toolId: String, constraint: String? = null) {
        val result = dependencyManager.acquire(RuntimeRequirement(name, constraint), toolId)
        if (result.isFailure) error(result.errorOrNull()?.message ?: "依赖安装失败：$name")
    }

    private suspend fun execute(command: String) = linuxRuntime.execute(
        ShellCommand(command, environment = runtimeEnvironment()),
    )

    private suspend fun runtimeEnvironment(localPayload: String? = null): Map<String, String> {
        val toolDir = ToolLayout.toolDirectory(toolId)
        val runtimePath = "/root/.local/bin:/opt/wanxiang/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        val declaredPath = manifest.environment["PATH"]
        val effectivePath = declaredPath
            ?.replace("\${PATH}", runtimePath)
            ?.replace("\$PATH", runtimePath)
            ?: runtimePath
        val payloadPath = localPayload ?: if (manifest.source == "LOCAL") "/opt/wanxiang/imports/$toolId" else null
        return providerManager.environment().filterKeys { it != "PATH" } +
            manifest.environment.filterKeys { it != "PATH" } +
            mapOf(
                "WANXIANG_TOOL_ID" to toolId,
                "WANXIANG_TOOL_DIR" to toolDir,
                "WANXIANG_TOOL_DATA" to ToolLayout.toolDataDirectory(toolId),
                "npm_config_prefix" to toolDir,
                "NPM_CONFIG_PREFIX" to toolDir,
                "PATH" to "$toolDir/bin:$effectivePath",
            ) + payloadPath?.let { mapOf("WANXIANG_PLUGIN_PAYLOAD" to it) }.orEmpty()
    }

    private fun localUnzipCompatibilityCommand(): String = """
        if ! command -v unzip >/dev/null 2>&1; then
            printf '%s\n' \
                '#!/bin/sh' \
                'set -eu' \
                'archive=' \
                'dest=.' \
                'jar_bin="${'$'}{JAVA_HOME:-}/bin/jar"' \
                'if [ ! -x "${'$'}{jar_bin}" ]; then' \
                '  for candidate in /opt/wanxiang/toolchains/android/jdk/bin/jar /usr/bin/jar /usr/lib/jvm/default-java/bin/jar; do' \
                '    if [ -x "${'$'}{candidate}" ]; then jar_bin="${'$'}{candidate}"; break; fi' \
                '  done' \
                'fi' \
                'while [ "${'$'}#" -gt 0 ]; do' \
                '  case "${'$'}1" in' \
                '    -q|-qq|-o) shift ;;' \
                '    -d) dest="${'$'}2"; shift 2 ;;' \
                '    -*) shift ;;' \
                '    *) archive="${'$'}1"; shift ;;' \
                '  esac' \
                'done' \
                '[ -n "${'$'}archive" ] || exit 2' \
                '[ -x "${'$'}{jar_bin}" ] || { echo "JDK jar unavailable for ZIP extraction" >&2; exit 127; }' \
                'mkdir -p "${'$'}dest"' \
                '(cd "${'$'}dest" && "${'$'}{jar_bin}" xf "${'$'}archive")' \
                > "${'$'}WANXIANG_TOOL_DIR/bin/unzip"
            chmod 755 "${'$'}WANXIANG_TOOL_DIR/bin/unzip"
        fi
    """.trimIndent()

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.executeAndReport(
        result: CommandResult,
    ): CommandResult {
        (result.stdout.lineSequence() + result.stderr.lineSequence())
            .filter { it.isNotBlank() }
            .forEach { line ->
                val scriptedProgress = INSTALLER_PROGRESS_PATTERN.matchEntire(line.trim())
                if (scriptedProgress != null) {
                    val relativeProgress = scriptedProgress.groupValues[1].toFloatOrNull()
                        ?.div(100f)
                        ?.coerceIn(0f, 1f)
                        ?: 0f
                    emit(
                        InstallEvent.Progress(
                            toolId = toolId,
                            message = scriptedProgress.groupValues[2],
                            progress = INSTALLER_PROGRESS_START + relativeProgress * INSTALLER_PROGRESS_SPAN,
                            phase = InstallEvent.Phase.RUNNING_INSTALLER,
                        ),
                    )
                } else {
                    emit(InstallEvent.Output(toolId, line))
                }
            }
        return result
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.executeAndReport(
        command: String,
    ): CommandResult = executeAndReport(execute(command))

    private fun checkReady() = check(linuxRuntime.state.value is top.wanxiang.app.core.model.RuntimeState.Ready) {
        "Linux Runtime 未就绪，请先初始化 Linux"
    }

    private companion object {
        const val LOCAL_COPY_PROGRESS_START = 0.02f
        const val LOCAL_COPY_PROGRESS_SPAN = 0.12f
        const val INSTALLER_PROGRESS_START = 0.55f
        const val INSTALLER_PROGRESS_SPAN = 0.23f

        /** 进程超时强杀的统一退出码（见 ProcessShellExecutor.TIMEOUT_EXIT_CODE）。 */
        const val VERIFY_TIMEOUT_EXIT_CODE = 124
        val INSTALLER_PROGRESS_PATTERN = Regex("""\[WANXIANG_PROGRESS:(\d{1,3})]\s+(.+)""")
    }
}
