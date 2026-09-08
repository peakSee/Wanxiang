package top.wkbin.taixu.runtime

import android.os.Build
import android.os.StatFs
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.core.model.CpuArch
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.core.model.StorageMountBinding
import top.wkbin.taixu.runtime.proot.ProotCommandBuilder
import top.wkbin.taixu.runtime.proot.QemuCompatibilityLayout
import top.wkbin.taixu.runtime.proot.syncGuestGroups
import top.wkbin.taixu.runtime.bridge.HostBridge
import top.wkbin.taixu.runtime.pty.PtyManager
import top.wkbin.taixu.runtime.proot.ProotInstaller
import top.wkbin.taixu.runtime.rootfs.RootfsInstaller
import top.wkbin.taixu.runtime.terminal.terminalBanner
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.LinuxSession
import top.wkbin.taixu.runtime.shell.ManagedProcess
import top.wkbin.taixu.runtime.shell.ProcessType
import top.wkbin.taixu.runtime.shell.ProcessRegistry
import top.wkbin.taixu.runtime.shell.SessionConfig
import top.wkbin.taixu.runtime.shell.ShellCommand
import top.wkbin.taixu.runtime.shell.ShellExecutor
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class LinuxRuntimeImpl @Inject constructor(
    private val pathManager: RuntimePathManager,
    private val prootInstaller: ProotInstaller,
    private val rootfsInstaller: RootfsInstaller,
    private val prootCommandBuilder: ProotCommandBuilder,
    private val ptyManager: PtyManager,
    private val shellExecutor: ShellExecutor,
    private val healthChecker: RuntimeHealthChecker,
    private val processRegistry: ProcessRegistry,
    private val settingsDataStore: top.wkbin.taixu.core.datastore.RuntimePreferences,
    private val storageMountBindingRepository: top.wkbin.taixu.core.database.StorageMountBindingRepository,
    private val hostBridge: HostBridge,
    private val assetSynchronizer: top.wkbin.taixu.runtime.scripts.RuntimeAssetSynchronizer,
    private val logger: AppLogger,
) : LinuxRuntime {

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.NotInitialized)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val _activeDistroId = MutableStateFlow("ubuntu")
    override val activeDistroId: StateFlow<String> = _activeDistroId.asStateFlow()

    private val _installedDistros = MutableStateFlow<List<top.wkbin.taixu.core.model.InstalledDistro>>(emptyList())
    override val installedDistros: StateFlow<List<top.wkbin.taixu.core.model.InstalledDistro>> = _installedDistros.asStateFlow()

    private val initializeMutex = Mutex()
    private val interactiveSessions = ConcurrentHashMap<LinuxSession, String>()

    override fun refreshInstalledDistros() {
        // 兑现“刷新”语义：结构性变更后由各调用方走到这里，统一失效路径管理器缓存
        pathManager.invalidateInstalledDistrosCache()
        val ids = pathManager.listInstalledDistroIds()
        val active = _activeDistroId.value
        val list = ids.map { id ->
            val spec = DistributionCatalog.require(id)
            val size = pathManager.distroSizeBytes(id)
            val marker = pathManager.rootfsInstalledMarker(id)
            val installedTime = if (marker.exists()) marker.lastModified() else System.currentTimeMillis()
            top.wkbin.taixu.core.model.InstalledDistro(
                id = id,
                displayName = spec.displayName,
                sizeBytes = size,
                installedAt = installedTime,
                isActive = id.equals(active, ignoreCase = true),
                packageManager = when (id.lowercase()) {
                    "alpine" -> "apk"
                    "arch" -> "pacman"
                    "fedora", "almalinux" -> "dnf"
                    "opensuse" -> "zypper"
                    else -> "apt"
                },
                statusText = if (id.equals(active, ignoreCase = true)) "当前主系统" else "已就绪",
            )
        }
        _installedDistros.value = list
    }

    override suspend fun switchActiveDistro(distroId: String): AppResult<Unit> = initializeMutex.withLock {
        val safeId = distroId.lowercase().trim()
        if (!pathManager.isDistroInstalled(safeId)) {
            return@withLock AppResult.Failure(
                AppError(ErrorCode.RUNTIME_NOT_INITIALIZED, "系统未安装或文件不完整：$safeId"),
            )
        }
        pathManager.ensureDistroDirectories(safeId)
        _activeDistroId.value = safeId
        settingsDataStore.setSelectedDistribution(safeId)
        refreshInstalledDistros()
        logger.i("Switched active Linux distro to $safeId")
        AppResult.Success(Unit)
    }

    override suspend fun installDistro(
        request: RuntimeInstallRequest,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): AppResult<Unit> = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
            val distroId = request.distributionId.lowercase().trim()
            val distribution = DistributionCatalog.require(distroId)
            logger.i("Installing new distro: ${distribution.displayName} ($distroId)")
            val result = rootfsInstaller.installOci(distribution, request.registryRoute, onProgress)
            val error = result.errorOrNull()
            if (error != null) {
                return@withContext AppResult.Failure(error)
            }
            configureRootfs(distroId)
            configureDns(distroId)
            configureEnvironment(distroId)
            refreshInstalledDistros()
            logger.i("Distro $distroId installed successfully")
            AppResult.Success(Unit)
        }
    }

    override suspend fun importDistro(
        request: RuntimeInstallRequest,
        archive: File,
    ): AppResult<Unit> = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                updateInitializing("校验设备环境", 0.05f)
                val architecture = detectArchitecture()
                if (architecture != CpuArch.ARM64) {
                    val error = AppError(ErrorCode.UNSUPPORTED_ARCHITECTURE, "手动导入仅支持 ARM64 RootFS")
                    failInitialization(error)
                    return@withContext AppResult.Failure(error)
                }
                checkStorage()
                updateInitializing("导入 Linux RootFS", 0.2f, archive.name)
                pathManager.ensureDirectories()
                val prootResult = prootInstaller.install()
                prootResult.errorOrNull()?.let { error ->
                    failInitialization(error)
                    return@withContext AppResult.Failure(error)
                }
                val distroId = request.distributionId.lowercase().trim()
                val result = rootfsInstaller.importArchive(distroId, archive)
                result.errorOrNull()?.let { error ->
                    failInitialization(error)
                    return@withContext AppResult.Failure(error)
                }
                updateInitializing("配置 Linux 系统", 0.65f)
                configureRootfs(distroId)
                configureDns(distroId)
                configureEnvironment(distroId)
                createWorkspace()
                val health = healthChecker.check()
                if (!health.isHealthy) {
                    val error = AppError(ErrorCode.INSTALLATION_FAILED, "导入后的 Linux 环境健康检查失败：${health.detail.orEmpty()}")
                    failInitialization(error)
                    return@withContext AppResult.Failure(error)
                }
                _activeDistroId.value = distroId
                settingsDataStore.setSelectedDistribution(distroId)
                refreshInstalledDistros()
                hostBridge.start()
                _state.value = RuntimeState.Ready
                AppResult.Success(Unit)
            } catch (cancellation: CancellationException) {
                _state.value = RuntimeState.NotInitialized
                throw cancellation
            } catch (throwable: Throwable) {
                failInitialization(AppError(ErrorCode.INSTALLATION_FAILED, throwable.message ?: "导入失败", throwable))
                AppResult.Failure(AppError(ErrorCode.INSTALLATION_FAILED, throwable.message ?: "导入失败", throwable))
            }
        }
    }

    override suspend fun uninstallDistro(distroId: String): AppResult<Unit> = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
            val safeId = distroId.lowercase().trim()
            val installed = pathManager.listInstalledDistroIds()
            if (installed.size <= 1) {
                return@withContext AppResult.Failure(
                    AppError(ErrorCode.INSTALLATION_FAILED, "不能卸载唯一的 Linux 系统，请先安装其他系统后再卸载"),
                )
            }
            closeInteractiveSessions(safeId)
            processRegistry.stopAll()
            val wasActive = _activeDistroId.value == safeId
            if (wasActive) {
                hostBridge.stop()
            }
            val res = rootfsInstaller.uninstallDistro(safeId)
            if (res is AppResult.Success) {
                if (wasActive) {
                    val nextDistro = installed.firstOrNull { it != safeId } ?: "ubuntu"
                    _activeDistroId.value = nextDistro
                    settingsDataStore.setSelectedDistribution(nextDistro)
                }
                refreshInstalledDistros()
            }
            if (wasActive && _state.value is RuntimeState.Ready && !hostBridge.isRunning.value) {
                hostBridge.start()
            }
            res
        }
    }

    override suspend fun resetSandbox(distroId: String?): AppResult<Unit> = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
            val safeId = (distroId ?: _activeDistroId.value).lowercase().trim()
            val wasActive = _activeDistroId.value == safeId
            try {
                closeInteractiveSessions(safeId)
                processRegistry.stopAll()
                if (wasActive) {
                    hostBridge.stop()
                }
                val distribution = DistributionCatalog.require(safeId)
                val result = rootfsInstaller.resetDistro(distribution)
                if (result is AppResult.Success) {
                    configureRootfs(safeId)
                    configureDns(safeId)
                    configureEnvironment(safeId)
                    refreshInstalledDistros()
                    if (wasActive) {
                        if (!hostBridge.isRunning.value) {
                            hostBridge.start()
                        }
                        _state.value = RuntimeState.Ready
                    }
                    logger.i("Reset sandbox distro $safeId successfully")
                    AppResult.Success(Unit)
                } else {
                    val error = result.errorOrNull()
                    if (wasActive) {
                        _state.value = RuntimeState.Error(
                            error?.cause ?: IllegalStateException(error?.message ?: "Linux 环境重置失败"),
                        )
                    }
                    AppResult.Failure(error ?: AppError(ErrorCode.INSTALLATION_FAILED, "重置 Linux 环境失败"))
                }
            } catch (throwable: Throwable) {
                logger.e("Failed to reset sandbox distro $safeId", throwable)
                AppResult.Failure(AppError(ErrorCode.IO, "重置 Linux 环境失败：${throwable.message}", throwable))
            }
        }
    }

    override suspend fun initialize(request: RuntimeInstallRequest): AppResult<Unit> = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
            if (_state.value is RuntimeState.Ready) {
                return@withContext AppResult.Success(Unit)
            }

            try {
                updateInitializing("detectArchitecture", 0f)
                val architecture = detectArchitecture()
                if (architecture != CpuArch.ARM64) {
                    val message = "Unsupported CPU architecture: $architecture. Phase 1 supports ARM64 only."
                    logger.e(message)
                    val error = AppError(ErrorCode.UNSUPPORTED_ARCHITECTURE, message)
                    _state.value = RuntimeState.Error(RuntimeArchitectureException(message))
                    return@withContext AppResult.Failure(error)
                }

                updateInitializing("checkStorage", 0.05f)
                checkStorage()

                updateInitializing("createDirectories", 0.1f)
                pathManager.ensureDirectories()
                pathManager.cleanupStalePtyMarkers(request.distributionId)

                updateInitializing("校验运行引擎", 0.15f, "校验 PRoot 主程序、外置 loader 与 ARM64 架构")
                val prootResult = prootInstaller.install()
                val prootError = prootResult.errorOrNull()
                if (prootError != null) {
                    failInitialization(prootError)
                    return@withContext AppResult.Failure(prootError)
                }

                val distroId = request.distributionId.lowercase().trim()
                val distribution = DistributionCatalog.require(distroId)
                updateInitializing("下载 ${distribution.displayName}", 0.2f, "通过 proot-distro 5.8.0 OCI 机制下载 linux/arm64 镜像")
                val rootfsResult = rootfsInstaller.installOci(
                    distribution,
                    request.registryRoute,
                ) { progress -> updateDownloadProgress("下载 ${distribution.displayName}", 0.2f, 0.55f, progress) }
                val rootfsError = rootfsResult.errorOrNull()
                if (rootfsError != null) {
                    failInitialization(rootfsError)
                    return@withContext AppResult.Failure(rootfsError)
                }

                updateInitializing("configureRootfs", 0.75f)
                configureRootfs(distroId)

                updateInitializing("configureDns", 0.8f)
                configureDns(distroId)

                updateInitializing("configureEnvironment", 0.85f)
                configureEnvironment(distroId)

                updateInitializing("createWorkspace", 0.9f)
                createWorkspace()

                updateInitializing("runHealthCheck", 0.95f)
                val health = healthChecker.check()
                if (!health.isHealthy) {
                    val message = "Runtime health check failed after initialization: ${health.detail.orEmpty()}"
                    logger.e(message)
                    val error = AppError(ErrorCode.INSTALLATION_FAILED, message)
                    failInitialization(error)
                    return@withContext AppResult.Failure(error)
                }

                _activeDistroId.value = distroId
                settingsDataStore.setSelectedDistribution(distroId)
                refreshInstalledDistros()

                hostBridge.start()
                _state.value = RuntimeState.Ready
                logger.i("Linux runtime initialized and ready with distro: $distroId")
                AppResult.Success(Unit)
            } catch (cancellation: CancellationException) {
                _state.value = RuntimeState.NotInitialized
                throw cancellation
            } catch (throwable: Throwable) {
                logger.e("Linux runtime initialization failed", throwable)
                _state.value = RuntimeState.Error(throwable)
                AppResult.Failure(
                    AppError(
                        code = when (throwable) {
                            is InsufficientStorageException -> ErrorCode.INSUFFICIENT_STORAGE
                            else -> ErrorCode.INSTALLATION_FAILED
                        },
                        message = throwable.message ?: "Linux runtime initialization failed",
                        cause = throwable,
                    ),
                )
            }
        }
    }

    override suspend fun restoreInstalledState(): Boolean = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
        if (_state.value is RuntimeState.Ready && hostBridge.isRunning.value) {
            return@withContext true
        }
        val selectedDistroId = runCatching { settingsDataStore.selectedDistribution.first() }.getOrDefault("ubuntu")

        if (!pathManager.isRootfsInstalled()) {
            logger.i("Restore skipped: rootfs marker or validator check failed (fresh install?)")
            return@withContext false
        }
        if (!pathManager.isProotInstalled()) {
            val message = "已安装的 Linux 环境无法恢复：PRoot 运行组件不完整（APK 内 native 库缺失或不可读）"
            logger.e("Restore failed: $message")
            _state.value = RuntimeState.Error(IllegalStateException(message))
            return@withContext false
        }

        val installedList = pathManager.listInstalledDistroIds()
        val effectiveDistro = if (selectedDistroId in installedList) selectedDistroId else installedList.firstOrNull() ?: selectedDistroId
        pathManager.ensureDistroDirectories(effectiveDistro)
        _activeDistroId.value = effectiveDistro
        refreshInstalledDistros()

        val health = try {
            healthChecker.check()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger.e("Restore failed: health check threw", throwable)
            _state.value = RuntimeState.Error(throwable)
            return@withContext false
        }
        if (!health.isHealthy) {
            val message = "已安装的 Linux 环境健康检查未通过：${health.detail.orEmpty()}"
            logger.e("Restore failed: $message")
            _state.value = RuntimeState.Error(IllegalStateException(message))
            return@withContext false
        }
        runCatching { configureChinaMirrors(effectiveDistro) }
        runCatching { configureEnvironment(effectiveDistro) }
        // APK 升级可能新增或替换内置 MCP/构建脚本。已有 rootfs 不会再走 configureRootfs，
        // 因此恢复时必须幂等同步一次，避免数据库预设已切换到新脚本而沙箱内文件仍是旧版本。
        runCatching { assetSynchronizer.syncAssetsToDistro(effectiveDistro) }
            .onFailure { logger.w("Restore asset synchronization failed for $effectiveDistro: ${it.message}", it) }
        hostBridge.start()
        _state.value = RuntimeState.Ready
        logger.i("Linux runtime restored from disk and ready with distro: $effectiveDistro")
        true
        }
    }

    override suspend fun updateRootfs(distroId: String?): AppResult<Unit> = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
            if (_state.value !is RuntimeState.Ready) {
                return@withContext AppResult.Failure(
                    AppError(ErrorCode.INSTALLATION_FAILED, "只有已就绪的 Linux Runtime 才能更新"),
                )
            }
            try {
                val targetDistro = distroId?.lowercase()?.trim() ?: _activeDistroId.value
                processRegistry.stopAll()
                updateInitializing("更新 RootFS", 0.05f, "保留 /root 和 /opt/taixu 用户数据")
                val distribution = DistributionCatalog.require(targetDistro)
                val result = rootfsInstaller.updateOci(
                    distribution,
                    RegistryRoute.AUTO,
                ) { progress -> updateDownloadProgress("更新 ${distribution.displayName}", 0.1f, 0.65f, progress) }
                result.errorOrNull()?.let { error ->
                    _state.value = RuntimeState.Error(error.cause ?: IllegalStateException(error.message))
                    return@withContext AppResult.Failure(error)
                }

                updateInitializing("configureRootfs", 0.75f)
                configureRootfs(targetDistro)
                configureDns(targetDistro)
                configureEnvironment(targetDistro)
                updateInitializing("runHealthCheck", 0.9f)
                val health = healthChecker.check()
                if (!health.isHealthy) {
                    rootfsInstaller.rollbackPendingUpdate(targetDistro)
                    _state.value = RuntimeState.Error(
                        IllegalStateException("更新后健康检查失败：${health.detail.orEmpty()}"),
                    )
                    return@withContext AppResult.Failure(
                        AppError(
                            ErrorCode.INSTALLATION_FAILED,
                            "RootFS 更新后的健康检查失败，已恢复旧版本",
                        ),
                    )
                }
                rootfsInstaller.finalizePendingUpdate(targetDistro)
                refreshInstalledDistros()
                _state.value = RuntimeState.Ready
                AppResult.Success(Unit)
            } catch (cancellation: CancellationException) {
                val targetDistro = distroId?.lowercase()?.trim() ?: _activeDistroId.value
                rootfsInstaller.rollbackPendingUpdate(targetDistro)
                _state.value = RuntimeState.Error(IllegalStateException("RootFS 更新已取消，旧版本已恢复"))
                throw cancellation
            } catch (throwable: Throwable) {
                val targetDistro = distroId?.lowercase()?.trim() ?: _activeDistroId.value
                rootfsInstaller.rollbackPendingUpdate(targetDistro)
                logger.e("Linux runtime update failed", throwable)
                _state.value = RuntimeState.Error(throwable)
                AppResult.Failure(
                    AppError(ErrorCode.INSTALLATION_FAILED, throwable.message ?: "RootFS 更新失败", throwable),
                )
            }
        }
    }

    override suspend fun checkRootfsUpdate(distroId: String?): AppResult<RootfsUpdateInfo> =
        withContext(Dispatchers.IO) {
            if (_state.value !is RuntimeState.Ready) {
                return@withContext AppResult.Failure(
                    AppError(ErrorCode.INSTALLATION_FAILED, "只有已就绪的 Linux Runtime 才能检查更新"),
                )
            }
            val targetDistro = distroId?.lowercase()?.trim() ?: _activeDistroId.value
            val distribution = DistributionCatalog.require(targetDistro)
            runCatching { rootfsInstaller.checkForUpdate(distribution, RegistryRoute.AUTO) }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = {
                    logger.e("Linux runtime RootFS update check failed", it)
                    AppResult.Failure(AppError(ErrorCode.NETWORK, "RootFS 更新检查失败：${it.message}", it))
                },
            )
        }

    override suspend fun healthCheck(distroId: String?): RuntimeHealth = withContext(Dispatchers.IO) {
        healthChecker.check()
    }

    override suspend fun execute(command: ShellCommand, distroId: String?): CommandResult {
        ensureReady()
        val safeDistro = distroId?.lowercase()?.trim()?.takeIf { it.isNotBlank() } ?: _activeDistroId.value
        val mounts = storageMounts()
        val execution = resolveExecutionLayout(safeDistro, command.useQemuCompatibility)
        return shellExecutor.execute(
            command = prootCommandBuilder.build(
                prootBinary = pathManager.activeProotFile(),
                rootfsDir = execution.rootfsDir,
                workspaceDir = pathManager.workspaceDir,
                homeDir = pathManager.homeDir(safeDistro),
                optDir = execution.optDir,
                tmpDir = pathManager.tmpDir,
                attachmentsDir = pathManager.attachmentsDir,
                command = command,
                mounts = mounts,
                emulatorBinary = execution.emulatorBinary,
            ),
            environment = pathManager.hostProcessEnvironment(safeDistro, execution.rootfsDir),
            timeoutMs = command.timeoutMs,
            onOutput = command.onOutput,
        )
    }

    private suspend fun resolveExecutionLayout(
        distroId: String,
        useQemuCompatibility: Boolean,
    ): ExecutionLayout {
        if (!useQemuCompatibility) {
            return ExecutionLayout(pathManager.rootfsDir(distroId), null, pathManager.taixuRootDir(distroId))
        }
        check(settingsDataStore.qemuCompatibilityEnabled.first()) {
            "QEMU 兼容模式未开启，请先在设置中打开兼容开关"
        }
        val taixuRoot = pathManager.taixuRootDir(distroId)
        check(QemuCompatibilityLayout.isReady(taixuRoot)) {
            "QEMU 兼容环境未就绪，请先安装并验证 qemu-x86-64-compat 插件"
        }
        return ExecutionLayout(
            rootfsDir = QemuCompatibilityLayout.guestRootfs(taixuRoot),
            emulatorBinary = QemuCompatibilityLayout.qemuBinary(taixuRoot),
            optDir = taixuRoot,
        )
    }

    private data class ExecutionLayout(
        val rootfsDir: File,
        val emulatorBinary: File?,
        val optDir: File,
    )

    /**
     * App 进程在宿主上携带的补充组列表（含 inet/everybody/OEM 组等）。
     * android.system.Os 未暴露 getgroups，改从 /proc/self/status 的 Groups 行解析。
     */
    private fun hostSupplementaryGroupIds(): List<Int> =
        runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Groups:") }
                    ?.removePrefix("Groups:")
                    ?.split(' ', '\t')
                    ?.mapNotNullTo(mutableListOf()) { it.trim().toIntOrNull() }
                    .orEmpty()
            }
        }.getOrElse { emptyList() }

    override suspend fun startSession(config: SessionConfig, distroId: String?): LinuxSession {
        ensureReady()
        val safeDistro = distroId?.lowercase()?.trim()?.takeIf { it.isNotBlank() } ?: _activeDistroId.value
        val effectiveConfig = if (config.commandLine == "/bin/bash -i") {
            val rootfs = pathManager.rootfsDir(safeDistro)
            val hasBash = File(rootfs, "bin/bash").exists() || File(rootfs, "usr/bin/bash").exists()
            if (!hasBash) {
                config.copy(commandLine = "/bin/sh -i", allowSttyResize = true)
            } else {
                config
            }
        } else {
            config
        }
        val markerId = UUID.randomUUID().toString()
        val markerFile = File(pathManager.taixuRootDir(safeDistro), ".pty-$markerId")
        val markerPath = "/opt/taixu/.pty-$markerId"
        val mounts = storageMounts()
        // 宿主补充 GID 不在 guest /etc/group 里会导致登录 shell 打 groups 警告，会话启动前幂等补齐。
        runCatching {
            syncGuestGroups(
                rootfsDir = pathManager.rootfsDir(safeDistro),
                hostGroupIds = hostSupplementaryGroupIds(),
            )
        }.onFailure {
            logger.w("同步宿主补充组到 /etc/group 失败，登录时可能出现 groups 警告", it)
        }
        if (effectiveConfig.showBanner) {
            runCatching {
                File(pathManager.taixuRootDir(safeDistro), "motd").writeText(terminalBanner())
            }.onFailure {
                logger.w("写入终端横幅失败，本次会话将无横幅", it)
            }
        }
        return try {
            val session = if (ptyManager.nativeAvailable) {
                ptyManager.openNative(
                    command = prootCommandBuilder.buildInteractive(
                        prootBinary = pathManager.activeProotFile(),
                        rootfsDir = pathManager.rootfsDir(safeDistro),
                        workspaceDir = pathManager.workspaceDir,
                        homeDir = pathManager.homeDir(safeDistro),
                        optDir = pathManager.taixuRootDir(safeDistro),
                        tmpDir = pathManager.tmpDir,
                        attachmentsDir = pathManager.attachmentsDir,
                        config = effectiveConfig,
                        nativePty = true,
                        mounts = mounts,
                    ),
                    hostEnvironment = pathManager.hostProcessEnvironment(safeDistro),
                    config = effectiveConfig,
                    cleanup = { markerFile.delete() },
                )
            } else {
                ptyManager.open(
                    command = prootCommandBuilder.buildInteractive(
                        prootBinary = pathManager.activeProotFile(),
                        rootfsDir = pathManager.rootfsDir(safeDistro),
                        workspaceDir = pathManager.workspaceDir,
                        homeDir = pathManager.homeDir(safeDistro),
                        optDir = pathManager.taixuRootDir(safeDistro),
                        tmpDir = pathManager.tmpDir,
                        attachmentsDir = pathManager.attachmentsDir,
                        config = effectiveConfig,
                        ptyMarker = markerPath,
                        mounts = mounts,
                    ),
                    hostEnvironment = pathManager.hostProcessEnvironment(safeDistro),
                    config = effectiveConfig,
                    resize = { columns, rows ->
                        resizePty(markerPath, columns, rows, safeDistro)
                    },
                    cleanup = { markerFile.delete() },
                )
            }
            trackInteractiveSession(session, safeDistro)
        } catch (throwable: Throwable) {
            markerFile.delete()
            throw throwable
        }
    }

    private suspend fun storageMounts(): List<StorageMountBinding> = buildList {
        val sharedEnabled = settingsDataStore.mountSharedStorageEnabled.first()
        if (sharedEnabled && File("/storage/emulated/0").isDirectory) {
            add(StorageMountBinding("system-shared", "共享存储", "/storage/emulated/0", "/sdcard", true, true))
        }
        if (!sharedEnabled && settingsDataStore.mountDownloadEnabled.first() && File("/storage/emulated/0/Download").isDirectory) {
            add(StorageMountBinding("system-download", "下载", "/storage/emulated/0/Download", "/sdcard/Download", true, true))
        }
        if (!sharedEnabled && settingsDataStore.mountDocumentsEnabled.first() && File("/storage/emulated/0/Documents").isDirectory) {
            add(StorageMountBinding("system-documents", "文档", "/storage/emulated/0/Documents", "/sdcard/Documents", true, true))
        }
        addAll(storageMountBindingRepository.bindings.first().filter { it.enabled })
    }

    private suspend fun resizePty(markerPath: String, columns: Int, rows: Int, distroId: String = "ubuntu") {
        val safeColumns = columns.coerceIn(20, 400)
        val safeRows = rows.coerceIn(5, 200)
        runCatching {
            shellExecutor.execute(
                command = prootCommandBuilder.build(
                    prootBinary = pathManager.activeProotFile(),
                    rootfsDir = pathManager.rootfsDir(distroId),
                    workspaceDir = pathManager.workspaceDir,
                    homeDir = pathManager.homeDir(distroId),
                    optDir = pathManager.taixuRootDir(distroId),
                    tmpDir = pathManager.tmpDir,
                    attachmentsDir = pathManager.attachmentsDir,
                    command = ShellCommand(
                        commandLine = "if test -s '$markerPath'; then " +
                            "stty -F \"\$(cat '$markerPath')\" cols $safeColumns rows $safeRows; " +
                            "fi",
                        timeoutMs = 2_000L,
                    ),
                ),
                environment = pathManager.hostProcessEnvironment(distroId),
                timeoutMs = 2_000L,
            )
        }
    }

    override suspend fun startBackground(
        id: String,
        command: ShellCommand,
        toolId: String?,
        type: ProcessType,
        distroId: String?,
    ): ManagedProcess {
        ensureReady()
        val mounts = storageMounts()
        return processRegistry.start(
            id = id,
            command = command,
            toolId = toolId,
            type = type,
            distroId = distroId ?: _activeDistroId.value,
            mounts = mounts,
        )
    }

    override suspend fun stopBackground(id: String): Boolean = processRegistry.stop(id)

    override fun listBackground(): List<ManagedProcess> = processRegistry.list()

    override suspend fun cleanupDeadBackground(): Int = processRegistry.cleanupDeadProcesses()

    override fun observeBackgroundLogs(idOrToolId: String): kotlinx.coroutines.flow.Flow<List<String>> =
        processRegistry.observeLogs(idOrToolId)

    override fun getBackgroundLogs(idOrToolId: String): List<String> =
        processRegistry.getLogs(idOrToolId)

    override fun clearBackgroundLogs(idOrToolId: String) {
        processRegistry.clearLogs(idOrToolId)
    }

    override suspend fun shutdown() {
        closeInteractiveSessions()
        processRegistry.stopAll()
        hostBridge.stop()
        _state.value = RuntimeState.NotInitialized
        logger.i("Linux runtime shut down")
    }

    private fun trackInteractiveSession(session: LinuxSession, distroId: String): LinuxSession {
        val tracked = object : LinuxSession {
            override val pid: Long? get() = session.pid
            override val isAlive: Boolean get() = session.isAlive
            override val output = session.output

            override suspend fun write(data: ByteArray) = session.write(data)
            override suspend fun resize(columns: Int, rows: Int) = session.resize(columns, rows)
            override suspend fun interrupt() = session.interrupt()

            override suspend fun close() {
                try {
                    session.close()
                } finally {
                    interactiveSessions.remove(this)
                }
            }
        }
        interactiveSessions[tracked] = distroId
        return tracked
    }

    private suspend fun closeInteractiveSessions(distroId: String? = null) {
        val sessions = interactiveSessions.entries
            .filter { distroId == null || it.value == distroId }
            .map { it.key }
        sessions.forEach { session ->
            runCatching { session.close() }
                .onFailure { logger.w("Failed to close interactive session before runtime cleanup: ${it.message}") }
        }
    }

    override fun rootfsPath(distroId: String?): File =
        pathManager.rootfsDir(distroId?.lowercase()?.trim() ?: _activeDistroId.value)

    override fun rootfsVersion(distroId: String?): String? =
        pathManager.rootfsVersion(distroId?.lowercase()?.trim() ?: _activeDistroId.value)

    override fun workspacePath(): File = pathManager.workspaceDir

    private fun detectArchitecture(): CpuArch {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return CpuArch.fromBuildAbi(abi)
    }

    private fun checkStorage() {
        val filesPath = pathManager.baseDir.parentFile?.absolutePath
            ?: pathManager.baseDir.absolutePath
        val statFs = StatFs(filesPath)
        val availableBytes = statFs.availableBytes
        if (availableBytes < MIN_FREE_BYTES) {
            throw InsufficientStorageException(
                "Not enough free space. Required at least ${MIN_FREE_BYTES / (1024 * 1024)} MB, " +
                    "available ${availableBytes / (1024 * 1024)} MB",
            )
        }
    }

    private fun configureRootfs(distroId: String = "ubuntu") {
        val rootfs = pathManager.rootfsDir(distroId)
        val etcDir = File(rootfs, "etc")
        etcDir.mkdirs()
        File(etcDir, "taixu-runtime").writeText("taixu-runtime=0.3.0\n")
        File(rootfs, "opt/taixu").mkdirs()
        pathManager.ensureDistroDirectories(distroId)
        stripSetuidBits(distroId)
        configureDpkgStatoverride(distroId)
        configureDpkgNoDoc(distroId)
        configureAptSettings(distroId)
        configureChinaMirrors(distroId)
        configurePipMirror(distroId)
        installAptStripSetuidHook(distroId)
        installPerlFixScript(distroId)
        runCatching {
            kotlinx.coroutines.runBlocking {
                assetSynchronizer.syncAssetsToDistro(distroId)
            }
        }
    }

    /**
     * 在 PRoot 沙箱中排除文档/手册页/区域语言包解包：
     * 1. 降低 50%~60% 的磁盘 I/O 和解包时间，极大避免 300s 安装超时；
     * 2. 规避 man 手册硬链接（如 perlthanks.1.gz 等）在 PRoot 下的 chown 报错。
     */
    private fun configureDpkgNoDoc(distroId: String = "ubuntu") {
        val dpkgCfgDir = File(pathManager.rootfsDir(distroId), "etc/dpkg/dpkg.cfg.d")
        dpkgCfgDir.mkdirs()
        File(dpkgCfgDir, "01_taixu_nodoc").writeText(
            """
            # PRoot 性能与稳定性优化：跳过文档与手册文件以减少 I/O 并避免硬链接解包异常
            path-exclude /usr/share/doc/*
            path-exclude /usr/share/man/*
            path-exclude /usr/share/groff/*
            path-exclude /usr/share/info/*
            path-exclude /usr/share/locale/*
            path-exclude /usr/share/lintian/*
            path-exclude /usr/share/linda/*
            """.trimIndent() + "\n",
        )
    }

    /**
     * 配置 apt 超时重试以及非交互默认选项，避免后台安装任务被挂起。
     */
    private fun configureAptSettings(distroId: String = "ubuntu") {
        val hookDir = File(pathManager.rootfsDir(distroId), "etc/apt/apt.conf.d")
        hookDir.mkdirs()
        File(hookDir, "99taixu-apt-config").writeText(
            """
            // 提高网络波动环境下的安装鲁棒性并默认使用非交互配置
            Acquire::Retries "3";
            Acquire::http::Timeout "60";
            Acquire::https::Timeout "60";
            DPkg::Options {
               "--force-confdef";
               "--force-confold";
            };
            """.trimIndent() + "\n",
        )
    }

    /**
     * 将沙箱内软件源切换到清华大学 TUNA 镜像站，加速国内 apt / pip 安装。
     * 仅对 apt 系发行版（debian / ubuntu / kali）生效；其余发行版保持官方源。
     */
    private fun configureChinaMirrors(distroId: String = "ubuntu") {
        val osRelease = readOsRelease(distroId)
        if (osRelease.isEmpty()) {
            logger.i("China mirrors skipped: os-release not found for $distroId")
            return
        }
        val id = osRelease["ID"] ?: osRelease["ID_LIKE"]
        val codename = osRelease["VERSION_CODENAME"]
        val sources = when (id) {
            "debian" -> codename?.let(::tunaDebianSources)
            "ubuntu" -> codename?.let(::tunaUbuntuSources)
            "kali" -> tunaKaliSources()
            else -> null
        }
        if (sources == null) {
            logger.i("China mirrors skipped for distro id=$id codename=$codename")
            return
        }
        val sourcesListDir = File(pathManager.rootfsDir(distroId), "etc/apt/sources.list.d")
        sourcesListDir.mkdirs()
        disableStockAptSources(sourcesListDir, distroId)
        File(sourcesListDir, "taixu-mirrors.list").writeText(sources + "\n")
        logger.i("China mirrors applied: TUNA apt sources for $id ($codename) in $distroId")
    }

    private fun disableStockAptSources(sourcesListDir: File, distroId: String = "ubuntu") {
        File(pathManager.rootfsDir(distroId), "etc/apt/sources.list").takeIf { it.isFile }
            ?.let { stock -> renameToDisabled(stock) }
        sourcesListDir.listFiles()?.forEach { entry ->
            val name = entry.name
            if (entry.isFile && (name.endsWith(".list") || name.endsWith(".sources")) &&
                name != "taixu-mirrors.list"
            ) {
                renameToDisabled(entry)
            }
        }
    }

    private fun renameToDisabled(file: File) {
        val disabled = File(file.parentFile, "${file.name}.taixu-disabled")
        if (!file.renameTo(disabled)) {
            logger.w("Failed to disable stock apt source: ${file.path}")
        }
    }

    private fun tunaDebianSources(codename: String): String = """
        # TaiXu: 清华大学 TUNA 镜像站（由官方源自动切换）
        deb https://mirrors.tuna.tsinghua.edu.cn/debian $codename main contrib non-free non-free-firmware
        deb https://mirrors.tuna.tsinghua.edu.cn/debian $codename-updates main contrib non-free non-free-firmware
        deb https://mirrors.tuna.tsinghua.edu.cn/debian-security $codename-security main contrib non-free non-free-firmware
    """.trimIndent()

    private fun tunaUbuntuSources(codename: String): String = """
        # TaiXu: 清华大学 TUNA 镜像站（由官方源自动切换）
        deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports $codename main restricted universe multiverse
        deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports $codename-updates main restricted universe multiverse
        deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports $codename-security main restricted universe multiverse
        deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports $codename-backports main restricted universe multiverse
    """.trimIndent()

    private fun tunaKaliSources(): String = """
        # TaiXu: 清华大学 TUNA 镜像站（由官方源自动切换）
        deb https://mirrors.tuna.tsinghua.edu.cn/kali kali-rolling main contrib non-free
    """.trimIndent()

    private fun configurePipMirror(distroId: String = "ubuntu") {
        val config = File(pathManager.rootfsDir(distroId), "etc/pip.conf")
        config.parentFile?.mkdirs()
        config.writeText(
            """
            # TaiXu: 清华大学 TUNA PyPI 镜像
            [global]
            index-url = https://pypi.tuna.tsinghua.edu.cn/simple
            """.trimIndent() + "\n",
        )
    }

    private fun readOsRelease(distroId: String = "ubuntu"): Map<String, String> {
        val rootfs = pathManager.rootfsDir(distroId)
        val file = File(rootfs, "etc/os-release")
            .takeIf { it.isFile }
            ?: File(rootfs, "usr/lib/os-release").takeIf { it.isFile }
            ?: return emptyMap()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) return@mapNotNull null
                val key = line.substring(0, index).trim()
                val value = line.substring(index + 1).trim().trim('"', '\'')
                key to value
            }.toMap()
        }.onFailure { logger.w("Failed to parse os-release for $distroId", it) }
            .getOrDefault(emptyMap())
    }

    private fun installPerlFixScript(distroId: String = "ubuntu") {
        val binDir = File(pathManager.rootfsDir(distroId), "usr/local/sbin")
        binDir.mkdirs()
        val script = File(binDir, "taixu-fix-perl")
        script.writeText(
            """
            #!/bin/sh
            set -e
            echo "[TaiXu] Scanning for perl deb packages to patch hardlinks..."
            TMPDIR="${'$'}(mktemp -d /tmp/taixu-perl-fix.XXXXXX)"
            trap 'rm -rf "${'$'}TMPDIR"' EXIT INT TERM

            # 优先从 apt 缓存寻找 perl deb 包，若无则尝试下载
            PERL_DEB="${'$'}(ls -1 /var/cache/apt/archives/perl_*.deb 2>/dev/null | head -n 1 || true)"
            if [ -z "${'$'}PERL_DEB" ]; then
                echo "[TaiXu] Downloading perl package..."
                cd "${'$'}TMPDIR" && apt-get download perl || true
                PERL_DEB="${'$'}(ls -1 "${'$'}TMPDIR"/perl_*.deb 2>/dev/null | head -n 1 || true)"
            fi

            if [ -z "${'$'}PERL_DEB" ] || [ ! -f "${'$'}PERL_DEB" ]; then
                echo "[TaiXu] Perl deb package not found, attempting apt --fix-broken install..."
                apt-get --fix-broken install -y || true
                exit 0
            fi

            echo "[TaiXu] Patching ${'$'}PERL_DEB..."
            WORKDIR="${'$'}TMPDIR/repack"
            mkdir -p "${'$'}WORKDIR/DEBIAN"
            dpkg-deb -e "${'$'}PERL_DEB" "${'$'}WORKDIR/DEBIAN"
            dpkg-deb --fsys-tarfile "${'$'}PERL_DEB" | tar -C "${'$'}WORKDIR" -xf -

            # 将 usr/bin/perlthanks 等硬链接转换为符号链接
            if [ -f "${'$'}WORKDIR/usr/bin/perlthanks" ]; then
                rm -f "${'$'}WORKDIR/usr/bin/perlthanks"
                ln -s perlbug "${'$'}WORKDIR/usr/bin/perlthanks"
                echo "[TaiXu] Converted /usr/bin/perlthanks to symlink"
            fi

            dpkg-deb -b "${'$'}WORKDIR" "${'$'}TMPDIR/perl-patched.deb"
            dpkg -i --force-overwrite "${'$'}TMPDIR/perl-patched.deb"
            echo "[TaiXu] Perl patch installed successfully."
            """.trimIndent() + "\n",
        )
        runCatching {
            android.system.Os.chmod(script.absolutePath, 0x1ED) // 0755
        }
    }

    private fun stripSetuidBits(distroId: String = "ubuntu") {
        var stripped = 0
        var failed = 0
        pathManager.rootfsDir(distroId).walkTopDown()
            .forEach { file ->
                if (file.name.endsWith(".dpkg-tmp")) {
                    file.delete()
                } else if (file.isFile) {
                    runCatching {
                        val mode = android.system.Os.lstat(file.absolutePath).st_mode
                        if (mode and 0xC00 != 0) {
                            android.system.Os.chmod(file.absolutePath, mode and 0x3FF)
                            stripped++
                        }
                    }.onFailure { failed++ }
                }
            }
        logger.i("stripSetuidBits ($distroId): cleared $stripped setuid/setgid bits, $failed failures")
    }

    private fun configureDpkgStatoverride(distroId: String = "ubuntu") {
        val dpkgDir = File(pathManager.rootfsDir(distroId), "var/lib/dpkg")
        dpkgDir.mkdirs()
        val statoverrideFile = File(dpkgDir, "statoverride")
        val overrides = listOf(
            "/usr/bin/su",
            "/usr/bin/mount",
            "/usr/bin/umount",
            "/usr/bin/newgrp",
            "/usr/bin/gpasswd",
            "/usr/bin/passwd",
            "/usr/bin/chfn",
            "/usr/bin/chsh",
            "/usr/bin/expiry",
        )
        val existingLines = if (statoverrideFile.exists()) {
            statoverrideFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        } else {
            mutableSetOf()
        }
        overrides.forEach { path ->
            val entry = "root root 0755 $path"
            val pathSuffix = " $path"
            if (existingLines.none { it.endsWith(pathSuffix) }) {
                existingLines.add(entry)
            }
        }
        statoverrideFile.writeText(existingLines.sorted().joinToString("\n", postfix = "\n"))
    }

    private fun installAptStripSetuidHook(distroId: String = "ubuntu") {
        val hookDir = File(pathManager.rootfsDir(distroId), "etc/apt/apt.conf.d")
        hookDir.mkdirs()
        val findCommand = "find /usr /bin /sbin -xdev -type f -perm /6000 -exec chmod ug-s {} +"
        val cleanupTmp = "rm -f /bin/*.dpkg-tmp /usr/bin/*.dpkg-tmp /usr/sbin/*.dpkg-tmp /sbin/*.dpkg-tmp 2>/dev/null || true"
        File(hookDir, "99taixu-strip-setuid").writeText(
            "// PRoot 沙箱：setuid 不生效，保留会导致 dpkg 升级卡死（见 taixu-runtime 文档）。\n" +
                "DPkg::Pre-Invoke { \"$cleanupTmp\"; };\n" +
                "DPkg::Post-Invoke { \"$findCommand\"; };\n",
        )
    }

    private fun configureDns(distroId: String = "ubuntu") {
        val etcDir = File(pathManager.rootfsDir(distroId), "etc")
        etcDir.mkdirs()
        val resolvConf = File(etcDir, "resolv.conf")
        // Ubuntu/Debian OCI 镜像里 /etc/resolv.conf 通常是指向 /run/systemd/resolve/... 的符号链接，
        // PRoot 沙箱无 systemd 运行导致链接悬空，直接写入会抛 FileNotFoundException: ENOENT，
        // 必须先移除该链接（普通文件/悬空链接两种情况都要处理）再写入。
        try {
            val resolvPath = resolvConf.toPath()
            if (java.nio.file.Files.isSymbolicLink(resolvPath) || resolvConf.exists()) {
                resolvConf.delete()
            }
        } catch (_: Exception) {
            // 删除失败不阻塞后续写入尝试（writeText 会给出最终错误）
        }
        resolvConf.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
    }

    private fun configureEnvironment(distroId: String = "ubuntu") {
        val profileDir = File(pathManager.rootfsDir(distroId), "etc/profile.d")
        profileDir.mkdirs()
        File(profileDir, "taixu-env.sh").writeText(
            """
            export LANG=C.UTF-8
            export PATH=/root/.local/bin:/opt/taixu/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export HOME=/root
            # HostBridge — 沙箱通过 localhost HTTP 桥接触发宿主侧操作
            export TAIXU_BRIDGE_URL="http://127.0.0.1:7980"
            export TAIXU_BRIDGE_PORT=7980
            # Android 二进制参考路径（用 taixu-android-exec 包装器执行）
            export ANDROID_BIN_PATH="/system/bin:/system/xbin"
            export ANDROID_LIB_PATH="/system/lib64:/system/lib:/vendor/lib64:/vendor/lib"
            """.trimIndent() + "\n",
        )
        val home = pathManager.homeDir(distroId)
        home.mkdirs()
        File(home, ".gitconfig").writeText(
            """
            [safe]
            	directory = *
            [http]
            	version = HTTP/1.1
            """.trimIndent() + "\n",
        )
        // 安装 HostBridge 沙箱脚本与密钥
        installHostBridgeScripts(distroId)
    }

    /**
     * 安装宿主桥接沙箱端脚本与 API 密钥。
     *
     * 写入三个文件到 /opt/taixu/（bind-mounted 到沙箱）：
     * - bin/taixu-host        — 桥接 CLI（install-apk / shell / health）
     * - bin/taixu-android-exec — Android 二进制执行包装器（设置正确的 linker 环境）
     * - .bridge-key           — API 认证密钥
     */
    private fun installHostBridgeScripts(distroId: String) {
        val binDir = pathManager.taixuBinDir(distroId)
        binDir.mkdirs()
        val rootDir = pathManager.taixuRootDir(distroId)

        // taixu-host — 宿主桥接 CLI
        val hostScript = File(binDir, "taixu-host")
        hostScript.writeText(TAIXU_HOST_SCRIPT)
        runCatching { android.system.Os.chmod(hostScript.absolutePath, 0x1ED) } // 0755

        // taixu-android-exec — Android 二进制执行包装器
        val androidExecScript = File(binDir, "taixu-android-exec")
        androidExecScript.writeText(TAIXU_ANDROID_EXEC_SCRIPT)
        runCatching { android.system.Os.chmod(androidExecScript.absolutePath, 0x1ED) }

        // API 密钥 — 每次配置时刷新（确保 app 重启后密钥同步）
        File(rootDir, ".bridge-key").writeText(hostBridge.bridgeKey)

        logger.i("HostBridge scripts installed for distro: $distroId")
    }

    private fun createWorkspace() {
        pathManager.workspaceDir.mkdirs()
    }

    private fun ensureReady() {
        if (_state.value !is RuntimeState.Ready) {
            throw IllegalStateException("Linux runtime is not ready. Call initialize() first.")
        }
    }

    private fun updateInitializing(step: String, progress: Float, detail: String? = null) {
        _state.value = RuntimeState.Initializing(
            step = step,
            progress = progress.coerceIn(0f, 1f),
            detail = detail,
        )
    }

    private fun updateDownloadProgress(
        step: String,
        baseProgress: Float,
        progressSpan: Float,
        progress: DownloadProgress,
    ) {
        val fraction = progress.fraction ?: 0f
        val scaled = baseProgress + fraction * progressSpan
        val detail = if (progress.totalMegabytes != null) {
            "${progress.downloadedMegabytes} / ${progress.totalMegabytes} MB"
        } else {
            "已下载 ${progress.downloadedMegabytes} MB"
        }
        updateInitializing(step, scaled, detail)
    }

    private fun failInitialization(error: AppError) {
        _state.value = RuntimeState.Error(
            error.cause ?: IllegalStateException(error.message),
        )
    }

    private class RuntimeArchitectureException(message: String) : IllegalStateException(message)

    private class InsufficientStorageException(message: String) : IllegalStateException(message)

    companion object {
        const val MIN_FREE_BYTES = 600L * 1024L * 1024L

        val TAIXU_HOST_SCRIPT = listOf(
            "#!/bin/sh",
            "# TaiXu Host Bridge CLI",
            "# Usage: taixu-host install-apk <path> | taixu-host shell <cmd> | taixu-host health",
            "",
            "BRIDGE_URL=\"http://127.0.0.1:7980\"",
            "KEY_FILE=\"/opt/taixu/.bridge-key\"",
            "",
            "if [ -f \"\${KEY_FILE}\" ]; then",
            "  BRIDGE_KEY=\$(cat \"\${KEY_FILE}\" 2>/dev/null | tr -d '[:space:]')",
            "else",
            "  BRIDGE_KEY=\"\"",
            "fi",
            "",
            "if ! command -v curl >/dev/null 2>&1; then",
            "  echo '{\"success\":false,\"error\":\"curl not installed\"}' >&2; exit 1",
            "fi",
            "",
            "AUTH=\"\"",
            "if [ -n \"\${BRIDGE_KEY}\" ]; then AUTH=\"Authorization: Bearer \${BRIDGE_KEY}\"; fi",
            "",
            "case \"\$1\" in",
            "  install-apk)",
            "    [ -z \"\$2\" ] && { echo 'Usage: taixu-host install-apk <path>' >&2; exit 1; }",
            "    TARGET=\"\$2\"",
            "    if [ ! -f \"\$TARGET\" ]; then",
            "      STRIPPED=\"\${TARGET#/}\"",
            "      if [ -f \"\$STRIPPED\" ]; then",
            "        TARGET=\"\$STRIPPED\"",
            "      elif [ -f \"./\$STRIPPED\" ]; then",
            "        TARGET=\"./\$STRIPPED\"",
            "      elif [ -f \"build/\$STRIPPED\" ]; then",
            "        TARGET=\"build/\$STRIPPED\"",
            "      else",
            "        BN=\"\$(basename \"\$TARGET\")\"",
            "        FOUND=\"\$(find . -maxdepth 6 -name \"\$BN\" -type f 2>/dev/null | head -n 1)\"",
            "        if [ -z \"\$FOUND\" ] && [ -d /workspace ]; then",
            "          FOUND=\"\$(find /workspace -maxdepth 7 -name \"\$BN\" -type f 2>/dev/null | head -n 1)\"",
            "        fi",
            "        if [ -z \"\$FOUND\" ]; then",
            "          FOUND=\"\$(find . -maxdepth 6 -name \"*.apk\" -type f 2>/dev/null | head -n 1)\"",
            "        fi",
            "        if [ -n \"\$FOUND\" ] && [ -f \"\$FOUND\" ]; then",
            "          TARGET=\"\$FOUND\"",
            "        fi",
            "      fi",
            "    fi",
            "    [ ! -f \"\$TARGET\" ] && { echo \"{\\\"success\\\":false,\\\"error\\\":\\\"Not found: \$2\\\"}\" >&2; exit 1; }",
            "    if command -v realpath >/dev/null 2>&1; then",
            "      ABS_TARGET=\"\$(realpath \"\$TARGET\")\"",
            "    elif command -v readlink >/dev/null 2>&1; then",
            "      ABS_TARGET=\"\$(readlink -f \"\$TARGET\" 2>/dev/null || echo \"\$TARGET\")\"",
            "    else",
            "      ABS_TARGET=\"\$TARGET\"",
            "    fi",
            "    if [ -n \"\${AUTH}\" ]; then",
            "      curl -s -X POST \"\${BRIDGE_URL}/api/install-apk\" -H 'Content-Type: application/json' -H \"\${AUTH}\" -d \"{\\\"path\\\":\\\"\${ABS_TARGET}\\\"}\"",
            "    else",
            "      curl -s -X POST \"\${BRIDGE_URL}/api/install-apk\" -H 'Content-Type: application/json' -d \"{\\\"path\\\":\\\"\${ABS_TARGET}\\\"}\"",
            "    fi",
            "    echo \"\"",
            "    ;;",
            "  shell)",
            "    [ -z \"\$2\" ] && { echo 'Usage: taixu-host shell <command>' >&2; exit 1; }",
            "    shift; CMD=\"\$*\"",
            "    if command -v jq >/dev/null 2>&1; then",
            "      BODY=\$(jq -nc --arg c \"\${CMD}\" '{command:\$c}')",
            "    else",
            "      ESC=\$(printf '%s' \"\${CMD}\" | sed 's/\\\\/\\\\\\\\/g; s/\"/\\\\\"/g' | tr '\\n' ' ')",
            "      BODY=\"{\\\"command\\\":\\\"\${ESC}\\\"}\"",
            "    fi",
            "    if [ -n \"\${AUTH}\" ]; then",
            "      curl -s -X POST \"\${BRIDGE_URL}/api/shell\" -H 'Content-Type: application/json' -H \"\${AUTH}\" -d \"\${BODY}\"",
            "    else",
            "      curl -s -X POST \"\${BRIDGE_URL}/api/shell\" -H 'Content-Type: application/json' -d \"\${BODY}\"",
            "    fi",
            "    echo \"\"",
            "    ;;",
            "  logcat)",
            "    shift",
            "    PKG=\"\${1:-}\"",
            "    TAG=\"\${2:-}\"",
            "    PRIO=\"\${3:-V}\"",
            "    KW=\"\${4:-}\"",
            "    LINES=\"\${5:-200}\"",
            "    if command -v jq >/dev/null 2>&1; then",
            "      BODY=\$(jq -nc --arg p \"\$PKG\" --arg t \"\$TAG\" --arg pr \"\$PRIO\" --arg k \"\$KW\" --arg l \"\$LINES\" '{package:\$p, tag:\$t, priority:\$pr, keyword:\$k, lines:(\$l|tonumber)}')",
            "    else",
            "      BODY=\"{\\\"package\\\":\\\"\$PKG\\\",\\\"tag\\\":\\\"\$TAG\\\",\\\"priority\\\":\\\"\$PRIO\\\",\\\"keyword\\\":\\\"\$KW\\\",\\\"lines\\\":\$LINES}\"",
            "    fi",
            "    if [ -n \"\${AUTH}\" ]; then",
            "      curl -s -X POST \"\${BRIDGE_URL}/api/logcat\" -H 'Content-Type: application/json' -H \"\${AUTH}\" -d \"\${BODY}\"",
            "    else",
            "      curl -s -X POST \"\${BRIDGE_URL}/api/logcat\" -H 'Content-Type: application/json' -d \"\${BODY}\"",
            "    fi",
            "    echo \"\"",
            "    ;;",
            "  health|status)",
            "    if [ -n \"\${AUTH}\" ]; then curl -s \"\${BRIDGE_URL}/api/health\" -H \"\${AUTH}\"; else curl -s \"\${BRIDGE_URL}/api/health\"; fi",
            "    echo \"\"",
            "    ;;",
            "  *)",
            "    echo \"TaiXu Host Bridge CLI\"",
            "    echo \"  taixu-host install-apk <path>   Install APK on host (wireless ADB or system dialog)\"",
            "    echo \"  taixu-host shell <command>      Run host shell (wireless ADB or Shizuku/root)\"",
            "    echo \"  taixu-host logcat [pkg] [tag] [prio] [kw] [lines] Capture logcat via wireless ADB\"",
            "    echo \"  taixu-host health               Bridge health and ADB status\"",
            "    exit 1;;",
            "esac",
        ).joinToString("\n") + "\n"

        val TAIXU_ANDROID_EXEC_SCRIPT = listOf(
            "#!/bin/sh",
            "# TaiXu Android Binary Executor",
            "# Sets up correct linker env for Android system binaries in PRoot sandbox.",
            "# Usage: taixu-android-exec /system/bin/settings put global captive_portal_http_url ''",
            "",
            "[ -z \"\$1\" ] && { echo 'Usage: taixu-android-exec <binary> [args...]' >&2; exit 1; }",
            "export LD_LIBRARY_PATH=/system/lib64:/system/lib:/vendor/lib64:/vendor/lib",
            "export ANDROID_DATA=/data",
            "export ANDROID_ROOT=/system",
            "unset LD_PRELOAD",
            "exec \"\$@\"",
        ).joinToString("\n") + "\n"
    }
}
