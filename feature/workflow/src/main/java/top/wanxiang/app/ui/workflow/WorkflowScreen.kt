package top.wanxiang.app.ui.workflow

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wanxiang.app.core.model.workflow.NodeRunStatus
import top.wanxiang.app.core.model.workflow.WorkflowApprovalRequest
import top.wanxiang.app.core.model.workflow.WorkflowDefinition
import top.wanxiang.app.core.model.workflow.WorkflowRunStatus
import top.wanxiang.app.core.model.workflow.WorkflowRuntimeState
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import top.wanxiang.app.ui.components.RuntimeButton
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeOutlinedButton
import top.wanxiang.app.ui.components.RuntimeTopBar

@Composable
fun WorkflowScreen(
    projectName: String = "",
    initialWorkflowId: String? = null,
    initialVariables: Map<String, String> = emptyMap(),
    onBack: () -> Unit,
    viewModel: WorkflowViewModel = hiltViewModel(),
) {
    val definitions by viewModel.definitions.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); viewModel.clearError() } }
    var pendingRun by remember { mutableStateOf<WorkflowDefinition?>(null) }
    val activeState by viewModel.activeState.collectAsStateWithLifecycle()
    val approval by viewModel.approvalRequest.collectAsStateWithLifecycle()
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()
    var discardRequested by rememberSaveable { mutableStateOf(false) }
    var autoStarted by rememberSaveable(initialWorkflowId) { mutableStateOf(false) }
    LaunchedEffect(initialWorkflowId, definitions, autoStarted) {
        if (!autoStarted && initialWorkflowId != null) {
            definitions.firstOrNull { it.id == initialWorkflowId }?.let {
                autoStarted = true
                pendingRun = it
            }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            RuntimeTopBar(
                title = activeState?.definition?.name ?: editorState?.definition?.name ?: "万象工作流",
                statusText = projectName.takeIf(String::isNotBlank)?.let { "工作区 · $it" },
                onBack = {
                    when {
                        activeState != null -> viewModel.closeRun()
                        editorState?.isDirty == true -> discardRequested = true
                        editorState != null -> viewModel.closeEditor()
                        else -> onBack()
                    }
                },
            )
        },
    ) { padding ->
        when {
            editorState != null -> WorkflowEditorView(
                state = editorState!!,
                activeRunState = activeState,
                onRun = {
                    if (editorState!!.isDirty) {
                        viewModel.saveEditor()
                    }
                    pendingRun = editorState!!.definition
                },
                onCancelRun = viewModel::cancel,
                onSelectNode = viewModel::selectNode,
                onMoveNode = viewModel::moveNode,
                onBeginConnection = viewModel::beginConnection,
                onCancelConnection = viewModel::cancelConnection,
                onConnect = viewModel::connectTo,
                onAddNode = viewModel::addNode,
                onUpdateNode = viewModel::updateNode,
                onUpdateMetadata = viewModel::updateMetadata,
                onRemoveNode = viewModel::removeSelectedNode,
                onRemoveNodeById = viewModel::removeNode,
                onConnectNodes = viewModel::connectNodes,
                onDisconnectNodes = viewModel::disconnectNodes,
                onDisconnectAllForNode = viewModel::disconnectAllForNode,
                onUpdateEdge = viewModel::updateEdge,
                onRemoveEdge = viewModel::removeEdge,
                onUndo = viewModel::undoEdit,
                onRedo = viewModel::redoEdit,
                onSave = viewModel::saveEditor,
                onAutoLayout = viewModel::autoLayout,
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            )
            activeState == null -> WorkflowCatalog(
                definitions = definitions,
                onRun = { pendingRun = it },
                history = history,
                onHistory = viewModel::showHistory,
                onEdit = viewModel::edit,
                onDelete = viewModel::deleteWorkflow,
                onCreate = viewModel::createWorkflow,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> WorkflowRunView(
                state = activeState!!,
                onCancel = viewModel::cancel,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
    approval?.let { request ->
        ApprovalDialog(request, onDecision = { approved, variables -> viewModel.decide(request.nodeId, approved, variables) })
    }
    pendingRun?.let { definition ->
        var apks by remember(definition.id, projectName) {
            mutableStateOf(viewModel.scanWorkspaceApks(projectName))
        }
        WorkflowStartDialog(
            definition = definition,
            supplied = initialVariables,
            models = models,
            availableApks = apks,
            onRefreshApks = { apks = viewModel.scanWorkspaceApks(projectName) },
            onDismiss = { pendingRun = null },
        ) { variables ->
            pendingRun = null
            viewModel.start(definition, projectName, variables)
        }
    }
    if (discardRequested) {
        RuntimeAlertDialog(
            onDismissRequest = { discardRequested = false },
            title = { Text("放弃未保存更改？") },
            text = { Text("当前工作流还有未保存的编辑。") },
            dismissButton = { RuntimeOutlinedButton(onClick = { discardRequested = false }) { Text("继续编辑") } },
            confirmButton = {
                RuntimeButton(onClick = { discardRequested = false; viewModel.closeEditor() }) { Text("放弃更改") }
            },
        )
    }
}

@Composable
private fun WorkflowCatalog(
    definitions: List<WorkflowDefinition>,
    onRun: (WorkflowDefinition) -> Unit,
    onEdit: (WorkflowDefinition) -> Unit,
    onDelete: (WorkflowDefinition) -> Unit,
    onCreate: () -> Unit,
    history: List<WorkflowRuntimeState>,
    onHistory: (WorkflowRuntimeState) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteRequested by remember { mutableStateOf<WorkflowDefinition?>(null) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "把构建、诊断、逆向与 Git 操作编排成可观察、可暂停的 DAG。",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                RuntimeButton(onClick = onCreate, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                    RuntimeIcon(RuntimeIconName.Plus, Modifier.size(16.dp))
                    Text("新建", maxLines = 1)
                }
            }
        }
        items(definitions, key = { it.id }) { workflow ->
            RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RuntimeIcon(RuntimeIconName.Hub, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(workflow.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${workflow.category} · ${workflow.nodes.size} 个节点", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(workflow.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!workflow.isBuiltin) {
                            RuntimeOutlinedButton(onClick = { deleteRequested = workflow }, contentPadding = PaddingValues(9.dp)) {
                                RuntimeIcon(RuntimeIconName.Trash, Modifier.size(16.dp))
                            }
                        }
                        RuntimeOutlinedButton(onClick = { onEdit(workflow) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                            RuntimeIcon(RuntimeIconName.Edit, Modifier.size(16.dp))
                            Text(if (workflow.isBuiltin) "创建副本" else "编辑", maxLines = 1)
                        }
                        RuntimeButton(onClick = { onRun(workflow) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                            RuntimeIcon(RuntimeIconName.Play, Modifier.size(16.dp))
                            Text("运行", maxLines = 1)
                        }
                    }
                }
            }
        }
        if (history.isNotEmpty()) item { Text("最近运行（最近 ${history.size} 条，只读）", style = MaterialTheme.typography.titleMedium) }
        items(history, key = { "history:${it.executionId}" }) { run ->
            RuntimeOutlinedButton(onClick = { onHistory(run) }, modifier = Modifier.fillMaxWidth()) {
                Text("${run.definition.name} · ${runStatusLabel(run.status)} · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(run.startedAt ?: 0))}", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    deleteRequested?.let { workflow ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteRequested = null },
            title = { Text("删除工作流？") },
            text = { Text("“${workflow.name}”及其运行历史将被永久删除，无法撤销。") },
            dismissButton = { RuntimeOutlinedButton(onClick = { deleteRequested = null }) { Text("取消") } },
            confirmButton = {
                RuntimeButton(
                    onClick = {
                        deleteRequested = null
                        onDelete(workflow)
                    },
                ) { Text("删除") }
            },
        )
    }
}

@Composable
private fun WorkflowRunView(
    state: WorkflowRuntimeState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        var selectedNode by remember(state.executionId) { mutableStateOf<String?>(null) }
        if (maxWidth >= 760.dp) {
            Row(Modifier.fillMaxSize()) {
                WorkflowCanvas2D(state.definition, state, Modifier.weight(1f).fillMaxSize(), selectedNodeId = selectedNode, onNodeSelected = { selectedNode = it })
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RunSummary(state)
                    val node = state.definition.nodes.firstOrNull { it.id == selectedNode }
                    if (node == null) Text("点选节点查看日志、错误与产物")
                    else {
                        Text(node.title, style = MaterialTheme.typography.titleMedium)
                        WorkflowNodeDetails(state.nodeStates[node.id])
                    }
                }
            }
        } else {
            WorkflowTimelineView(state, Modifier.fillMaxSize())
        }
        if (state.status in setOf(WorkflowRunStatus.RUNNING, WorkflowRunStatus.WAITING_APPROVAL)) {
            RuntimeOutlinedButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("取消", maxLines = 1) }
        }
    }
}

@Composable
fun WorkflowTimelineView(state: WorkflowRuntimeState, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            RunSummary(state)
        }
        items(state.definition.nodes, key = { it.id }) { node ->
            val run = state.nodeStates[node.id]
            val status = run?.status ?: NodeRunStatus.IDLE
            val isRunning = status == NodeRunStatus.RUNNING || status == NodeRunStatus.STREAMING
            val isWaiting = status == NodeRunStatus.WAITING_APPROVAL
            val color = statusColor(status)

            val pulseAlpha = if (isRunning) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse_${node.id}")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.40f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "pulseAlpha",
                )
                alpha
            } else if (isWaiting) {
                0.85f
            } else {
                0.55f
            }

            val cardContainerColor = if (isRunning) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f * pulseAlpha)
            } else if (isWaiting) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.20f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }

            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = cardContainerColor,
                borderColor = color.copy(alpha = pulseAlpha),
                contentPadding = PaddingValues(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RuntimeIcon(statusIcon(status), Modifier.size(18.dp), tint = color.copy(alpha = if (isRunning) pulseAlpha else 1f))
                        Spacer(Modifier.width(10.dp))
                        Text(node.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val duration = run?.output?.durationMs
                        if (duration != null && duration > 0L) {
                            Text("${duration}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.padding(end = 6.dp))
                        }
                        Text(statusLabel(status), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, fontWeight = if (isRunning || isWaiting) FontWeight.Bold else FontWeight.Normal)
                    }
                    run?.progressMessage?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    WorkflowNodeDetails(run)
                }
            }
        }
        item { Spacer(Modifier.height(64.dp)) }
    }
}

@Composable
private fun RunSummary(state: WorkflowRuntimeState) {
    val color = when (state.status) {
        WorkflowRunStatus.SUCCESS -> Color(0xFF2E7D32)
        WorkflowRunStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    RuntimeCard(modifier = Modifier.fillMaxWidth(), borderColor = color.copy(alpha = 0.45f), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RuntimeIcon(statusIcon(state.nodeStates.values.lastOrNull()?.status ?: NodeRunStatus.PENDING), Modifier.size(20.dp), tint = color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(runStatusLabel(state.status), fontWeight = FontWeight.SemiBold)
                WorkflowElapsed(state)
                state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun ApprovalDialog(request: WorkflowApprovalRequest, onDecision: (Boolean, Map<String, String>) -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(request.executionId, request.nodeId) {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    }
    val values = remember(request) { mutableStateMapOf<String, String>().apply { request.requestedVariables.forEach { put(it, "") } } }
    RuntimeAlertDialog(
        onDismissRequest = { onDecision(false, emptyMap()) },
        title = { Text(request.title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (request.description.isNotBlank()) Text(request.description, style = MaterialTheme.typography.bodyMedium)
                request.requestedVariables.forEach { key ->
                    OutlinedTextField(value = values[key].orEmpty(), onValueChange = { values[key] = it }, label = { Text(key, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            }
        },
        dismissButton = { RuntimeOutlinedButton(onClick = { onDecision(false, emptyMap()) }) { Text("拒绝", maxLines = 1) } },
        confirmButton = { RuntimeButton(onClick = { onDecision(true, values.toMap()) }) { Text("确认执行", maxLines = 1) } },
    )
}

@Composable
internal fun statusColor(status: NodeRunStatus): Color = when (status) {
    NodeRunStatus.SUCCESS -> Color(0xFF2E7D32)
    NodeRunStatus.FAILED, NodeRunStatus.CANCELLED -> MaterialTheme.colorScheme.error
    NodeRunStatus.RUNNING, NodeRunStatus.STREAMING, NodeRunStatus.WAITING_APPROVAL -> MaterialTheme.colorScheme.primary
    NodeRunStatus.SKIPPED -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.outline
}

internal fun statusIcon(status: NodeRunStatus) = when (status) {
    NodeRunStatus.SUCCESS -> RuntimeIconName.Check
    NodeRunStatus.FAILED, NodeRunStatus.CANCELLED -> RuntimeIconName.Alert
    NodeRunStatus.RUNNING, NodeRunStatus.STREAMING -> RuntimeIconName.Play
    NodeRunStatus.WAITING_APPROVAL -> RuntimeIconName.Shield
    NodeRunStatus.SKIPPED -> RuntimeIconName.ArrowUp
    else -> RuntimeIconName.Hub
}

internal fun statusLabel(status: NodeRunStatus) = when (status) {
    NodeRunStatus.IDLE -> "空闲"
    NodeRunStatus.PENDING -> "等待"
    NodeRunStatus.RUNNING -> "运行中"
    NodeRunStatus.STREAMING -> "输出中"
    NodeRunStatus.WAITING_APPROVAL -> "待确认"
    NodeRunStatus.SUCCESS -> "成功"
    NodeRunStatus.FAILED -> "失败"
    NodeRunStatus.SKIPPED -> "已跳过"
    NodeRunStatus.CANCELLED -> "已取消"
}

private fun runStatusLabel(status: WorkflowRunStatus) = when (status) {
    WorkflowRunStatus.IDLE -> "准备执行"
    WorkflowRunStatus.RUNNING -> "工作流运行中"
    WorkflowRunStatus.WAITING_APPROVAL -> "等待人工确认"
    WorkflowRunStatus.SUCCESS -> "工作流已完成"
    WorkflowRunStatus.FAILED -> "工作流执行失败"
    WorkflowRunStatus.CANCELLED -> "工作流已取消"
}
