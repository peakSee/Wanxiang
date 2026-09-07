package top.wanxiang.app.runtime.doctor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import top.wanxiang.app.core.model.DoctorCategory
import top.wanxiang.app.core.model.DoctorItem
import top.wanxiang.app.core.model.DoctorReport
import top.wanxiang.app.core.model.DoctorStatus
import top.wanxiang.app.core.model.RuntimeState
import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.shell.ShellCommand
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class EnvironmentDoctor @Inject constructor(
    @ApplicationContext private val context: Context? = null,
    private val linuxRuntime: LinuxRuntime,
) {
    suspend fun check(): DoctorReport = withContext(Dispatchers.IO) {
        // 宿主侧权限检查：不依赖沙箱状态，两种路径都要展示
        val allFilesAccessItem = checkAllFilesAccess()

        val state = linuxRuntime.state.value
        if (state !is RuntimeState.Ready) {
            val unreadyItem = DoctorItem(
                id = "sandbox_unready",
                category = DoctorCategory.SANDBOX,
                title = "PRoot 沙箱状态",
                status = DoctorStatus.ERROR,
                summary = if (state is RuntimeState.Error) "沙箱异常: ${state.throwable.message}" else "沙箱未初始化",
                detail = "请先在仪表盘初始化并启动 Linux 沙箱环境",
                fixable = false,
            )
            return@withContext DoctorReport(
                items = listOf(unreadyItem, allFilesAccessItem),
                timestamp = System.currentTimeMillis(),
                overallStatus = DoctorStatus.ERROR,
                healthyCount = itemsHealthy(listOf(unreadyItem, allFilesAccessItem)),
                warningCount = itemsWarning(listOf(unreadyItem, allFilesAccessItem)),
                errorCount = 1,
            )
        }

        val items = mutableListOf<DoctorItem>()

        // 1. 沙箱与存储检查
        items.add(checkSandboxStorage())
        items.add(allFilesAccessItem)

        // 2. DNS 与网络连通性检查
        items.add(checkDnsAndNetwork())

        // 3. CA 根证书检查
        items.add(checkCaCertificates())

        // 4. APT 软件源与镜像加速检查
        items.add(checkAptMirrors())

        // 5. 基础开发工具链检查 (git/curl/tar/xz)
        items.add(checkBaseDevTools())

        // 6. Node.js 运行时检查
        items.add(checkNodeRuntime())

        // 7. Android 开发环境检查（可选插件，未安装时引导获取离线/在线插件包）
        items.add(checkAndroidEnvironment())

        val healthyCount = items.count { it.status == DoctorStatus.HEALTHY }
        val warningCount = items.count { it.status == DoctorStatus.WARNING }
        val errorCount = items.count { it.status == DoctorStatus.ERROR }

        val overallStatus = when {
            errorCount > 0 -> DoctorStatus.ERROR
            warningCount > 0 -> DoctorStatus.WARNING
            else -> DoctorStatus.HEALTHY
        }

        DoctorReport(
            items = items,
            timestamp = System.currentTimeMillis(),
            overallStatus = overallStatus,
            healthyCount = healthyCount,
            warningCount = warningCount,
            errorCount = errorCount,
        )
    }

    private fun checkAllFilesAccess(): DoctorItem {
        val granted = if (context == null) {
            true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        return if (granted) {
            DoctorItem(
                id = "host_all_files_access",
                category = DoctorCategory.SANDBOX,
                title = "所有文件访问权限",
                status = DoctorStatus.HEALTHY,
                summary = "已授权「所有文件访问」，文件浏览器可完整访问共享存储",
            )
        } else {
            DoctorItem(
                id = "host_all_files_access",
                category = DoctorCategory.SANDBOX,
                title = "所有文件访问权限",
                status = DoctorStatus.WARNING,
                summary = "未授权「所有文件访问」权限",
                detail = "Android 会过滤共享存储中其他应用的文件，文件浏览器 /sdcard 入口可能只显示文件夹而看不到文件。点击右侧「去授权」立即前往系统设置开启。",
                fixable = true,
            )
        }
    }

    private fun itemsHealthy(items: List<DoctorItem>): Int = items.count { it.status == DoctorStatus.HEALTHY }

    private fun itemsWarning(items: List<DoctorItem>): Int = items.count { it.status == DoctorStatus.WARNING }

    private suspend fun checkSandboxStorage(): DoctorItem {
        val res = runCatching {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = "mkdir -p /workspace /tmp && touch /workspace/.doctor_probe && rm -f /workspace/.doctor_probe",
                    timeoutMs = 5000L,
                ),
            )
        }.getOrNull()

        return if (res != null && res.isSuccess) {
            DoctorItem(
                id = "sandbox_storage",
                category = DoctorCategory.SANDBOX,
                title = "PRoot 沙箱与工作区",
                status = DoctorStatus.HEALTHY,
                summary = "沙箱虚拟环境正常，/workspace 与 /tmp 读写就绪",
            )
        } else {
            DoctorItem(
                id = "sandbox_storage",
                category = DoctorCategory.SANDBOX,
                title = "PRoot 沙箱与工作区",
                status = DoctorStatus.ERROR,
                summary = "工作区读写或权限测试失败",
                detail = res?.stderr?.ifBlank { res.stdout } ?: "命令执行超时",
            )
        }
    }

    private suspend fun checkDnsAndNetwork(): DoctorItem {
        val resolvCheck = runCatching {
            linuxRuntime.execute(ShellCommand("cat /etc/resolv.conf 2>/dev/null", timeoutMs = 3000L))
        }.getOrNull()

        val resolvContent = resolvCheck?.stdout.orEmpty()
        val hasNameserver = resolvContent.contains("nameserver", ignoreCase = true)

        if (!hasNameserver) {
            return DoctorItem(
                id = "network_dns",
                category = DoctorCategory.NETWORK_SSL,
                title = "DNS 域名解析",
                status = DoctorStatus.WARNING,
                summary = "未配置有效 DNS 解析服务器",
                detail = "/etc/resolv.conf 为空或缺失，可能导致无法解析软件下载域名",
            )
        }

        return DoctorItem(
            id = "network_dns",
            category = DoctorCategory.NETWORK_SSL,
            title = "DNS 域名解析",
            status = DoctorStatus.HEALTHY,
            summary = "DNS 解析配置正常",
            detail = resolvContent.lineSequence().filter { it.startsWith("nameserver") }.take(2).joinToString(", "),
        )
    }

    private suspend fun checkCaCertificates(): DoctorItem {
        val certCheck = runCatching {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = "test -f /etc/ssl/certs/ca-certificates.crt || test -d /etc/ssl/certs",
                    timeoutMs = 3000L,
                ),
            )
        }.getOrNull()

        val hasCerts = certCheck != null && certCheck.isSuccess
        return if (hasCerts) {
            DoctorItem(
                id = "ca_certificates",
                category = DoctorCategory.NETWORK_SSL,
                title = "SSL 根证书 (CA)",
                status = DoctorStatus.HEALTHY,
                summary = "CA 根证书正常就绪，支持 HTTPS 依赖下载",
            )
        } else {
            DoctorItem(
                id = "ca_certificates",
                category = DoctorCategory.NETWORK_SSL,
                title = "SSL 根证书 (CA)",
                status = DoctorStatus.WARNING,
                summary = "系统缺失 CA 根证书",
                detail = "下载 HTTPS 资源或 Git Clone 时可能出现 SSL 验证错误",
            )
        }
    }

    private suspend fun checkAptMirrors(): DoctorItem {
        val sourcesCheck = runCatching {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = "cat /etc/apt/sources.list /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list 2>/dev/null || true",
                    timeoutMs = 4000L,
                ),
            )
        }.getOrNull()

        val content = sourcesCheck?.stdout.orEmpty()
        val hasInvalidUbuntuMirror = (content.contains("/ubuntu ") || content.contains("/ubuntu/")) &&
            !content.contains("ubuntu-ports")

        if (hasInvalidUbuntuMirror) {
            return DoctorItem(
                id = "apt_mirrors",
                category = DoctorCategory.PACKAGE_MANAGER,
                title = "APT 软件包源",
                status = DoctorStatus.WARNING,
                summary = "APT 源配置异常 (Ubuntu ARM64 需使用 ubuntu-ports 源)",
                detail = "检测到 ARM64 架构下使用了 x86 镜像路径，会导致软件包 404 错误。请点击一键修复自动纠正。",
            )
        }

        val hasDomesticMirror = content.contains("tsinghua.edu.cn", ignoreCase = true) ||
            content.contains("aliyun.com", ignoreCase = true) ||
            content.contains("ustc.edu.cn", ignoreCase = true) ||
            content.contains("tencent.com", ignoreCase = true) ||
            content.contains("163.com", ignoreCase = true)

        return if (hasDomesticMirror) {
            DoctorItem(
                id = "apt_mirrors",
                category = DoctorCategory.PACKAGE_MANAGER,
                title = "APT 软件包源",
                status = DoctorStatus.HEALTHY,
                summary = "已配置国内镜像源加速 (清华/阿里/中科大)",
            )
        } else {
            DoctorItem(
                id = "apt_mirrors",
                category = DoctorCategory.PACKAGE_MANAGER,
                title = "APT 软件包源",
                status = DoctorStatus.WARNING,
                summary = "当前为官方默认源，国内安装可能缓慢或超时",
                detail = "建议一键切换为国内镜像源以获得高速稳定的依赖下载体验",
            )
        }
    }

    private suspend fun checkBaseDevTools(): DoctorItem {
        val toolsCheck = runCatching {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = "for t in curl git tar xz; do which \$t >/dev/null 2>&1 || echo \$t; done",
                    timeoutMs = 4000L,
                ),
            )
        }.getOrNull()

        val missingTools = toolsCheck?.stdout?.lines()?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

        return if (missingTools.isEmpty()) {
            DoctorItem(
                id = "base_devtools",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "核心基础工具链",
                status = DoctorStatus.HEALTHY,
                summary = "Git, Curl, Tar, XZ 等常用工具已就绪",
            )
        } else {
            DoctorItem(
                id = "base_devtools",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "核心基础工具链",
                status = DoctorStatus.WARNING,
                summary = "缺少常用基础工具: ${missingTools.joinToString(", ")}",
                detail = "部分安装脚本或插件依赖上述工具进行解压与代码拉取",
            )
        }
    }

    /**
     * 复用插件中心 android-core 组件的同一探针命令，保证体检与插件安装的状态判定口径一致。
     */
    private suspend fun checkAndroidEnvironment(): DoctorItem {
        val checkCommand = top.wanxiang.app.core.model.BuiltinPluginBundles.bundles
            .firstOrNull { it.id == "android-suite" }
            ?.components?.firstOrNull { it.id == "android-core" }
            ?.checkCommand

        val installed = if (checkCommand.isNullOrBlank()) {
            false
        } else {
            val res = runCatching {
                linuxRuntime.execute(
                    ShellCommand(
                        commandLine = checkCommand,
                        timeoutMs = 8000L,
                    ),
                )
            }.getOrNull()
            res != null && res.isSuccess
        }

        return if (installed) {
            DoctorItem(
                id = "android_environment",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Android 开发环境",
                status = DoctorStatus.HEALTHY,
                summary = "JDK 17 / Android SDK / Gradle / NDK 已就绪，可构建与反编译 APK",
            )
        } else {
            DoctorItem(
                id = "android_environment",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Android 开发环境",
                status = DoctorStatus.WARNING,
                summary = "未安装 Android / Flutter / 反编译环境",
                detail = "可加入官方 QQ 群下载全量离线插件包（已集成 Android、Flutter、反编译三大环境，免在线安装），或前往插件中心在线安装。",
                fixable = false,
            )
        }
    }

    private suspend fun checkNodeRuntime(): DoctorItem {
        val nodeCheck = runCatching {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = "node --version 2>/dev/null || /opt/wanxiang/bin/node --version 2>/dev/null || /usr/bin/node --version 2>/dev/null",
                    timeoutMs = 4000L,
                ),
            )
        }.getOrNull()

        val rawVersion = nodeCheck?.stdout?.trim().orEmpty()
        if (rawVersion.isBlank()) {
            return DoctorItem(
                id = "node_runtime",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Node.js 运行时",
                status = DoctorStatus.WARNING,
                summary = "未检测到 Node.js 运行时",
                detail = "OpenClaw、Claude Code 等 AI 智能体工具强依赖 Node.js (推荐 >= v20)",
            )
        }

        val majorVersion = Regex("v?(\\d+)").find(rawVersion)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return if (majorVersion >= 20) {
            DoctorItem(
                id = "node_runtime",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Node.js 运行时",
                status = DoctorStatus.HEALTHY,
                summary = "Node.js $rawVersion (满足主流 AI 工具要求)",
            )
        } else {
            DoctorItem(
                id = "node_runtime",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Node.js 运行时",
                status = DoctorStatus.WARNING,
                summary = "Node.js $rawVersion 版本较低 (建议 >= v20)",
                detail = "新版 AI 工具可能需要更新的 JavaScript/V8 引擎特性",
            )
        }
    }
}
