package top.wanxiang.app

import android.app.Application
import top.wanxiang.app.core.common.logging.CrashReporter
import top.wanxiang.app.harness.HarnessLoop
import top.wanxiang.app.core.datastore.SettingsDataStore
import top.wanxiang.app.core.database.AgentSkillRepository
import top.wanxiang.app.core.database.McpServerRepository
import top.wanxiang.app.core.database.SkillScanRoot
import top.wanxiang.app.service.AgentForegroundService
import top.wanxiang.app.runtime.privilege.PrivilegeManager
import top.wanxiang.app.harness.browser.BrowserMcpBootstrap
import dagger.hilt.android.HiltAndroidApp
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.wanxiang.app.core.network.WanxiangCloudClient

@HiltAndroidApp
class WanXiangApplication : Application() {
    @Inject lateinit var crashReporter: CrashReporter

    // 启动性能：HarnessLoop / Room 仓储的构造图很重（DAO、DataStore、Agent 引擎全家桶），
    // eager 注入会拖慢第一帧。改为 dagger.Lazy，把实际构建推迟到首个 IO 协程内。
    @Inject lateinit var harnessLoopLazy: Lazy<HarnessLoop>
    @Inject lateinit var settingsDataStore: SettingsDataStore
    @Inject lateinit var agentSkillRepositoryLazy: Lazy<AgentSkillRepository>
    @Inject lateinit var mcpServerRepositoryLazy: Lazy<McpServerRepository>
    @Inject lateinit var pathManagerLazy: Lazy<top.wanxiang.app.runtime.RuntimePathManager>
    @Inject lateinit var privilegeManager: PrivilegeManager
    @Inject lateinit var browserMcpBootstrap: BrowserMcpBootstrap
    @Inject lateinit var wanxiangCloudClient: WanxiangCloudClient

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        configureCursorWindowSize()
        crashReporter.install()
        appScope.launch(Dispatchers.IO) {
            // 并发执行互不依赖的启动任务（crash 导出 / 特权恢复 / 浏览器 MCP bootstrap /
            // 技能入库 / MCP 预设入库），单任务失败不拖垮其他任务。
            coroutineScope {
                // 上一次未捕获崩溃会先落在应用私有目录；下次启动后复制到公共下载目录，
                // 方便测试用户直接从 Download/WanXiang/crash-reports 取出并反馈。
                launch { runCatching { crashReporter.exportPendingReports() } }
                launch { runCatching { privilegeManager.reconcilePersistedMode() } }
                // 启动进程内 MCP HTTP server（loopback 127.0.0.1:8787）供 harness / 外部 IDE 接入浏览器工具
                launch { runCatching { browserMcpBootstrap.bootstrap() } }
                launch {
                    runCatching {
                        val skillRepository = agentSkillRepositoryLazy.get()
                        skillRepository.ensureInitialized()
                        // 批量自动发现：把 rikkahub / aicode 等工具的 skills 目录整体复制到
                        // attachments/skills 或工作区 skills 目录后，重启即可全部导入；
                        // 按 resourcePath 去重，重复扫描安全。
                        val pathManager = pathManagerLazy.get()
                        val imported = skillRepository.syncFromDirectories(
                            listOf(
                                SkillScanRoot(java.io.File(pathManager.attachmentsDir, "skills"), "/attachments/skills"),
                                SkillScanRoot(java.io.File(pathManager.workspaceDir, "skills"), "/workspace/skills"),
                            )
                        )
                        if (imported.isNotEmpty()) {
                            android.util.Log.i("WanXiangApp", "Skill 目录自动发现并导入 ${imported.size} 个：${imported.joinToString { it.name }}")
                        }
                    }
                }
                launch { runCatching { mcpServerRepositoryLazy.get().ensureInitialized() } }
                // 启动静默拉取云端远程配置（版本 + 镜像列表），成功写 DataStore 缓存；
                // 失败无感，下次启动重试——绝不能让云端故障影响 App 可用性。
                launch {
                    runCatching {
                        val config = wanxiangCloudClient.getAllConfig().getOrThrow()
                        settingsDataStore.setCloudConfig(wanxiangCloudClient.encodeConfig(config))
                        android.util.Log.i("WanXiangApp", "云端配置拉取成功：${config.size} 项")
                    }.onFailure {
                        android.util.Log.w("WanXiangApp", "云端配置拉取失败（静默降级）", it)
                    }
                }
            }
            settingsDataStore.incrementLaunchCount()
            // 时序门：上面的任务全部就绪后才构造 HarnessLoop——
            //  1) MCP 预设已入库：McpManager 预热（构造时触发）能读到完整 server 列表，
            //     否则首启预热读到空表，第一轮对话缺工具；
            //  2) 浏览器 HTTP server 已监听 + 引擎已注册：内置 browser server 的自环发现
            //     不会撞"连接拒绝 → 5 分钟冷却"；
            //  3) 构造不再由前台服务监听协程在主线程提前触发（重 Hilt 图主线程构造即启动 jank，
            //     且预热时机不受控）。即使页面抢先注入触发构造，预热失败也按轮自愈，此处只是尽量保证顺序。
            val harnessLoop = harnessLoopLazy.get()
            // Agent 开始执行时拉起前台服务，保证后台存活 + 通知进度；结束后由服务发带回复框的通知。
            // 并入本协程：构造完成后才开始监听，不再单独开协程抢构造。
            launch {
                harnessLoop.running.collectLatest { running ->
                    if (running) {
                        runCatching { AgentForegroundService.start(this@WanXiangApplication) }
                    }
                }
            }
            // 进程被杀后重启时，先按 operation replay policy 修复中断检查点，再续跑已
            // 获得 autoResume 授权且仍有尝试预算的 durable task。等待审批的任务保持冻结，
            // 不可重放工具只写中断结果，绝不自动再次产生副作用。
            runCatching {
                val recovered = harnessLoop.recoverAllInterruptedSessions()
                if (recovered > 0) {
                    android.util.Log.i("WanXiangApp", "已恢复 $recovered 个被中断的 Agent 会话/任务")
                }
            }.onFailure {
                android.util.Log.w("WanXiangApp", "恢复中断会话失败", it)
            }
        }
    }

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }

    /**
     * 🌟 全局 CursorWindow 缓冲扩容：将 Android SQLite 原生游标窗口由默认 2MB 扩容至 100MB，
     * 彻底解决已有会话中超大单行记录在 Room 查询时报 `Row too big to fit into CursorWindow` 的问题。
     */
    private fun configureCursorWindowSize() {
        runCatching {
            val field = android.database.CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            field.set(null, 100 * 1024 * 1024) // 100MB
        }.onFailure {
            android.util.Log.w("WanXiangApp", "Failed to configure CursorWindow size", it)
        }
    }
}
