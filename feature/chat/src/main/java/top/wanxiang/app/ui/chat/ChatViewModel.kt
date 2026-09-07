package top.wanxiang.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wanxiang.app.core.tools.AiProfileWriter
import top.wanxiang.app.core.model.ExecutionMode
import top.wanxiang.app.core.model.McpConnectionState
import top.wanxiang.app.core.model.ApprovalMode
import top.wanxiang.app.core.database.AiModelRepository
import top.wanxiang.app.core.database.AiModelEntity
import top.wanxiang.app.core.database.HarnessSessionRepository
import top.wanxiang.app.core.database.HarnessSessionEntity
import top.wanxiang.app.core.database.AgentSkillRepository
import top.wanxiang.app.core.database.McpServerRepository
import top.wanxiang.app.core.database.AgentApprovalRepository
import top.wanxiang.app.core.database.AgentApprovalRequestEntity
import top.wanxiang.app.core.datastore.AgentPreferences
import top.wanxiang.app.core.datastore.GitCredential
import top.wanxiang.app.core.datastore.GitPreferences
import top.wanxiang.app.harness.HarnessLoop
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.UserMessage
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.harness.PendingMessage
import top.wanxiang.app.harness.QueuedPrompt
import top.wanxiang.app.harness.ContextWindowPolicy
import top.wanxiang.app.harness.events.HarnessEvent
import top.wanxiang.app.harness.events.HarnessEventBus
import top.wanxiang.app.harness.mcp.McpManager
import top.wanxiang.app.harness.queue.PromptQueue
import top.wanxiang.app.harness.session.ConversationBranch
import top.wanxiang.app.harness.session.ConversationBranchKind
import top.wanxiang.app.harness.session.LaneManager
import top.wanxiang.app.runtime.WorkspaceManager
import top.wanxiang.app.runtime.WorkspaceProject
import top.wanxiang.app.core.tools.AgentModelDiscovery
import top.wanxiang.app.core.tools.AgentProviderCatalog
import top.wanxiang.app.core.tools.ProviderEndpointPolicy
import top.wanxiang.app.core.tools.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import top.wanxiang.app.feature.chat.R
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import top.wanxiang.app.runtime.terminal.TerminalSessionManager

private const val MAX_RUNTIME_EVENTS = 160
private const val TAG = "ChatViewModel"
private const val KEY_INPUT_DRAFT = "chat_input_draft"

data class SubagentResultUiState(
    val sessionId: String,
    val branch: ConversationBranch,
    val messages: List<HarnessMessage> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/** 空会话首屏的权限感知引导档位；决定开场提示卡的文案与色调。 */
enum class OnboardingPrivilege { SANDBOX, SANDBOX_UNLOCKABLE, SHIZUKU_READY, ROOT_READY }

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val harnessLoop: HarnessLoop,
    private val sessionDao: HarnessSessionRepository,
    private val aiModelDao: AiModelRepository,
    private val workspaceManager: WorkspaceManager,
    private val settingsDataStore: AgentPreferences,
    private val gitPreferences: GitPreferences,
    private val linuxRuntime: top.wanxiang.app.runtime.LinuxRuntime,
    private val terminalSessionManager: TerminalSessionManager,
    private val mcpManager: McpManager,
    private val agentSkillRepository: AgentSkillRepository,
    private val mcpServerRepository: McpServerRepository,
    private val approvalRepository: AgentApprovalRepository,
    private val agentContextDao: top.wanxiang.app.core.database.AgentContextRepository,
    private val compactionManager: top.wanxiang.app.harness.compaction.CompactionManager,
    private val quickPhraseRepository: top.wanxiang.app.core.database.QuickPhraseRepository,
    private val laneManager: LaneManager,
    private val eventBus: HarnessEventBus,
    private val modelDiscovery: AgentModelDiscovery,
    private val providerCatalog: AgentProviderCatalog,
    private val providerRepository: ProviderRepository,
    private val profileWriter: top.wanxiang.app.core.tools.AiProfileWriter,
    private val privilegeManager: top.wanxiang.app.runtime.privilege.PrivilegeManager,
    private val pathManager: top.wanxiang.app.runtime.RuntimePathManager,
) : ViewModel() {

    /**
     * 模型回复里引用的沙箱绝对路径（如 /workspace/xxx.jpg）到宿主真实目录的映射，
     * 供聊天媒体渲染把 PRoot 内路径翻译成 Android 可读文件。
     */
    val sandboxHostRoots: Map<String, java.io.File> = mapOf(
        "workspace" to pathManager.workspaceDir,
        "attachments" to pathManager.attachmentsDir,
    )

    /** 空会话首屏权限感知引导：按实际特权状态给出不同玩法提示。 */
    val privilegeOnboarding: StateFlow<OnboardingPrivilege?> =
        flow { emit(privilegeManager.getPrivilegeInfo()) }
            .map { info ->
                when {
                    info.mode == ExecutionMode.ROOT && info.modeActive -> OnboardingPrivilege.ROOT_READY
                    info.mode == ExecutionMode.SHIZUKU && info.modeActive -> OnboardingPrivilege.SHIZUKU_READY
                    info.shizukuAvailable || info.rootAvailable -> OnboardingPrivilege.SANDBOX_UNLOCKABLE
                    else -> OnboardingPrivilege.SANDBOX
                }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _eventHistory = MutableStateFlow<Map<String, List<HarnessEvent>>>(emptyMap())
    private val _permissionRequests = kotlinx.coroutines.flow.MutableSharedFlow<HarnessEvent.PermissionRequired>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val permissionRequests: kotlinx.coroutines.flow.SharedFlow<HarnessEvent.PermissionRequired> = _permissionRequests

    init {
        viewModelScope.launch {
            quickPhraseRepository.ensureInitialized()
        }
        viewModelScope.launch {
            eventBus.events.collect { event ->
                _eventHistory.value = _eventHistory.value.toMutableMap().apply {
                    this[event.sessionId] = (this[event.sessionId].orEmpty() + event).takeLast(MAX_RUNTIME_EVENTS)
                }
                if (event is HarnessEvent.PermissionRequired) {
                    _permissionRequests.tryEmit(event)
                }
            }
        }
    }

    val quickPhrases: StateFlow<List<top.wanxiang.app.core.model.QuickPhrase>> = quickPhraseRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeDistroId: StateFlow<String> = linuxRuntime.activeDistroId
    val installedDistros: StateFlow<List<top.wanxiang.app.core.model.InstalledDistro>> = linuxRuntime.installedDistros

    fun switchDistro(distroId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 先关闭所有旧系统 PTY 会话，再切换发行版
            terminalSessionManager.closeAllSessions()
            linuxRuntime.switchActiveDistro(distroId)
        }
    }

    val messages: StateFlow<List<HarnessMessage>> = harnessLoop.messages
    val running: StateFlow<Boolean> = harnessLoop.running
    val error: StateFlow<String?> = harnessLoop.error
    val status: StateFlow<String?> = harnessLoop.status
    val thinkingLive: StateFlow<Boolean> = harnessLoop.thinkingLive
    val workspace: StateFlow<String> = harnessLoop.workspace
    val projectType: StateFlow<String> = harnessLoop.projectType
    /** 基于当前工作区内容自动推荐的 MCP 预设（已启用的已过滤），仅提示不自动启用。 */
    val mcpRecommendations: StateFlow<List<top.wanxiang.app.harness.mcp.McpWorkspaceRecommender.Recommendation>> =
        harnessLoop.mcpRecommendations

    fun enableMcpRecommendation(presetId: String) = harnessLoop.enableRecommendedMcp(presetId)
    fun dismissMcpRecommendation(presetId: String) = harnessLoop.dismissMcpRecommendation(presetId)
    /** 运行中排队的待发送消息（当前任务结束后自动接续）。 */
    val pendingMessages: StateFlow<List<PendingMessage>> = harnessLoop.pendingMessages
    val queuedPrompts: StateFlow<List<QueuedPrompt>> = harnessLoop.queuedPrompts

    private val _sendMode = MutableStateFlow(ComposerSendMode.NEXT_RUN)
    val sendMode: StateFlow<ComposerSendMode> = _sendMode.asStateFlow()

    // ===== Git 面板状态（绑定当前会话，不跨会话共享）=====
    private val _gitPanelState = MutableStateFlow(GitPanelState())
    val gitPanelState: StateFlow<GitPanelState> = _gitPanelState.asStateFlow()

    /** 读取当前会话工作区的 Git 状态（分支 + 改动 + 分支列表 + 提交历史）。 */
    fun refreshGitStatus() {
        // 会话未绑定工作区时回退到沙箱内 /workspace 根目录；相对路径补 /workspace 前缀
        val ws = workspace.value.ifBlank { "/workspace" }
            .let { if (it.startsWith("/")) it else "/workspace/$it" }
        _gitPanelState.value = GitPanelState(loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val statusOut = runGitRead(ws, "git status --porcelain -b")
            if (statusOut == null || statusOut.contains("not a git repository", ignoreCase = true)) {
                _gitPanelState.value = GitPanelState(loading = false, notARepo = true)
                return@launch
            }
            val lines = statusOut.lines().filter { it.isNotBlank() }
            val branch = lines.firstOrNull { it.startsWith("## ") }
                ?.removePrefix("## ")
                ?.substringBefore("...")
                ?.substringBefore(" [")
                ?.trim()
                ?.removePrefix("No commits yet on ")
                ?.takeIf { it.isNotBlank() }
            val staged = mutableListOf<GitFileChange>()
            val unstaged = mutableListOf<GitFileChange>()
            val untracked = mutableListOf<String>()
            for (line in lines.filterNot { it.startsWith("## ") }) {
                if (line.length < 4) continue
                val x = line[0]
                val y = line[1]
                val path = line.drop(3)
                when {
                    x == '?' && y == '?' -> untracked += path
                    x != ' ' -> staged += GitFileChange(x, path)
                    y != ' ' -> unstaged += GitFileChange(y, path)
                }
            }
            val localBranches = runGitRead(ws, "git branch --format=%(refname:short)")
                ?.lines()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val remoteBranches = runGitRead(ws, "git branch -r --format=%(refname:short)")
                ?.lines()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val tags = runGitRead(ws, "git tag --list")
                ?.lines()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val commits = runGitRead(ws, "git log --pretty=format:%h%x1f%s%x1f%an%x1f%ad --date=short -30")
                ?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            val hasIdentity = runGitRead(ws, "git config user.name")?.isNotBlank() == true &&
                runGitRead(ws, "git config user.email")?.isNotBlank() == true
            val hasRemote = runGitRead(ws, "git remote")?.isNotBlank() == true
            _gitPanelState.value = GitPanelState(
                loading = false,
                branch = branch,
                staged = staged,
                unstaged = unstaged,
                untracked = untracked,
                localBranches = localBranches,
                remoteBranches = remoteBranches,
                tags = tags,
                commits = commits,
                hasIdentity = hasIdentity,
                hasRemote = hasRemote,
            )
        }
    }

    private suspend fun runGitRead(ws: String, cmd: String): String? {
        val result = linuxRuntime.execute(
            top.wanxiang.app.runtime.shell.ShellCommand(
                commandLine = "$cmd 2>&1 || true",
                workingDirectory = ws,
                timeoutMs = 15_000L,
            ),
        )
        return if (result.isSuccess) {
            (result.stdout + "\n" + result.stderr).trim().takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    /** 查看某个改动文件的 diff（相对 HEAD，含暂存与未暂存）。 */
    fun loadFileDiff(path: String) {
        val ws = workspace.value.ifBlank { "/workspace" }.let { if (it.startsWith("/")) it else "/workspace/$it" }
        _gitPanelState.value = _gitPanelState.value.copy(diffPath = path, diffText = null, diffLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val quoted = "'${path.replace("'", "'\\''")}'"
            val diff = runGitRead(ws, "git diff HEAD -- $quoted")
            _gitPanelState.value = _gitPanelState.value.copy(
                diffPath = path,
                diffText = diff ?: "未跟踪的新文件（无 diff）",
                diffLoading = false,
            )
        }
    }

    /** 关闭 diff 视图，返回状态列表。 */
    fun clearDiff() {
        _gitPanelState.value = _gitPanelState.value.copy(diffPath = null, diffText = null, diffLoading = false)
    }

    fun gitStage(path: String) = runGitWrite("git add -- ${shellQuote(path)}")
    fun gitUnstage(path: String) = runGitWrite("git reset HEAD -- ${shellQuote(path)}")
    fun gitStageAll() = runGitWrite("git add -A")
    fun gitUnstageAll() = runGitWrite("git reset HEAD")
    fun gitCommit(message: String) = runGitWrite("git commit -m ${shellQuote(message)}")
    /**
     * 本地有未提交改动时先弹确认（可能覆盖或冲突）；干净时直接 pull。
     * 通过 [gitPanelState] 的 `pullDirtyConfirm` 字段驱动 UI 弹窗，用户在 UI 上点"继续拉取"再调 [gitPullNow]。
     */
    fun gitPull() {
        val s = _gitPanelState.value
        val dirty = s.staged.isNotEmpty() || s.unstaged.isNotEmpty()
        if (dirty) {
            _gitPanelState.value = s.copy(pullDirtyConfirm = true)
        } else {
            gitPullNow()
        }
    }

    fun gitPullNow() {
        _gitPanelState.value = _gitPanelState.value.copy(pullDirtyConfirm = false)
        runGitNetworkOp(cmd = "git pull", resolveHostFromOrigin = true, timeoutMs = 180_000L)
    }

    fun dismissPullDirtyConfirm() {
        _gitPanelState.value = _gitPanelState.value.copy(pullDirtyConfirm = false)
    }
    fun gitPush() = runGitNetworkOp(cmd = "git push", resolveHostFromOrigin = true, timeoutMs = 180_000L)
    fun gitCheckout(branch: String) = runGitWrite("git checkout ${shellQuote(branch)}")
    fun gitCreateBranch(name: String) = runGitWrite("git checkout -b ${shellQuote(name)}")
    fun gitDeleteBranch(branch: String) = runGitWrite("git branch -d ${shellQuote(branch)}")
    fun gitInit() = runGitWrite("git init")
    fun gitClone(url: String) = runGitNetworkOp(
        cmd = "git clone --depth 1 ${shellQuote(url)} .",
        resolveHostFromOrigin = false,
        explicitHost = GitAuth.hostOf(url),
        timeoutMs = 600_000L,
    )
    fun gitConfigIdentity(name: String, email: String) = runGitWrite("git config user.name ${shellQuote(name)} && git config user.email ${shellQuote(email)}")
    fun gitRevert(path: String) = runGitWrite("git checkout -- ${shellQuote(path)}")
    fun gitDeleteUntracked(path: String) = runGitWrite("rm -- ${shellQuote(path)}")
    fun gitCreateTag(name: String) = runGitWrite("git tag ${shellQuote(name)}")
    fun gitDeleteTag(name: String) = runGitWrite("git tag -d ${shellQuote(name)}")

    /**
     * 网络型 git 操作（clone/pull/push），带凭证注入。凭证不落 `.git/config`：通过
     * `GIT_CONFIG_KEY_0=credential.helper` + 一次性 `mktemp` 文件传给 git，跑完立即删除。
     * - clone：从入参 URL 解析 host；
     * - pull/push：从当前仓库 `origin` 反查 host。
     */
    private fun runGitNetworkOp(
        cmd: String,
        resolveHostFromOrigin: Boolean,
        explicitHost: String? = null,
        timeoutMs: Long,
    ) {
        val ws = currentGitWs()
        viewModelScope.launch(Dispatchers.IO) {
            val host = if (resolveHostFromOrigin) {
                GitAuth.hostOf(runGitRead(ws, "git remote get-url origin").orEmpty().trim())
            } else {
                explicitHost
            }
            val creds = gitPreferences.credentials.first()
            val cred = GitAuth.findCredential(creds, host)
            val effective = if (cred != null) GitAuth.wrap(cmd, cred) else cmd
            linuxRuntime.execute(
                top.wanxiang.app.runtime.shell.ShellCommand(
                    commandLine = "$effective 2>&1 || true",
                    workingDirectory = ws,
                    timeoutMs = timeoutMs,
                ),
            )
            refreshGitStatus()
        }
    }

    // ===== Git 凭证 CRUD（HTTPS 私有仓库：GitHub/Gitee/GitLab PAT 等）=====

    /** 已保存的 Git 凭证列表（加密存储，UI 只显示名称+主机+用户名，token 掩码）。 */
    val gitCredentials: StateFlow<List<GitCredential>> = gitPreferences.credentials
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前工作区 URL 命中的凭证 id（Git 面板顶部提示用），无匹配则 null。 */
    private val _matchedCredentialId = MutableStateFlow<String?>(null)
    val matchedCredentialId: StateFlow<String?> = _matchedCredentialId.asStateFlow()

    fun addGitCredential(name: String, host: String, username: String, token: String) {
        val trimmedHost = host.trim().lowercase().removePrefix("https://").removeSuffix("/")
        if (name.isBlank() || trimmedHost.isBlank() || username.isBlank() || token.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val current = gitPreferences.credentials.first()
            val updated = current + GitCredential(
                id = java.util.UUID.randomUUID().toString(),
                name = name.trim(),
                host = trimmedHost,
                username = username.trim(),
                token = token.trim(),
                createdAtMillis = System.currentTimeMillis(),
            )
            gitPreferences.setCredentials(updated)
        }
    }

    fun deleteGitCredential(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = gitPreferences.credentials.first()
            gitPreferences.setCredentials(current.filterNot { it.id == id })
        }
    }

    /** 用给定 URL 探测是否有可用凭证（clone 对话框显示「将自动使用」提示）。 */
    fun probeCredential(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val host = GitAuth.hostOf(url)
            val creds = gitPreferences.credentials.first()
            _matchedCredentialId.value = GitAuth.findCredential(creds, host)?.id
        }
    }

    private fun currentGitWs(): String =
        workspace.value.ifBlank { "/workspace" }.let { if (it.startsWith("/")) it else "/workspace/$it" }

    fun loadCommitDetail(hash: String) {
        val ws = workspace.value.ifBlank { "/workspace" }.let { if (it.startsWith("/")) it else "/workspace/$it" }
        _gitPanelState.value = _gitPanelState.value.copy(commitDetailHash = hash, commitDetailText = null, commitDetailLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val detail = runGitRead(ws, "git show --stat --format=fuller $hash")
            _gitPanelState.value = _gitPanelState.value.copy(
                commitDetailHash = hash,
                commitDetailText = detail ?: "无法读取该提交",
                commitDetailLoading = false,
            )
        }
    }

    fun clearCommitDetail() {
        _gitPanelState.value = _gitPanelState.value.copy(commitDetailHash = null, commitDetailText = null, commitDetailLoading = false)
    }

    private fun runGitWrite(cmd: String) {
        val ws = workspace.value.ifBlank { "/workspace" }.let { if (it.startsWith("/")) it else "/workspace/$it" }
        viewModelScope.launch(Dispatchers.IO) {
            linuxRuntime.execute(
                top.wanxiang.app.runtime.shell.ShellCommand(
                    commandLine = "$cmd 2>&1 || true",
                    workingDirectory = ws,
                    timeoutMs = 30_000L,
                ),
            )
            refreshGitStatus()
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    val runtimeEvents: StateFlow<List<HarnessEvent>> = combine(
        harnessLoop.currentSessionId,
        _eventHistory,
        messages,
    ) { sessionId, history, msgList ->
        val live = history[sessionId].orEmpty()
        mergeHistoricalAndLiveEvents(sessionId, msgList, live)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _branchRefresh = MutableStateFlow(0)
    private val branchMessageRevision = messages.map { list -> list.size to list.lastOrNull()?.id }.distinctUntilChanged()
    // 子智能体在独立 lane 中执行时主会话消息不变，用运行事件驱动分支重投影，
    // 这样运行中也能在协同卡片里点进子 lane 看实时进展。
    private val branchEventRevision = runtimeEvents.map { events ->
        events.size to (events.lastOrNull()?.hashCode() ?: 0)
    }.distinctUntilChanged()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val branches: StateFlow<List<ConversationBranch>> = combine(
        harnessLoop.currentSessionId,
        branchMessageRevision,
        _branchRefresh,
        branchEventRevision,
    ) { sessionId, _, _, _ -> sessionId }.mapLatest { sessionId ->
        if (sessionId.isBlank()) emptyList() else runCatching { laneManager.branches(sessionId) }.getOrDefault(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前选中的会话 ID */
    val currentSessionId: StateFlow<String> = harnessLoop.currentSessionId
    /** 所有会话的多 Agent 并发运行状态映射 (IDLE / RUNNING / COMPLETED / FAILED) */
    val sessionRunStates: StateFlow<Map<String, top.wanxiang.app.core.model.SessionRunState>> = harnessLoop.sessionRunStates
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pendingApprovals: StateFlow<List<AgentApprovalRequestEntity>> = harnessLoop.currentSessionId.flatMapLatest { sessionId ->
        if (sessionId.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else approvalRepository.pendingForSession(sessionId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 当前会话的活跃结构化任务规划（模型通过 plan 工具写入的 AgentPlanEntity）。
     * 以 currentSessionId + 运行状态为键重新读取：一轮执行内状态多次变化，
     * 借此近似实时刷新看板进度；无规划或非活跃时为 null。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activePlan: StateFlow<top.wanxiang.app.core.database.AgentPlanEntity?> =
        combine(harnessLoop.currentSessionId, harnessLoop.status) { sessionId, _ -> sessionId }
            .distinctUntilChanged()
            .flatMapLatest { sessionId ->
                kotlinx.coroutines.flow.flow {
                    emit(if (sessionId.isBlank()) null else agentContextDao.getActivePlan(sessionId)?.takeIf { it.status == "active" })
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 当前会话最近一次上下文压缩的快照（折叠条数 + 摘要预览）。
     * 会话从未压缩时为 null——UI 据此隐藏提示横幅。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeCompaction: StateFlow<top.wanxiang.app.harness.compaction.CompactionSnapshot?> =
        combine(harnessLoop.currentSessionId, harnessLoop.status) { sessionId, _ -> sessionId }
            .distinctUntilChanged()
            .flatMapLatest { sessionId ->
                kotlinx.coroutines.flow.flow {
                    emit(if (sessionId.isBlank()) null else compactionManager.latestSnapshot(sessionId))
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 全量长期记忆（memory 工具写入），供记忆抽屉管理与模型上下文核对。 */
    val memories: StateFlow<List<top.wanxiang.app.core.database.AgentMemoryEntity>> =
        agentContextDao.observeAllMemories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val scratchpadRefresh = MutableStateFlow(0)

    /** 当前会话的草稿便签；随运行状态变化与手动刷新重建。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val scratchpads: StateFlow<List<top.wanxiang.app.core.database.AgentScratchpadEntity>> =
        combine(
            harnessLoop.currentSessionId,
            harnessLoop.status,
            scratchpadRefresh,
        ) { sessionId, _, _ -> sessionId }
            .distinctUntilChanged()
            .flatMapLatest { sessionId ->
                flow {
                    emit(if (sessionId.isBlank()) emptyList() else agentContextDao.listScratchpads(sessionId))
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteMemory(id: String) {
        viewModelScope.launch { agentContextDao.deleteMemoryById(id) }
    }

    fun deleteScratchpad(key: String) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            agentContextDao.deleteScratchpad(sessionId, key)
            scratchpadRefresh.value++
        }
    }

    fun clearScratchpads() {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            agentContextDao.clearScratchpads(sessionId)
            scratchpadRefresh.value++
        }
    }

    fun resolveApproval(requestId: String, approved: Boolean) {
        harnessLoop.resolveApproval(requestId, approved)
    }

    val sessions: StateFlow<List<HarnessSessionEntity>> = sessionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setCurrentSessionApprovalMode(mode: ApprovalMode) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            sessionDao.setApprovalMode(sessionId, mode.id, System.currentTimeMillis())
        }
    }

    val models: StateFlow<List<AiModelEntity>> = aiModelDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val workspaces: StateFlow<List<WorkspaceProject>> = workspaceManager.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 思考过程块是否默认展开（持久化，重启后保留）。 */
    val thinkingExpanded: StateFlow<Boolean> = settingsDataStore.thinkingExpanded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setThinkingExpanded(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setThinkingExpanded(value) }
    }

    // 输入草稿：同步写入 SavedStateHandle，进程重建 / 旋转后可恢复
    private val _input = MutableStateFlow(savedStateHandle.get<String>(KEY_INPUT_DRAFT) ?: "")
    val input: StateFlow<String> = _input.asStateFlow()

    /** 统一输入写入入口：StateFlow 供组合使用，SavedStateHandle 供状态恢复使用 */
    private fun setInput(value: String) {
        _input.value = value
        savedStateHandle[KEY_INPUT_DRAFT] = value
    }

    val activeSkills: StateFlow<List<top.wanxiang.app.core.model.AgentSkill>> = agentSkillRepository.activeSkills
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allSkills: StateFlow<List<top.wanxiang.app.core.model.AgentSkill>> = agentSkillRepository.allSkills
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val mcpServers: StateFlow<List<top.wanxiang.app.core.model.McpServerConfig>> = mcpServerRepository.servers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 当前会话上下文用量的 UI 估算。Harness 发请求时会用同一字符/token 近似值再做最终压缩，
     * 因此这里明确是预估值，而不是 provider 返回的精确 tokenizer 计数。
     */
    val contextUsage: StateFlow<ContextUsage> = combine(
        messages,
        models,
        allSkills,
        mcpServers,
        settingsDataStore.contextBudgetTokens,
    ) { currentMessages, currentModels, skills, mcps, defaultBudget ->
        ContextUsageInputs(
            currentMessages = currentMessages,
            activeModel = currentModels.firstOrNull { it.isActive },
            skills = skills,
            mcps = mcps,
            defaultBudget = defaultBudget,
        )
    }.combine(settingsDataStore.contextCompactionEnabled) { inputs, compactionEnabled ->
        val activeModel = inputs.activeModel
        val systemTokens = if (activeModel?.pureChatMode == true) {
            0
        } else {
            val skillTokens = inputs.skills.filter { it.isEnabled }.sumOf { ContextWindowPolicy.estimateTokens(it.systemPrompt) }
            val mcpTokens = inputs.mcps.filter { it.isEnabled }.sumOf {
                ContextWindowPolicy.estimateTokens("${it.name}\n${it.description}\n${it.command}\n${it.args.joinToString(" ")}")
            }
            1_600 + skillTokens + mcpTokens
        }
        val effectiveUsage = ContextWindowPolicy.estimateEffectiveUsage(
            messages = inputs.currentMessages,
            budget = (activeModel?.contextTokens ?: inputs.defaultBudget).coerceAtLeast(1),
            systemTokens = systemTokens,
            compactionEnabled = compactionEnabled,
        )
        val totalPromptTokens = inputs.currentMessages.filterIsInstance<AssistantText>().mapNotNull { it.promptTokens?.toLong() }.sum()
        val totalCachedTokens = inputs.currentMessages.filterIsInstance<AssistantText>().mapNotNull { it.cachedTokens?.toLong() }.sum()
        val cacheHitPct = if (totalPromptTokens > 0L && totalCachedTokens > 0L) {
            ((totalCachedTokens * 100L) / totalPromptTokens).toInt().coerceIn(1, 100)
        } else null

        ContextUsage(
            usedTokens = effectiveUsage.totalTokens,
            limitTokens = (activeModel?.contextTokens ?: inputs.defaultBudget).coerceAtLeast(1),
            systemTokens = systemTokens,
            toolTokens = effectiveUsage.toolTokens,
            conversationTokens = effectiveUsage.conversationTokens,
            compacted = effectiveUsage.keepFromIndex > 0,
            cachedTokens = totalCachedTokens,
            cacheHitRatePercent = cacheHitPct,
        )

    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContextUsage())

    /** 各 MCP 服务的实时连通性状态（与 McpManager 共享，聊天挂载面板 / 设置页联动）。 */
    val mcpConnectionStates: StateFlow<Map<String, McpConnectionState>> = mcpManager.connectionStates

    fun refreshMcpConnections() {
        viewModelScope.launch { mcpManager.refreshConnections() }
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean) {
        viewModelScope.launch {
            agentSkillRepository.setEnabled(skillId, enabled)
        }
    }

    fun setMcpServerEnabled(serverId: String, enabled: Boolean) {
        viewModelScope.launch {
            mcpServerRepository.setEnabled(serverId, enabled)
            mcpManager.refreshConnections()
        }
    }

    /** 斜杠指令建议列表（当输入以 / 开头时实时过滤展示，自动合并已激活的专精技能）。 */
    val matchingCommands: StateFlow<List<SlashCommandItem>> = kotlinx.coroutines.flow.combine(_input, agentSkillRepository.activeSkills) { text, skills ->
        if (text.startsWith("/")) SlashCommands.filterCommands(context, text, skills)
        else emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun skillToMentionItem(skill: top.wanxiang.app.core.model.AgentSkill): MentionItem = MentionItem(
        id = skill.id,
        name = skill.name,
        description = skill.description,
        category = context.getString(R.string.chat_skill_category),
        type = MentionType.SKILL,
        icon = top.wanxiang.app.ui.components.RuntimeIconName.Brain,
    )

    private fun mcpToMentionItem(
        mcp: top.wanxiang.app.core.model.McpServerConfig,
        description: String = mcp.description,
    ): MentionItem = MentionItem(
        id = mcp.id,
        name = mcp.name,
        description = description,
        category = context.getString(R.string.chat_mcp_category),
        type = MentionType.MCP_SERVER,
        icon = top.wanxiang.app.ui.components.RuntimeIconName.Cpu,
    )

    /** @ 艾特唤醒建议列表（当输入包含 @ 时实时过滤技能与 MCP 插件）。 */
    val matchingMentions: StateFlow<List<MentionItem>> = kotlinx.coroutines.flow.combine(
        _input,
        agentSkillRepository.allSkills,
        mcpServerRepository.servers,
    ) { text, skills, mcps ->
        val atIndex = text.lastIndexOf('@')
        if (atIndex < 0) return@combine emptyList()
        val mentionToken = text.substring(atIndex + 1)
        if (mentionToken.any { it.isWhitespace() }) return@combine emptyList()
        val query = mentionToken.lowercase()

        val skillMentions = skills.filter { it.isEnabled }.map(::skillToMentionItem)
        val mcpMentions = mcps.filter { it.isEnabled && !it.isBuiltin }.map { mcp ->
            mcpToMentionItem(mcp, context.getString(R.string.chat_mcp_service_description, mcp.transportType))
        }
        val all = skillMentions + mcpMentions
        if (query.isEmpty()) all
        else all.filter { it.name.lowercase().contains(query) || it.description.lowercase().contains(query) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前会话中明确被用户手动钉选常驻的技能与 MCP ID 集合（默认为空，不默认常驻）。 */
    private val _pinnedMentionIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedMentionIds: StateFlow<Set<String>> = _pinnedMentionIds.asStateFlow()

    /** 当前会话中已钉选常驻的技能与 MCP 列表（默认为空，仅在用户显式钉选后常驻展示并生效）。 */
    val pinnedCapabilities: StateFlow<List<MentionItem>> = kotlinx.coroutines.flow.combine(
        _pinnedMentionIds,
        agentSkillRepository.allSkills,
        mcpServerRepository.servers,
    ) { pinnedIds, skills, mcps ->
        if (pinnedIds.isEmpty()) return@combine emptyList()
        val skillItems = skills
            .filter { it.isEnabled && (it.id in pinnedIds || it.name.lowercase() in pinnedIds) }
            .map(::skillToMentionItem)
        val mcpItems = mcps
            .filter { it.isEnabled && !it.isBuiltin && (it.id in pinnedIds || it.name.lowercase() in pinnedIds) }
            .map(::mcpToMentionItem)
        skillItems + mcpItems
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun togglePinMention(id: String) {
        val target = id.trim().lowercase()
        _pinnedMentionIds.update { current ->
            if (target in current || current.any { it.equals(id, ignoreCase = true) }) {
                current.filterNot { it.equals(id, ignoreCase = true) || it == target }.toSet()
            } else {
                current + target
            }
        }
    }

    fun unpinMention(id: String) {
        val target = id.trim().lowercase()
        _pinnedMentionIds.update { current ->
            current.filterNot { it.equals(id, ignoreCase = true) || it == target }.toSet()
        }
    }

    fun pinMention(id: String) {
        val target = id.trim().lowercase()
        _pinnedMentionIds.update { it + target }
    }

    /** 当前输入框中已挂载的技能与 MCP 标签列表（用于输入框顶部展示高亮双排 Chips）。 */
    val attachedMentions: StateFlow<List<MentionItem>> = kotlinx.coroutines.flow.combine(
        _input,
        agentSkillRepository.allSkills,
        mcpServerRepository.servers,
    ) { text, skills, mcps ->
        if (!text.contains("@")) return@combine emptyList()
        val regex = Regex("""@([^\s@,，:：\n]+)""")
        val matchedNames = regex.findAll(text).map { it.groupValues[1].trim().lowercase() }.toSet()
        if (matchedNames.isEmpty()) return@combine emptyList()

        val matchedSkills = skills
            .filter { skill ->
                skill.isEnabled && (
                    skill.name.lowercase() in matchedNames || skill.id.lowercase() in matchedNames
                    )
            }
            .map(::skillToMentionItem)

        val matchedMcps = mcps
            .filter { mcp ->
                mcp.isEnabled && !mcp.isBuiltin && (
                    mcp.name.lowercase() in matchedNames || mcp.id.lowercase() in matchedNames
                    )
            }
            .map(::mcpToMentionItem)

        matchedSkills + matchedMcps
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _initializing = MutableStateFlow(true)
    val initializing: StateFlow<Boolean> = _initializing.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // 恢复最近会话；没有则新建
            val latest = sessionDao.observeAll().first().firstOrNull()
            if (latest != null) {
                harnessLoop.loadSession(latest.id)
            } else {
                harnessLoop.newSession(context.getString(R.string.chat_new_session))
            }
            _initializing.value = false
        }
    }

    fun onInputChanged(value: String) {
        setInput(value)
    }

    fun applySlashCommand(command: SlashCommandItem) {
        if (command.command == "/clear") {
            createSession(context.getString(R.string.chat_new_session))
            setInput("")
            notifyNewSessionCreated()
        } else {
            setInput(command.template)
        }
    }

    fun applyQuickPhrase(phrase: top.wanxiang.app.core.model.QuickPhrase) {
        if (phrase.content.trim() == "/clear") {
            createSession(context.getString(R.string.chat_new_session))
            setInput("")
            notifyNewSessionCreated()
        } else {
            setInput(phrase.content)
        }
    }

    /** /clear 会静默重建会话，用 Toast 明确告知用户上下文已重置 */
    private fun notifyNewSessionCreated() {
        Toast.makeText(context, context.getString(R.string.chat_new_session_created), Toast.LENGTH_SHORT).show()
    }

    fun applyMention(item: MentionItem) {
        val text = _input.value
        val atIndex = text.lastIndexOf('@')
        val prefix = if (atIndex >= 0) text.substring(0, atIndex) else text
        // Persist the stable id so names containing spaces or punctuation cannot be
        // truncated by the mention parser; the attached chip still shows the friendly name.
        setInput("${prefix}@${item.id} ")
    }

    /** 从输入框中整块移除某个已挂载的 @能力 标签 */
    fun removeMention(item: MentionItem) {
        val current = _input.value
        // 正则替换 @name 及其后可能跟随的空格
        val updated = current.replace(Regex("""@${Regex.escape(item.name)}\s*"""), "")
            .replace(Regex("""@${Regex.escape(item.id)}\s*"""), "")
            .trimStart()
        setInput(updated)
    }

    fun triggerMentionInput() {
        val current = _input.value
        if (!current.endsWith("@")) {
            setInput(if (current.isBlank()) "@" else "$current @")
        }
    }

    fun setSendMode(mode: ComposerSendMode) {
        _sendMode.value = mode
    }

    private val _pendingAttachments = MutableStateFlow<List<ChatAttachment>>(emptyList())

    /** 待发送附件；处理（复制/压缩/编码）在 IO 线程完成 */
    val pendingAttachments: StateFlow<List<ChatAttachment>> = _pendingAttachments.asStateFlow()

    /** 附件处理中（复制/压缩/编码期间为 true，UI 据此展示加载指示） */
    private val _attachmentsProcessing = MutableStateFlow(false)
    val attachmentsProcessing: StateFlow<Boolean> = _attachmentsProcessing.asStateFlow()

    /** 需要以 Toast 提示用户的轻量通知（附件失败、模型档案已存在等），展示后调用 clearNotice() */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    fun onAttachmentsPicked(uris: List<Uri>, isImage: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _attachmentsProcessing.value = true
            val items = uris.mapNotNull { AttachmentHelper.processUri(context, it, isImage) }
            val failed = uris.size - items.size
            if (failed > 0) {
                _notice.value = context.getString(R.string.chat_attachment_process_failed, failed)
            }
            _pendingAttachments.update { it + items }
            _attachmentsProcessing.value = false
        }
    }

    fun removeAttachment(attachment: ChatAttachment) {
        _pendingAttachments.update { list -> list.filter { it.id != attachment.id } }
    }

    /** 组装附件挂载说明并委托 send() 发送；View 只需在输入框非空或有附件时触发 */
    fun sendFromComposer() {
        val trimmedInput = _input.value.trim()
        val attachments = _pendingAttachments.value
        if (trimmedInput.isBlank() && attachments.isEmpty()) return
        val imageUrls = attachments.mapNotNull { it.base64DataUrl }
        val nonImageFiles = attachments.filter { !it.isImage }
        val fullMessage = buildString {
            if (trimmedInput.isNotBlank()) {
                append(trimmedInput)
            } else if (nonImageFiles.isNotEmpty()) {
                append(context.getString(R.string.chat_attachment_files_prompt))
            } else if (imageUrls.isNotEmpty()) {
                append(context.getString(R.string.chat_attachment_images_prompt))
            }
            if (attachments.isNotEmpty()) {
                append(context.getString(R.string.chat_attachment_mount_header))
                attachments.forEachIndexed { i, att ->
                    val guestPath = att.guestFilePath ?: "/attachments/${att.name}"
                    val kind = context.getString(if (att.isImage) R.string.chat_attachment_image else R.string.chat_attachment_file)
                    append(context.getString(R.string.chat_attachment_line, i + 1, kind, att.name, AttachmentHelper.formatFileSize(att.sizeBytes), guestPath))
                }
                append(context.getString(R.string.chat_attachment_access_hint))
            }
        }
        _pendingAttachments.value = emptyList()
        send(fullMessage, imageUrls)
    }

    fun send(customText: String? = null, imageUrls: List<String> = emptyList()) {
        val rawText = (customText ?: _input.value).trim()
        if (rawText.isBlank() && imageUrls.isEmpty()) return
        setInput("")

        val pinnedIds = _pinnedMentionIds.value
        val effectiveText = if (pinnedIds.isNotEmpty()) {
            val existingMentions = top.wanxiang.app.harness.MentionExtractor.parse(rawText)
            val missingPins = pinnedIds.filter { pin ->
                pin.lowercase() !in existingMentions
            }
            if (missingPins.isNotEmpty()) {
                rawText + missingPins.joinToString(prefix = " ", separator = " ") { "@$it" }
            } else {
                rawText
            }
        } else {
            rawText
        }

        if (!running.value) {
            harnessLoop.send(effectiveText, imageUrls = imageUrls)
        } else {
            when (_sendMode.value) {
                ComposerSendMode.STEER -> harnessLoop.steer(effectiveText, imageUrls = imageUrls)
                ComposerSendMode.NEXT_RUN -> harnessLoop.send(effectiveText, imageUrls = imageUrls)
            }
        }
    }

    /** 创建针对工具安装或沙箱异常的专属自愈会话并立即启动诊断 */
    fun startHealingTask(title: String, prompt: String) {
        viewModelScope.launch {
            harnessLoop.newSession(title = title)
            setInput("")
            harnessLoop.send(prompt)
        }
    }

    /** 重新生成最后一次回复 */
    fun regenerateLast() {
        if (running.value) return
        harnessLoop.regenerateLast()
    }

    fun retryToolCall(toolCallId: String) {
        if (running.value) return
        harnessLoop.retryToolCall(toolCallId)
    }

    fun createBranch(messageId: String, displayName: String) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank() || running.value) return
        viewModelScope.launch {
            runCatching {
                laneManager.createConversationBranch(sessionId, displayName, messageId)
                harnessLoop.activateBranch(messageId, sessionId)
            }
            _branchRefresh.value++
        }
    }

    fun switchBranch(branch: ConversationBranch) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank() || running.value) return
        viewModelScope.launch {
            harnessLoop.activateBranch(branch.leafId, sessionId)
            _branchRefresh.value++
        }
    }

    /** 编辑并重新发送某条用户消息 */
    fun editAndResend(userMessageId: String, newText: String) {
        if (running.value || newText.isBlank()) return
        harnessLoop.truncateAndResend(userMessageId, newText)
    }

    /** 删除单条消息 */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            harnessLoop.deleteMessage(messageId)
        }
    }

    fun stop() = harnessLoop.cancel()

    fun removePendingMessage(index: Int) = harnessLoop.removePendingMessage(index)

    fun removeQueuedPrompt(prompt: QueuedPrompt) {
        val sameQueue = queuedPrompts.value.filter { it.queue == prompt.queue }
        val index = sameQueue.indexOfFirst { it.id == prompt.id }
        if (index >= 0) harnessLoop.removeQueuedPrompt(prompt.queue, index)
    }

    /** 将排队消息转为即时修正（Steer）指令并插入下一轮 */
    fun convertQueuedPromptToSteer(prompt: QueuedPrompt) {
        removeQueuedPrompt(prompt)
        harnessLoop.steer(prompt.message.text, imageUrls = prompt.message.imageUrls)
    }

    /** 编辑排队消息：先移出队列（不发送），把原文回显到输入框，修改后由用户手动发送。 */
    fun editQueuedPrompt(prompt: QueuedPrompt) {
        removeQueuedPrompt(prompt)
        setInput(prompt.message.text)
    }

    fun clearPendingMessages() = harnessLoop.clearPendingMessages()

    fun clearError() = harnessLoop.clearError()

    /** 新建会话（支持自定义标题并关联工作区）。 */
    fun createSession(title: String = "", workspace: String = "", projectType: String = "") {
        viewModelScope.launch {
            harnessLoop.newSession(title.trim().ifBlank { context.getString(R.string.chat_new_session) }, workspace, projectType)
        }
    }

    fun switchSession(id: String) {
        viewModelScope.launch { harnessLoop.loadSession(id) }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch { harnessLoop.deleteSession(id) }
    }

    fun renameSession(id: String, title: String) {
        viewModelScope.launch { harnessLoop.renameSession(id, title) }
    }

    // ---- 撤回到此轮（Checkpoint Rewind） ----

    /**
     * 撤回到 [messageId] 所在用户轮：按 checkpoint 锚点定位轮次，
     * prepare/commit 两段式执行；CONVERSATION/BOTH 会派生回退分支并切换过去。
     */
    fun rewindToMessage(messageId: String, scope: top.wanxiang.app.harness.checkpoint.RewindScope) {
        viewModelScope.launch(Dispatchers.IO) {
            val sessionId = harnessLoop.currentSessionId.value
            val target = harnessLoop.sessionCheckpoints(sessionId)
                .firstOrNull { it.anchorMessageId == messageId }
            if (target == null) {
                _notice.value = context.getString(R.string.chat_rewind_no_checkpoint)
                return@launch
            }
            runCatching {
                val plan = harnessLoop.prepareRewind(sessionId, target.turn, scope)
                val result = harnessLoop.commitRewind(plan, workspace.value)
                val forkedSessionId = result.forkedSessionId
                if (forkedSessionId != null) {
                    harnessLoop.loadSession(forkedSessionId)
                }
                _notice.value = buildString {
                    append(context.getString(R.string.chat_rewind_done, result.filesRestored, result.filesDeleted))
                    if (forkedSessionId != null) append(" · ").append(context.getString(R.string.chat_rewind_switched))
                    result.note?.let { append("\n").append(it) }
                }
            }.onFailure { throwable ->
                _notice.value = context.getString(R.string.chat_rewind_failed, throwable.message ?: "unknown")
            }
        }
    }

    // ---- 模型管理 ----

    private val _providerModelIds = MutableStateFlow<List<String>>(emptyList())
    val providerModelIds: StateFlow<List<String>> = _providerModelIds.asStateFlow()

    private val _discoveringProviderModels = MutableStateFlow(false)
    val discoveringProviderModels: StateFlow<Boolean> = _discoveringProviderModels.asStateFlow()

    private val _providerModelDiscoveryError = MutableStateFlow<String?>(null)
    val providerModelDiscoveryError: StateFlow<String?> = _providerModelDiscoveryError.asStateFlow()

    private val _modelPickerProfileId = MutableStateFlow<String?>(null)
    val modelPickerProfileId: StateFlow<String?> = _modelPickerProfileId.asStateFlow()

    fun addModel(name: String, provider: String, model: String, baseUrl: String) {
        val trimmedName = name.trim().ifBlank { model }
        val trimmedModel = model.trim()
        if (trimmedModel.isBlank()) return
        viewModelScope.launch {
            val id = "${provider.trim().lowercase()}-${trimmedModel.lowercase()}"
                .replace(Regex("[^a-z0-9-]"), "-")
            // 派生 id 与既有档案冲突时提示而非静默覆盖
            if (aiModelDao.findById(id) != null) {
                _notice.value = context.getString(R.string.chat_model_profile_exists)
                return@launch
            }
            profileWriter.upsertProfile(
                AiProfileWriter.UpsertRequest(
                    id = id,
                    name = trimmedName,
                    provider = provider.trim().ifBlank { trimmedModel },
                    model = trimmedModel,
                    baseUrl = baseUrl.trim(),
                ),
            )
        }
    }

    fun setActiveModel(id: String) {
        selectModel(id)
    }

    private val _subagentResult = MutableStateFlow<SubagentResultUiState?>(null)
    val subagentResult: StateFlow<SubagentResultUiState?> = _subagentResult.asStateFlow()

    fun openSubagentResult(branch: ConversationBranch) {
        if (branch.kind != ConversationBranchKind.SUBAGENT || branch.laneName.isNullOrBlank()) return
        val sessionId = currentSessionId.value.takeIf { it.isNotBlank() } ?: return
        _subagentResult.value = SubagentResultUiState(sessionId = sessionId, branch = branch)
        loadSubagentResult(sessionId, branch)
    }

    fun refreshSubagentResult() {
        val current = _subagentResult.value ?: return
        _subagentResult.value = current.copy(loading = true, error = null)
        loadSubagentResult(current.sessionId, current.branch)
    }

    fun closeSubagentResult() {
        _subagentResult.value = null
    }

    private fun loadSubagentResult(sessionId: String, branch: ConversationBranch) {
        val laneName = branch.laneName ?: return
        viewModelScope.launch {
            runCatching {
                val latestBranch = laneManager.branches(sessionId)
                    .firstOrNull { it.kind == ConversationBranchKind.SUBAGENT && it.laneName == laneName }
                    ?: branch
                latestBranch to laneManager.subagentTranscript(sessionId, laneName)
            }.onSuccess { (latestBranch, transcript) ->
                val current = _subagentResult.value
                if (current?.sessionId == sessionId && current.branch.laneName == laneName) {
                    _subagentResult.value = current.copy(
                        branch = latestBranch,
                        messages = transcript,
                        loading = false,
                        error = null,
                    )
                }
            }.onFailure { throwable ->
                val current = _subagentResult.value
                if (current?.sessionId == sessionId && current.branch.laneName == laneName) {
                    _subagentResult.value = current.copy(
                        loading = false,
                        error = throwable.message ?: "无法读取子智能体成果",
                    )
                }
            }
        }
    }

    fun selectModel(id: String, subModel: String? = null) {
        viewModelScope.launch {
            val entity = aiModelDao.findById(id) ?: return@launch
            val sessionId = currentSessionId.value.takeIf { it.isNotBlank() } ?: return@launch
            val variant = subModel?.trim()?.takeIf { it.isNotBlank() }
                ?: entity.model.substringBefore(',').trim().takeIf { it.isNotBlank() }
            sessionDao.setModelSelection(sessionId, entity.id, variant, System.currentTimeMillis())
        }
    }

    /** 打开某供应商档案后，通过 v1/models 拉取同端点可用模型列表。 */
    fun openProviderModelPicker(profileId: String) {
        _modelPickerProfileId.value = profileId
        discoverProviderModels(profileId)
    }

    fun closeProviderModelPicker() {
        _modelPickerProfileId.value = null
        _providerModelIds.value = emptyList()
        _providerModelDiscoveryError.value = null
        _discoveringProviderModels.value = false
    }

    fun discoverProviderModels(profileId: String) {
        viewModelScope.launch {
            val profile = aiModelDao.findById(profileId) ?: return@launch
            _discoveringProviderModels.value = true
            _providerModelDiscoveryError.value = null
            _providerModelIds.value = emptyList()
            val provider = providerCatalog.find(profile.provider)
            val baseUrl = profile.baseUrl.ifBlank { provider.baseUrl }
            val cleanUrl = ProviderEndpointPolicy.normalizeUrl(baseUrl)
            if (!ProviderEndpointPolicy.isSafeBaseUrl(cleanUrl)) {
                _providerModelDiscoveryError.value = context.getString(R.string.chat_model_discovery_bad_url)
                _discoveringProviderModels.value = false
                return@launch
            }
            val apiKey = profile.secretRef.takeIf { it.isNotBlank() }
                ?.let { providerRepository.readModelApiKeys(it).firstOrNull() }
                ?: providerRepository.readApiKey()
            runCatching { modelDiscovery.discover(provider, cleanUrl, apiKey) }
                .onSuccess { ids ->
                    _providerModelIds.value = ids
                    if (ids.isEmpty()) {
                        _providerModelDiscoveryError.value = context.getString(R.string.chat_model_discovery_empty)
                    }
                }
                .onFailure {
                    Log.w(TAG, "模型发现失败 profile=$profileId", it)
                    _providerModelDiscoveryError.value = context.getString(R.string.chat_model_discovery_failed)
                }
            _discoveringProviderModels.value = false
        }
    }

    /**
     * 在同一供应商档案内为当前会话选择具体模型 ID（复用 baseUrl / Key / 推理参数）。
     * 档案本身保持不变，避免其他会话被连带切换。
     */
    fun switchModelInProfile(profileId: String, modelId: String) {
        val trimmed = modelId.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val profile = aiModelDao.findById(profileId) ?: return@launch
            val sessionId = currentSessionId.value.takeIf { it.isNotBlank() } ?: return@launch
            sessionDao.setModelSelection(sessionId, profile.id, trimmed, System.currentTimeMillis())
            closeProviderModelPicker()
        }
    }

    fun updateActiveModelReasoning(mode: String?, effort: String?) {
        viewModelScope.launch {
            val sessionModelId = currentSessionId.value.takeIf { it.isNotBlank() }
                ?.let { sessionDao.findById(it)?.modelId }
            val profile = sessionModelId?.let { aiModelDao.findById(it) } ?: aiModelDao.activeModel() ?: return@launch
            aiModelDao.updateReasoning(profile.id, mode, effort)
        }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch {
            profileWriter.deleteProfile(id)
        }
    }
}

internal fun mergeHistoricalAndLiveEvents(
    sessionId: String,
    messages: List<HarnessMessage>,
    live: List<HarnessEvent>,
): List<HarnessEvent> {
    if (sessionId.isBlank()) return emptyList()
    if (live.isEmpty()) return synthesizeHistoricalEvents(sessionId, messages)
    if (messages.isEmpty()) return live

    val liveEntryIds = mutableSetOf<String>()
    live.forEach { event ->
        when (event) {
            is HarnessEvent.ProviderRoundSettled -> event.entryId?.let { liveEntryIds.add(it) }
            is HarnessEvent.ToolCallStarted -> liveEntryIds.add(event.toolCallId)
            is HarnessEvent.ToolCallSettled -> liveEntryIds.add(event.toolCallId)
            else -> Unit
        }
    }

    val liveMinTimestamp = live.minOfOrNull { it.timestamp } ?: Long.MAX_VALUE
    val priorMessages = messages.filter { msg ->
        msg.createdAt < liveMinTimestamp && !liveEntryIds.contains(msg.id)
    }

    if (priorMessages.isEmpty()) return live

    val priorEvents = synthesizeHistoricalEvents(sessionId, priorMessages)
    return priorEvents + live
}

private fun synthesizeHistoricalEvents(sessionId: String, messages: List<HarnessMessage>): List<HarnessEvent> {
    if (sessionId.isBlank() || messages.isEmpty()) return emptyList()
    val events = mutableListOf<HarnessEvent>()
    var currentRound = 0
    var opId = "hist-$sessionId"
    val toolCallMap = messages.filterIsInstance<ToolCall>().associateBy { it.id }

    messages.forEach { msg ->
        when (msg) {
            is UserMessage -> {
                currentRound++
                opId = "hist-${msg.id}"
                events.add(
                    HarnessEvent.OperationStarted(
                        sessionId = sessionId,
                        timestamp = msg.createdAt,
                        operationId = opId,
                        laneName = "main",
                    )
                )
            }
            is AssistantText -> {
                events.add(
                    HarnessEvent.ProviderRoundStarted(
                        sessionId = sessionId,
                        timestamp = msg.createdAt,
                        operationId = opId,
                        round = currentRound,
                        attempt = 1,
                        modelId = null,
                    )
                )
                events.add(
                    HarnessEvent.ProviderRoundSettled(
                        sessionId = sessionId,
                        timestamp = msg.createdAt,
                        operationId = opId,
                        round = currentRound,
                        entryId = msg.id,
                        inputTokens = 0L,
                        outputTokens = msg.text.length.toLong(),
                    )
                )
            }
            is ToolCall -> {
                events.add(
                    HarnessEvent.ToolCallStarted(
                        sessionId = sessionId,
                        timestamp = msg.createdAt,
                        operationId = opId,
                        toolCallId = msg.id,
                        toolName = msg.tool.name.lowercase(),
                    )
                )
            }
            is ToolResult -> {
                val call = toolCallMap[msg.toolCallId]
                val toolName = call?.tool?.name?.lowercase() ?: "tool"
                events.add(
                    HarnessEvent.ToolCallSettled(
                        sessionId = sessionId,
                        timestamp = msg.createdAt,
                        operationId = opId,
                        toolCallId = msg.toolCallId,
                        toolName = toolName,
                        success = msg.success,
                        durationMs = msg.durationMs,
                    )
                )
            }
            else -> Unit
        }
    }
    return events
}

enum class ComposerSendMode(val queue: PromptQueue) {
    STEER(PromptQueue.STEER),
    NEXT_RUN(PromptQueue.NEXT_RUN),
}

private data class ContextUsageInputs(
    val currentMessages: List<HarnessMessage>,
    val activeModel: AiModelEntity?,
    val skills: List<top.wanxiang.app.core.model.AgentSkill>,
    val mcps: List<top.wanxiang.app.core.model.McpServerConfig>,
    val defaultBudget: Int,
)

data class ContextUsage(
    val usedTokens: Int = 0,
    val limitTokens: Int = 128_000,
    val systemTokens: Int = 0,
    val toolTokens: Int = 0,
    val conversationTokens: Int = 0,
    val compacted: Boolean = false,
    val cachedTokens: Long = 0L,
    val cacheHitRatePercent: Int? = null,
)

/** Git 面板状态：当前会话工作区的分支、改动、分支列表与提交历史（不跨会话共享）。 */
data class GitFileChange(
    val status: Char,
    val path: String,
)

data class GitPanelState(
    val loading: Boolean = false,
    val branch: String? = null,
    val staged: List<GitFileChange> = emptyList(),
    val unstaged: List<GitFileChange> = emptyList(),
    val untracked: List<String> = emptyList(),
    val localBranches: List<String> = emptyList(),
    val remoteBranches: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val commits: List<String> = emptyList(),
    val hasIdentity: Boolean = false,
    val hasRemote: Boolean = false,
    val notARepo: Boolean = false,
    val diffPath: String? = null,
    val diffText: String? = null,
    val diffLoading: Boolean = false,
    /** 本地脏时 pull 前需要用户二次确认（可能被覆盖或产生冲突）。 */
    val pullDirtyConfirm: Boolean = false,
    val commitDetailHash: String? = null,
    val commitDetailText: String? = null,
    val commitDetailLoading: Boolean = false,
    val error: String? = null,
)


data class MentionItem(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val type: MentionType,
    val icon: top.wanxiang.app.ui.components.RuntimeIconName,
)

enum class MentionType {
    SKILL, MCP_SERVER
}
