@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package top.wanxiang.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import top.wanxiang.app.feature.chat.R
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import top.wanxiang.app.ui.components.RuntimeButton
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconButton
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeOutlinedButton
import top.wanxiang.app.ui.components.RuntimeTextButton
import top.wanxiang.app.ui.components.GitTokens
import top.wanxiang.app.ui.components.gitCardSurface
import top.wanxiang.app.ui.components.gitSubtleBorder
import top.wanxiang.app.ui.components.gitSubtleText
import top.wanxiang.app.ui.components.RuntimeTopBar
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Git 页面（全屏）：RuntimeTopBar 顶栏 + 状态/分支/历史 三 Tab。
 * 状态由 [ChatViewModel.gitPanelState] 提供，绑定当前会话（不跨会话共享）。
 */
@Composable
fun GitPanel(
    state: GitPanelState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onFileDiff: (String) -> Unit = {},
    onClearDiff: () -> Unit = {},
    onStage: (String) -> Unit = {},
    onUnstage: (String) -> Unit = {},
    onStageAll: () -> Unit = {},
    onUnstageAll: () -> Unit = {},
    onCommit: (String) -> Unit = {},
    onPull: () -> Unit = {},
    onPush: () -> Unit = {},
    onCheckout: (String) -> Unit = {},
    onCreateBranch: (String) -> Unit = {},
    onDeleteBranch: (String) -> Unit = {},
    onRenameBranch: (String, String) -> Unit = { _, _ -> },
    onDeleteRemoteBranch: (String) -> Unit = {},
    onInitRepo: () -> Unit = {},
    onClone: (String) -> Unit = {},
    onConfigIdentity: (String, String) -> Unit = { _, _ -> },
    onRevert: (String) -> Unit = {},
    onRevertAll: () -> Unit = {},
    onDeleteUntracked: (String) -> Unit = {},
    onCreateTag: (String) -> Unit = {},
    onDeleteTag: (String) -> Unit = {},
    onCommitDetail: (String) -> Unit = {},
    onClearCommitDetail: () -> Unit = {},
    credentials: List<top.wanxiang.app.core.datastore.GitCredential> = emptyList(),
    matchedCredentialId: String? = null,
    onAddCredential: (name: String, host: String, username: String, token: String) -> Unit = { _, _, _, _ -> },
    onDeleteCredential: (String) -> Unit = {},
    onProbeCredential: (String) -> Unit = {},
    onPullNow: () -> Unit = {},
    onDismissPullDirty: () -> Unit = {},
    onConfirmCheckoutDirty: (String) -> Unit = {},
    onDismissCheckoutConfirm: () -> Unit = {},
    onStash: () -> Unit = {},
    onStashPop: () -> Unit = {},
    aiCommit: top.wanxiang.app.ui.chat.GitAiCommitState = top.wanxiang.app.ui.chat.GitAiCommitState.Idle,
    onAiGenerate: () -> Unit = {},
    credentialHealth: Map<String, top.wanxiang.app.ui.chat.GitCredHealth> = emptyMap(),
    onVerifyCredential: (String) -> Unit = {},
    onVerifyAllCredentials: () -> Unit = {},
    repoListState: top.wanxiang.app.ui.chat.GitRepoListState = top.wanxiang.app.ui.chat.GitRepoListState.Idle,
    onFetchRepos: (String) -> Unit = {},
    onClearRepoList: () -> Unit = {},
    progress: top.wanxiang.app.ui.chat.GitProgress? = null,
    onCancelProgress: () -> Unit = {},
    recentCloneUrls: List<String> = emptyList(),
    onLoadMoreCommits: () -> Unit = {},
    onUnshallow: () -> Unit = {},
    onCommitFileDiff: (String, String) -> Unit = { _, _ -> },
    gitOp: GitOpMessage = GitOpMessage.Idle,
    onSwitchWorkspace: (String) -> Unit = {},
    onRetryCloneClean: (String, String) -> Unit = { _, _ -> },
    onUndoRename: (String, String) -> Unit = { _, _ -> },
    onConsumeGitOp: () -> Unit = {},
) {
    if (state.diffPath != null) {
        GitDiffView(state, onBack = onClearDiff)
        return
    }
    BackHandler(onBack = onDismiss)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    /** AiCode 布局：凭据/署名从第 4 个 tab 改为顶栏 🔑 图标进入的子页面。 */
    var showCredentialsPage by rememberSaveable { mutableStateOf(false) }
    var showCredentialDialog by rememberSaveable { mutableStateOf(false) }
    var credentialName by rememberSaveable { mutableStateOf("") }
    var credentialEmail by rememberSaveable { mutableStateOf("") }
    var showCloneDialog by rememberSaveable { mutableStateOf(false) }
    var cloneUrl by rememberSaveable { mutableStateOf("") }
    var showAddPatDialog by rememberSaveable { mutableStateOf(false) }
    var newPatName by rememberSaveable { mutableStateOf("") }
    var newPatHost by rememberSaveable { mutableStateOf("github.com") }
    var newPatUser by rememberSaveable { mutableStateOf("") }
    var newPatToken by rememberSaveable { mutableStateOf("") }

    // ===== Git 反馈 Snackbar 内嵌面板：用户在哪操作就在哪提示，不打扰背后的聊天页 =====
    val gitSnackHost = remember { androidx.compose.material3.SnackbarHostState() }
    val gitSnackContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(gitOp) {
        val snapshot = gitOp
        val isError: Boolean
        val message: String
        val action: GitOpAction?
        when (snapshot) {
            is GitOpMessage.Ok -> { isError = false; message = snapshot.message; action = snapshot.action }
            is GitOpMessage.Error -> { isError = true; message = snapshot.message; action = snapshot.action }
            else -> return@LaunchedEffect
        }
        run {
            val actionLabel = when (action) {
                is GitOpAction.SwitchWorkspaceTo -> "切过去"
                is GitOpAction.RetryWithClean -> "清空再试"
                is GitOpAction.UndoRename -> "撤销"
                is GitOpAction.StashPop -> "还原 stash"
                is GitOpAction.CopyError -> "复制"
                is GitOpAction.RetrySame -> if (isError) null else "重试"
                null -> if (isError) "复制错误" else null
            }
            val result = if (actionLabel != null) {
                gitSnackHost.showSnackbar(message, actionLabel = actionLabel, duration = androidx.compose.material3.SnackbarDuration.Long)
            } else {
                gitSnackHost.showSnackbar(message, duration = androidx.compose.material3.SnackbarDuration.Long)
            }
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                when (action) {
                    is GitOpAction.SwitchWorkspaceTo -> onSwitchWorkspace(action.path)
                    is GitOpAction.RetryWithClean -> onRetryCloneClean(action.url, action.targetDir)
                    is GitOpAction.UndoRename -> onUndoRename(action.newName, action.oldName)
                    is GitOpAction.StashPop -> onStashPop()
                    else -> {}
                }
                // 错误（含无 action 兜底）→ 复制原文到剪贴板，方便粘给助手
                if (isError && (action == null || action is GitOpAction.CopyError)) {
                    val cm = gitSnackContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("git-error", message))
                }
            }
            onConsumeGitOp()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        RuntimeTopBar(
            title = if (showCredentialsPage) "凭据与署名" else stringResource(R.string.chat_git_panel_title),
            onBack = { if (showCredentialsPage) showCredentialsPage = false else onDismiss() },
            statusText = if (showCredentialsPage) null else state.branch?.let { b ->
                val ab = state.aheadBehind?.let { (a, bh) -> "  ↑$a ↓$bh" } ?: ""
                val n = state.staged.size + state.unstaged.size + state.untracked.size
                val badge = if (n > 0) "  ● $n" else ""
                "$b$ab$badge"
            },
            actions = {
                if (!showCredentialsPage) {
                    RuntimeIconButton(onClick = { showCredentialsPage = true }) {
                        RuntimeIcon(RuntimeIconName.Key, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RuntimeIconButton(onClick = onRefresh, enabled = !state.loading) {
                        RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                    }
                }
            },
        )

        if (showCredentialsPage) {
            // 凭据子页：署名配置卡 + PAT 列表（AiCode「凭据与署名」页等价物）
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                IdentityCard(
                    hasIdentity = state.hasIdentity,
                    onEdit = { showCredentialDialog = true },
                )
                CredentialsTab(
                    credentials = credentials,
                    onAdd = { showAddPatDialog = true },
                    onDelete = onDeleteCredential,
                    health = credentialHealth,
                    onVerify = onVerifyCredential,
                    onVerifyAll = onVerifyAllCredentials,
                )
            }
        } else {
            // Git 流式进度内嵌在面板顶部（用户在 panel 里点 clone/pull/push，不用回 chat 主页面看）
            progress?.let { p ->
                GitProgressBanner(progress = p, onCancel = onCancelProgress)
            }

            // AiCode 布局：3 页 HorizontalPager + 底部悬浮 FloatingTabBar（状态/分支/提交）
            val panelScope = rememberCoroutineScope()
            val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = selectedTab.coerceAtMost(2)) { 3 }
            LaunchedEffect(pagerState.currentPage) { selectedTab = pagerState.currentPage }
            LaunchedEffect(selectedTab) {
                if (pagerState.currentPage != selectedTab) pagerState.animateScrollToPage(selectedTab)
            }
            Box(Modifier.fillMaxSize()) {
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when {
                        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            RuntimeCircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        }
                        state.notARepo || state.branch == null -> NotARepoView(
                            onInit = onInitRepo,
                            onOpenClone = { showCloneDialog = true },
                        )
                        state.error != null -> CenterHint(state.error, isError = true)
                        page == 0 -> StatusTab(state, onFileDiff, onStage, onUnstage, onStageAll, onUnstageAll, onCommit, onPull, onPush, onRevert, onRevertAll, onDeleteUntracked, aiCommit, onAiGenerate, onStash, onStashPop)
                        page == 1 -> BranchesTab(state, onCheckout, onCreateBranch, onDeleteBranch, onRenameBranch, onDeleteRemoteBranch, onCreateTag, onDeleteTag)
                        page == 2 -> LogTab(state, onCommitDetail = onCommitDetail, onCloseCommit = onClearCommitDetail, onCommitFileDiff = onCommitFileDiff, onLoadMore = onLoadMoreCommits, onUnshallow = onUnshallow)
                    }
                }
                if (!state.loading && !state.notARepo && state.branch != null) {
                    top.wanxiang.app.ui.components.FloatingTabBar(
                        selected = pagerState.currentPage,
                        onSelect = { panelScope.launch { pagerState.animateScrollToPage(it) } },
                        items = listOf(
                            top.wanxiang.app.ui.components.FloatingTabItem(top.wanxiang.app.ui.components.RuntimeIconName.Activity, "状态"),
                            top.wanxiang.app.ui.components.FloatingTabItem(top.wanxiang.app.ui.components.RuntimeIconName.GitBranch, "分支"),
                            top.wanxiang.app.ui.components.FloatingTabItem(top.wanxiang.app.ui.components.RuntimeIconName.GitCommit, "提交"),
                        ),
                        maskColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                // Git 操作反馈 Snackbar：常驻面板底部（notARepo 时 clone 失败也要看得见），
                // 仓库态抬高 84dp 避开悬浮栏。
                androidx.compose.material3.SnackbarHost(
                    hostState = gitSnackHost,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp)
                        .padding(bottom = if (!state.loading && !state.notARepo && state.branch != null) 84.dp else 16.dp),
                )
            }
        }
    }

    if (showCredentialDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showCredentialDialog = false },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    showCredentialDialog = false
                    if (credentialName.isNotBlank()) onConfigIdentity(credentialName.trim(), credentialEmail.trim())
                }) { Text("保存") }
            },
            dismissButton = { RuntimeTextButton(onClick = { showCredentialDialog = false }) { Text("取消") } },
            title = { Text("Git 署名配置") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = credentialName, onValueChange = { credentialName = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("用户名（user.name）") }, singleLine = true)
                    OutlinedTextField(value = credentialEmail, onValueChange = { credentialEmail = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("邮箱（user.email）") }, singleLine = true)
                }
            },
        )
    }

    if (showCloneDialog) {
        var cloneBlankError by remember { mutableStateOf(false) }
        val doClone = {
            val u = cloneUrl.trim()
            if (u.isBlank()) {
                cloneBlankError = true
            } else {
                cloneBlankError = false
                showCloneDialog = false
                onClone(u)
            }
        }
        val detectedHost = remember(cloneUrl, credentials) { GitAuth.hostOf(cloneUrl) ?: credentials.firstOrNull()?.host }
        RuntimeAlertDialog(
            onDismissRequest = { showCloneDialog = false; onClearRepoList() },
            confirmButton = { RuntimeButton(onClick = doClone) { Text("克隆") } },
            dismissButton = { RuntimeTextButton(onClick = { showCloneDialog = false }) { Text("取消") } },
            title = { Text("克隆远程仓库") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "私有仓库会自动使用「凭证」标签页里匹配的 HTTPS PAT。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = cloneUrl,
                        onValueChange = {
                            cloneUrl = it
                            onProbeCredential(it.trim())
                            cloneBlankError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://github.com/owner/repo.git") },
                        singleLine = true,
                        isError = cloneBlankError,
                        supportingText = if (cloneBlankError) {
                            { Text("请先输入仓库 URL", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { doClone() }),
                    )
                    if (matchedCredentialId != null) {
                        val matched = credentials.firstOrNull { it.id == matchedCredentialId }
                        if (matched != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "✓ 将自动使用凭证：${matched.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                RuntimeTextButton(onClick = { onFetchRepos(detectedHost ?: matched.host) }) {
                                    Text("📋 浏览我的仓库", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    } else if (cloneUrl.isNotBlank() && GitAuth.hostOf(cloneUrl) != null) {
                        Text(
                            "此主机没有保存的凭证，公有仓库可直接克隆；私有仓库请先到「凭证」标签页添加。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (credentials.isNotEmpty()) {
                        // URL 未填或不含可识别主机：提供浏览快捷入口
                        RuntimeTextButton(onClick = { onFetchRepos(credentials.first().host) }) {
                            Text("📋 从「${credentials.first().name}」拉仓库列表", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (recentCloneUrls.isNotEmpty()) {
                        Text(
                            "最近克隆过：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        recentCloneUrls.take(3).forEach { u ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    cloneUrl = u
                                    onProbeCredential(u)
                                }.padding(vertical = 4.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("🕘", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    u,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    when (val st = repoListState) {
                        is top.wanxiang.app.ui.chat.GitRepoListState.Loading ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                Text("正在拉取仓库列表…", style = MaterialTheme.typography.labelSmall)
                            }
                        is top.wanxiang.app.ui.chat.GitRepoListState.Error ->
                            Text(st.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        is top.wanxiang.app.ui.chat.GitRepoListState.Ready -> {
                            Text("点仓库自动填 URL（共 ${st.repos.size} 个）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            st.repos.take(30).forEach { repo ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { cloneUrl = repo.cloneUrl; onProbeCredential(repo.cloneUrl) }.padding(vertical = 4.dp, horizontal = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        if (repo.private) "🔒" else "🌐",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    Text(repo.fullName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        top.wanxiang.app.ui.chat.GitRepoListState.Idle -> {}
                    }
                }
            },
        )
    }

    if (showAddPatDialog) {
        val savePat = {
            if (newPatName.isNotBlank() && newPatHost.isNotBlank() && newPatUser.isNotBlank() && newPatToken.isNotBlank()) {
                onAddCredential(newPatName.trim(), newPatHost.trim(), newPatUser.trim(), newPatToken.trim())
                newPatName = ""; newPatHost = "github.com"; newPatUser = ""; newPatToken = ""
                showAddPatDialog = false
            }
        }
        RuntimeAlertDialog(
            onDismissRequest = { showAddPatDialog = false },
            confirmButton = { RuntimeButton(onClick = savePat) { Text("保存") } },
            dismissButton = { RuntimeTextButton(onClick = { showAddPatDialog = false }) { Text("取消") } },
            title = { Text("新增 HTTPS 凭证") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newPatName, onValueChange = { newPatName = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("名称（如 GitHub 我的账号）") }, singleLine = true)
                    OutlinedTextField(value = newPatHost, onValueChange = { newPatHost = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("主机（github.com / gitee.com）") }, singleLine = true)
                    OutlinedTextField(value = newPatUser, onValueChange = { newPatUser = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("用户名（GitLab 可填 oauth2）") }, singleLine = true)
                    OutlinedTextField(
                        value = newPatToken,
                        onValueChange = { newPatToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("PAT / 密码 — 填完按键盘上的「完成」即保存") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { savePat() }),
                    )
                }
            },
        )
    }

    if (state.pullDirtyConfirm) {
        RuntimeAlertDialog(
            onDismissRequest = onDismissPullDirty,
            title = { Text("确认拉取") },
            text = {
                Text(
                    "本地有未提交改动（已暂存 ${state.staged.size}、未暂存 ${state.unstaged.size}），" +
                        "拉取可能覆盖工作区或产生冲突。建议先提交或暂存后再拉。要继续吗？",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                RuntimeButton(onClick = { onPullNow() }) { Text("仍然拉取") }
            },
            dismissButton = {
                RuntimeTextButton(onClick = onDismissPullDirty) { Text("取消") }
            },
        )
    }

    state.pendingCheckout?.let { target ->
        RuntimeAlertDialog(
            onDismissRequest = onDismissCheckoutConfirm,
            title = { Text("确认切换分支") },
            text = {
                Text(
                    "本地有未提交改动（已暂存 ${state.staged.size}、未暂存 ${state.unstaged.size}）。" +
                        "切到 `$target` 可能覆盖工作区或产生冲突。建议先 stash 或提交。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { RuntimeButton(onClick = { onConfirmCheckoutDirty(target) }) { Text("仍然切换") } },
            dismissButton = { RuntimeTextButton(onClick = onDismissCheckoutConfirm) { Text("取消") } },
        )
    }
}

@Composable
private fun CredentialsTab(
    credentials: List<top.wanxiang.app.core.datastore.GitCredential>,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    health: Map<String, top.wanxiang.app.ui.chat.GitCredHealth> = emptyMap(),
    onVerify: (String) -> Unit = {},
    onVerifyAll: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已保存的 HTTPS 凭证（加密存储，运行时一次性使用不落盘）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (credentials.isNotEmpty()) {
                RuntimeTextButton(onClick = onVerifyAll) { Text("全部重验", style = MaterialTheme.typography.labelSmall) }
            }
            RuntimeButton(onClick = onAdd) { Text("新增") }
        }
        if (credentials.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RuntimeIcon(
                        RuntimeIconName.Key,
                        Modifier.size(48.dp),
                        MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        "还没有 HTTPS 凭证",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "添加一个 GitHub / Gitee / GitLab 的 Personal Access Token，就能克隆或推送私有仓库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    RuntimeButton(onClick = onAdd, modifier = Modifier.padding(top = 8.dp)) {
                        Text("添加第一条凭证", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            credentials.forEach { cred ->
                RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(cred.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${cred.username}@${cred.host}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("token: ${cred.token.take(4)}${"\u2022".repeat(8)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            when (val h = health[cred.id]) {
                                is top.wanxiang.app.ui.chat.GitCredHealth.Ok ->
                                    Text("✓ Token 有效${h.checkedAtMillis.relativeAgo()}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                is top.wanxiang.app.ui.chat.GitCredHealth.Invalid ->
                                    Text("✗ Token 无效 (HTTP ${h.code})${h.checkedAtMillis.relativeAgo()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                is top.wanxiang.app.ui.chat.GitCredHealth.Unknown ->
                                    Text("⚠ ${h.reason}", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                                is top.wanxiang.app.ui.chat.GitCredHealth.Checking ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        androidx.compose.foundation.layout.Box(Modifier.size(10.dp)) {
                                            CircularProgressIndicator(strokeWidth = 1.dp, modifier = Modifier.size(10.dp))
                                        }
                                        Text("验证中…", style = MaterialTheme.typography.labelSmall)
                                    }
                                null -> {}
                            }
                        }
                        RuntimeIconButton(onClick = { onVerify(cred.id) }) {
                            RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RuntimeIconButton(onClick = { onDelete(cred.id) }) {
                            RuntimeIcon(RuntimeIconName.Close, Modifier.size(16.dp), MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterHint(text: String, isError: Boolean = false, icon: RuntimeIconName? = null) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) {
                RuntimeIcon(
                    icon,
                    Modifier.size(48.dp),
                    if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NotARepoView(onInit: () -> Unit, onOpenClone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RuntimeIcon(RuntimeIconName.GitBranch, Modifier.size(40.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "当前工作区不是 Git 仓库",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RuntimeButton(
            onClick = onOpenClone,
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("克隆远程仓库", fontWeight = FontWeight.SemiBold) }
        RuntimeTextButton(
            onClick = onInit,
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("初始化空仓库") }
    }
}

// ======================= Diff 视图 =======================

@Composable
private fun GitDiffView(state: GitPanelState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        RuntimeTopBar(title = state.diffPath ?: "", onBack = onBack)
        // weight(1f)：同 LogTab——Column 未加权子项可能拿到无限 maxHeight，
        // 内层 LazyColumn(DiffText) 会崩
        if (state.diffLoading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                RuntimeCircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                DiffText(state.diffText ?: "")
            }
        }
    }
}

@Composable
private fun DiffText(text: String) {
    val lines = text.lines()
    LazyColumn(Modifier.fillMaxSize()) {
        items(lines.size) { i ->
            val line = lines[i]
            val color = when {
                line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF2E7D32)
                line.startsWith("-") && !line.startsWith("---") -> Color(0xFFC62828)
                line.startsWith("@@") -> Color(0xFF1565C0)
                line.startsWith("diff ") || line.startsWith("index ") || line.startsWith("---") || line.startsWith("+++") || line.startsWith("commit ") || line.startsWith("Author:") || line.startsWith("Date:") -> Color(0xFF00838F)
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                line.ifEmpty { " " },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = color,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp),
            )
        }
    }
}

// ======================= 状态 Tab =======================

@Composable
private fun StatusTab(
    state: GitPanelState,
    onFileDiff: (String) -> Unit,
    onStage: (String) -> Unit,
    onUnstage: (String) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    onCommit: (String) -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onRevert: (String) -> Unit,
    onRevertAll: () -> Unit = {},
    onDeleteUntracked: (String) -> Unit,
    aiCommit: top.wanxiang.app.ui.chat.GitAiCommitState = top.wanxiang.app.ui.chat.GitAiCommitState.Idle,
    onAiGenerate: () -> Unit = {},
    onStash: () -> Unit = {},
    onStashPop: () -> Unit = {},
) {
    val staged = state.staged
    val unstaged = state.unstaged
    val untracked = state.untracked
    val clean = staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty()

    var showCommitDialog by rememberSaveable { mutableStateOf(false) }
    var commitMessage by rememberSaveable { mutableStateOf("") }
    // AI 完成后把生成的消息回填到 commitMessage
    LaunchedEffect(aiCommit) {
        when (aiCommit) {
            is top.wanxiang.app.ui.chat.GitAiCommitState.Done -> commitMessage = aiCommit.message
            else -> {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 状态统计卡
        RuntimeCard(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem("已暂存", staged.size, Color(0xFF2E7D32))
                StatItem("已修改", unstaged.size, Color(0xFFB45309))
                StatItem("未跟踪", untracked.size, Color(0xFF757575))
            }
        }

        // 主操作：提交（有已暂存改动且已配置署名才可用）
        RuntimeButton(
            onClick = { showCommitDialog = true },
            enabled = staged.isNotEmpty() && state.hasIdentity,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) { Text("提交改动", fontWeight = FontWeight.SemiBold, maxLines = 1) }
        if (!state.hasIdentity) {
            Text(
                "请先在凭据中配置署名（用户名称与邮箱）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 次级操作：暂存全部 / 拉取 / 推送
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeOutlinedButton(
                onClick = onStageAll,
                enabled = !clean,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) { Text("暂存全部", maxLines = 1) }
            RuntimeOutlinedButton(
                onClick = onPull,
                enabled = state.hasRemote,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) { Text("拉取", maxLines = 1) }
            RuntimeOutlinedButton(
                onClick = onPush,
                enabled = state.hasRemote,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) { Text("推送", maxLines = 1) }
        }

        // 第三排：stash（工作区脏时可存；有 stash 时可恢复）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeOutlinedButton(
                onClick = onStash,
                enabled = !clean,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) { Text("存起改动", maxLines = 1) }
            RuntimeOutlinedButton(
                onClick = onStashPop,
                enabled = state.stashCount > 0,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) { Text(if (state.stashCount > 0) "恢复 ${state.stashCount} 号" else "无 stash", maxLines = 1) }
        }

        if (clean) {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.chat_git_no_changes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            if (staged.isNotEmpty()) {
                SectionHeader("已暂存 (${staged.size})", actionLabel = "全部取消暂存", onAction = onUnstageAll)
                RuntimeCard(contentPadding = PaddingValues(0.dp)) {
                    Column {
                        staged.forEachIndexed { i, f ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            FileRow(f, action = "取消暂存", onAction = { onUnstage(f.path) }, onClick = { onFileDiff(f.path) })
                        }
                    }
                }
            }
            if (unstaged.isNotEmpty()) {
                SectionHeader("已修改 (${unstaged.size})", actionLabel = if (unstaged.isNotEmpty()) "全部回退" else null, onAction = onRevertAll)
                RuntimeCard(contentPadding = PaddingValues(0.dp)) {
                    Column {
                        unstaged.forEachIndexed { i, f ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            FileRow(f, action = "暂存", onAction = { onStage(f.path) }, onClick = { onFileDiff(f.path) }, secondaryAction = "回退", onSecondaryAction = { onRevert(f.path) })
                        }
                    }
                }
            }
            if (untracked.isNotEmpty()) {
                SectionHeader("未跟踪 (${untracked.size})")
                RuntimeCard(contentPadding = PaddingValues(0.dp)) {
                    Column {
                        untracked.forEachIndexed { i, path ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            FileRow(GitFileChange('?', path), action = "添加", onAction = { onStage(path) }, onClick = { onFileDiff(path) }, secondaryAction = "删除", onSecondaryAction = { onDeleteUntracked(path) })
                        }
                    }
                }
            }
        }
    }

    if (showCommitDialog) {
        val aiLoading = aiCommit is top.wanxiang.app.ui.chat.GitAiCommitState.Loading
        RuntimeAlertDialog(
            onDismissRequest = { showCommitDialog = false },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    showCommitDialog = false
                    if (commitMessage.isNotBlank()) onCommit(commitMessage.trim())
                }) { Text("提交") }
            },
            dismissButton = { RuntimeTextButton(onClick = { showCommitDialog = false }) { Text("取消") } },
            title = { Text("提交改动") },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RuntimeButton(
                            onClick = onAiGenerate,
                            enabled = !aiLoading && staged.isNotEmpty(),
                        ) { Text(if (aiLoading) "✨ 生成中..." else "✨ AI 生成", style = MaterialTheme.typography.labelMedium) }
                        if (staged.isEmpty()) Text("需先暂存改动", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (aiCommit is top.wanxiang.app.ui.chat.GitAiCommitState.Error) {
                        Text(aiCommit.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("提交信息（可让 AI 生成后手动微调）") },
                    )
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            RuntimeTextButton(onClick = onAction) { Text(actionLabel, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun StatItem(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            color = color,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileRow(
    change: GitFileChange,
    action: String,
    onAction: () -> Unit,
    onClick: () -> Unit,
    secondaryAction: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val color = statusColor(change.status)
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = {
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(change.path))
                android.widget.Toast.makeText(context, "已复制：${change.path}", android.widget.Toast.LENGTH_SHORT).show()
            })
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(5.dp)) {
            Text(
                change.status.toString(),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = color,
                ),
            )
        }
        Text(
            change.path,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RuntimeTextButton(onClick = onAction) { Text(action, style = MaterialTheme.typography.labelSmall) }
        if (secondaryAction != null && onSecondaryAction != null) {
            RuntimeTextButton(onClick = onSecondaryAction) { Text(secondaryAction, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

// ======================= 分支 Tab =======================

@Composable
private fun BranchesTab(
    state: GitPanelState,
    onCheckout: (String) -> Unit,
    onCreateBranch: (String) -> Unit,
    onDeleteBranch: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDeleteRemoteBranch: (String) -> Unit,
    onCreateTag: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var newBranchName by rememberSaveable { mutableStateOf("") }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRename by rememberSaveable { mutableStateOf<String?>(null) }
    var renameInput by rememberSaveable { mutableStateOf("") }
    var pendingDeleteRemote by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateTag by rememberSaveable { mutableStateOf(false) }
    var newTagName by rememberSaveable { mutableStateOf("") }
    var pendingDeleteTag by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeOutlinedButton(onClick = { showCreateDialog = true }, modifier = Modifier.weight(1f)) { Text("新建分支", maxLines = 1) }
            RuntimeOutlinedButton(onClick = { showCreateTag = true }, modifier = Modifier.weight(1f)) { Text("新建标签", maxLines = 1) }
        }

        if (state.localBranches.isEmpty() && state.remoteBranches.isEmpty() && state.tags.isEmpty()) {
            CenterHint("还没有分支。克隆或初始化仓库后这里会显示本地/远程分支", icon = RuntimeIconName.GitBranch)
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (state.localBranches.isNotEmpty()) {
                    item { SectionHeader("本地分支") }
                    items(state.localBranches) { branch ->
                        val isCurrent = branch == state.branch
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !isCurrent) { onCheckout(branch) }.padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (isCurrent) RuntimeIcon(RuntimeIconName.Check, Modifier.size(15.dp), MaterialTheme.colorScheme.primary)
                            else RuntimeIcon(RuntimeIconName.GitBranch, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                branch,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal, color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (!isCurrent) RuntimeTextButton(onClick = { pendingDelete = branch }) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                            RuntimeTextButton(onClick = { pendingRename = branch; renameInput = branch }) { Text("重命名", style = MaterialTheme.typography.labelSmall) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
                if (state.remoteBranches.isNotEmpty()) {
                    item { SectionHeader("远程分支") }
                    items(state.remoteBranches) { branch ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.GitBranch, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(branch, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            RuntimeTextButton(onClick = { pendingDeleteRemote = branch }) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
                if (state.tags.isNotEmpty()) {
                    item { SectionHeader("标签") }
                    items(state.tags) { tag ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Hub, Modifier.size(15.dp), MaterialTheme.colorScheme.tertiary)
                            Text(tag, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            RuntimeTextButton(onClick = { pendingDeleteTag = tag }) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showCreateDialog = false },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    showCreateDialog = false
                    if (newBranchName.isNotBlank()) onCreateBranch(newBranchName.trim())
                }) { Text("创建") }
            },
            dismissButton = { RuntimeTextButton(onClick = { showCreateDialog = false }) { Text("取消") } },
            title = { Text("新建分支") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = newBranchName,
                        onValueChange = { newBranchName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("分支名称") },
                        singleLine = true,
                    )
                }
            },
        )
    }

    pendingDelete?.let { branch ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    pendingDelete = null
                    onDeleteBranch(branch)
                }) { Text("删除") }
            },
            dismissButton = { RuntimeTextButton(onClick = { pendingDelete = null }) { Text("取消") } },
            title = { Text("删除分支") },
            text = { Text("确定删除分支 $branch 吗？") },
        )
    }

    if (showCreateTag) {
        RuntimeAlertDialog(
            onDismissRequest = { showCreateTag = false },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    showCreateTag = false
                    if (newTagName.isNotBlank()) onCreateTag(newTagName.trim())
                }) { Text("创建") }
            },
            dismissButton = { RuntimeTextButton(onClick = { showCreateTag = false }) { Text("取消") } },
            title = { Text("新建标签") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = newTagName,
                        onValueChange = { newTagName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("标签名称") },
                        singleLine = true,
                    )
                }
            },
        )
    }

    pendingDeleteTag?.let { tag ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingDeleteTag = null },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    pendingDeleteTag = null
                    onDeleteTag(tag)
                }) { Text("删除") }
            },
            dismissButton = { RuntimeTextButton(onClick = { pendingDeleteTag = null }) { Text("取消") } },
            title = { Text("删除标签") },
            text = { Text("确定删除标签 $tag 吗？") },
        )
    }

    pendingRename?.let { oldName ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingRename = null },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    val n = renameInput.trim()
                    pendingRename = null
                    if (n.isNotBlank() && n != oldName) onRename(oldName, n)
                }) { Text("重命名") }
            },
            dismissButton = { RuntimeTextButton(onClick = { pendingRename = null }) { Text("取消") } },
            title = { Text("重命名分支") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("原分支：$oldName", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = renameInput, onValueChange = { renameInput = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("新的分支名") }, singleLine = true)
                }
            },
        )
    }

    pendingDeleteRemote?.let { remote ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingDeleteRemote = null },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    pendingDeleteRemote = null
                    // remote 形如 "origin/main"，git push origin --delete 需要去前缀
                    val branch = remote.substringAfter("/", remote)
                    onDeleteRemoteBranch(branch)
                }) { Text("删除") }
            },
            dismissButton = { RuntimeTextButton(onClick = { pendingDeleteRemote = null }) { Text("取消") } },
            title = { Text("删除远程分支") },
            text = { Text("将从远端删除 $remote，不可撤销。确定？") },
        )
    }
}

// ======================= 提交 Tab（AiCode 拓扑图版） =======================

@Composable
private fun LogTab(
    state: GitPanelState,
    onCommitDetail: (String) -> Unit,
    onCloseCommit: () -> Unit,
    onCommitFileDiff: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    onUnshallow: () -> Unit,
) {
    val graph = state.graph
    val commits = graph.commits
    if (commits.isEmpty()) {
        CenterHint("还没有提交历史", icon = RuntimeIconName.GitCommit)
        return
    }
    val laneColors = remember(graph.maxLane) {
        (0..graph.maxLane).map { GitTokens.laneColors[it % GitTokens.laneColors.size] }
    }
    val edgesByCommit = remember(graph) { groupEdgesByCommit(graph) }
    // 线性历史收紧泳道宽度；有分叉每泳道 16dp（AiCode 同参数）。
    val laneWidth = if (graph.maxLane == 0) 10.dp else 16.dp
    val canvasWidth = laneWidth * (graph.maxLane + 1) + 8.dp
    val rowHeight = 72.dp
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 预拉取：滑到剩 20 项时后台取下一页（AiCode 同阈值）。
    val shouldLoadMore by remember {
        androidx.compose.runtime.derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible >= totalItems - 20
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && graph.hasMore && !state.graphLoadingMore) onLoadMore()
    }
    val cardColor = gitCardSurface()
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "提交记录 (${commits.size})",
            style = MaterialTheme.typography.labelLarge,
            color = gitSubtleText(),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
        )
        LazyColumn(
            modifier = Modifier
                // weight 而非 fillMaxSize：Column 对未加权子项可能给出无限高度约束，
                // LazyColumn 收到 Infinity maxHeight 直接抛 IllegalStateException（88 提交
                // 大列表实测崩溃）。weight(1f) 保证拿到"剩余有界空间"，从根上消灭这类崩溃。
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 70.dp),
        ) {
            itemsIndexed(commits, key = { _, c -> c.hash }) { index, c ->
                val shape = when {
                    index == 0 && commits.size == 1 && !graph.hasMore -> RoundedCornerShape(14.dp)
                    index == 0 -> RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                    index == commits.lastIndex && !graph.hasMore -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
                    else -> androidx.compose.ui.graphics.RectangleShape
                }
                Surface(
                    color = cardColor,
                    shape = shape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    GraphCommitRow(
                        commit = c,
                        lane = graph.lanes[c.hash] ?: 0,
                        edges = edgesByCommit[index].orEmpty(),
                        activeTopLanes = graph.activeTopLanes[c.hash].orEmpty(),
                        activeBottomLanes = graph.activeBottomLanes[c.hash].orEmpty(),
                        laneColors = laneColors,
                        canvasWidth = canvasWidth,
                        laneWidth = laneWidth,
                        rowHeight = rowHeight,
                        refs = graph.refs[c.hash].orEmpty(),
                        isTopTerminal = index == 0,
                        onOpen = { onCommitDetail(c.hash) },
                    )
                }
            }
            item(key = "footer") {
                Surface(
                    color = cardColor,
                    shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (graph.hasMore) {
                            if (state.graphLoadingMore) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Text("上拉加载更早提交", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else if (state.isShallow) {
                            // 浅克隆仓库（clone --depth 1）历史被截断：给出反浅克隆入口
                            TextButton(onClick = onUnshallow) {
                                Text("加载完整历史（取消浅克隆）", style = MaterialTheme.typography.labelMedium)
                            }
                        } else {
                            Text("没有更多了", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    // 提交详情弹层（不打断列表布局，泳道保持完整）
    state.commitDetailHash?.let { hash ->
        val commit = graph.commits.find { it.hash == hash }
        if (commit != null) {
            CommitDetailSheet(
                commit = commit,
                files = state.commitFiles[hash],
                loading = state.loadingCommit == hash && state.commitFiles[hash] == null,
                onDismiss = onCloseCommit,
                onFileDiff = { path -> onCommitFileDiff(commit.hash, path) },
            )
        }
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun CommitDetailSheet(
    commit: top.wanxiang.app.ui.chat.git.GraphCommit,
    files: List<GitFileChange>?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onFileDiff: (String) -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(commit.message, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (commit.body.isNotBlank()) {
                Text(commit.body.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "${commit.author} · ${commit.date}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    commit.hash,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                RuntimeTextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(commit.shortHash)) }) {
                    Text("复制短哈希", style = MaterialTheme.typography.labelMedium)
                }
                RuntimeTextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(commit.hash)) }) {
                    Text("复制完整哈希", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (commit.isMerge) {
                Text("合并提交", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.height(4.dp))
            when {
                loading -> Text("正在加载改动文件…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                files == null -> Unit
                files.isEmpty() -> Text("该提交无文件改动", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text("改动 ${files.size} 个文件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    files.forEach { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onFileDiff(file.path) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            StatusChip(file.status)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    file.path.substringAfterLast('/'),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                val dir = file.path.substringBeforeLast('/', "")
                                if (dir.isNotEmpty()) {
                                    Text(
                                        dir,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = gitSubtleBorder(), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphCommitRow(
    commit: top.wanxiang.app.ui.chat.git.GraphCommit,
    lane: Int,
    edges: List<top.wanxiang.app.ui.chat.git.GraphEdge>,
    activeTopLanes: List<Int>,
    activeBottomLanes: List<Int>,
    laneColors: List<Color>,
    canvasWidth: androidx.compose.ui.unit.Dp,
    laneWidth: androidx.compose.ui.unit.Dp,
    rowHeight: androidx.compose.ui.unit.Dp,
    refs: List<top.wanxiang.app.ui.chat.git.GitGraphRef>,
    isTopTerminal: Boolean,
    onOpen: () -> Unit,
) {
    val nodeColor = laneColors.getOrElse(lane) { Color.Gray }
    Surface(color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                .heightIn(min = rowHeight)
                .clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GraphCanvas(
                edges = edges,
                activeTopLanes = activeTopLanes,
                activeBottomLanes = activeBottomLanes,
                lane = lane,
                isMerge = commit.isMerge,
                laneColors = laneColors,
                canvasWidth = canvasWidth,
                laneWidth = laneWidth,
                suppressTopLane = isTopTerminal,
                modifier = Modifier.width(canvasWidth).fillMaxHeight(),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .padding(end = 16.dp),
            ) {
                if (refs.isNotEmpty()) {
                    RefPills(refs = refs)
                    Spacer(Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.Top) {
                    RuntimeIcon(
                        RuntimeIconName.ChevronRight,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            commit.message,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = nodeColor.copy(alpha = 0.15f), shape = RoundedCornerShape(999.dp)) {
                                Text(
                                    commit.shortHash,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = nodeColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                commit.author,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                commit.date,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(
            color = gitSubtleBorder(),
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = canvasWidth + 12.dp),
        )
    }
}

/** 拓扑图 Canvas：分段竖线 + 跨列贝塞尔 + 节点（合并双圈）。AiCode 原版移植。 */
@Composable
private fun GraphCanvas(
    edges: List<top.wanxiang.app.ui.chat.git.GraphEdge>,
    activeTopLanes: List<Int>,
    activeBottomLanes: List<Int>,
    lane: Int,
    isMerge: Boolean,
    laneColors: List<Color>,
    canvasWidth: androidx.compose.ui.unit.Dp,
    laneWidth: androidx.compose.ui.unit.Dp,
    suppressTopLane: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val nodeColor = laneColors.getOrElse(lane) { Color.Gray }
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val lanePx = laneWidth.toPx()
        val padPx = 4.dp.toPx()
        val centerX = lane * lanePx + lanePx / 2f + padPx
        val centerY = size.height / 2f
        val stroke = 2.5.dp.toPx()

        val allLanes = (activeTopLanes + activeBottomLanes + lane).toSet()
        val crossEdgeToLanes = edges.filter { it.fromLane != it.toLane }.map { it.toLane }.toSet()
        val curveBotLanes = crossEdgeToLanes.filterNot { it in activeTopLanes }.toSet()

        for (l in allLanes) {
            val inTop = l in activeTopLanes
            val inBot = l in activeBottomLanes && l !in curveBotLanes
            val color = laneColors.getOrElse(l) { Color.Gray }
            val x = l * lanePx + lanePx / 2f + padPx
            if (inTop && inBot) {
                if (suppressTopLane) {
                    drawLine(color, Offset(x, centerY), Offset(x, size.height.toFloat()), strokeWidth = stroke, cap = StrokeCap.Round)
                } else {
                    drawLine(color, Offset(x, 0f), Offset(x, size.height.toFloat()), strokeWidth = stroke, cap = StrokeCap.Round)
                }
            } else if (inTop) {
                if (!suppressTopLane) {
                    drawLine(color, Offset(x, 0f), Offset(x, centerY), strokeWidth = stroke, cap = StrokeCap.Round)
                }
            } else if (inBot) {
                drawLine(color, Offset(x, centerY), Offset(x, size.height.toFloat()), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }

        for (edge in edges) {
            if (edge.fromLane == edge.toLane) continue
            val color = laneColors.getOrElse(edge.lane) { Color.Gray }
            val fromX = edge.fromLane * lanePx + lanePx / 2f + padPx
            val toX = edge.toLane * lanePx + lanePx / 2f + padPx
            val midY = centerY + (size.height - centerY) * 0.5f
            val path = androidx.compose.ui.graphics.Path().apply {
                if (edge.isMergeIn) {
                    moveTo(toX, size.height.toFloat())
                    cubicTo(toX, midY, fromX, midY, fromX, centerY)
                } else {
                    moveTo(fromX, centerY)
                    cubicTo(fromX, midY, toX, midY, toX, size.height.toFloat())
                }
            }
            drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        val nodeRadius = if (isMerge) 7.dp.toPx() else 5.dp.toPx()
        if (isMerge) {
            drawCircle(nodeColor, radius = nodeRadius, center = Offset(centerX, centerY), style = Stroke(width = 2.5.dp.toPx()))
            drawCircle(nodeColor, radius = nodeRadius / 2f, center = Offset(centerX, centerY))
        } else {
            drawCircle(nodeColor, radius = nodeRadius, center = Offset(centerX, centerY))
        }
    }
}

/** 引用 pill 行：当前分支 primary、分支 secondaryContainer、标签 tertiaryContainer。 */
@Composable
private fun RefPills(refs: List<top.wanxiang.app.ui.chat.git.GitGraphRef>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        refs.forEach { ref ->
            val bg = if (ref.isCurrent) MaterialTheme.colorScheme.primary
                else if (ref.isBranch) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.tertiaryContainer
            val fg = if (ref.isCurrent) MaterialTheme.colorScheme.onPrimary
                else if (ref.isBranch) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onTertiaryContainer
            Surface(color = bg, shape = RoundedCornerShape(4.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    RuntimeIcon(
                        when {
                            ref.isRemote -> RuntimeIconName.Cloud
                            ref.isBranch -> RuntimeIconName.GitBranch
                            else -> RuntimeIconName.Tag
                        },
                        Modifier.size(11.dp),
                        tint = fg,
                    )
                    Text(
                        ref.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = fg,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 状态码彩色小药丸（AiCode StatusChip 等价：32x20 pill）。 */
@Composable
private fun StatusChip(status: Char) {
    Surface(
        color = statusColor(status),
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.size(width = 32.dp, height = 20.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = status.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
            )
        }
    }
}

/** 把 graph.edges 按来源提交索引分组（每提交 parents.size 条边，扁平有序）。AiCode 原版。 */
private fun groupEdgesByCommit(graph: top.wanxiang.app.ui.chat.git.GitGraph): Map<Int, List<top.wanxiang.app.ui.chat.git.GraphEdge>> {
    val result = mutableMapOf<Int, List<top.wanxiang.app.ui.chat.git.GraphEdge>>()
    var edgeIdx = 0
    graph.commits.forEachIndexed { commitIdx, commit ->
        val n = if (commit.parents.isEmpty()) 0 else commit.parents.size
        val list = mutableListOf<top.wanxiang.app.ui.chat.git.GraphEdge>()
        repeat(n) {
            if (edgeIdx < graph.edges.size) list.add(graph.edges[edgeIdx++])
        }
        result[commitIdx] = list
    }
    return result
}

/** 签名卡（凭据与署名子页顶部）：显示当前署名状态，点击编辑。 */
@Composable
private fun IdentityCard(hasIdentity: Boolean, onEdit: () -> Unit) {
    RuntimeCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RuntimeIcon(RuntimeIconName.Edit, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("Git 署名（user.name / user.email）", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text(
                    if (hasIdentity) "已配置，可正常提交" else "未配置，提交前需填写",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasIdentity) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
// ======================= 状态配色 =======================

/** 时间戳 → "（2 分钟前）"；0 或近未来返回空串。 */
private fun Long.relativeAgo(): String {
    if (this <= 0L) return ""
    val diff = System.currentTimeMillis() - this
    if (diff < 0) return ""
    return when {
        diff < 60_000 -> "（刚刚）"
        diff < 60 * 60_000 -> "（${diff / 60_000} 分钟前）"
        diff < 24 * 60 * 60_000 -> "（${diff / (60 * 60_000)} 小时前）"
        else -> "（${diff / (24 * 60 * 60_000)} 天前）"
    }
}

private fun statusColor(status: Char): Color = when (status) {
    'A' -> Color(0xFF2E7D32)  // 新增 绿
    'M' -> Color(0xFFB45309)  // 修改 琥珀
    'D' -> Color(0xFFC62828)  // 删除 红
    'R', 'C' -> Color(0xFF1565C0)  // 重命名/复制 蓝
    '?' -> Color(0xFF757575)  // 未跟踪 灰
    'U' -> Color(0xFF7B1FA2)  // 冲突 紫红
    'T' -> Color(0xFF00838F)  // 类型变更 青
    else -> Color(0xFF757575)
}
