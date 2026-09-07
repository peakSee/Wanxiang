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
import top.wanxiang.app.ui.components.RuntimeTopBar

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
    aiCommit: top.wanxiang.app.ui.chat.GitAiCommitState = top.wanxiang.app.ui.chat.GitAiCommitState.Idle,
    onAiGenerate: () -> Unit = {},
    credentialHealth: Map<String, top.wanxiang.app.ui.chat.GitCredHealth> = emptyMap(),
    onVerifyCredential: (String) -> Unit = {},
    repoListState: top.wanxiang.app.ui.chat.GitRepoListState = top.wanxiang.app.ui.chat.GitRepoListState.Idle,
    onFetchRepos: (String) -> Unit = {},
    onClearRepoList: () -> Unit = {},
) {
    if (state.commitDetailHash != null) {
        GitCommitDetailView(state, onBack = onClearCommitDetail)
        return
    }
    if (state.diffPath != null) {
        GitDiffView(state, onBack = onClearDiff)
        return
    }
    BackHandler(onBack = onDismiss)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        RuntimeTopBar(
            title = stringResource(R.string.chat_git_panel_title),
            onBack = onDismiss,
            statusText = state.branch,
            actions = {
                RuntimeIconButton(onClick = { showCredentialDialog = true }) {
                    RuntimeIcon(RuntimeIconName.Key, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RuntimeIconButton(onClick = onRefresh, enabled = !state.loading) {
                    RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                }
            },
        )

        TabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("状态") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("分支") })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("历史") })
            Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("凭证") })
        }

        // 横向 Pager：左右滑切换 4 页；TabRow 与 Pager 双向同步。
        // 每一页自己处理"仓库状态不足"分支（凭证页永远可用；其它三页无仓库时显示 NotARepoView）
        val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = selectedTab) { 4 }
        LaunchedEffect(pagerState.currentPage) { selectedTab = pagerState.currentPage }
        LaunchedEffect(selectedTab) {
            if (pagerState.currentPage != selectedTab) pagerState.animateScrollToPage(selectedTab)
        }
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                3 -> CredentialsTab(
                    credentials = credentials,
                    onAdd = { showAddPatDialog = true },
                    onDelete = onDeleteCredential,
                    health = credentialHealth,
                    onVerify = onVerifyCredential,
                )
                else -> when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        RuntimeCircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                    state.notARepo || state.branch == null -> NotARepoView(
                        onInit = onInitRepo,
                        onOpenClone = { showCloneDialog = true },
                    )
                    state.error != null -> CenterHint(state.error, isError = true)
                    page == 0 -> StatusTab(state, onFileDiff, onStage, onUnstage, onStageAll, onUnstageAll, onCommit, onPull, onPush, onRevert, onRevertAll, onDeleteUntracked, aiCommit, onAiGenerate)
                    page == 1 -> BranchesTab(state, onCheckout, onCreateBranch, onDeleteBranch, onRenameBranch, onDeleteRemoteBranch, onCreateTag, onDeleteTag)
                    page == 2 -> LogTab(state, onCommitDetail)
                }
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
        val doClone = {
            val u = cloneUrl.trim()
            if (u.isNotBlank()) {
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
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://github.com/owner/repo.git") },
                        singleLine = true,
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
}

@Composable
private fun CredentialsTab(
    credentials: List<top.wanxiang.app.core.datastore.GitCredential>,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    health: Map<String, top.wanxiang.app.ui.chat.GitCredHealth> = emptyMap(),
    onVerify: (String) -> Unit = {},
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
            RuntimeButton(onClick = onAdd) { Text("新增") }
        }
        if (credentials.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Text(
                    "还没有凭证。添加一个 GitHub/Gitee/GitLab 的 Personal Access Token，就能克隆或推送私有仓库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                                    Text("✓ Token 有效", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                is top.wanxiang.app.ui.chat.GitCredHealth.Invalid ->
                                    Text("✗ Token 无效 (HTTP ${h.code})", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
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
private fun CenterHint(text: String, isError: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        if (state.diffLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                RuntimeCircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        } else {
            DiffText(state.diffText ?: "")
        }
    }
}

@Composable
private fun GitCommitDetailView(state: GitPanelState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        RuntimeTopBar(title = state.commitDetailHash ?: "", onBack = onBack)
        if (state.commitDetailLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                RuntimeCircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        } else {
            DiffText(state.commitDetailText ?: "")
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
            CenterHint("无分支信息")
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

// ======================= 历史 Tab =======================

@Composable
private fun LogTab(state: GitPanelState, onCommitDetail: (String) -> Unit) {
    if (state.commits.isEmpty()) {
        CenterHint("无提交历史")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(state.commits) { index, commit ->
            val parts = commit.split('\u001f')
            val hash = parts.getOrNull(0) ?: ""
            val subject = parts.getOrNull(1) ?: ""
            val author = parts.getOrNull(2) ?: ""
            val date = parts.getOrNull(3) ?: ""
            val avatarColor = colorForAuthor(author)
            val isLast = index == state.commits.lastIndex
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onCommitDetail(hash) }.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 左侧：竖线 + 头像圆点
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.width(30.dp).fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (!isLast) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = 28.dp)
                                .width(2.dp)
                                .height(60.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        ) {}
                    }
                    Surface(
                        modifier = Modifier.size(24.dp),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = avatarColor,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                author.take(1).uppercase().ifBlank { "?" },
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        subject.ifBlank { "(无提交信息)" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            author.ifBlank { "?" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text(
                            date,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        hash,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
        }
    }
}

/** 根据作者字符串哈希出一个稳定色，用于头像圆点。 */
private val authorPalette = listOf(
    Color(0xFF1976D2), Color(0xFF388E3C), Color(0xFFD84315), Color(0xFF6A1B9A),
    Color(0xFF00838F), Color(0xFFC62828), Color(0xFF558B2F), Color(0xFF4527A0),
    Color(0xFF00695C), Color(0xFFEF6C00),
)
private fun colorForAuthor(author: String): Color {
    if (author.isBlank()) return Color(0xFF757575)
    return authorPalette[(author.hashCode() and 0x7FFFFFFF) % authorPalette.size]
}

// ======================= 状态配色 =======================

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
