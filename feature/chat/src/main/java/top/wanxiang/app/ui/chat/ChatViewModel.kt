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
import top.wanxiang.app.runtime.shell.ShellCommand
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
import kotlinx.coroutines.delay
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
    private val providerClient: top.wanxiang.app.harness.ProviderClient,
    private val debugActionBus: top.wanxiang.app.runtime.debug.DebugActionBus,
    private val textExtractor: top.wanxiang.app.runtime.sandbox.SandboxTextExtractor,
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
        // Debug 广播总线：ChatViewModel 收到 CloneRepo 就直接调 gitClone，绕过 adb+IME 不可靠问题
        viewModelScope.launch {
            debugActionBus.flow.collect { action ->
                when (action) {
                    is top.wanxiang.app.runtime.debug.DebugActionBus.Action.CloneRepo -> gitClone(action.url)
                    is top.wanxiang.app.runtime.debug.DebugActionBus.Action.Diagnostic -> {
                        viewModelScope.launch(Dispatchers.IO) {
                            val r = runCatching {
                                linuxRuntime.execute(
                                    top.wanxiang.app.runtime.shell.ShellCommand(
                                        commandLine = action.command + " 2>&1",
                                        workingDirectory = "/root",
                                        timeoutMs = 300_000L,
                                    ),
                                )
                            }.getOrNull()
                            val out = ((r?.stdout ?: "") + "\n" + (r?.stderr ?: "")).trim().take(2000)
                            android.util.Log.i("WanxiangDiag", "CMD=${action.command} → EXIT=${r?.exitCode} OUT=$out")
                            _gitOpMessage.value = GitOpMessage.Error("诊断：exit=${r?.exitCode}\n$out")
                        }
                    }
                    is top.wanxiang.app.runtime.debug.DebugActionBus.Action.SwitchWorkspace -> {
                        harnessLoop.debugSetWorkspace(action.path)
                        _gitOpMessage.value = GitOpMessage.Ok("已切工作区到 ${action.path}")
                        refreshGitStatus()
                    }
                    top.wanxiang.app.runtime.debug.DebugActionBus.Action.RefreshStatus -> refreshGitStatus()
                    is top.wanxiang.app.runtime.debug.DebugActionBus.Action.AddCred ->
                        addGitCredential(action.name, action.host, action.user, action.token)
                    top.wanxiang.app.runtime.debug.DebugActionBus.Action.ClearCreds -> {
                        viewModelScope.launch(Dispatchers.IO) { gitPreferences.setCredentials(emptyList()) }
                    }
                    is top.wanxiang.app.runtime.debug.DebugActionBus.Action.VerifyCred ->
                        verifyGitCredential(action.id)
                    is top.wanxiang.app.runtime.debug.DebugActionBus.Action.FetchRepos ->
                        fetchUserRepos(action.host)
                    top.wanxiang.app.runtime.debug.DebugActionBus.Action.AiGenerateCommit ->
                        aiGenerateCommitMessage()
                    top.wanxiang.app.runtime.debug.DebugActionBus.Action.GitPull -> gitPull()
                    top.wanxiang.app.runtime.debug.DebugActionBus.Action.GitPush -> gitPush()
                    top.wanxiang.app.runtime.debug.DebugActionBus.Action.GitStash -> gitStash()
                    top.wanxiang.app.runtime.debug.DebugActionBus.Action.GitStashPop -> gitStashPop()
                    top.wanxiang.app.runtime.debug.DebugActionBus.Action.GitRevertAll -> gitRevertAllUnstaged()
                    is top.wanxiang.app.runtime.debug.DebugActionBus.Action.GitRenameBranch ->
                        gitRenameBranch(action.old, action.new)
                    is top.wanxiang.app.runtime.debug.DebugActionBus.Action.GitDeleteRemote ->
                        gitDeleteRemoteBranch(action.name)
                    is top.wanxiang.app.runtime.debug.DebugActionBus.Action.GitCheckout ->
                        gitCheckout(action.branch)
                    is top.wanxiang.app.runtime.debug.DebugActionBus.Action.ExtractText -> {
                        viewModelScope.launch(Dispatchers.IO) {
                            val r = runCatching { textExtractor.extract(action.guestPath, action.name) }
                            val msg = when (val res = r.getOrNull()) {
                                is top.wanxiang.app.runtime.sandbox.SandboxTextExtractor.Result.Ok ->
                                    "OK len=${res.text.length} truncated=${res.truncated} 前 200: ${res.text.take(200)}"
                                is top.wanxiang.app.runtime.sandbox.SandboxTextExtractor.Result.Skipped -> "SKIP ${res.reason}"
                                is top.wanxiang.app.runtime.sandbox.SandboxTextExtractor.Result.Failed -> "FAIL ${res.error}"
                                null -> "EXC ${r.exceptionOrNull()?.message}"
                            }
                            android.util.Log.i("WanxiangDiag", "extract ${action.name} → $msg")
                            _gitOpMessage.value = GitOpMessage.Error("抽取 ${action.name}: $msg")
                        }
                    }
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
            val statusOut = runGitRead(ws, "git status --porcelain -b -uall")
            if (statusOut == null || statusOut.contains("not a git repository", ignoreCase = true)) {
                _gitPanelState.value = GitPanelState(loading = false, notARepo = true)
                return@launch
            }
            val lines = statusOut.lines().filter { it.isNotBlank() }
            val branchHeader = lines.firstOrNull { it.startsWith("## ") }?.removePrefix("## ") ?: ""
            val branch = branchHeader
                .substringBefore("...")
                .substringBefore(" [")
                .trim()
                .removePrefix("No commits yet on ")
                .takeIf { it.isNotBlank() }
            // 解析形如 `[ahead 2, behind 5]` 或 `[ahead 2]` / `[behind 3]`
            val aheadBehind = Regex("""\[([^\]]+)\]""").find(branchHeader)?.groupValues?.get(1)?.let { inner ->
                val ahead = Regex("ahead (\\d+)").find(inner)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val behind = Regex("behind (\\d+)").find(inner)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (ahead == 0 && behind == 0) null else ahead to behind
            }
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
            val commits = runGitRead(ws, "git log --pretty=format:%h%x1f%s%x1f%an%x1f%ar -30")
                ?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            val hasIdentity = runGitRead(ws, "git config user.name")?.isNotBlank() == true &&
                runGitRead(ws, "git config user.email")?.isNotBlank() == true
            val hasRemote = runGitRead(ws, "git remote")?.isNotBlank() == true
            val stashCount = runGitRead(ws, "git stash list")
                ?.lines()?.count { it.isNotBlank() } ?: 0
            _gitPanelState.value = GitPanelState(
                loading = false,
                branch = branch,
                aheadBehind = aheadBehind,
                stashCount = stashCount,
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
        runStreamingGitOp(label = "拉取", cmd = "git pull --progress", resolveHostFromOrigin = true, timeoutMs = 300_000L)
    }

    fun dismissPullDirtyConfirm() {
        _gitPanelState.value = _gitPanelState.value.copy(pullDirtyConfirm = false)
    }
    fun gitPush() {
        viewModelScope.launch(Dispatchers.IO) {
            val ws = currentGitWs()
            // 检查有无 upstream；若没 → 首次 push 用 `git push -u origin <branch>` 自动建立
            val branch = _gitPanelState.value.branch ?: runGitRead(ws, "git rev-parse --abbrev-ref HEAD")?.trim()
            val upstreamSet = runGitRead(ws, "git rev-parse --abbrev-ref --symbolic-full-name @{u}")
            val hasUpstream = upstreamSet != null && !upstreamSet.contains("unknown") && !upstreamSet.contains("no upstream")
            val cmd = if (hasUpstream) "git push --progress"
                else "git push --progress -u origin ${shellQuote(branch ?: "HEAD")}"
            runStreamingGitOp(label = "推送", cmd = cmd, resolveHostFromOrigin = true, timeoutMs = 300_000L)
        }
    }
    /** 一键 stash 当前所有改动（含未跟踪），message 可选。 */
    fun gitStash(message: String = "") = runGitWrite(
        "git stash push -u${if (message.isNotBlank()) " -m " + shellQuote(message) else ""}",
    )
    /** 弹出最近一个 stash（保留记录用 apply；彻底用 pop）。 */
    fun gitStashPop() = runGitWrite("git stash pop")
    fun gitStashApply() = runGitWrite("git stash apply")
    fun gitStashDrop(index: Int = 0) = runGitWrite("git stash drop stash@{$index}")
    /**
     * 切分支前检查工作区是否脏（P1-12）：
     * - 若 dirty + 无 `checkoutDirtyConfirm` → 弹二次确认（可能覆盖或冲突）
     * - 干净或已确认 → 直接 checkout
     */
    fun gitCheckout(branch: String) {
        val s = _gitPanelState.value
        val dirty = s.staged.isNotEmpty() || s.unstaged.isNotEmpty()
        if (dirty && s.pendingCheckout != branch) {
            _gitPanelState.value = s.copy(pendingCheckout = branch)
            return
        }
        _gitPanelState.value = _gitPanelState.value.copy(pendingCheckout = null)
        runGitWrite("git checkout ${shellQuote(branch)}")
    }

    fun confirmCheckoutDirty(branch: String) {
        _gitPanelState.value = _gitPanelState.value.copy(pendingCheckout = null)
        runGitWrite("git checkout ${shellQuote(branch)}")
    }

    fun dismissCheckoutConfirm() {
        _gitPanelState.value = _gitPanelState.value.copy(pendingCheckout = null)
    }
    fun gitCreateBranch(name: String) = runGitWrite("git checkout -b ${shellQuote(name)}")
    fun gitDeleteBranch(branch: String) = runGitWrite("git branch -d ${shellQuote(branch)}")
    fun gitInit() = runGitWrite("git init")
    /**
     * 克隆到当前工作区下的一个以仓库名命名的子目录。**流式进度**通过 [gitProgress] StateFlow 上抛，
     * 顶栏横幅实时显示；成功后 [gitOpMessage] 带 [GitOpAction.SwitchWorkspaceTo] 一键切工作区。
     * 失败按错误种类映射人话文案（同名目录 / 网络 / 认证 / URL 无效等），网络类自动重试 2 次。
     */
    fun gitClone(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            _gitOpMessage.value = GitOpMessage.Error("仓库 URL 是空的，先粘贴一个再点克隆")
            return
        }
        val repoName = trimmed.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
        val ws = currentGitWs()
        viewModelScope.launch(Dispatchers.IO) {
            // 工作区不存在 → 明确告知用户（不再让 PRoot 静默回退到 / 导致 clone 到根目录）
            if (!workspaceExists(ws)) {
                _gitOpMessage.value = GitOpMessage.Error(
                    "工作区目录 `$ws` 不存在。先到工坊页新建该工作区，或把会话的工作区切到一个已存在的目录",
                )
                return@launch
            }
            // 目标已存在 → 明确提示，给"清空再试"选项
            if (workspaceExists("$ws/$repoName")) {
                _gitOpMessage.value = GitOpMessage.Error(
                    "`$repoName/` 已存在。要不要清空再重新克隆？（会删除现有内容）",
                    action = GitOpAction.RetryWithClean(trimmed, "$ws/$repoName"),
                )
                return@launch
            }
            // 网络类自动重试：2 次退避（1s → 3s）
            var attempt = 0
            var lastError = ""
            while (attempt < 3 && !cancelRequested.get()) {
                if (attempt > 0) {
                    _gitProgress.value = GitProgress(label = "重试第 $attempt 次…")
                    delay(1_000L * attempt)
                }
                cancelRequested.set(false)
                _gitProgress.value = GitProgress(label = "开始克隆 $repoName（第 ${attempt + 1} 次）…")
                val outcome = doCloneOnce(trimmed, repoName, ws)
                when (outcome) {
                    is CloneOutcome.Success -> {
                        _gitProgress.value = null
                        _gitOpMessage.value = GitOpMessage.Ok(
                            message = "✓ 已克隆到 $repoName/",
                            action = GitOpAction.SwitchWorkspaceTo("$ws/$repoName"),
                        )
                        refreshGitStatus()
                        return@launch
                    }
                    is CloneOutcome.Cancelled -> {
                        _gitProgress.value = null
                        _gitOpMessage.value = GitOpMessage.Error("已取消")
                        return@launch
                    }
                    is CloneOutcome.Failure -> {
                        lastError = outcome.rawOutput
                        // 网络/临时错误才重试；其它直接失败
                        if (!isRetryable(outcome.rawOutput)) {
                            _gitProgress.value = null
                            val friendly = translateGitError(outcome.rawOutput, repoName)
                            _gitOpMessage.value = GitOpMessage.Error(friendly, action = actionForError(friendly, trimmed, "$ws/$repoName"))
                            return@launch
                        }
                        attempt++
                    }
                }
            }
            _gitProgress.value = null
            _gitOpMessage.value = GitOpMessage.Error(
                "克隆失败（重试 3 次仍不通）：\n" + translateGitError(lastError, repoName),
                action = GitOpAction.RetrySame("clone:$trimmed"),
            )
        }
    }

    private sealed interface CloneOutcome {
        data object Success : CloneOutcome
        data class Failure(val rawOutput: String) : CloneOutcome
        data object Cancelled : CloneOutcome
    }

    private suspend fun doCloneOnce(url: String, repoName: String, ws: String): CloneOutcome {
        val host = GitAuth.hostOf(url)
        val creds = gitPreferences.credentials.first()
        val cred = GitAuth.findCredential(creds, host)
        val raw = "git clone --progress --depth 1 ${shellQuote(url)} ${shellQuote(repoName)}"
        val effective = if (cred != null) GitAuth.wrap(raw, cred) else raw
        // 用 forcePty 让 git 走 tty，能吐 `% Receiving objects: NN%` 进度
        val result = runCatching {
            linuxRuntime.execute(
                top.wanxiang.app.runtime.shell.ShellCommand(
                    commandLine = "$effective 2>&1",
                    workingDirectory = ws,
                    timeoutMs = 600_000L,
                    onOutput = { chunk -> applyGitProgress(chunk, "克隆 $repoName…") },
                    forcePty = true,
                ),
            )
        }
        val r = result.getOrNull()
        val out = ((r?.stdout ?: "") + "\n" + (r?.stderr ?: "")).trim()
        return when {
            cancelRequested.get() -> CloneOutcome.Cancelled
            r != null && r.isSuccess -> CloneOutcome.Success
            else -> CloneOutcome.Failure(out.ifBlank { "git 无输出（可能网络不通或 URL 错误）" })
        }
    }

    private suspend fun workspaceExists(path: String): Boolean {
        val r = runCatching {
            linuxRuntime.execute(
                top.wanxiang.app.runtime.shell.ShellCommand(
                    commandLine = "test -d ${shellQuote(path)} && echo yes || echo no",
                    workingDirectory = "/root",
                    timeoutMs = 10_000L,
                ),
            )
        }.getOrNull()
        return r?.stdout?.trim() == "yes"
    }

    private fun isRetryable(out: String): Boolean {
        val l = out.lowercase()
        return "failed to connect" in l || "could not resolve host" in l ||
            "connection reset" in l || "timed out" in l || "rpc failed" in l ||
            "early eof" in l || "the requested url returned error: 5" in l
    }

    /** 把 git stderr 翻成人话（保留原摘要供调试）。 */
    private fun translateGitError(out: String, repoName: String = ""): String {
        val l = out.lowercase()
        val core = when {
            "already exists and is not an empty directory" in l ->
                "`$repoName` 目录已存在且非空。可清空再试或改目录名。"
            "authentication failed" in l || "invalid credentials" in l || "password authentication" in l ->
                "认证失败：用户名或 PAT 不对/过期。到「凭证」页更新。"
            "could not read username" in l || "terminal prompts disabled" in l ->
                "私有仓库需要凭证。请先到「凭证」页添加该主机的 PAT。"
            "permission denied" in l ->
                "权限不足：账号没这个仓库的读写权，或 PAT 缺 scope。"
            "repository not found" in l || "not found" in l && "http" in l ->
                "仓库不存在（404）：URL 拼错了，或它是私有的但你无权访问。"
            "unable to access" in l || "failed to connect" in l || "could not resolve host" in l ->
                "网络不通：手机没连上代理，或 GitHub/Gitee 不可达。"
            "connection reset" in l || "timed out" in l ->
                "网络被重置：可能中途断流。稍后再试。"
            "no space left on device" in l ->
                "手机存储不够。清理工作区或卸载一些项目再试。"
            "empty reply" in l || "rpc failed" in l ->
                "服务端断开：可能仓库太大或对方限流。可以试浅克隆。"
            else -> out.take(400)
        }
        return core
    }

    private fun actionForError(friendly: String, url: String, targetDir: String): GitOpAction? = when {
        "已存在" in friendly -> GitOpAction.RetryWithClean(url, targetDir)
        "网络不通" in friendly || "网络被重置" in friendly || "服务端断开" in friendly ->
            GitOpAction.RetrySame("clone:$url")
        else -> null
    }

    /** 「同名目录已存在，清空重试」的用户动作。 */
    fun retryCloneAfterClean(url: String, targetDir: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                linuxRuntime.execute(
                    top.wanxiang.app.runtime.shell.ShellCommand(
                        commandLine = "rm -rf ${shellQuote(targetDir)}",
                        workingDirectory = "/root",
                        timeoutMs = 15_000L,
                    ),
                )
            }
            gitClone(url)
        }
    }
    fun gitConfigIdentity(name: String, email: String) = runGitWrite("git config user.name ${shellQuote(name)} && git config user.email ${shellQuote(email)}")
    fun gitRevert(path: String) = runGitWrite("git checkout -- ${shellQuote(path)}")
    /** 一键回退所有已修改未暂存文件（等价 IDE 里 "Rollback" 未暂存部分）；未跟踪文件不动。 */
    fun gitRevertAllUnstaged() = runGitWrite("git checkout -- .")
    /** 重命名分支：在目标分支上执行 -m 会连带切换，所以先记下当前分支再切回。 */
    fun gitRenameBranch(oldName: String, newName: String) = runGitWrite(
        "git branch -m ${shellQuote(oldName)} ${shellQuote(newName)}",
    )
    /** 删除远程分支（不可撤销，UI 应二次确认）。 */
    fun gitDeleteRemoteBranch(name: String) = runGitNetworkOp(
        cmd = "git push origin --delete ${shellQuote(name)}",
        resolveHostFromOrigin = true,
        timeoutMs = 120_000L,
    )
    fun gitDeleteUntracked(path: String) = runGitWrite("rm -- ${shellQuote(path)}")
    fun gitCreateTag(name: String) = runGitWrite("git tag ${shellQuote(name)}")
    fun gitDeleteTag(name: String) = runGitWrite("git tag -d ${shellQuote(name)}")

    /**
     * 网络型 git 操作（clone/pull/push），带凭证注入。凭证不落 `.git/config`：通过
     * `GIT_CONFIG_KEY_0=credential.helper` + 一次性 `mktemp` 文件传给 git，跑完立即删除。
     * - clone：从入参 URL 解析 host；
     * - pull/push：从当前仓库 `origin` 反查 host。
     */
    /**
     * pull/push 通用流式：设 progress → 用 forcePty 让 git 吐进度 → 解析 → 完成清理。
     * 与 clone 共用 [applyGitProgress] + [translateGitError]。**网络类失败自动 3 次退避重试**。
     */
    private fun runStreamingGitOp(
        label: String,
        cmd: String,
        resolveHostFromOrigin: Boolean,
        timeoutMs: Long,
    ) {
        val ws = currentGitWs()
        viewModelScope.launch(Dispatchers.IO) {
            cancelRequested.set(false)
            val host = if (resolveHostFromOrigin) {
                GitAuth.hostOf(runGitRead(ws, "git remote get-url origin").orEmpty().trim())
            } else null
            val creds = gitPreferences.credentials.first()
            val cred = GitAuth.findCredential(creds, host)
            val effective = if (cred != null) GitAuth.wrap(cmd, cred) else cmd
            var attempt = 0
            var lastOut = ""
            var lastExit: Int? = null
            while (attempt < 3 && !cancelRequested.get()) {
                if (attempt > 0) {
                    _gitProgress.value = GitProgress(label = "$label 重试第 $attempt 次…")
                    delay(1_000L * attempt)
                } else {
                    _gitProgress.value = GitProgress(label = "$label 中…")
                }
                val result = runCatching {
                    linuxRuntime.execute(
                        top.wanxiang.app.runtime.shell.ShellCommand(
                            commandLine = "$effective 2>&1",
                            workingDirectory = ws,
                            timeoutMs = timeoutMs,
                            onOutput = { chunk -> applyGitProgress(chunk, "$label 中…") },
                            forcePty = true,
                        ),
                    )
                }.getOrNull()
                lastOut = ((result?.stdout ?: "") + "\n" + (result?.stderr ?: "")).trim()
                lastExit = result?.exitCode
                if (result != null && result.isSuccess) {
                    _gitProgress.value = null
                    _gitOpMessage.value = GitOpMessage.Ok("✓ $label 完成")
                    refreshGitStatus()
                    return@launch
                }
                if (!isRetryable(lastOut)) break
                attempt++
            }
            _gitProgress.value = null
            _gitOpMessage.value = GitOpMessage.Error(
                message = "$label 失败：\n" + translateGitError(lastOut),
                action = if (isRetryable(lastOut)) GitOpAction.RetrySame(cmd) else null,
            )
            refreshGitStatus()
        }
    }

    private fun runGitNetworkOp(
        cmd: String,
        resolveHostFromOrigin: Boolean,
        explicitHost: String? = null,
        timeoutMs: Long,
    ) {
        val ws = currentGitWs()
        val isPush = cmd.startsWith("git push")
        val isPull = cmd.startsWith("git pull")
        _gitOpMessage.value = GitOpMessage.Busy(if (isPush) "正在推送…" else if (isPull) "正在拉取…" else "正在执行 git 命令…")
        viewModelScope.launch(Dispatchers.IO) {
            val host = if (resolveHostFromOrigin) {
                GitAuth.hostOf(runGitRead(ws, "git remote get-url origin").orEmpty().trim())
            } else {
                explicitHost
            }
            val creds = gitPreferences.credentials.first()
            val cred = GitAuth.findCredential(creds, host)
            val effective = if (cred != null) GitAuth.wrap(cmd, cred) else cmd
            val result = runCatching {
                linuxRuntime.execute(
                    top.wanxiang.app.runtime.shell.ShellCommand(
                        commandLine = "$effective 2>&1",
                        workingDirectory = ws,
                        timeoutMs = timeoutMs,
                    ),
                )
            }
            val r = result.getOrNull()
            val out = ((r?.stdout ?: "") + "\n" + (r?.stderr ?: "")).trim()
            val label = if (isPush) "推送" else if (isPull) "拉取" else "操作"
            if (r != null && r.isSuccess) {
                _gitOpMessage.value = GitOpMessage.Ok("✓ $label 成功")
            } else {
                _gitOpMessage.value = GitOpMessage.Error("$label 失败：\n" + out.take(600).ifBlank { "git 无输出" })
            }
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

    /** 一次性 git 操作反馈（克隆/推送/拉取）：Busy / Ok / Error。UI 用 Snackbar 显示 + 消费后清回 Idle。 */
    private val _gitOpMessage = MutableStateFlow<GitOpMessage>(GitOpMessage.Idle)
    val gitOpMessage: StateFlow<GitOpMessage> = _gitOpMessage.asStateFlow()
    fun consumeGitOpMessage() { _gitOpMessage.value = GitOpMessage.Idle }

    /** 流式进度：ChatScreen 顶部横幅显示，git 每吐一行进度就更新。null = 无进行中操作。 */
    private val _gitProgress = MutableStateFlow<GitProgress?>(null)
    val gitProgress: StateFlow<GitProgress?> = _gitProgress.asStateFlow()

    /** 用户点横幅右侧 ✕ 取消当前 git 操作（把 [cancelRequested] 置 true，异步任务下次轮询时看到就 kill）。 */
    private val cancelRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    fun cancelGitOp() {
        if (_gitProgress.value != null) {
            cancelRequested.set(true)
            _gitProgress.value = _gitProgress.value?.copy(label = "正在取消…")
        }
    }

    /**
     * 从 git 的 stderr 输出里抽进度信息（`Receiving objects: 45% (123/270), 3.5 MiB | 1.2 MiB/s`）。
     * git 用 `\r` 在同一行覆盖写，所以 onOutput 每次的 chunk 可能带 `\r` 分段；只取最后一段。
     */
    private fun applyGitProgress(rawChunk: String, defaultLabel: String) {
        // 取最后一段（覆盖式 progress 输出）
        val last = rawChunk.split('\r').lastOrNull().orEmpty()
        if (last.isBlank()) return
        val phase = when {
            "Counting objects" in last -> "枚举对象"
            "Compressing objects" in last -> "压缩对象"
            "Receiving objects" in last -> "接收对象"
            "Resolving deltas" in last -> "解析增量"
            "Checking out files" in last -> "检出文件"
            "Writing objects" in last -> "写入对象"
            "Enumerating objects" in last -> "枚举对象"
            else -> null
        }
        val percent = Regex("(\\d+)%").find(last)?.groupValues?.get(1)?.toIntOrNull()
        val objFrac = Regex("\\((\\d+)/(\\d+)\\)").find(last)
        val objDone = objFrac?.groupValues?.get(1)?.toIntOrNull()
        val objTotal = objFrac?.groupValues?.get(2)?.toIntOrNull()
        val speedOrBytes = Regex("([\\d.]+\\s*[KM]?B)(?:/s)?").find(last)?.groupValues?.get(1)
        _gitProgress.value = GitProgress(
            label = phase ?: defaultLabel,
            percent = percent,
            objects = if (objDone != null && objTotal != null) "$objDone/$objTotal" else null,
            transfer = speedOrBytes,
            raw = last.take(120),
        )
    }

    fun addGitCredential(name: String, host: String, username: String, token: String) {
        val trimmedHost = host.trim().lowercase().removePrefix("https://").removeSuffix("/")
        if (name.isBlank() || trimmedHost.isBlank() || username.isBlank() || token.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val current = gitPreferences.credentials.first()
            val newId = java.util.UUID.randomUUID().toString()
            val updated = current + GitCredential(
                id = newId,
                name = name.trim(),
                host = trimmedHost,
                username = username.trim(),
                token = token.trim(),
                createdAtMillis = System.currentTimeMillis(),
            )
            gitPreferences.setCredentials(updated)
            // 保存后**立即**跑一次健康检查，用户能一眼看到凭证是不是有效（P1-9）
            verifyGitCredential(newId)
        }
    }

    fun deleteGitCredential(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = gitPreferences.credentials.first()
            gitPreferences.setCredentials(current.filterNot { it.id == id })
        }
    }

    /** 用户从 Snackbar 「切过去」按钮触发：把工作区切到某个绝对路径（clone 完成后）。 */
    fun switchWorkspace(path: String) {
        val relative = path.removePrefix("/workspace/").trim('/')
        harnessLoop.debugSetWorkspace(relative.ifBlank { path })
        _gitOpMessage.value = GitOpMessage.Ok("✓ 工作区已切到 $relative")
        refreshGitStatus()
    }

    /** 从给定 path 切工作区（DebugActionBus 用）。 */
    fun switchWorkspaceFromDebug(path: String) = switchWorkspace(path)

    /** 用给定 URL 探测是否有可用凭证（clone 对话框显示「将自动使用」提示）。 */
    fun probeCredential(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val host = GitAuth.hostOf(url)
            val creds = gitPreferences.credentials.first()
            _matchedCredentialId.value = GitAuth.findCredential(creds, host)?.id
        }
    }

    // ===== AI 生成 commit message（万象独有：把 staged diff 交给当前激活模型，产出 Conventional Commits 消息）=====

    private val _aiCommit = MutableStateFlow<GitAiCommitState>(GitAiCommitState.Idle)
    val aiCommit: StateFlow<GitAiCommitState> = _aiCommit.asStateFlow()

    /** 触发一次 AI 生成。若正在 loading 忽略。 */
    fun aiGenerateCommitMessage() {
        if (_aiCommit.value is GitAiCommitState.Loading) return
        val ws = currentGitWs()
        _aiCommit.value = GitAiCommitState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            // 8KB diff 上限：过大模型也读不完 + 计费高
            val diff = runGitRead(ws, "git diff --staged --stat && echo === && git diff --staged | head -c 8192")
                .orEmpty()
                .trim()
            if (diff.isBlank()) {
                _aiCommit.value = GitAiCommitState.Error("没有已暂存的改动，请先 stage 文件")
                return@launch
            }
            try {
                val model = providerClient.resolveModel()
                val messages = listOf(
                    top.wanxiang.app.harness.ApiMessage(
                        role = "system",
                        content = "你是 Git 提交消息助手。根据用户给的 diff，写一条 Conventional Commits 风格的消息：" +
                            "第一行 `<type>(<scope>): <简短摘要>` 不超过 72 字符；空一行；body 说明「为什么这么做」而非「改了哪些行」（≤ 4 行）。" +
                            "type 从 feat/fix/refactor/docs/test/chore/perf/build/ci 里选。中文输出，禁止 markdown 代码块包裹，只输出消息本身。",
                    ),
                    top.wanxiang.app.harness.ApiMessage(role = "user", content = "以下是 diff：\n\n$diff"),
                )
                val result = providerClient.chat(model, messages)
                val text = result.content?.trim().orEmpty()
                _aiCommit.value = if (text.isBlank()) GitAiCommitState.Error("模型返回空，试试再点一次")
                    else GitAiCommitState.Done(text)
            } catch (t: Throwable) {
                _aiCommit.value = GitAiCommitState.Error("AI 生成失败：${t.message ?: "未知"}")
            }
        }
    }

    fun consumeAiCommit() { _aiCommit.value = GitAiCommitState.Idle }

    // ===== 从 GitHub/Gitee 拉自己的仓库列表（clone 对话框里点「浏览我的仓库」用） =====

    private val _repoList = MutableStateFlow<GitRepoListState>(GitRepoListState.Idle)
    val repoList: StateFlow<GitRepoListState> = _repoList.asStateFlow()

    /** 用给定主机上第一条凭证打 provider 的 /user/repos API，取回名字+URL 列表。 */
    fun fetchUserRepos(host: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _repoList.value = GitRepoListState.Loading
            val cred = gitPreferences.credentials.first().firstOrNull { it.host.equals(host, ignoreCase = true) }
            if (cred == null) {
                _repoList.value = GitRepoListState.Error("$host 无保存凭证，先到「凭证」页添加")
                return@launch
            }
            val quoted = shellQuote(cred.token)
            val (url, auth) = when {
                cred.host.contains("github", true) ->
                    "https://api.github.com/user/repos?per_page=100&sort=updated" to
                        "-H 'Authorization: Bearer $quoted' -H 'User-Agent: wanxiang-app'"
                cred.host.contains("gitee", true) ->
                    "https://gitee.com/api/v5/user/repos?per_page=100&sort=updated" to
                        "'access_token=$quoted'"
                cred.host.contains("gitlab", true) ->
                    "https://${cred.host.substringBefore('/')}/api/v4/projects?membership=true&per_page=100&order_by=last_activity_at" to
                        "-H 'PRIVATE-TOKEN: $quoted'"
                else -> {
                    _repoList.value = GitRepoListState.Error("暂不支持 $host 仓库列表")
                    return@launch
                }
            }
            val curlCmd = if (cred.host.contains("gitee", true)) {
                "curl -s --max-time 15 '$url?$auth'"
            } else {
                "curl -s --max-time 15 $auth '$url'"
            }
            val res = runCatching {
                linuxRuntime.execute(ShellCommand(commandLine = curlCmd, workingDirectory = "/root", timeoutMs = 25_000L))
            }
            val body = res.getOrNull()?.stdout?.trim().orEmpty()
            if (body.isBlank() || body.startsWith("<") || body.startsWith("curl:")) {
                _repoList.value = GitRepoListState.Error("拉取失败（沙箱可能没网，或 provider 拒了）。可以直接手动粘 URL")
                return@launch
            }
            runCatching {
                val json = org.json.JSONArray(body)
                val list = buildList {
                    for (i in 0 until json.length()) {
                        val obj = json.getJSONObject(i)
                        val name = obj.optString("full_name").ifBlank { obj.optString("path_with_namespace") }
                        val cloneUrl = obj.optString("clone_url").ifBlank { obj.optString("http_url_to_repo") }
                        val isPrivate = obj.optBoolean("private", false)
                        if (name.isNotBlank() && cloneUrl.isNotBlank()) add(GitRepoInfo(name, cloneUrl, isPrivate))
                    }
                }
                _repoList.value = if (list.isEmpty()) GitRepoListState.Error("账号下无仓库或返回空")
                    else GitRepoListState.Ready(list)
            }.onFailure {
                _repoList.value = GitRepoListState.Error("解析失败：${it.message}")
            }
        }
    }

    fun clearRepoList() { _repoList.value = GitRepoListState.Idle }

    // ===== 凭证健康检查（点击每条凭证的验证图标 → 用 curl 打 provider /user 端点） =====

    private val _credHealth = MutableStateFlow<Map<String, GitCredHealth>>(emptyMap())
    val credHealth: StateFlow<Map<String, GitCredHealth>> = _credHealth.asStateFlow()

    fun verifyGitCredential(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _credHealth.value = _credHealth.value + (id to GitCredHealth.Checking)
            val cred = gitPreferences.credentials.first().firstOrNull { it.id == id }
            if (cred == null) {
                _credHealth.value = _credHealth.value + (id to GitCredHealth.Unknown("凭证已删除"))
                return@launch
            }
            val (url, authMode) = when {
                cred.host.contains("github", true) -> "https://api.github.com/user" to "Bearer"
                cred.host.contains("gitee", true) -> "https://gitee.com/api/v5/user" to "query"
                cred.host.contains("gitlab", true) -> "https://${cred.host.substringBefore('/')}/api/v4/user" to "PRIVATE-TOKEN"
                else -> null to null
            }
            if (url == null) {
                _credHealth.value = _credHealth.value + (id to GitCredHealth.Unknown("未知主机：${cred.host}"))
                return@launch
            }
            val quoted = shellQuote(cred.token)
            val curlCmd = when (authMode) {
                "query" -> "curl -s -o /dev/null -w '%{http_code}' --max-time 12 '${url}?access_token=$quoted'"
                "PRIVATE-TOKEN" -> "curl -s -o /dev/null -w '%{http_code}' --max-time 12 -H 'PRIVATE-TOKEN: $quoted' '$url'"
                else -> "curl -s -o /dev/null -w '%{http_code}' --max-time 12 -H 'Authorization: Bearer $quoted' -H 'User-Agent: wanxiang-app' '$url'"
            }
            val result = runCatching {
                linuxRuntime.execute(ShellCommand(commandLine = curlCmd, workingDirectory = "/root", timeoutMs = 18_000L))
            }
            val code = result.getOrNull()?.stdout?.trim()?.takeLast(3).orEmpty()
            val h = when {
                code == "200" -> GitCredHealth.Ok
                code == "401" || code == "403" -> GitCredHealth.Invalid(code)
                code == "000" || code.isBlank() || !code.all { it.isDigit() } -> GitCredHealth.Unknown("网络不通（可能手机没挂代理或 host 不可达）")
                else -> GitCredHealth.Unknown("HTTP $code")
            }
            _credHealth.value = _credHealth.value + (id to h)
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
            val result = runCatching {
                linuxRuntime.execute(
                    top.wanxiang.app.runtime.shell.ShellCommand(
                        commandLine = "$cmd 2>&1",
                        workingDirectory = ws,
                        timeoutMs = 30_000L,
                    ),
                )
            }
            val r = result.getOrNull()
            val out = ((r?.stdout ?: "") + "\n" + (r?.stderr ?: "")).trim()
            // 只在失败时提示；成功的 stage/commit/init 通过下面的 refreshGitStatus 视觉可见（列表变化）不啰嗦
            if (r != null && !r.isSuccess) {
                val op = cmd.substringAfter("git ").substringBefore(' ').ifBlank { "git" }
                _gitOpMessage.value = GitOpMessage.Error("$op 失败：\n" + out.take(500))
            }
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
            // **并发**抽文档文本（P0 附件多文件并发）：coroutineScope + 每个 item 单独 launch；
            // 5 个 PDF 同时下发到沙箱 → 一起跑，不用排队（沙箱本身多进程能扛，且每个 extract 内部有 90s timeout 兜底）
            val docItems = items.filter { !it.isImage }
            if (docItems.isNotEmpty()) {
                kotlinx.coroutines.coroutineScope {
                    docItems.forEach { att -> launch { extractTextForAttachment(att) } }
                }
            }
        }
    }

    /** 附件 id → 抽取到的纯文本；null 值表示"已尝试但抽不出"，key 不在则"未开始或进行中"。 */
    private val _extractedTexts = MutableStateFlow<Map<String, String?>>(emptyMap())
    val extractedTexts: StateFlow<Map<String, String?>> = _extractedTexts.asStateFlow()

    private suspend fun extractTextForAttachment(attachment: ChatAttachment) {
        val guest = attachment.guestFilePath ?: return
        _attachmentExtracting.value = _attachmentExtracting.value + (attachment.id to true)
        val result = runCatching { textExtractor.extract(guest, attachment.name) }.getOrNull()
        val text = when (result) {
            is top.wanxiang.app.runtime.sandbox.SandboxTextExtractor.Result.Ok -> {
                val truncated = if (result.truncated) "\n\n[文档过长，前 40KB 已展示]" else ""
                result.text + truncated
            }
            is top.wanxiang.app.runtime.sandbox.SandboxTextExtractor.Result.Skipped -> {
                android.util.Log.i("AttachmentExtract", "跳过 ${attachment.name}: ${result.reason}")
                null
            }
            is top.wanxiang.app.runtime.sandbox.SandboxTextExtractor.Result.Failed -> {
                _notice.value = "解析 ${attachment.name} 失败：${result.error}"
                null
            }
            null -> null
        }
        _extractedTexts.value = _extractedTexts.value + (attachment.id to text)
        _attachmentExtracting.value = _attachmentExtracting.value - attachment.id
    }

    private val _attachmentExtracting = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val attachmentExtracting: StateFlow<Map<String, Boolean>> = _attachmentExtracting.asStateFlow()

    fun removeAttachment(attachment: ChatAttachment) {
        _pendingAttachments.update { list -> list.filter { it.id != attachment.id } }
        _extractedTexts.value = _extractedTexts.value - attachment.id
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
                val extractedMap = _extractedTexts.value
                attachments.forEachIndexed { i, att ->
                    val guestPath = att.guestFilePath ?: "/attachments/${att.name}"
                    val kind = context.getString(if (att.isImage) R.string.chat_attachment_image else R.string.chat_attachment_file)
                    append(context.getString(R.string.chat_attachment_line, i + 1, kind, att.name, AttachmentHelper.formatFileSize(att.sizeBytes), guestPath))
                    // 附件文本抽取结果（PDF/DOCX/PPTX/EPUB/MD/HTML/TXT 会自动抽）：直接贴给 AI，比让 AI 再读文件快
                    val text = extractedMap[att.id]
                    if (!text.isNullOrBlank()) {
                        append("\n   📄 **$guestPath 内容**（沙箱已抽取 ${text.length} 字符）：\n")
                        append("```\n")
                        append(text.take(20_000))
                        append("\n```\n")
                    }
                }
                append(context.getString(R.string.chat_attachment_access_hint))
            }
        }
        _pendingAttachments.value = emptyList()
        _extractedTexts.value = emptyMap()
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

/** AI 生成 commit 消息的状态机：Idle / Loading / Done(text) / Error(reason)。 */
sealed interface GitAiCommitState {
    data object Idle : GitAiCommitState
    data object Loading : GitAiCommitState
    data class Done(val message: String) : GitAiCommitState
    data class Error(val reason: String) : GitAiCommitState
}

/** 凭证健康检查的四种状态。 */
sealed interface GitCredHealth {
    data object Ok : GitCredHealth
    data class Invalid(val code: String) : GitCredHealth
    data class Unknown(val reason: String) : GitCredHealth
    data object Checking : GitCredHealth
}

/** 仓库列表条目（clone 对话框「浏览我的仓库」用）。 */
data class GitRepoInfo(val fullName: String, val cloneUrl: String, val private: Boolean)

/** 仓库列表加载的三态。 */
sealed interface GitRepoListState {
    data object Idle : GitRepoListState
    data object Loading : GitRepoListState
    data class Ready(val repos: List<GitRepoInfo>) : GitRepoListState
    data class Error(val reason: String) : GitRepoListState
}

/** Git 一次性操作反馈（克隆/拉取/推送）：给 UI Snackbar 消费。 */
sealed interface GitOpMessage {
    data object Idle : GitOpMessage
    data class Busy(val label: String) : GitOpMessage
    data class Ok(val message: String, val action: GitOpAction? = null) : GitOpMessage
    data class Error(val message: String, val action: GitOpAction? = null) : GitOpMessage
}

/** 反馈消息里可点的动作（Snackbar 的 action 按钮）。 */
sealed interface GitOpAction {
    /** 一键把工作区切到某个子目录（clone 完成后）。 */
    data class SwitchWorkspaceTo(val path: String) : GitOpAction
    /** 同名目录已存在时的一键清空再试。 */
    data class RetryWithClean(val url: String, val targetDir: String) : GitOpAction
    /** 网络错误重试。 */
    data class RetrySame(val command: String) : GitOpAction
}

/** 流式进度条数据。git 每次输出进度 chunk 更新一次。 */
data class GitProgress(
    val label: String,
    val percent: Int? = null,
    val objects: String? = null,
    val transfer: String? = null,
    val raw: String? = null,
)

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
    /** 相对 upstream 的 ahead/behind 计数（无 upstream 或未同步为 null）。 */
    val aheadBehind: Pair<Int, Int>? = null,
    val staged: List<GitFileChange> = emptyList(),
    val unstaged: List<GitFileChange> = emptyList(),
    val untracked: List<String> = emptyList(),
    val localBranches: List<String> = emptyList(),
    val remoteBranches: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val commits: List<String> = emptyList(),
    val hasIdentity: Boolean = false,
    val hasRemote: Boolean = false,
    /** 当前 `git stash list` 条数（>0 时可 pop）。 */
    val stashCount: Int = 0,
    val notARepo: Boolean = false,
    val diffPath: String? = null,
    val diffText: String? = null,
    val diffLoading: Boolean = false,
    /** 本地脏时 pull 前需要用户二次确认（可能被覆盖或产生冲突）。 */
    val pullDirtyConfirm: Boolean = false,
    /** 本地脏时切分支前的二次确认（branch 名）。非空 = 需确认。 */
    val pendingCheckout: String? = null,
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
