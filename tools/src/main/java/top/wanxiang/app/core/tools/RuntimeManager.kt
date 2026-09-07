package top.wanxiang.app.core.tools

import top.wanxiang.app.core.common.result.AppResult
import top.wanxiang.app.core.database.RuntimeEntity
import top.wanxiang.app.core.model.InstalledRuntime
import top.wanxiang.app.core.model.RuntimeName
import top.wanxiang.app.core.model.RuntimeRequirement
import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.tools.RuntimeBinaryInstaller
import top.wanxiang.app.runtime.shell.ShellCommand
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface RuntimeManager {
    suspend fun resolve(requirement: RuntimeRequirement): InstalledRuntime?
    suspend fun acquire(requirement: RuntimeRequirement, toolId: String): AppResult<InstalledRuntime>
    suspend fun release(runtimeId: String, toolId: String): AppResult<Unit>
    suspend fun unusedRuntimes(): List<InstalledRuntime>
    suspend fun cleanup(runtimeId: String): AppResult<Unit>
}

@Singleton
class RuntimeManagerImpl @Inject constructor(
    private val linuxRuntime: LinuxRuntime,
    private val runtimeRepository: RuntimeRepository,
    private val runtimeBinaryInstaller: RuntimeBinaryInstaller,
) : RuntimeManager {
    private val installMutex = Mutex()

    override suspend fun resolve(requirement: RuntimeRequirement): InstalledRuntime? {
        val id = runtimeId(requirement.name)
        val entity = runtimeRepository.findRuntime(id) ?: return null
        if (entity.state != STATE_INSTALLED) return null
        if (!versionSatisfies(entity.version, requirement.constraint)) return null
        return entity.toInstalled(runtimeRepository.referenceCount(id))
    }

    override suspend fun acquire(
        requirement: RuntimeRequirement,
        toolId: String,
    ): AppResult<InstalledRuntime> = installMutex.withLock {
        acquireLocked(requirement, toolId)
    }

    private suspend fun acquireLocked(
        requirement: RuntimeRequirement,
        toolId: String,
    ): AppResult<InstalledRuntime> {
        val id = runtimeId(requirement.name)
        val existing = resolve(requirement)
        if (existing != null) {
            runtimeRepository.addReference(toolId, id)
            return AppResult.Success(existing.copy(referenceCount = runtimeRepository.referenceCount(id)))
        }

        val incompatible = runtimeRepository.findRuntime(id)
        if (incompatible != null && runtimeRepository.referenceCount(id) > 0) {
            return AppResult.Failure(
                top.wanxiang.app.core.common.result.AppError(
                    top.wanxiang.app.core.common.result.ErrorCode.INSTALLATION_FAILED,
                    "Runtime ${requirement.name} 已被其他工具引用，当前版本 ${incompatible.version ?: "未知"} " +
                        "不满足 ${requirement.constraint ?: "当前"} 要求，拒绝原地替换共享 Runtime",
                ),
            )
        }

        return try {
            if (linuxRuntime.state.value !is top.wanxiang.app.core.model.RuntimeState.Ready) {
                error("Linux Runtime 未就绪")
            }
            val packageName = packageName(requirement.name)
            // 修复上次被中断的 dpkg/apt 事务（例如安装脚本超时被强杀后，git 等包处于
            // "已解包未配置"状态），否则后续 apt-get install 会报 Unmet dependencies。
            var install = linuxRuntime.execute(
                ShellCommand(
                    commandLine =
                        "rm -rf /var/lib/dpkg/updates/* /var/lib/dpkg/lock* 2>/dev/null || true; " +
                            "dpkg --configure -a 2>/dev/null || true; " +
                            "apt-get update -y; apt-get --fix-broken install -y || true; " +
                            "apt-get install -y $packageName",
                    environment = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
                    timeoutMs = DEPENDENCY_TIMEOUT_MS,
                ),
            )
            if (!install.isSuccess && (install.stderr.contains("perlthanks") || install.stdout.contains("perlthanks") || install.stderr.contains("dpkg-new"))) {
                linuxRuntime.execute(
                    ShellCommand(
                        commandLine = "/usr/local/sbin/wanxiang-fix-perl || true; apt-get --fix-broken install -y; apt-get install -y $packageName",
                        environment = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
                        timeoutMs = DEPENDENCY_TIMEOUT_MS,
                    ),
                )
            }
            var executable = executablePath(requirement.name)
            var versionResult = linuxRuntime.execute(ShellCommand("$executable --version"))
            var version = versionResult.stdout.trim().lineSequence().firstOrNull()
            if ((!install.isSuccess || !versionResult.isSuccess || !versionSatisfies(version, requirement.constraint)) &&
                requirement.name == RuntimeName.NODE
            ) {
                runtimeBinaryInstaller.installNode()
                executable = executablePath(RuntimeName.NODE, official = true)
                versionResult = linuxRuntime.execute(ShellCommand("$executable --version"))
                version = versionResult.stdout.trim().lineSequence().firstOrNull()
            }
            if (!versionResult.isSuccess) {
                error(versionResult.stderr.ifBlank { "无法验证 $executable" })
            }
            if (!versionSatisfies(version, requirement.constraint)) {
                error("$packageName 版本不满足要求 ${requirement.constraint}：${version ?: "未知"}")
            }
            val entity = RuntimeEntity(id, requirement.name.name, version, executable, STATE_INSTALLED)
            runtimeRepository.saveRuntime(entity)
            runtimeRepository.addReference(toolId, id)
            AppResult.Success(entity.toInstalled(1))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            AppResult.Failure(top.wanxiang.app.core.common.result.AppError(
                top.wanxiang.app.core.common.result.ErrorCode.INSTALLATION_FAILED,
                throwable.message ?: "共享 Runtime 安装失败",
                throwable,
            ))
        }
    }

    override suspend fun release(runtimeId: String, toolId: String): AppResult<Unit> = try {
        runtimeRepository.removeReference(toolId, runtimeId)
        if (runtimeRepository.referenceCount(runtimeId) == 0) {
            // 只移除引用记录；实际 apt purge 需由后续清理策略显式触发，避免误删系统依赖。
        }
        AppResult.Success(Unit)
    } catch (throwable: Throwable) {
        AppResult.Failure(top.wanxiang.app.core.common.result.AppError(
            top.wanxiang.app.core.common.result.ErrorCode.IO,
            throwable.message ?: "释放 Runtime 引用失败",
            throwable,
        ))
    }

    override suspend fun unusedRuntimes(): List<InstalledRuntime> = installMutex.withLock {
        runtimeRepository.listInstalledRuntimes().mapNotNull { entity ->
            if (runtimeRepository.referenceCount(entity.id) == 0) {
                entity.toInstalled(0)
            } else {
                null
            }
        }
    }

    override suspend fun cleanup(runtimeId: String): AppResult<Unit> = installMutex.withLock {
        try {
            val entity = runtimeRepository.findRuntime(runtimeId)
                ?: return@withLock AppResult.Success(Unit)
            check(runtimeRepository.referenceCount(runtimeId) == 0) {
                "Runtime 仍被工具引用，不能清理：${entity.name}"
            }
            if (linuxRuntime.state.value !is top.wanxiang.app.core.model.RuntimeState.Ready) {
                error("Linux Runtime 未就绪")
            }
            val result = if (entity.executablePath.startsWith("/opt/wanxiang/")) {
                runtimeBinaryInstaller.removeNode()
                null
            } else {
                val packageName = packageName(RuntimeName.valueOf(entity.name))
                linuxRuntime.execute(
                    ShellCommand(
                        commandLine = "apt-get purge -y -- $packageName",
                        environment = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
                        timeoutMs = DEPENDENCY_TIMEOUT_MS,
                    ),
                )
            }
            if (result != null && !result.isSuccess) {
                error(result.stderr.ifBlank { "清理 Runtime 失败" })
            }
            runtimeRepository.deleteRuntime(runtimeId)
            AppResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            AppResult.Failure(
                top.wanxiang.app.core.common.result.AppError(
                    top.wanxiang.app.core.common.result.ErrorCode.IO,
                    throwable.message ?: "清理共享 Runtime 失败",
                    throwable,
                ),
            )
        }
    }

    private fun runtimeId(name: RuntimeName) = name.name.lowercase()
    private fun packageName(name: RuntimeName) = when (name) {
        RuntimeName.NODE -> "nodejs"
        RuntimeName.PYTHON -> "python3"
        RuntimeName.GIT -> "git"
        RuntimeName.CA_CERTIFICATES -> "ca-certificates"
        RuntimeName.CURL -> "curl"
    }
    private fun executablePath(name: RuntimeName, official: Boolean = false) = when (name) {
        RuntimeName.NODE -> if (official) "/opt/wanxiang/bin/node" else "/usr/bin/node"
        RuntimeName.PYTHON -> "/usr/bin/python3"
        RuntimeName.GIT -> "/usr/bin/git"
        RuntimeName.CA_CERTIFICATES -> "/usr/sbin/update-ca-certificates"
        RuntimeName.CURL -> "/usr/bin/curl"
    }
    private fun RuntimeEntity.toInstalled(count: Int) = InstalledRuntime(
        id = id,
        name = RuntimeName.valueOf(name),
        version = version,
        executablePath = executablePath,
        referenceCount = count,
    )

    private fun versionSatisfies(version: String?, constraint: String?): Boolean {
        if (constraint.isNullOrBlank()) return true
        val actual = version?.findVersion() ?: return false
        val required = constraint.removePrefix(">=").trim().findVersion() ?: return false
        return when {
            constraint.trim().startsWith(">=") -> compareVersions(actual, required) >= 0
            constraint.trim().startsWith(">") -> compareVersions(actual, required) > 0
            constraint.trim().startsWith("=") -> compareVersions(actual, required) == 0
            else -> compareVersions(actual, required) >= 0
        }
    }

    private fun String.findVersion(): List<Int>? = Regex("\\d+(?:\\.\\d+){0,3}")
        .find(this)
        ?.value
        ?.split('.')
        ?.map { it.toInt() }

    private fun compareVersions(left: List<Int>, right: List<Int>): Int {
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val difference = (left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    private companion object {
        const val STATE_INSTALLED = "INSTALLED"
        const val DEPENDENCY_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
