package top.wanxiang.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.PaddingValues
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.harness.workflow.ProactiveWorkflowSuggestion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import top.wanxiang.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import top.wanxiang.app.feature.chat.R
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.wanxiang.app.core.database.AiModelEntity
import top.wanxiang.app.core.model.ApprovalMode
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.harness.UserMessage
import top.wanxiang.app.runtime.WorkspaceProject
import top.wanxiang.app.ui.components.MainDestination
import top.wanxiang.app.ui.components.RuntimeBottomBar
import top.wanxiang.app.ui.components.liquidGlassContent
import top.wanxiang.app.ui.theme.LocalLiquidGlassBackdrop
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyListState
import top.wanxiang.app.core.model.QuickPhrase
import top.wanxiang.app.core.database.AgentApprovalRequestEntity
import top.wanxiang.app.core.database.AgentPlanEntity
import top.wanxiang.app.harness.QueuedPrompt
import top.wanxiang.app.harness.compaction.CompactionSnapshot
import top.wanxiang.app.harness.mcp.McpWorkspaceRecommender
import top.wanxiang.app.runtime.ProjectType
import top.wanxiang.app.ui.chat.ChatRenderItem
import top.wanxiang.app.ui.chat.projectChatMessages

// 悬浮玻璃底栏实际占高 = 上下 8dp padding + 64dp 条体 = 80dp，留 2dp 微缝防贴死
private val AgentBottomBarHeight = 82.dp

// 浏览器 URL 栏贴合版：与底栏总占位一致（8 + 64 + 8 = 80dp），零缝隙直接贴在底栏上方
private val BrowserBottomBarHeight = 80.dp

@Composable
private fun chatBottomInsets(bottomBarHeight: Dp): WindowInsets {
    val bottomBarInsets = WindowInsets.navigationBars.add(WindowInsets(bottom = bottomBarHeight))
    return bottomBarInsets.union(WindowInsets.ime)
}

/**
 * 万象 · 智枢对话界面 (WanXiang Agent)
 * 智能结对编程、工具自动化调用与代码生成
 */
@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)
@Composable
fun ChatScreen(
    onNavigate: (MainDestination) -> Unit,
    onOpenFile: ((projectName: String, relativePath: String) -> Unit)? = null,
    terminalPane: (@Composable (project: String) -> Unit)? = null,
    browserPane: (@Composable (onExit: (() -> Unit)?) -> Unit)? = null,
    browserActivityTick: Long = 0L,
    browserBackPressed: (() -> Boolean)? = null,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val pendingAttachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val workflowSuggestions by viewModel.workflowSuggestions.collectAsStateWithLifecycle()
    val attachmentExtracting by viewModel.attachmentExtracting.collectAsStateWithLifecycle()
    val attachmentExtractedTexts by viewModel.extractedTexts.collectAsStateWithLifecycle()
    val attachmentStatuses: Map<String, top.wanxiang.app.ui.chat.AttachmentStatus> = buildMap {
        pendingAttachments.forEach { att ->
            when {
                attachmentExtracting[att.id] == true -> put(att.id, top.wanxiang.app.ui.chat.AttachmentStatus.Extracting)
                attachmentExtractedTexts.containsKey(att.id) -> {
                    val text = attachmentExtractedTexts[att.id]
                    put(
                        att.id,
                        if (text.isNullOrBlank()) top.wanxiang.app.ui.chat.AttachmentStatus.NoText
                        else top.wanxiang.app.ui.chat.AttachmentStatus.Extracted(
                            chars = text.length,
                            truncated = text.contains("[文档过长"),
                        ),
                    )
                }
            }
        }
    }
    val status by viewModel.status.collectAsStateWithLifecycle()
    val thinkingLive by viewModel.thinkingLive.collectAsStateWithLifecycle()
    val thinkingExpanded by viewModel.thinkingExpanded.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val providerModelIds by viewModel.providerModelIds.collectAsStateWithLifecycle()
    val discoveringProviderModels by viewModel.discoveringProviderModels.collectAsStateWithLifecycle()
    val providerModelDiscoveryError by viewModel.providerModelDiscoveryError.collectAsStateWithLifecycle()
    val modelPickerProfileId by viewModel.modelPickerProfileId.collectAsStateWithLifecycle()
    val workspace by viewModel.workspace.collectAsStateWithLifecycle()
    val mcpRecommendations by viewModel.mcpRecommendations.collectAsStateWithLifecycle()
    val sessionProjectType by viewModel.projectType.collectAsStateWithLifecycle()
    val matchingCommands by viewModel.matchingCommands.collectAsStateWithLifecycle()
    val matchingMentions by viewModel.matchingMentions.collectAsStateWithLifecycle()
    val attachedMentions by viewModel.attachedMentions.collectAsStateWithLifecycle()
    val queuedPrompts by viewModel.queuedPrompts.collectAsStateWithLifecycle()
    val sendMode by viewModel.sendMode.collectAsStateWithLifecycle()
    val branches by viewModel.branches.collectAsStateWithLifecycle()
    val runtimeEvents by viewModel.runtimeEvents.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val sessionRunStates by viewModel.sessionRunStates.collectAsStateWithLifecycle()
    val activeDistroId by viewModel.activeDistroId.collectAsStateWithLifecycle()
    val installedDistros by viewModel.installedDistros.collectAsStateWithLifecycle()
    val allSkills by viewModel.allSkills.collectAsStateWithLifecycle()
    val pinnedCapabilities by viewModel.pinnedCapabilities.collectAsStateWithLifecycle()
    val pinnedMentionIds by viewModel.pinnedMentionIds.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val scratchpads by viewModel.scratchpads.collectAsStateWithLifecycle()
    val mcpServers by viewModel.mcpServers.collectAsStateWithLifecycle()
    val mcpConnectionStates by viewModel.mcpConnectionStates.collectAsStateWithLifecycle()
    val initializing by viewModel.initializing.collectAsStateWithLifecycle()
    val pendingApprovals by viewModel.pendingApprovals.collectAsStateWithLifecycle()
    val activePlan by viewModel.activePlan.collectAsStateWithLifecycle()
    val activeCompaction by viewModel.activeCompaction.collectAsStateWithLifecycle()
    val contextUsage by viewModel.contextUsage.collectAsStateWithLifecycle()
    val quickPhrases by viewModel.quickPhrases.collectAsStateWithLifecycle()
    val onboardingPrivilege by viewModel.privilegeOnboarding.collectAsStateWithLifecycle()
    val attachmentsProcessing by viewModel.attachmentsProcessing.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val subagentResult by viewModel.subagentResult.collectAsStateWithLifecycle()
    val gitPanelState by viewModel.gitPanelState.collectAsStateWithLifecycle()
    val gitCredentials by viewModel.gitCredentials.collectAsStateWithLifecycle()
    val matchedCredId by viewModel.matchedCredentialId.collectAsStateWithLifecycle()
    val gitUncommittedCount = gitPanelState.let { s -> s.staged.size + s.unstaged.size + s.untracked.size }
    val gitAiCommit by viewModel.aiCommit.collectAsStateWithLifecycle()
    val gitCredHealth by viewModel.credHealth.collectAsStateWithLifecycle()
    val gitRepoList by viewModel.repoList.collectAsStateWithLifecycle()
    val gitProgress by viewModel.gitProgress.collectAsStateWithLifecycle()
    val gitOpMsg by viewModel.gitOpMessage.collectAsStateWithLifecycle()
    val recentCloneUrls by viewModel.recentCloneUrls.collectAsStateWithLifecycle()

    // 弹窗开关与编辑目标：用 rememberSaveable 保存，旋转 / 进程重建后不丢失
    var showSessions by rememberSaveable { mutableStateOf(false) }
    var showNewSession by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showApprovalModes by rememberSaveable { mutableStateOf(false) }
    var showSkillsMcpSheet by rememberSaveable { mutableStateOf(false) }
    var showBranches by rememberSaveable { mutableStateOf(false) }
    var showRuntimeTimeline by rememberSaveable { mutableStateOf(false) }
    var showMemorySheet by rememberSaveable { mutableStateOf(false) }
    var showFloatingPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var branchFromMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var showGitPanel by rememberSaveable { mutableStateOf(false) }
    // 进入 chat 时异步刷一次 git 状态（顶栏徽标要显示未提交数），不阻塞首帧
    LaunchedEffect(Unit) { viewModel.refreshGitStatus() }
    // 编辑目标消息只保存 id，避免把不可保存的实体放进状态保存器
    var editTargetMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    val editTargetMessage = remember(messages, editTargetMessageId) {
        messages.filterIsInstance<UserMessage>().firstOrNull { it.id == editTargetMessageId }
    }


    val listState = rememberLazyListState()
    val context = LocalContext.current

    val currentSession = remember(sessions, currentSessionId) { sessions.firstOrNull { it.id == currentSessionId } }
    val boundModelProfile = remember(models, currentSession?.modelId) {
        currentSession?.modelId?.let { id -> models.firstOrNull { it.id == id } }
    }
    val activeModel = remember(models, boundModelProfile, currentSession?.modelVariant) {
        val profile = boundModelProfile ?: models.firstOrNull { it.isActive }
        profile?.copy(
            model = currentSession?.modelVariant
                ?.takeIf { boundModelProfile != null && it.isNotBlank() }
                ?: profile.model.substringBefore(',').trim(),
        )
    }
    val currentApprovalMode = remember(currentSession?.approvalMode) { ApprovalMode.fromId(currentSession?.approvalMode) }
    val activeWorkspaceProject = remember(workspace, workspaces) {
        workspaces.firstOrNull { it.linuxPath == workspace }
    }
    val effectiveWorkspaceProject = remember(activeWorkspaceProject, sessionProjectType) {
        val override = when (sessionProjectType.uppercase()) {
            "ANDROID" -> ProjectType.ANDROID
            "FLUTTER" -> ProjectType.FLUTTER
            "REVERSE" -> ProjectType.REVERSE
            "GENERAL" -> ProjectType.GENERAL
            else -> null
        }
        activeWorkspaceProject?.let { project -> override?.let { project.copy(projectType = it) } ?: project }
    }
    val distroDisplayName = remember(activeDistroId) {
        runCatching { top.wanxiang.app.runtime.DistributionCatalog.require(activeDistroId).displayName }
            .getOrDefault(activeDistroId)
    }

    val toolResults = remember(messages) {
        messages.filterIsInstance<ToolResult>().associateBy { it.toolCallId }
    }
    val lastAssistantMessageId = remember(messages) {
        messages.filterIsInstance<AssistantText>().lastOrNull()?.id
    }
    // 两者语义不同（实时思考高亮 vs 重新生成定位），但取值一致，复用同一计算结果
    val liveThinkingMessageId = lastAssistantMessageId
    val currentBranch = remember(branches) { branches.firstOrNull { it.isCurrent } }

    val isImeVisible = WindowInsets.isImeVisible
    val coroutineScope = rememberCoroutineScope()

    // Navigation3 removes inactive tab content from composition. Persist this marker with the
    // entry so returning to 智枢 does not perform a second, redundant scrollToItem during the
    // tab transition.
    var initialPositionedSessionKey by rememberSaveable { mutableStateOf<String?>(null) }
    val currentSessionKey = remember(messages) { messages.firstOrNull()?.id ?: "" }

    val lastMessageSignature = remember(messages) {
        val last = messages.lastOrNull()
        when (last) {
            is AssistantText -> "${last.id}:${last.reasoning?.length ?: 0}:${last.text.length}"
            is ToolCall -> "${last.id}:${last.args.hashCode()}"
            is ToolResult -> "${last.id}:${last.output.length}"
            is UserMessage -> "${last.id}:${last.text.length}"
            else -> "${messages.size}"
        }
    }

    // 🌟 1. 消息发送与流式输出跟随滚动
    // 仅当用户本来就停在底部附近时才跟随输出滚动；上滑阅读历史时不打扰，
    // 重新滑回底部（或新发消息）后恢复自动跟随。
    LaunchedEffect(messages.size, lastMessageSignature) {
        if (messages.isNotEmpty()) {
            delay(30)
            val totalCount = listState.layoutInfo.totalItemsCount
            if (totalCount > 0) {
                if (initialPositionedSessionKey != currentSessionKey) {
                    initialPositionedSessionKey = currentSessionKey
                    listState.scrollToItem(totalCount - 1)
                } else {
                    val layoutInfo = listState.layoutInfo
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                    val isNearBottom = lastVisible == null || lastVisible.index >= totalCount - 3
                    if (isNearBottom) {
                        listState.scrollToItem(totalCount - 1)
                    }
                }
            }
        }
    }

    // 🌟 2. 软键盘弹起时自动平滑滚动定位到最后一条消息
    LaunchedEffect(isImeVisible) {
        if (isImeVisible && listState.layoutInfo.totalItemsCount > 0) {
            delay(80)
            val totalCount = listState.layoutInfo.totalItemsCount
            listState.animateScrollToItem(totalCount - 1)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val appContext = LocalContext.current.applicationContext
    // Git 操作结果（克隆/拉取/推送）Snackbar 反馈：Busy 不显示 snackbar（面板已显 loading），
    // Ok/Error 显示 + 消费回 Idle 避免重播
    val gitOp by viewModel.gitOpMessage.collectAsStateWithLifecycle()
    LaunchedEffect(gitOp, showGitPanel) {
        // Git 面板打开时，反馈由面板内嵌 Snackbar 呈现（用户操作页），聊天层不抢。
        if (showGitPanel) return@LaunchedEffect
        val snapshot = gitOp
        when (snapshot) {
            is top.wanxiang.app.ui.chat.GitOpMessage.Ok -> {
                val actionLabel = when (snapshot.action) {
                    is top.wanxiang.app.ui.chat.GitOpAction.SwitchWorkspaceTo -> "切过去"
                    is top.wanxiang.app.ui.chat.GitOpAction.RetryWithClean -> "清空再试"
                    is top.wanxiang.app.ui.chat.GitOpAction.UndoRename -> "撤销"
                    is top.wanxiang.app.ui.chat.GitOpAction.StashPop -> "还原 stash"
                    is top.wanxiang.app.ui.chat.GitOpAction.CopyError -> "复制"
                    is top.wanxiang.app.ui.chat.GitOpAction.RetrySame -> "重试"
                    null -> null
                }
                val result = if (actionLabel != null) {
                    snackbarHostState.showSnackbar(snapshot.message, actionLabel = actionLabel, duration = SnackbarDuration.Long)
                } else {
                    snackbarHostState.showSnackbar(snapshot.message, duration = SnackbarDuration.Long)
                }
                if (result == SnackbarResult.ActionPerformed) {
                    when (snapshot.action) {
                        is top.wanxiang.app.ui.chat.GitOpAction.SwitchWorkspaceTo ->
                            viewModel.switchWorkspace(snapshot.action.path)
                        is top.wanxiang.app.ui.chat.GitOpAction.RetryWithClean ->
                            viewModel.retryCloneAfterClean(snapshot.action.url, snapshot.action.targetDir)
                        is top.wanxiang.app.ui.chat.GitOpAction.UndoRename ->
                            viewModel.gitRenameBranch(snapshot.action.newName, snapshot.action.oldName)
                        is top.wanxiang.app.ui.chat.GitOpAction.StashPop -> viewModel.gitStashPop()
                        else -> {}
                    }
                }
                viewModel.consumeGitOpMessage()
            }
            is top.wanxiang.app.ui.chat.GitOpMessage.Error -> {
                val actionLabel = when (snapshot.action) {
                    is top.wanxiang.app.ui.chat.GitOpAction.RetryWithClean -> "清空再试"
                    is top.wanxiang.app.ui.chat.GitOpAction.SwitchWorkspaceTo -> "切过去"
                    is top.wanxiang.app.ui.chat.GitOpAction.UndoRename -> "撤销"
                    is top.wanxiang.app.ui.chat.GitOpAction.StashPop -> "还原 stash"
                    is top.wanxiang.app.ui.chat.GitOpAction.CopyError -> "复制错误"
                    is top.wanxiang.app.ui.chat.GitOpAction.RetrySame -> null
                    null -> "复制错误"   // 默认给复制，用户可粘出来问
                }
                val result = if (actionLabel != null) {
                    snackbarHostState.showSnackbar(snapshot.message, actionLabel = actionLabel, duration = SnackbarDuration.Long)
                } else {
                    snackbarHostState.showSnackbar(snapshot.message, duration = SnackbarDuration.Long)
                }
                if (result == SnackbarResult.ActionPerformed) {
                    when (snapshot.action) {
                        is top.wanxiang.app.ui.chat.GitOpAction.RetryWithClean ->
                            viewModel.retryCloneAfterClean(snapshot.action.url, snapshot.action.targetDir)
                        is top.wanxiang.app.ui.chat.GitOpAction.UndoRename ->
                            viewModel.gitRenameBranch(snapshot.action.newName, snapshot.action.oldName)
                        is top.wanxiang.app.ui.chat.GitOpAction.StashPop -> viewModel.gitStashPop()
                        else -> { /* CopyError 走下面 */ }
                    }
                    // 无 action 或 CopyError → 复制错误文本到剪贴板（用户能粘出来问）
                    if (snapshot.action == null || snapshot.action is top.wanxiang.app.ui.chat.GitOpAction.CopyError) {
                        val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("git-error", snapshot.message))
                    }
                }
                viewModel.consumeGitOpMessage()
            }
            else -> {}
        }
    }
    val grantLabel = stringResource(R.string.chat_snackbar_grant)
    val gotItLabel = stringResource(R.string.chat_snackbar_got_it)
    LaunchedEffect(Unit) {
        viewModel.permissionRequests.collect { req ->
            val actionLabel = when (req.permission) {
                "WRITE_SETTINGS" -> grantLabel
                else -> gotItLabel
            }
            val result = snackbarHostState.showSnackbar(
                message = req.reason,
                actionLabel = actionLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed && req.permission == "WRITE_SETTINGS") {
                runCatching {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${appContext.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                }
            }
        }
    }

    // VM 层轻量通知（附件处理失败 / 模型档案已存在 / 新会话已创建等）以 Toast 上抛
    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(appContext, it, Toast.LENGTH_SHORT).show()
            viewModel.clearNotice()
        }
    }

    // 切换会话后自动关闭遗留的子智能体成果抽屉，避免跨会话展示旧 lane
    LaunchedEffect(currentSessionId) {
        val opened = subagentResult
        if (opened != null && opened.sessionId != currentSessionId) viewModel.closeSubagentResult()
    }

    // 模型·权限·主线·轮次 工具条：单栏浏览器分页模式下随对话页一起滑走（浏览器页全屏），
    // 双栏/纯对话模式固定在顶部。提出为局部 lambda 避免三处重复传参。
    // onOpenBrowser 非空时工具条末尾追加"浏览器"入口（agent 有新动态时高亮）。
    val chatTopBar: @Composable (onOpenBrowser: (() -> Unit)?, browserHighlight: Boolean) -> Unit =
        { onOpenBrowser, browserHighlight ->
            Column(Modifier.fillMaxWidth()) {
                ChatTopBar(
                    workspace = workspace,
                    distroDisplayName = distroDisplayName,
                    activeModel = activeModel,
                    approvalMode = currentApprovalMode,
                    currentBranch = currentBranch,
                    runtimeEvents = runtimeEvents,
                    running = running,
                    onShowFloatingPermissionDialog = { showFloatingPermissionDialog = true },
                    onOpenSessions = { showSessions = true },
                    onOpenModels = { showModels = true },
                    onOpenApprovalModes = { showApprovalModes = true },
                    onOpenBranches = { showBranches = true },
                    onOpenRuntime = { showRuntimeTimeline = true },
                    onOpenBrowser = onOpenBrowser,
                    browserHighlight = browserHighlight,
                    onOpenGit = { viewModel.refreshGitStatus(); showGitPanel = true },
                    gitUncommittedCount = gitUncommittedCount,
                )
                // Git 流式进度横幅（clone/pull/push 时可见）
                gitProgress?.let { p ->
                    GitProgressBanner(progress = p, onCancel = { viewModel.cancelGitOp() })
                }
            }
        }

    // 模型回复里的 /workspace、/attachments 等沙箱路径在此翻译为宿主真实文件，
    // 否则 Coil 会按 Android 根文件系统路径加载而必然失败。
    CompositionLocalProvider(LocalSandboxHostRoots provides viewModel.sandboxHostRoots) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val isDualPane = maxWidth >= 720.dp

            val knownMentionNames = remember(allSkills, mcpServers) {
                (allSkills.filter { it.isEnabled }.flatMap { listOf(it.name, it.id) } +
                    mcpServers.filter { it.isEnabled }.flatMap { listOf(it.name, it.id) })
                    .filter { it.isNotBlank() }
                    .distinct()
            }

            val bottomInsets = chatBottomInsets(AgentBottomBarHeight)

            // 对话主内容：双栏左栏与单栏整页共用（仅 modifier 不同）
            val chatContent: @Composable (Modifier) -> Unit = { paneModifier ->
                ChatPaneContent(
                    onboardingPrivilege = onboardingPrivilege,
                    modifier = paneModifier,
                    messages = messages,
                    listState = listState,
                    running = running,
                    status = status,
                    thinkingExpanded = thinkingExpanded,
                    thinkingLive = thinkingLive,
                    liveThinkingMessageId = liveThinkingMessageId,
                    toolResults = toolResults,
                    workspace = workspace,
                    workspaceProject = effectiveWorkspaceProject,
                    onOpenFile = onOpenFile,
                    onEditMessage = { editTargetMessageId = it.id },
                    onDeleteMessage = viewModel::deleteMessage,
                    onRewindMessage = viewModel::rewindToMessage,
                    error = error,
                    onClearError = viewModel::clearError,
                    matchingCommands = matchingCommands,
                    matchingMentions = matchingMentions,
                    attachedMentions = attachedMentions,
                    knownMentionNames = knownMentionNames,
                    queuedPrompts = queuedPrompts,
                    onEditQueuedPrompt = viewModel::editQueuedPrompt,
                    onRemoveQueuedPrompt = viewModel::removeQueuedPrompt,
                    onConvertToSteer = viewModel::convertQueuedPromptToSteer,
                    sendMode = sendMode,
                    input = input,
                    onInputChanged = viewModel::onInputChanged,
                    onApplyCommand = viewModel::applySlashCommand,
                    onApplyMention = viewModel::applyMention,
                    onRemoveMention = viewModel::removeMention,
                    attachments = pendingAttachments,
                    attachmentStatuses = attachmentStatuses,
                    attachmentsProcessing = attachmentsProcessing,
                    onAttachmentsPicked = viewModel::onAttachmentsPicked,
                    onRemoveAttachment = viewModel::removeAttachment,
                    workflowSuggestions = workflowSuggestions,
                    onLaunchWorkflowSuggestion = viewModel::launchWorkflowSuggestion,
                    onDismissWorkflowSuggestion = viewModel::dismissWorkflowSuggestion,
                    onSend = viewModel::sendFromComposer,
                    onStop = viewModel::stop,
                    lastAssistantMessageId = lastAssistantMessageId,
                    onRegenerate = viewModel::regenerateLast,
                    onCreateBranch = { branchFromMessageId = it },
                    onRetryTool = viewModel::retryToolCall,
                    initializing = initializing,
                    pinnedCapabilities = pinnedCapabilities,
                    onOpenSkillsMcp = { showSkillsMcpSheet = true },
                    onUnpinMention = viewModel::unpinMention,
                    activePlan = activePlan,
                    pendingApprovals = pendingApprovals,
                    onResolveApproval = viewModel::resolveApproval,
                    contextUsage = contextUsage,
                    activeModel = activeModel,
                    onUpdateReasoning = viewModel::updateActiveModelReasoning,
                    quickPhrases = quickPhrases,
                    onSelectPhrase = viewModel::applyQuickPhrase,
                    activeCompaction = activeCompaction,
                    mcpRecommendations = mcpRecommendations,
                    onEnableMcpRecommendation = viewModel::enableMcpRecommendation,
                    onDismissMcpRecommendation = viewModel::dismissMcpRecommendation,
                    onViewSubagentLanes = { showBranches = true },
                    subagentBranches = branches,
                    onOpenSubagentBranch = viewModel::openSubagentResult,
                )
            }

            if (isDualPane && terminalPane != null) {
                Column(modifier = Modifier.fillMaxSize()) {
                    chatTopBar(null, false)
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .liquidGlassContent()
                            .padding(horizontal = 12.dp)
                            .windowInsetsPadding(bottomInsets),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                    // 左栏：Agent 对话与指令区
                    chatContent(
                        Modifier
                            .weight(0.48f)
                            .fillMaxHeight()
                    )

                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight().padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )

                    // 右栏：实时演武终端 (Terminal) / 浏览器联动视窗（可切换）
                    var rightPaneBrowser by rememberSaveable { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .weight(0.52f)
                            .fillMaxHeight()
                            .padding(top = 4.dp, bottom = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                RoundedCornerShape(14.dp),
                            ),
                    ) {
                        if (browserPane != null) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(onClick = { rightPaneBrowser = false }) {
                                        Text(
                                            "终端",
                                            fontWeight = if (rightPaneBrowser) FontWeight.Normal else FontWeight.Bold,
                                            color = if (rightPaneBrowser) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                        )
                                    }
                                    TextButton(onClick = { rightPaneBrowser = true }) {
                                        Text(
                                            "浏览器",
                                            fontWeight = if (rightPaneBrowser) FontWeight.Bold else FontWeight.Normal,
                                            color = if (rightPaneBrowser) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                }
                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    if (rightPaneBrowser) {
                                        browserPane(null)
                                    } else {
                                        terminalPane(workspace)
                                    }
                                }
                            }
                        } else {
                            terminalPane(workspace)
                        }
                    }
                    }
                }
            } else if (browserPane != null) {
                // 单栏：智枢对话 ↔ 浏览器 左右滑动切换，一边看 harness 操作一边看浏览器变化
                val pagerState = rememberPagerState(initialPage = 0) { 2 }
                var lastSeenBrowserTick by remember { mutableStateOf(0L) }
                LaunchedEffect(pagerState.currentPage, browserActivityTick) {
                    if (pagerState.currentPage == 1) lastSeenBrowserTick = browserActivityTick
                }
                // 浏览器页系统返回：优先回退 WebView 历史，耗尽后切回对话页
                BackHandler(enabled = pagerState.currentPage == 1) {
                    if (browserBackPressed?.invoke() != true) {
                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    HorizontalPager(state = pagerState) { page ->
                        if (page == 0) {
                            // 对话页：顶部工具条（模型·权限·主线·轮次·浏览器）属于对话的一部分，随页滑走
                            Column(modifier = Modifier.fillMaxSize()) {
                                chatTopBar(
                                    {
                                        coroutineScope.launch { pagerState.animateScrollToPage(1) }
                                    },
                                    browserActivityTick > lastSeenBrowserTick,
                                )
                                chatContent(
                                    Modifier
                                        .fillMaxSize()
                                        .liquidGlassContent()
                                        .padding(horizontal = 12.dp)
                                        .windowInsetsPadding(bottomInsets)
                                )
                            }
                        } else {
                            // 浏览器页：全屏，仅保留自己的极薄状态栏（末尾带"返回对话"）+ URL 栏；
                            // 底部用贴合版 insets（无呼吸空间），URL 栏直接贴在底部导航栏上方
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                                    .windowInsetsPadding(chatBottomInsets(BrowserBottomBarHeight))
                            ) {
                                browserPane(
                                    {
                                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    chatTopBar(null, false)
                    chatContent(
                        Modifier
                            .fillMaxSize()
                            .liquidGlassContent()
                            .padding(horizontal = 12.dp)
                            .windowInsetsPadding(bottomInsets)
                    )
                }
            }

            if (LocalLiquidGlassBackdrop.current == null) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    RuntimeBottomBar(MainDestination.Agent, onNavigate)
                }
            }
        }
    }

    // 编辑并重新发送对话框
    editTargetMessage?.let { target ->
        EditAndResendDialog(
            originalText = target.text,
            onDismiss = { editTargetMessageId = null },
            onConfirm = { newText ->
                viewModel.editAndResend(target.id, newText)
                editTargetMessageId = null
            },
        )
    }

    // 智枢悬浮窗权限申请提示弹窗
    if (showFloatingPermissionDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showFloatingPermissionDialog = false },
            title = {
                Text(
                    stringResource(R.string.chat_floating_permission_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    stringResource(R.string.chat_floating_permission_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFloatingPermissionDialog = false
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                        context.startActivity(intent)
                    },
                ) {
                    Text(stringResource(R.string.chat_floating_permission_grant))
                }
            },
            dismissButton = {
                top.wanxiang.app.ui.components.RuntimeTextButton(
                    onClick = { showFloatingPermissionDialog = false },
                ) {
                    Text(stringResource(R.string.chat_floating_permission_cancel))
                }
            },
        )
    }

    if (showGitPanel) {
        GitPanel(
            state = gitPanelState,
            onRefresh = viewModel::refreshGitStatus,
            onDismiss = { showGitPanel = false },
            onFileDiff = viewModel::loadFileDiff,
            onClearDiff = viewModel::clearDiff,
            onStage = viewModel::gitStage,
            onUnstage = viewModel::gitUnstage,
            onStageAll = viewModel::gitStageAll,
            onUnstageAll = viewModel::gitUnstageAll,
            onCommit = viewModel::gitCommit,
            onPull = viewModel::gitPull,
            onPush = viewModel::gitPush,
            onCheckout = viewModel::gitCheckout,
            onCreateBranch = viewModel::gitCreateBranch,
            onDeleteBranch = viewModel::gitDeleteBranch,
            onRenameBranch = viewModel::gitRenameBranch,
            onDeleteRemoteBranch = viewModel::gitDeleteRemoteBranch,
            onInitRepo = viewModel::gitInit,
            onClone = viewModel::gitClone,
            credentials = gitCredentials,
            matchedCredentialId = matchedCredId,
            onAddCredential = viewModel::addGitCredential,
            onDeleteCredential = viewModel::deleteGitCredential,
            onProbeCredential = viewModel::probeCredential,
            onPullNow = viewModel::gitPullNow,
            onDismissPullDirty = viewModel::dismissPullDirtyConfirm,
            onConfirmCheckoutDirty = viewModel::confirmCheckoutDirty,
            onDismissCheckoutConfirm = viewModel::dismissCheckoutConfirm,
            onStash = { viewModel.gitStash() },
            onStashPop = viewModel::gitStashPop,
            aiCommit = gitAiCommit,
            onAiGenerate = viewModel::aiGenerateCommitMessage,
            credentialHealth = gitCredHealth,
            onVerifyCredential = viewModel::verifyGitCredential,
            onVerifyAllCredentials = viewModel::verifyAllCredentials,
            repoListState = gitRepoList,
            onFetchRepos = viewModel::fetchUserRepos,
            onClearRepoList = viewModel::clearRepoList,
            progress = gitProgress,
            onCancelProgress = viewModel::cancelGitOp,
            recentCloneUrls = recentCloneUrls,
            onLoadMoreCommits = viewModel::loadMoreCommits,
            onCommitFileDiff = viewModel::loadCommitFileDiff,
            gitOp = gitOp,
            onSwitchWorkspace = viewModel::switchWorkspace,
            onRetryCloneClean = viewModel::retryCloneAfterClean,
            onUndoRename = viewModel::gitRenameBranch,
            onConsumeGitOp = viewModel::consumeGitOpMessage,
            onConfigIdentity = viewModel::gitConfigIdentity,
            onRevert = viewModel::gitRevert,
            onRevertAll = viewModel::gitRevertAllUnstaged,
            onDeleteUntracked = viewModel::gitDeleteUntracked,
            onCreateTag = viewModel::gitCreateTag,
            onDeleteTag = viewModel::gitDeleteTag,
            onCommitDetail = viewModel::loadCommitDetail,
            onClearCommitDetail = viewModel::clearCommitDetail,
        )
    }

    if (showSessions) {
        SessionsDialog(
            sessions = sessions,
            currentSessionId = currentSessionId,
            sessionRunStates = sessionRunStates,
            onDismiss = { showSessions = false },
            onSwitch = { id -> viewModel.switchSession(id); showSessions = false },
            onNew = { showSessions = false; showNewSession = true },
            onDelete = viewModel::deleteSession,
            onRename = viewModel::renameSession,
        )
    }

    if (showNewSession) {
        NewSessionDialog(
            workspaces = workspaces,
            onDismiss = { showNewSession = false },
            onCreate = { title, selected, selectedType ->
                showNewSession = false
                viewModel.createSession(title = title, workspace = selected, projectType = selectedType.name)
            },
        )
    }

    if (showModels) {
        ModelDialog(
            models = models,
            selectedProfileId = activeModel?.id,
            selectedModelVariant = activeModel?.model,
            providerModelIds = providerModelIds,
            discoveringProviderModels = discoveringProviderModels,
            providerModelDiscoveryError = providerModelDiscoveryError,
            modelPickerProfileId = modelPickerProfileId,
            onDismiss = {
                viewModel.closeProviderModelPicker()
                showModels = false
            },
            onSelectProfile = { id -> viewModel.setActiveModel(id) },
            onSelectSubModel = { id, subModel -> viewModel.selectModel(id, subModel) },
            onOpenModelPicker = viewModel::openProviderModelPicker,
            onCloseModelPicker = viewModel::closeProviderModelPicker,
            onRefreshModels = viewModel::discoverProviderModels,
            onSwitchModel = viewModel::switchModelInProfile,
            onAdd = { name, provider, model, baseUrl -> viewModel.addModel(name, provider, model, baseUrl) },
            onDelete = viewModel::deleteModel,
        )
    }

    if (showSkillsMcpSheet) {
        // 打开挂载面板时自动探测一次 MCP 连通性
        LaunchedEffect(Unit) { viewModel.refreshMcpConnections() }
        SkillsAndMcpSheet(
            allSkills = allSkills,
            mcpServers = mcpServers,
            mcpConnectionStates = mcpConnectionStates,
            pinnedMentionIds = pinnedMentionIds,
            onDismiss = { showSkillsMcpSheet = false },
            onToggleSkill = { id, enabled ->
                viewModel.setSkillEnabled(id, enabled)
            },
            onToggleMcpServer = { id, enabled ->
                viewModel.setMcpServerEnabled(id, enabled)
            },
            onTogglePin = viewModel::togglePinMention,
            onNavigateToSettings = {
                showSkillsMcpSheet = false
                onNavigate(MainDestination.Settings)
            },
        )
    }

    if (showBranches) {
        BranchBrowserSheet(
            branches = branches,
            running = running,
            onDismiss = { showBranches = false },
            onSwitch = { branch ->
                viewModel.switchBranch(branch)
                showBranches = false
            },
            // 成果抽屉叠在分支列表之上，关闭后自然回到列表，再关闭即回主会话
            onOpenSubagent = viewModel::openSubagentResult,
        )
    }

    // 子智能体完整调研成果（独立 lane 的只读 transcript）
    subagentResult?.let { state ->
        SubagentResultSheet(
            state = state,
            onRefresh = viewModel::refreshSubagentResult,
            onDismiss = viewModel::closeSubagentResult,
        )
    }

    if (showRuntimeTimeline) {
        RuntimeTimelineSheet(
            events = runtimeEvents,
            messages = messages,
            memories = memories,
            scratchpads = scratchpads,
            onDeleteMemory = viewModel::deleteMemory,
            onDeleteScratchpad = viewModel::deleteScratchpad,
            onClearScratchpads = viewModel::clearScratchpads,
            onNavigateToMessage = { messageId ->
                showRuntimeTimeline = false
                coroutineScope.launch {
                    // 关键修复：旧的 targetIndex 用 (messages.filter { ToolResult }).indexOfFirst
                    // 计算，没有 LazyColumn 实际头部结构（init / empty / compaction）的偏移补偿，
                    // 也没有上界保护 —— 当消息在 sheet 打开后发生变化（流式新增 / 删除）时，
                    // 可能传入越界索引，触发 LazyListState 的 IllegalArgumentException。
                    // 这里改用与 ChatMessageList 完全一致的 projectChatMessages 投影，
                    // 并 clamp 到合法区间。
                    runCatching {
                        val renderItems = projectChatMessages(messages, toolResults)
                        val targetIndex = renderItems.indexOfFirst { item ->
                            item is ChatRenderItem.MessageItem && item.message.id == messageId
                        }
                        if (targetIndex < 0) return@launch
                        // LazyColumn 头部偏移：(init ? 1 : empty ? 1 : 0) + (compaction ? 1 : 0)。
                        // activePlan 不是独立头部 —— 不要再加 1。
                        val headerOffset = (if (initializing) 1 else 0) +
                            (if (!initializing && messages.isEmpty()) 1 else 0) +
                            (if (activeCompaction != null) 1 else 0)
                        val totalCount = listState.layoutInfo.totalItemsCount
                        if (totalCount <= 0) return@launch
                        val safeIndex = (targetIndex + headerOffset).coerceIn(0, totalCount - 1)
                        listState.animateScrollToItem(safeIndex)
                    }
                }
            },
            onDismiss = { showRuntimeTimeline = false },
        )
    }

    if (showApprovalModes) {
        ApprovalModeSheet(
            currentApprovalMode = currentApprovalMode,
            onSelect = { mode ->
                viewModel.setCurrentSessionApprovalMode(mode)
                showApprovalModes = false
            },
            onDismiss = { showApprovalModes = false },
        )
    }

    if (showMemorySheet) {
        SessionMemorySheet(
            memories = memories,
            scratchpads = scratchpads,
            onDeleteMemory = viewModel::deleteMemory,
            onDeleteScratchpad = viewModel::deleteScratchpad,
            onClearScratchpads = viewModel::clearScratchpads,
            onDismiss = { showMemorySheet = false },
        )
    }

    branchFromMessageId?.let { messageId ->
        CreateBranchDialog(
            messageId = messageId,
            onDismiss = { branchFromMessageId = null },
            onCreate = { id, name ->
                viewModel.createBranch(id, name)
                branchFromMessageId = null
            },
        )
    }
    } // CompositionLocalProvider(LocalSandboxHostRoots)
}

@Composable
private fun ChatPaneContent(
    modifier: Modifier = Modifier,
    messages: List<HarnessMessage>,
    onboardingPrivilege: OnboardingPrivilege? = null,
    listState: LazyListState,
    running: Boolean,
    status: String?,
    thinkingExpanded: Boolean,
    thinkingLive: Boolean,
    liveThinkingMessageId: String?,
    toolResults: Map<String, ToolResult>,
    workspace: String,
    workspaceProject: WorkspaceProject? = null,
    attachmentStatuses: Map<String, top.wanxiang.app.ui.chat.AttachmentStatus> = emptyMap(),
    workflowSuggestions: List<ProactiveWorkflowSuggestion> = emptyList(),
    onLaunchWorkflowSuggestion: (ProactiveWorkflowSuggestion) -> Unit = {},
    onDismissWorkflowSuggestion: (String) -> Unit = {},
    onOpenFile: ((projectName: String, relativePath: String) -> Unit)?,
    onEditMessage: (UserMessage) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRewindMessage: (String, top.wanxiang.app.harness.checkpoint.RewindScope) -> Unit = { _, _ -> },
    error: String?,
    onClearError: () -> Unit,
    matchingCommands: List<SlashCommandItem>,
    matchingMentions: List<MentionItem> = emptyList(),
    attachedMentions: List<MentionItem> = emptyList(),
    knownMentionNames: List<String> = emptyList(),
    queuedPrompts: List<QueuedPrompt>,
    onEditQueuedPrompt: (QueuedPrompt) -> Unit,
    onRemoveQueuedPrompt: (QueuedPrompt) -> Unit,
    onConvertToSteer: (QueuedPrompt) -> Unit = {},
    sendMode: ComposerSendMode,
    input: String,
    onInputChanged: (String) -> Unit,
    onApplyCommand: (SlashCommandItem) -> Unit,
    onApplyMention: (MentionItem) -> Unit = {},
    onRemoveMention: (MentionItem) -> Unit = {},
    attachments: List<ChatAttachment> = emptyList(),
    attachmentsProcessing: Boolean = false,
    onAttachmentsPicked: (List<Uri>, Boolean) -> Unit = { _, _ -> },
    onRemoveAttachment: (ChatAttachment) -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    lastAssistantMessageId: String?,
    onRegenerate: () -> Unit,
    onCreateBranch: (String) -> Unit,
    onRetryTool: (String) -> Unit,
    initializing: Boolean = false,
    pinnedCapabilities: List<MentionItem> = emptyList(),
    onOpenSkillsMcp: () -> Unit = {},
    onUnpinMention: (String) -> Unit = {},
    activeModel: AiModelEntity? = null,
    onUpdateReasoning: (mode: String?, effort: String?) -> Unit = { _, _ -> },
    pendingApprovals: List<AgentApprovalRequestEntity> = emptyList(),
    activePlan: AgentPlanEntity? = null,
    activeCompaction: CompactionSnapshot? = null,
    mcpRecommendations: List<McpWorkspaceRecommender.Recommendation> = emptyList(),
    onEnableMcpRecommendation: (String) -> Unit = {},
    onDismissMcpRecommendation: (String) -> Unit = {},
    onResolveApproval: (String, Boolean) -> Unit = { _, _ -> },
    contextUsage: ContextUsage = ContextUsage(),
    quickPhrases: List<QuickPhrase> = emptyList(),
    onSelectPhrase: (QuickPhrase) -> Unit = {},
    onViewSubagentLanes: () -> Unit = {},
    subagentBranches: List<top.wanxiang.app.harness.session.ConversationBranch> = emptyList(),
    onOpenSubagentBranch: (top.wanxiang.app.harness.session.ConversationBranch) -> Unit = {},
) {
    Column(modifier = modifier) {
        ChatMessageList(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            listState = listState,
            messages = messages,
            toolResults = toolResults,
            initializing = initializing,
            running = running,
            imageGenerationModel = activeModel?.imageGenerationEnabled == true,
            status = status,
            workspace = workspace,
            workspaceProject = workspaceProject,
            onboardingPrivilege = onboardingPrivilege,
            thinkingExpanded = thinkingExpanded,
            thinkingLive = thinkingLive,
            liveThinkingMessageId = liveThinkingMessageId,
            lastAssistantMessageId = lastAssistantMessageId,
            knownMentionNames = knownMentionNames,
            quickPhrases = quickPhrases,
            onSelectPhrase = onSelectPhrase,
            onSelectCommand = onApplyCommand,
            onEditMessage = onEditMessage,
            onDeleteMessage = onDeleteMessage,
            onRewindMessage = onRewindMessage,
            onCreateBranch = onCreateBranch,
            onRegenerate = onRegenerate,
            onRetryTool = onRetryTool,
            onOpenFile = onOpenFile,
            activeCompaction = activeCompaction,
            activePlan = activePlan,
            pendingApprovals = pendingApprovals,
            onResolveApproval = onResolveApproval,
            onViewSubagentLanes = onViewSubagentLanes,
            subagentBranches = subagentBranches,
            onOpenSubagent = onOpenSubagentBranch,
        )

        activePlan?.let { plan ->
            val steps = remember(plan.stepsJson) { PlanStepParser.parse(plan.stepsJson) }
            if (steps.isNotEmpty()) {
                StickyPlanBar(
                    goal = plan.goal,
                    steps = steps,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }

        error?.let {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClearError) {
                        Text(stringResource(R.string.chat_close), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        McpRecommendationBanner(
            recommendations = mcpRecommendations,
            onEnable = onEnableMcpRecommendation,
            onDismiss = onDismissMcpRecommendation,
        )

        ProactiveWorkflowBanner(
            suggestions = workflowSuggestions,
            onLaunch = onLaunchWorkflowSuggestion,
            onDismiss = onDismissWorkflowSuggestion,
        )

        ChatComposer(
            listState = listState,
            running = running,
            initializing = initializing,
            status = status,
            messages = messages,
            toolResults = toolResults,
            workspace = workspace,
            input = input,
            onInputChanged = onInputChanged,
            onSend = onSend,
            onStop = onStop,
            sendMode = sendMode,
            matchingCommands = matchingCommands,
            onApplyCommand = onApplyCommand,
            matchingMentions = matchingMentions,
            onApplyMention = onApplyMention,
            attachments = attachments,
            attachmentsProcessing = attachmentsProcessing,
            onAttachmentsPicked = onAttachmentsPicked,
            onRemoveAttachment = onRemoveAttachment,
            queuedPrompts = queuedPrompts,
            onEditQueuedPrompt = onEditQueuedPrompt,
            onRemoveQueuedPrompt = onRemoveQueuedPrompt,
            onConvertToSteer = onConvertToSteer,
            knownMentionNames = knownMentionNames,
            attachedMentions = attachedMentions,
            onRemoveMention = onRemoveMention,
            pinnedCapabilities = pinnedCapabilities,
            onUnpinMention = onUnpinMention,
            onOpenSkillsMcp = onOpenSkillsMcp,
            activeModel = activeModel,
            onUpdateReasoning = onUpdateReasoning,
            contextUsage = contextUsage,
        )
    }
}

/** 工作区感知的 MCP 预设推荐横幅：每条推荐展示启用理由，可一键启用或忽略。 */
@Composable
private fun McpRecommendationBanner(
    recommendations: List<McpWorkspaceRecommender.Recommendation>,
    onEnable: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    if (recommendations.isEmpty()) return
    recommendations.forEach { recommendation ->
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        recommendation.presetName,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        recommendation.reason,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { onEnable(recommendation.presetId) }) {
                    Text(stringResource(R.string.chat_enable), color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                TextButton(onClick = { onDismiss(recommendation.presetId) }) {
                    Text(stringResource(R.string.chat_ignore), color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

/** Event-driven workflow actions shown directly above the composer as interactive pills. */
@Composable
private fun ProactiveWorkflowBanner(
    suggestions: List<ProactiveWorkflowSuggestion>,
    onLaunch: (ProactiveWorkflowSuggestion) -> Unit,
    onDismiss: (String) -> Unit,
) {
    if (suggestions.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        suggestions.forEach { suggestion ->
            Surface(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.90f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)),
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RuntimeIcon(RuntimeIconName.Hub, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(
                        modifier = Modifier
                            .clickable { onLaunch(suggestion) }
                            .padding(end = 4.dp),
                    ) {
                        Text(
                            text = if (suggestion.projectName.isNotBlank()) "${suggestion.projectName} · ${suggestion.title}" else suggestion.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            maxLines = 1,
                        )
                        if (suggestion.description.isNotBlank()) {
                            Text(
                                text = suggestion.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Button(
                        onClick = { onLaunch(suggestion) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text("运行", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(
                        onClick = { onDismiss(suggestion.workflowId) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        RuntimeIcon(
                            RuntimeIconName.Close,
                            Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.65f),
                        )
                    }
                }
            }
        }
    }
}
