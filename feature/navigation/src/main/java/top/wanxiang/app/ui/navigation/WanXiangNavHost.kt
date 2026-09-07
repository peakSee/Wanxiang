package top.wanxiang.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import top.wanxiang.app.ui.chat.ChatScreen
import top.wanxiang.app.ui.chat.ChatViewModel
import top.wanxiang.app.ui.components.MainDestination
import top.wanxiang.app.ui.components.RuntimeBottomBar
import top.wanxiang.app.ui.theme.LocalLiquidGlassBackdrop
import top.wanxiang.app.ui.developer.DeveloperScreen
import top.wanxiang.app.ui.developer.AdbLogcatScreen
import top.wanxiang.app.ui.home.HomeScreen
import top.wanxiang.app.ui.settings.AgentSettingsScreen
import top.wanxiang.app.ui.settings.ModelEditorScreen
import top.wanxiang.app.ui.settings.ModelProfilesScreen
import top.wanxiang.app.ui.settings.LocalLlmScreen
import top.wanxiang.app.ui.settings.SettingsScreen
import top.wanxiang.app.ui.settings.SettingsViewModel
import top.wanxiang.app.ui.iteration.CustomIterationScreen
import top.wanxiang.app.ui.terminal.TerminalScreen
import top.wanxiang.app.ui.browser.BrowserScreen
import top.wanxiang.app.ui.workspace.CodeEditorScreen
import top.wanxiang.app.ui.workspace.WorkspaceExplorerScreen
import top.wanxiang.app.ui.workspace.WorkspaceScreen
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppDestination : NavKey

@Serializable data object HomeDestination : AppDestination
@Serializable data object AgentDestination : AppDestination
@Serializable data object WorkspaceDestination : AppDestination
@Serializable data object WorkshopSettingsDestination : AppDestination
@Serializable data object WorkshopEnvironmentSettingsDestination : AppDestination
@Serializable data object WorkshopSigningSettingsDestination : AppDestination
@Serializable data class WorkshopScriptEditorDestination(val type: String) : AppDestination
@Serializable data class WorkspaceExplorerDestination(val projectName: String, val initialPath: String = "") : AppDestination
@Serializable data class CodeEditorDestination(val projectName: String, val relativePath: String) : AppDestination
@Serializable data object SettingsDestination : AppDestination
@Serializable data object AgentEcoSettingsDestination : AppDestination
@Serializable data object LinuxEnvSettingsDestination : AppDestination
@Serializable data object AppearanceSettingsDestination : AppDestination
@Serializable data object SystemDevSettingsDestination : AppDestination
@Serializable data object AboutCommunityDestination : AppDestination
@Serializable data object SponsorDestination : AppDestination
@Serializable data object AgentSettingsDestination : AppDestination
@Serializable data object AgentSubagentSettingsDestination : AppDestination
@Serializable data object AgentSkillSettingsDestination : AppDestination
@Serializable data object McpSettingsDestination : AppDestination
@Serializable data object ToolCenterDestination : AppDestination
@Serializable data class ToolDetailDestination(val toolId: String) : AppDestination
@Serializable data object DistroManagementDestination : AppDestination
@Serializable data object StorageMountSettingsDestination : AppDestination
@Serializable data object StorageUsageDestination : AppDestination
@Serializable data object AppManagementDestination : AppDestination
@Serializable data object EnvironmentVariableSettingsDestination : AppDestination
@Serializable data object SshSettingsDestination : AppDestination
@Serializable data object FtpSettingsDestination : AppDestination
@Serializable data object ModelProfilesDestination : AppDestination
@Serializable data object LocalLlmDestination : AppDestination
@Serializable data class ModelEditorDestination(val modelId: String? = null) : AppDestination
@Serializable data object QuickPhrasesDestination : AppDestination
@Serializable data object StatsDestination : AppDestination
@Serializable data object PermissionGuideDestination : AppDestination
@Serializable data object DeveloperDestination : AppDestination
@Serializable data object AdbLogcatDestination : AppDestination
@Serializable data object CustomIterationDestination : AppDestination
@Serializable data class TerminalDestination(val toolId: String = "", val project: String = "") : AppDestination
@Serializable data object BrowserDestination : AppDestination

/**
 * 万象核心导航分发系统
 * 采用 Navigation 3，为每个 Tab 独立维护持久回退栈与状态生命周期
 */
@Composable
fun WanXiangNavHost(
    globalNavigationBus: top.wanxiang.app.core.common.navigation.GlobalNavigationBus? = null,
) {
    // Root tab entries are removed from composition when another tab becomes active. Keep the
    // conversation owner at the Activity scope so switching back to 智枢 does not rebuild Hilt's
    // graph, restore the latest session, and restart its initialization skeleton on every visit.
    val chatViewModel: ChatViewModel = hiltViewModel()
    // 浏览器引擎是全局单例：BrowserViewModel 同样挂到 Activity 作用域，
    // 使智枢内嵌浏览器面板与独立浏览器页共享同一份 tab/URL/共浏览状态。
    val browserViewModel: top.wanxiang.app.ui.browser.BrowserViewModel = hiltViewModel()
    val browserUiState by browserViewModel.uiState.collectAsStateWithLifecycle()
    // SettingsViewModel owns dozens of eagerly shared DataStore/database streams and performs
    // repository initialization. Let the settings navigation graph share the Activity-scoped
    // instance instead of constructing that whole graph once for every Navigation3 entry.
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val homeStack = rememberNavBackStack(HomeDestination)
    val agentStack = rememberNavBackStack(AgentDestination)
    val workspaceStack = rememberNavBackStack(WorkspaceDestination)
    val settingsStack = rememberNavBackStack(SettingsDestination)
    var pendingHealingTask by remember { mutableStateOf<HealingTask?>(null) }
    var selectedMain by rememberSaveable { mutableStateOf(MainDestination.Home) } // 默认进入万象开辟主界

    LaunchedEffect(globalNavigationBus) {
        globalNavigationBus?.events?.collect { target ->
            when (target) {
                top.wanxiang.app.core.common.navigation.AppNavigationTarget.AdbLogcat -> {
                    selectedMain = MainDestination.Settings
                    if (settingsStack.lastOrNull() != AdbLogcatDestination) {
                        if (settingsStack.lastOrNull() == SettingsDestination) {
                            settingsStack.add(SystemDevSettingsDestination)
                        }
                        if (settingsStack.lastOrNull() == SystemDevSettingsDestination) {
                            settingsStack.add(AdbLogcatDestination)
                        } else if (settingsStack.lastOrNull() != AdbLogcatDestination) {
                            settingsStack.add(AdbLogcatDestination)
                        }
                    }
                    globalNavigationBus.clearLatest(target)
                }
            }
        }
    }

    var lastNavTime by remember { mutableStateOf(0L) }

    val activeStack = when (selectedMain) {
        MainDestination.Home -> homeStack
        MainDestination.Agent -> agentStack
        MainDestination.Workspace -> workspaceStack
        MainDestination.Settings -> settingsStack
    }

    fun navigateMain(destination: MainDestination) {
        selectedMain = destination
    }

    fun NavBackStack<NavKey>.push(from: NavKey, destination: NavKey) {
        val now = System.currentTimeMillis()
        if (now - lastNavTime < 120L) return
        if (lastOrNull() == from && lastOrNull() != destination) {
            lastNavTime = now
            add(destination)
        }
    }

    fun popBack() {
        val now = System.currentTimeMillis()
        if (now - lastNavTime < 120L) return
        lastNavTime = now
        if (activeStack.size > 1) activeStack.removeLastOrNull()
    }

    @Composable
    fun GuardedEntry(
        destination: NavKey,
        content: @Composable () -> Unit,
    ) {
        val isActive = destination == activeStack.lastOrNull()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isActive) Modifier
                    else Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                )
        ) {
            content()
        }
    }

    val appEntryProvider: (NavKey) -> NavEntry<NavKey> = entryProvider {
            entry<HomeDestination> {
                GuardedEntry(HomeDestination) {
                    HomeScreen(
                        onNavigate = ::navigateMain,
                        onOpenTerminal = { homeStack.push(HomeDestination, TerminalDestination()) },
                        onOpenToolCenter = { homeStack.push(HomeDestination, ToolCenterDestination) },
                    )
                }
            }
            entry<AgentDestination> {
                GuardedEntry(AgentDestination) {
                    LaunchedEffect(pendingHealingTask) {
                        pendingHealingTask?.let { task ->
                            chatViewModel.startHealingTask(task.title, task.prompt)
                            pendingHealingTask = null
                        }
                    }
                    ChatScreen(
                        viewModel = chatViewModel,
                        onNavigate = ::navigateMain,
                        // 内嵌终端面板非独立导航节点，无返回目标：隐藏顶栏返回箭头，避免点击无反馈
                        terminalPane = { project -> TerminalScreen(onBack = {}, project = project, showBackButton = false) },
                        // 内嵌浏览器面板：与独立浏览器页共享 Activity 级 BrowserViewModel，
                        // 手机端左右滑动切换对话/浏览器，宽屏双栏可切"终端/浏览器"
                        browserPane = { onExit ->
                            top.wanxiang.app.ui.browser.BrowserPane(
                                viewModel = browserViewModel,
                                onExit = onExit,
                            )
                        },
                        browserActivityTick = browserUiState.activityTick,
                        browserBackPressed = { browserViewModel.handleBackImmediate() },
                        onOpenFile = { projectName, relativePath ->
                            agentStack.push(AgentDestination, CodeEditorDestination(projectName, relativePath))
                        },
                    )
                }
            }
            entry<WorkspaceDestination> {
                GuardedEntry(WorkspaceDestination) {
                    WorkspaceScreen(
                        onNavigate = ::navigateMain,
                        onOpenExplorer = { projectName -> workspaceStack.push(WorkspaceDestination, WorkspaceExplorerDestination(projectName)) },
                        onOpenTerminal = { project -> workspaceStack.push(WorkspaceDestination, TerminalDestination(project = project)) },
                        onOpenToolCenter = { workspaceStack.push(WorkspaceDestination, ToolCenterDestination) },
                        onOpenWorkshopSettings = { workspaceStack.push(WorkspaceDestination, WorkshopSettingsDestination) },
                    )
                }
            }
            entry<WorkshopSettingsDestination> {
                GuardedEntry(WorkshopSettingsDestination) {
                    top.wanxiang.app.ui.workspace.WorkshopSettingsScreen(
                        onBack = ::popBack,
                        onOpenEnvironment = { workspaceStack.push(WorkshopSettingsDestination, WorkshopEnvironmentSettingsDestination) },
                        onOpenSigning = { workspaceStack.push(WorkshopSettingsDestination, WorkshopSigningSettingsDestination) },
                        onEditScript = { type -> workspaceStack.push(WorkshopSettingsDestination, WorkshopScriptEditorDestination(type.name)) },
                    )
                }
            }
            entry<WorkshopEnvironmentSettingsDestination> {
                GuardedEntry(WorkshopEnvironmentSettingsDestination) {
                    top.wanxiang.app.ui.workspace.WorkshopEnvironmentSettingsScreen(onBack = ::popBack)
                }
            }
            entry<WorkshopSigningSettingsDestination> {
                GuardedEntry(WorkshopSigningSettingsDestination) {
                    top.wanxiang.app.ui.workspace.WorkshopSigningScreen(onBack = ::popBack)
                }
            }
            entry<WorkshopScriptEditorDestination> { destination ->
                GuardedEntry(destination) {
                    top.wanxiang.app.ui.workspace.WorkshopScriptEditorScreen(
                        type = top.wanxiang.app.ui.workspace.WorkshopScriptType.valueOf(destination.type),
                        onBack = ::popBack,
                    )
                }
            }
            entry<WorkspaceExplorerDestination> { destination ->
                GuardedEntry(destination) {
                    WorkspaceExplorerScreen(
                        projectName = destination.projectName,
                        initialPath = destination.initialPath,
                        onBack = ::popBack,
                        onOpenFile = { relativePath ->
                            workspaceStack.push(destination, CodeEditorDestination(destination.projectName, relativePath))
                        },
                        onOpenTerminal = { project ->
                            workspaceStack.push(destination, TerminalDestination(project = project))
                        },
                    )
                }
            }
            entry<CodeEditorDestination> { destination ->
                GuardedEntry(destination) {
                    CodeEditorScreen(
                        projectName = destination.projectName,
                        relativePath = destination.relativePath,
                        onBack = ::popBack,
                    )
                }
            }
            entry<SettingsDestination> {
                GuardedEntry(SettingsDestination) {
                    SettingsScreen(
                        onNavigate = ::navigateMain,
                        onOpenAgentEco = { settingsStack.push(SettingsDestination, AgentEcoSettingsDestination) },
                        onOpenLinuxEnv = { settingsStack.push(SettingsDestination, LinuxEnvSettingsDestination) },
                        onOpenAppearance = { settingsStack.push(SettingsDestination, AppearanceSettingsDestination) },
                        onOpenSystemDev = { settingsStack.push(SettingsDestination, SystemDevSettingsDestination) },
                        onOpenAboutCommunity = { settingsStack.push(SettingsDestination, AboutCommunityDestination) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<AppearanceSettingsDestination> {
                GuardedEntry(AppearanceSettingsDestination) {
                    top.wanxiang.app.ui.settings.AppearanceSettingsScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<AgentEcoSettingsDestination> {
                GuardedEntry(AgentEcoSettingsDestination) {
                    top.wanxiang.app.ui.settings.AgentEcoSettingsScreen(
                        onBack = ::popBack,
                        onOpenModelProfiles = { settingsStack.push(AgentEcoSettingsDestination, ModelProfilesDestination) },
                        onOpenLocalLlm = { settingsStack.push(AgentEcoSettingsDestination, LocalLlmDestination) },
                        onOpenToolCenter = { settingsStack.push(AgentEcoSettingsDestination, ToolCenterDestination) },
                        onOpenAgentSettings = { settingsStack.push(AgentEcoSettingsDestination, AgentSettingsDestination) },
                        onOpenSubagentSettings = { settingsStack.push(AgentEcoSettingsDestination, AgentSubagentSettingsDestination) },
                        onOpenSkillSettings = { settingsStack.push(AgentEcoSettingsDestination, AgentSkillSettingsDestination) },
                        onOpenMcpSettings = { settingsStack.push(AgentEcoSettingsDestination, McpSettingsDestination) },
                        onOpenQuickPhrases = { settingsStack.push(AgentEcoSettingsDestination, QuickPhrasesDestination) },
                        onOpenStats = { settingsStack.push(AgentEcoSettingsDestination, StatsDestination) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<LinuxEnvSettingsDestination> {
                GuardedEntry(LinuxEnvSettingsDestination) {
                    top.wanxiang.app.ui.settings.LinuxEnvironmentSettingsScreen(
                        onBack = ::popBack,
                        onOpenDistroManagement = { settingsStack.push(LinuxEnvSettingsDestination, DistroManagementDestination) },
                        onOpenStorageMounts = { settingsStack.push(LinuxEnvSettingsDestination, StorageMountSettingsDestination) },
                        onOpenStorageUsage = { settingsStack.push(LinuxEnvSettingsDestination, StorageUsageDestination) },
                        onOpenAppManagement = { settingsStack.push(LinuxEnvSettingsDestination, AppManagementDestination) },
                        onOpenEnvironmentVariables = { settingsStack.push(LinuxEnvSettingsDestination, EnvironmentVariableSettingsDestination) },
                        onOpenSshSettings = { settingsStack.push(LinuxEnvSettingsDestination, SshSettingsDestination) },
                        onOpenFtpSettings = { settingsStack.push(LinuxEnvSettingsDestination, FtpSettingsDestination) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<SystemDevSettingsDestination> {
                GuardedEntry(SystemDevSettingsDestination) {
                    top.wanxiang.app.ui.settings.SystemDevSettingsScreen(
                        onBack = ::popBack,
                        onOpenDeveloper = { settingsStack.push(SystemDevSettingsDestination, DeveloperDestination) },
                        onOpenAdbLogcat = { settingsStack.push(SystemDevSettingsDestination, AdbLogcatDestination) },
                        onOpenCustomIteration = { settingsStack.push(SystemDevSettingsDestination, CustomIterationDestination) },
                        onOpenPermissionGuide = { settingsStack.push(SystemDevSettingsDestination, PermissionGuideDestination) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<PermissionGuideDestination> {
                GuardedEntry(PermissionGuideDestination) {
                    top.wanxiang.app.ui.settings.permission.PermissionGuideScreen(
                        onBack = ::popBack,
                    )
                }
            }
            entry<AboutCommunityDestination> {
                GuardedEntry(AboutCommunityDestination) {
                    top.wanxiang.app.ui.settings.AboutCommunityScreen(
                        onBack = ::popBack,
                        onOpenSponsor = { settingsStack.push(AboutCommunityDestination, SponsorDestination) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<SponsorDestination> {
                GuardedEntry(SponsorDestination) {
                    top.wanxiang.app.ui.settings.SponsorScreen(onBack = ::popBack)
                }
            }
            entry<DistroManagementDestination> {
                GuardedEntry(DistroManagementDestination) {
                    top.wanxiang.app.ui.settings.DistroManagementScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<AgentSettingsDestination> {
                GuardedEntry(AgentSettingsDestination) {
                    AgentSettingsScreen(
                        onBack = ::popBack,
                        category = top.wanxiang.app.ui.settings.AgentSettingsCategory.EXECUTION,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<AgentSubagentSettingsDestination> {
                GuardedEntry(AgentSubagentSettingsDestination) {
                    AgentSettingsScreen(onBack = ::popBack, category = top.wanxiang.app.ui.settings.AgentSettingsCategory.SUBAGENTS, viewModel = settingsViewModel)
                }
            }
            entry<AgentSkillSettingsDestination> {
                GuardedEntry(AgentSkillSettingsDestination) {
                    AgentSettingsScreen(onBack = ::popBack, category = top.wanxiang.app.ui.settings.AgentSettingsCategory.SKILLS, viewModel = settingsViewModel)
                }
            }
            entry<McpSettingsDestination> {
                GuardedEntry(McpSettingsDestination) {
                    top.wanxiang.app.ui.settings.McpSettingsScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<ToolCenterDestination> {
                GuardedEntry(ToolCenterDestination) {
                    top.wanxiang.app.ui.settings.ToolCenterScreen(
                        onBack = ::popBack,
                        onLaunchPty = { toolId -> activeStack.push(ToolCenterDestination, TerminalDestination(toolId = toolId)) },
                        onOpenToolDetail = { toolId -> activeStack.push(ToolCenterDestination, ToolDetailDestination(toolId = toolId)) },
                        onStartAiHealing = { toolId, toolName, logs ->
                            val prompt = top.wanxiang.app.ui.settings.ToolSelfHealingHelper.buildHealingPrompt(toolId, toolName, logs)
                            pendingHealingTask = HealingTask("🔧 自愈: $toolName", prompt)
                            selectedMain = MainDestination.Agent
                        },
                    )
                }
            }
            entry<ToolDetailDestination> { destination ->
                GuardedEntry(destination) {
                    top.wanxiang.app.ui.settings.ToolDetailScreen(
                        toolId = destination.toolId,
                        onBack = ::popBack,
                        onLaunchTerminal = { toolId -> activeStack.push(destination, TerminalDestination(toolId = toolId)) },
                        onStartAiHealing = { toolId, toolName, logs ->
                            val prompt = top.wanxiang.app.ui.settings.ToolSelfHealingHelper.buildHealingPrompt(toolId, toolName, logs)
                            pendingHealingTask = HealingTask("🔧 自愈: $toolName", prompt)
                            selectedMain = MainDestination.Agent
                        },
                    )
                }
            }
            entry<StorageMountSettingsDestination> {
                GuardedEntry(StorageMountSettingsDestination) {
                    top.wanxiang.app.ui.settings.StorageMountSettingsScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<StorageUsageDestination> {
                GuardedEntry(StorageUsageDestination) {
                    top.wanxiang.app.ui.settings.StorageUsageScreen(onBack = ::popBack)
                }
            }
            entry<AppManagementDestination> {
                GuardedEntry(AppManagementDestination) {
                    top.wanxiang.app.ui.settings.AppManagementScreen(onBack = ::popBack)
                }
            }
            entry<EnvironmentVariableSettingsDestination> {
                GuardedEntry(EnvironmentVariableSettingsDestination) {
                    top.wanxiang.app.ui.settings.EnvironmentVariableSettingsScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<SshSettingsDestination> {
                GuardedEntry(SshSettingsDestination) {
                    top.wanxiang.app.ui.settings.SshSettingsScreen(onBack = ::popBack)
                }
            }
            entry<FtpSettingsDestination> {
                GuardedEntry(FtpSettingsDestination) {
                    top.wanxiang.app.ui.settings.FtpSettingsScreen(onBack = ::popBack)
                }
            }
            entry<ModelProfilesDestination> {
                GuardedEntry(ModelProfilesDestination) {
                    ModelProfilesScreen(
                        onBack = ::popBack,
                        onCreate = { settingsStack.push(ModelProfilesDestination, ModelEditorDestination()) },
                        onEdit = { modelId -> settingsStack.push(ModelProfilesDestination, ModelEditorDestination(modelId)) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<LocalLlmDestination> {
                GuardedEntry(LocalLlmDestination) {
                    LocalLlmScreen(
                        onBack = ::popBack,
                        onOpenEngine = { settingsStack.push(LocalLlmDestination, ToolDetailDestination("llama-cpp")) },
                    )
                }
            }
            entry<ModelEditorDestination> { destination ->
                GuardedEntry(destination) {
                    ModelEditorScreen(
                        modelId = destination.modelId,
                        onBack = ::popBack,
                        onSaved = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<QuickPhrasesDestination> {
                GuardedEntry(QuickPhrasesDestination) {
                    top.wanxiang.app.ui.settings.QuickPhrasesScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<StatsDestination> {
                GuardedEntry(StatsDestination) {
                    top.wanxiang.app.ui.settings.stats.StatsScreen(onBack = ::popBack)
                }
            }
            entry<DeveloperDestination> {
                GuardedEntry(DeveloperDestination) {
                    DeveloperScreen(onBack = ::popBack)
                }
            }
            entry<AdbLogcatDestination> {
                GuardedEntry(AdbLogcatDestination) {
                    AdbLogcatScreen(onBack = ::popBack)
                }
            }
            entry<CustomIterationDestination> {
                GuardedEntry(CustomIterationDestination) {
                    CustomIterationScreen(
                        onBack = ::popBack,
                        onNavigateToChat = { prompt ->
                            pendingHealingTask = HealingTask("🚀 自定义迭代", prompt)
                            selectedMain = MainDestination.Agent
                        },
                    )
                }
            }
            entry<TerminalDestination> { destination ->
                GuardedEntry(destination) {
                    TerminalScreen(onBack = ::popBack, project = destination.project)
                }
            }
            entry<BrowserDestination> {
                GuardedEntry(BrowserDestination) {
                    BrowserScreen(onBack = ::popBack, viewModel = browserViewModel)
                }
            }
    }

    val density = LocalDensity.current
    val liquidGlassBackdrop = LocalLiquidGlassBackdrop.current
    val showLiquidBottomBar = liquidGlassBackdrop != null &&
        activeStack.size == 1 &&
        WindowInsets.ime.getBottom(density) == 0
    Box(Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = activeStack,
            modifier = Modifier.fillMaxSize(),
            onBack = ::popBack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = appEntryProvider,
        )
        if (liquidGlassBackdrop != null) {
            // Keep the expensive glass layers composed while a secondary destination is open.
            // Recreating both backdrop render layers in the same frame as the root screen was
            // the main source of pop-navigation stalls. Moving the retained bar off-screen also
            // prevents its invisible click targets from intercepting the secondary page.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .zIndex(if (showLiquidBottomBar) 1f else -1f)
                    .graphicsLayer {
                        alpha = if (showLiquidBottomBar) 1f else 0f
                        translationY = if (showLiquidBottomBar) 0f else size.height
                    },
            ) {
                RuntimeBottomBar(
                    selected = selectedMain,
                    onNavigate = ::navigateMain,
                )
            }
        }
    }
}

private data class HealingTask(
    val title: String,
    val prompt: String,
)
