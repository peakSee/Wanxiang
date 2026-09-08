package top.wanxiang.app.ui.workflow

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import top.wanxiang.app.ui.components.RuntimeIconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.wanxiang.app.core.model.workflow.FailurePolicy
import top.wanxiang.app.core.model.workflow.HostWorkflowActions
import top.wanxiang.app.core.model.workflow.HostWorkflowPrivilege
import top.wanxiang.app.core.model.workflow.NodeRunStatus
import top.wanxiang.app.core.model.workflow.WorkflowEdge
import top.wanxiang.app.core.model.workflow.WorkflowNode
import top.wanxiang.app.core.model.workflow.WorkflowNodeType
import top.wanxiang.app.core.model.workflow.WorkflowRunStatus
import top.wanxiang.app.core.model.workflow.WorkflowRuntimeState
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import top.wanxiang.app.ui.components.RuntimeButton
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeOutlinedButton

@Composable
fun WorkflowEditorView(
    state: WorkflowEditorUiState,
    activeRunState: WorkflowRuntimeState? = null,
    onRun: () -> Unit = {},
    onCancelRun: () -> Unit = {},
    onSelectNode: (String?) -> Unit,
    onMoveNode: (String, Float, Float) -> Unit,
    onBeginConnection: (String) -> Unit,
    onCancelConnection: () -> Unit,
    onConnect: (String) -> Unit,
    onAddNode: (WorkflowNodeType) -> Unit,
    onUpdateNode: (WorkflowNode) -> Unit,
    onUpdateMetadata: (String, String, String) -> Unit,
    onRemoveNode: () -> Unit,
    onRemoveNodeById: (String) -> Unit = {},
    onConnectNodes: (String, String, String, String?) -> Unit = { _, _, _, _ -> },
    onDisconnectNodes: (String, String) -> Unit = { _, _ -> },
    onDisconnectAllForNode: (String) -> Unit = {},
    onUpdateEdge: (WorkflowEdge) -> Unit,
    onRemoveEdge: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onAutoLayout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRunning = activeRunState?.status in setOf(WorkflowRunStatus.RUNNING, WorkflowRunStatus.WAITING_APPROVAL)
    var sheetExpanded by remember { mutableStateOf(false) }
    var showRunConsole by remember { mutableStateOf(false) }
    var nodePendingDelete by remember { mutableStateOf<WorkflowNode?>(null) }

    BoxWithConstraints(modifier) {
        val editorHeight = maxHeight
        val isWideScreen = maxWidth >= 820.dp
        val selectedNode = state.definition.nodes.firstOrNull { it.id == state.selectedNodeId }
        val isInspectorOpen = !isWideScreen && sheetExpanded && selectedNode != null

        Column(Modifier.fillMaxSize()) {
            state.message?.let { message ->
                Surface(
                    color = if (message == "已保存" || message == "请选择目标节点") {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message == "已保存" || message == "请选择目标节点") {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val canvas: @Composable (Modifier) -> Unit = { canvasModifier ->
                    WorkflowCanvas2D(
                        definition = state.definition,
                        state = activeRunState,
                        editable = !isRunning,
                        selectedNodeId = state.selectedNodeId,
                        connectionSourceId = state.connectionSourceId,
                        onNodeSelected = onSelectNode,
                        onNodeClicked = { nodeId ->
                            onSelectNode(nodeId)
                            sheetExpanded = true
                        },
                        onCanvasTapped = {
                            sheetExpanded = false
                            onSelectNode(null)
                        },
                        onNodeMoved = onMoveNode,
                        onConnectionRequested = { _, target -> onConnect(target) },
                        onBeginConnection = onBeginConnection,
                        onRemoveNode = { nodeId ->
                            val target = state.definition.nodes.firstOrNull { it.id == nodeId }
                            if (target != null) nodePendingDelete = target
                        },
                        onConfigureNode = { nodeId ->
                            onSelectNode(nodeId)
                            sheetExpanded = true
                        },
                        modifier = canvasModifier,
                    )
                }
                val inspector: @Composable (Modifier) -> Unit = { inspectorModifier ->
                    EditorInspector(
                        state = state,
                        activeRunState = activeRunState,
                        onUpdateNode = onUpdateNode,
                        onUpdateMetadata = onUpdateMetadata,
                        onBeginConnection = onBeginConnection,
                        onCancelConnection = onCancelConnection,
                        onRemoveNode = {
                            val selected = state.definition.nodes.firstOrNull { it.id == state.selectedNodeId }
                            if (selected != null) nodePendingDelete = selected
                        },
                        onConnectNodes = onConnectNodes,
                        onDisconnectAllForNode = onDisconnectAllForNode,
                        onUpdateEdge = onUpdateEdge,
                        onRemoveEdge = onRemoveEdge,
                        onOpenConsole = { showRunConsole = true },
                        modifier = inspectorModifier,
                    )
                }

                if (isWideScreen) {
                    Row(Modifier.fillMaxSize()) {
                        canvas(Modifier.weight(1f).fillMaxSize())
                        if (sheetExpanded && selectedNode != null) {
                            VerticalDivider()
                            Box(Modifier.width(420.dp).fillMaxSize()) {
                                Column(Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        val theme = selectedNode.type.visualTheme()
                                        Surface(
                                            color = theme.accentColor.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(6.dp),
                                        ) {
                                            Text(
                                                text = theme.tag,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = theme.accentColor,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            )
                                        }
                                        Text(
                                            text = selectedNode.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        RuntimeIconButton(
                                            onClick = {
                                                sheetExpanded = false
                                                onSelectNode(null)
                                            },
                                            modifier = Modifier.size(32.dp),
                                            contentDescription = "关闭面板",
                                        ) {
                                            RuntimeIcon(RuntimeIconName.Close, Modifier.size(18.dp))
                                        }
                                    }
                                    HorizontalDivider()
                                    inspector(Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                } else {
                    EditorBottomSheetLayout(
                        maxHeight = editorHeight,
                        selectedNode = selectedNode,
                        sheetExpanded = sheetExpanded && selectedNode != null,
                        onSheetExpandedChange = { expanded ->
                            sheetExpanded = expanded
                            if (!expanded) onSelectNode(null)
                        },
                        isRunning = isRunning,
                        canvas = canvas,
                        inspector = inspector,
                    )
                }
            }

            AnimatedVisibility(
                visible = !isInspectorOpen,
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                    expandVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                    fadeIn(tween(160)),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                    shrinkVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                    fadeOut(tween(140)),
            ) {
                EditorBottomDock(
                    state = state,
                    activeRunState = activeRunState,
                    onAddNode = onAddNode,
                    onDeleteSelectedNode = {
                        val selected = state.definition.nodes.firstOrNull { it.id == state.selectedNodeId }
                        if (selected != null) nodePendingDelete = selected
                    },
                    onToggleConsole = { showRunConsole = true },
                    onUndo = onUndo,
                    onRedo = onRedo,
                    onSave = onSave,
                    onAutoLayout = onAutoLayout,
                    onRun = {
                        onRun()
                        showRunConsole = true
                    },
                    onCancelRun = onCancelRun,
                )
            }
        }
    }

    // 节点删除确认弹窗
    nodePendingDelete?.let { target ->
        RuntimeAlertDialog(
            onDismissRequest = { nodePendingDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeIcon(RuntimeIconName.Trash, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    Text("确认删除节点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = "是否确定删除「${target.title}」？\n与该节点相连的所有输入和输出连线也将被一并断开移除。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                RuntimeButton(
                    onClick = {
                        val id = target.id
                        nodePendingDelete = null
                        if (state.selectedNodeId == id) onRemoveNode() else onRemoveNodeById(id)
                    },
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                RuntimeOutlinedButton(onClick = { nodePendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }

    // 运行日志与控制台弹窗
    if (showRunConsole && activeRunState != null) {
        RunConsoleModal(
            activeRunState = activeRunState,
            onDismiss = { showRunConsole = false },
            onCancelRun = onCancelRun,
            onRerun = onRun,
        )
    }
}

@Composable
private fun EditorBottomSheetLayout(
    maxHeight: Dp,
    selectedNode: WorkflowNode?,
    sheetExpanded: Boolean,
    onSheetExpandedChange: (Boolean) -> Unit,
    isRunning: Boolean,
    canvas: @Composable (Modifier) -> Unit,
    inspector: @Composable (Modifier) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // 画布全屏铺满，彻底消除常驻 56dp 底栏占用
        canvas(Modifier.fillMaxSize())

        // 仅在单击节点并且展开时才向上弹出，非持久化
        AnimatedVisibility(
            visible = sheetExpanded && selectedNode != null,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(280, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(220, easing = FastOutSlowInEasing)) + fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (selectedNode != null) {
                val theme = selectedNode.type.visualTheme()
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(maxHeight * 0.62f)
                        .shadow(
                            elevation = 16.dp,
                            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {},
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    tonalElevation = 4.dp,
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                    ) {
                        // 顶部居中拖拽把手指示条
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 36.dp, height = 4.dp)
                                    .background(
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                        CircleShape,
                                    ),
                            )
                        }

                        // 弹窗顶部栏：类型彩色药丸、节点标题、关闭按钮
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                color = theme.accentColor.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    text = theme.tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = theme.accentColor,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                            Text(
                                text = selectedNode.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            RuntimeIconButton(
                                onClick = { onSheetExpandedChange(false) },
                                modifier = Modifier.size(32.dp),
                                contentDescription = "收起面板",
                            ) {
                                RuntimeIcon(RuntimeIconName.Close, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()

                        // 属性配置与连线检查器
                        inspector(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorBottomDock(
    state: WorkflowEditorUiState,
    activeRunState: WorkflowRuntimeState?,
    onAddNode: (WorkflowNodeType) -> Unit,
    onDeleteSelectedNode: () -> Unit,
    onToggleConsole: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onAutoLayout: () -> Unit,
    onRun: () -> Unit,
    onCancelRun: () -> Unit,
) {
    var addDialogVisible by remember { mutableStateOf(false) }
    val isRunning = activeRunState?.status in setOf(WorkflowRunStatus.RUNNING, WorkflowRunStatus.WAITING_APPROVAL)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp), // 去除圆角，平铺沉底贴边
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                thickness = 1.dp,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            // 添加节点
            DockActionButton(
                icon = RuntimeIconName.Plus,
                label = "添加",
                onClick = { addDialogVisible = true },
                tint = MaterialTheme.colorScheme.primary,
            )

            // 运行 / 停止调试
            if (isRunning) {
                DockActionButton(
                    icon = RuntimeIconName.Stop,
                    label = "停止",
                    onClick = onCancelRun,
                    tint = MaterialTheme.colorScheme.error,
                )
            } else {
                DockActionButton(
                    icon = RuntimeIconName.Play,
                    label = "运行",
                    onClick = onRun,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            // 控制台 / 日志
            if (activeRunState != null) {
                DockActionButton(
                    icon = RuntimeIconName.Terminal,
                    label = if (isRunning) "运行中" else "日志",
                    onClick = onToggleConsole,
                    tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    badge = if (isRunning) {
                        {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .align(Alignment.TopEnd)
                            )
                        }
                    } else null,
                )
            }

            // 删除节点（当选中节点且未在运行时呈现）
            if (state.selectedNodeId != null && !isRunning) {
                DockActionButton(
                    icon = RuntimeIconName.Trash,
                    label = "删除",
                    onClick = onDeleteSelectedNode,
                    tint = MaterialTheme.colorScheme.error,
                )
            }

            // 自动排版
            DockActionButton(
                icon = RuntimeIconName.Tune,
                label = "排版",
                onClick = onAutoLayout,
            )

            // 撤销
            DockActionButton(
                icon = RuntimeIconName.Back,
                label = "撤销",
                enabled = state.canUndo && !isRunning,
                onClick = onUndo,
            )

            // 重做
            DockActionButton(
                icon = RuntimeIconName.Reverse,
                label = "重做",
                enabled = state.canRedo && !isRunning,
                onClick = onRedo,
            )

            // 保存
            DockActionButton(
                icon = RuntimeIconName.Save,
                label = when {
                    state.isSaving -> "保存中"
                    state.isDirty -> "保存*"
                    else -> "已保存"
                },
                enabled = !state.isSaving && state.isDirty && !isRunning,
                tint = if (state.isDirty) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onSave,
            )
        }
    }
}

    if (addDialogVisible) {
        NodePickerModal(
            onDismiss = { addDialogVisible = false },
            onSelect = { type ->
                addDialogVisible = false
                onAddNode(type)
            },
        )
    }
}

@Composable
private fun DockActionButton(
    icon: RuntimeIconName,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    badge: (@Composable BoxScope.() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(0.dp), // 去除圆角
        color = Color.Transparent,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 2.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                RuntimeIcon(icon, Modifier.size(19.dp), tint = tint)
                badge?.invoke(this)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = tint,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RunConsoleModal(
    activeRunState: WorkflowRuntimeState,
    onDismiss: () -> Unit,
    onCancelRun: () -> Unit,
    onRerun: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val isRunning = activeRunState.status in setOf(WorkflowRunStatus.RUNNING, WorkflowRunStatus.WAITING_APPROVAL)
    val startedAt = activeRunState.startedAt ?: 0L
    val finishedAt = activeRunState.finishedAt ?: 0L
    val durationMs = if (finishedAt > 0L && startedAt > 0L) {
        finishedAt - startedAt
    } else if (startedAt > 0L) {
        (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
    } else {
        0L
    }

    val consoleOutput = remember(activeRunState) {
        buildString {
            appendLine("=== 太墟工作流「${activeRunState.definition.name}」控制台输出 ===")
            appendLine("全局状态: ${runStatusLabel(activeRunState.status)}  |  执行耗时: ${durationMs}ms")
            activeRunState.error?.let {
                appendLine("❌ 异常信息: $it")
            }
            appendLine()
            activeRunState.definition.nodes.forEach { node ->
                val run = activeRunState.nodeStates[node.id]
                val status = run?.status ?: NodeRunStatus.PENDING
                appendLine("--------------------------------------------------")
                appendLine("[${statusLabel(status)}] 节点: ${node.title} (${node.id})")
                val progress = run?.progressMessage
                val textOutput = run?.output?.textOutput
                val errorOutput = run?.output?.error
                if (!progress.isNullOrBlank()) {
                    appendLine("进度: $progress")
                }
                if (!textOutput.isNullOrBlank()) {
                    appendLine(textOutput)
                }
                if (!errorOutput.isNullOrBlank()) {
                    appendLine("stderr: $errorOutput")
                }
            }
            appendLine("--------------------------------------------------")
            appendLine("=== 执行输出结束 ===")
        }
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(consoleOutput) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text("运行调试控制台", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Surface(
                    color = runStatusColor(activeRunState.status).copy(alpha = 0.16f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = runStatusLabel(activeRunState.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = runStatusColor(activeRunState.status),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    activeRunState.definition.nodes.forEach { node ->
                        val nodeRun = activeRunState.nodeStates[node.id]
                        val st = nodeRun?.status ?: NodeRunStatus.PENDING
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = statusColor(st).copy(alpha = 0.14f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = statusIconText(st),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor(st),
                                )
                                Text(
                                    text = node.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor(st),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                Surface(
                    color = Color(0xFF0F172A),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                ) {
                    Box(Modifier.fillMaxSize().padding(10.dp)) {
                        Text(
                            text = consoleOutput,
                            color = Color(0xFFE2E8F0),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            modifier = Modifier.verticalScroll(scrollState),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeOutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(consoleOutput))
                }) {
                    Text("复制日志")
                }
                if (isRunning) {
                    RuntimeOutlinedButton(onClick = onCancelRun) {
                        Text("停止运行", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    RuntimeButton(onClick = onRerun) {
                        Text("重新运行")
                    }
                }
            }
        },
        dismissButton = {
            RuntimeOutlinedButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun statusIconText(status: NodeRunStatus) = when (status) {
    NodeRunStatus.SUCCESS -> "✔"
    NodeRunStatus.FAILED -> "✘"
    NodeRunStatus.RUNNING, NodeRunStatus.STREAMING -> "●"
    NodeRunStatus.SKIPPED -> "↷"
    NodeRunStatus.CANCELLED -> "⊘"
    NodeRunStatus.WAITING_APPROVAL -> "🛡"
    NodeRunStatus.IDLE, NodeRunStatus.PENDING -> "⌛"
}

@Composable
private fun runStatusColor(status: WorkflowRunStatus): Color = when (status) {
    WorkflowRunStatus.SUCCESS -> Color(0xFF2E7D32)
    WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED -> MaterialTheme.colorScheme.error
    WorkflowRunStatus.RUNNING, WorkflowRunStatus.WAITING_APPROVAL -> MaterialTheme.colorScheme.primary
    WorkflowRunStatus.IDLE -> MaterialTheme.colorScheme.outline
}

private fun runStatusLabel(status: WorkflowRunStatus): String = when (status) {
    WorkflowRunStatus.IDLE -> "未运行"
    WorkflowRunStatus.RUNNING -> "运行中"
    WorkflowRunStatus.WAITING_APPROVAL -> "等待审批"
    WorkflowRunStatus.SUCCESS -> "执行成功"
    WorkflowRunStatus.FAILED -> "执行失败"
    WorkflowRunStatus.CANCELLED -> "已取消"
}


@Composable
private fun NodePickerModal(
    onDismiss: () -> Unit,
    onSelect: (WorkflowNodeType) -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("添加工作流节点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "选择要加入画布的节点类型，创建后可在检查面板配置参数：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WorkflowNodeType.entries.forEach { type ->
                    val meta = type.metadata()
                    Surface(
                        onClick = { onSelect(type) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    RuntimeIcon(meta.icon, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(meta.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    meta.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            RuntimeOutlinedButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun EditorInspector(
    state: WorkflowEditorUiState,
    activeRunState: WorkflowRuntimeState?,
    onUpdateNode: (WorkflowNode) -> Unit,
    onUpdateMetadata: (String, String, String) -> Unit,
    onBeginConnection: (String) -> Unit,
    onCancelConnection: () -> Unit,
    onRemoveNode: () -> Unit,
    onConnectNodes: (String, String, String, String?) -> Unit,
    onDisconnectAllForNode: (String) -> Unit,
    onUpdateEdge: (WorkflowEdge) -> Unit,
    onRemoveEdge: (String) -> Unit,
    onOpenConsole: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = state.definition.nodes.firstOrNull { it.id == state.selectedNodeId }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WorkflowMetadataCard(state, onUpdateMetadata)

        if (selected == null) {
            RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                Text("请点击画布中的节点以配置参数、管理连线或查看执行日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val nodeRun = activeRunState?.nodeStates?.get(selected.id)
            if (nodeRun != null) {
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = statusColor(nodeRun.status).copy(alpha = 0.5f),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("调试运行状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Surface(
                                color = statusColor(nodeRun.status).copy(alpha = 0.16f),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text(
                                    text = statusLabel(nodeRun.status),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor(nodeRun.status),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        WorkflowNodeDetails(nodeRun)
                        RuntimeOutlinedButton(onClick = onOpenConsole, modifier = Modifier.fillMaxWidth()) {
                            RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(16.dp))
                            Text("打开完整终端控制台日志", maxLines = 1)
                        }
                    }
                }
            }

            NodeInspectorCard(
                state = state,
                node = selected,
                isConnecting = state.connectionSourceId == selected.id,
                onApply = onUpdateNode,
                onBeginConnection = { onBeginConnection(selected.id) },
                onCancelConnection = onCancelConnection,
                onRemove = onRemoveNode,
                onConnectNodes = onConnectNodes,
                onRemoveEdge = onRemoveEdge,
            )

            NodeConnectionsCard(
                state = state,
                node = selected,
                onConnectNodes = onConnectNodes,
                onDisconnectAll = onDisconnectAllForNode,
                onUpdateEdge = onUpdateEdge,
                onRemoveEdge = onRemoveEdge,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun WorkflowMetadataCard(state: WorkflowEditorUiState, onApply: (String, String, String) -> Unit) {
    var name by remember(state.definition.id, state.definition.name) { mutableStateOf(state.definition.name) }
    var description by remember(state.definition.id, state.definition.description) { mutableStateOf(state.definition.description) }
    var category by remember(state.definition.id, state.definition.category) { mutableStateOf(state.definition.category) }
    var expanded by remember { mutableStateOf(false) }

    RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("工作流信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    text = if (expanded) "收起" else "修改",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                OutlinedTextField(name, { name = it }, label = { Text("工作流名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(category, { category = it }, label = { Text("分类") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("说明") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
                RuntimeOutlinedButton(onClick = { onApply(name, description, category) }, modifier = Modifier.align(Alignment.End)) { Text("应用信息") }
            } else {
                Text(
                    text = "${name} · [${category}] - ${description.ifBlank { "暂无说明" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NodeInspectorCard(
    state: WorkflowEditorUiState,
    node: WorkflowNode,
    isConnecting: Boolean,
    onApply: (WorkflowNode) -> Unit,
    onBeginConnection: () -> Unit,
    onCancelConnection: () -> Unit,
    onRemove: () -> Unit,
    onConnectNodes: (String, String, String, String?) -> Unit,
    onRemoveEdge: (String) -> Unit,
) {
    val meta = node.type.metadata()
    var title by remember(node.id, node.title) { mutableStateOf(node.title) }
    var description by remember(node.id, node.description) { mutableStateOf(node.description) }
    var timeout by remember(node.id, node.timeoutSeconds) { mutableStateOf(node.timeoutSeconds.toString()) }
    var policy by remember(node.id, node.failurePolicy) { mutableStateOf(node.failurePolicy) }
    var policyExpanded by remember { mutableStateOf(false) }

    val configMap = remember(node.id, node.config) {
        mutableStateMapOf<String, String>().apply { putAll(node.config) }
    }
    var showAdvancedJson by remember { mutableStateOf(false) }
    var rawJsonText by remember(node.id, node.config) {
        mutableStateOf(Json { prettyPrint = true }.encodeToString(node.config))
    }
    val parsedConfig = remember(rawJsonText) {
        runCatching { Json.decodeFromString<Map<String, String>>(rawJsonText) }.getOrNull()
    }

    RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(50),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RuntimeIcon(meta.icon, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(meta.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = node.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RuntimeOutlinedButton(
                    onClick = onRemove,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    RuntimeIcon(RuntimeIconName.Trash, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                    Text("删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "💡 节点作用：${meta.summary}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = meta.guide,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("节点标题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("节点备注 / 说明") },
                minLines = 1,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("核心参数配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

            when (node.type) {
                WorkflowNodeType.BASH_COMMAND -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(16.dp), tint = Color(0xFF06B6D4))
                                Text("终端 Shell 命令行（在沙箱环境中执行）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF06B6D4))
                            }
                            OutlinedTextField(
                                value = configMap["command"].orEmpty(),
                                onValueChange = { configMap["command"] = it },
                                label = { Text("Bash 命令（在此输入执行的命令）") },
                                placeholder = { Text("例如：git status --short && git diff") },
                                supportingText = { Text("支持引用上游输出：\${previous.output}、工作区：\${WORKSPACE_PATH}") },
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("常用命令预设（点击直接填入）：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    "系统内核" to "echo '=== 系统内核 ===' && uname -a && cat /etc/os-release | head -n 8",
                                    "内存磁盘" to "echo '=== 资源使用 ===' && free -h && df -h /",
                                    "Git 状态" to "git status --short && git diff --stat",
                                    "测试网络" to "curl -I -s -m 5 https://www.baidu.com | head -n 4",
                                    "工具排查" to "for cmd in git python3 curl make; do which \$cmd && echo \"✔ \$cmd\" || echo \"✘ \$cmd\"; done",
                                ).forEach { (name, cmd) ->
                                    Surface(
                                        onClick = { configMap["command"] = cmd },
                                        shape = RoundedCornerShape(50),
                                        color = Color(0xFF06B6D4).copy(alpha = 0.14f),
                                    ) {
                                        Text(
                                            text = "+ $name",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF06B6D4),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = configMap["workingDirectory"].orEmpty(),
                                onValueChange = { configMap["workingDirectory"] = it },
                                label = { Text("工作目录（可选）") },
                                placeholder = { Text("留空默认为当前工程根目录") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                WorkflowNodeType.CONDITION_BRANCH -> {
                    OutlinedTextField(
                        value = configMap["expression"] ?: configMap["condition"].orEmpty(),
                        onValueChange = {
                            configMap["expression"] = it
                            configMap.remove("condition")
                        },
                        label = { Text("条件表达式") },
                        placeholder = { Text("exitCode == 0  或  \${HOST_PRIVILEGED} == true") },
                        supportingText = {
                            Text("支持 exitCode / output contains / \${VAR} 比较，以及 && ||。成立→exit 0，否则 exit 1。")
                        },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    BranchRouterCard(
                        state = state,
                        node = node,
                        onConnectNodes = onConnectNodes,
                        onRemoveEdge = onRemoveEdge,
                    )
                }

                WorkflowNodeType.PROCESS_SERVICE -> {
                    OutlinedTextField(
                        value = configMap["command"].orEmpty(),
                        onValueChange = { configMap["command"] = it },
                        label = { Text("后台服务启动命令（必填）") },
                        placeholder = { Text("例如：python3 -m http.server 8080") },
                        supportingText = { Text("将在沙箱后台常驻运行，进程 ID 保存于 \${PROCESS_ID}") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = configMap["workingDirectory"].orEmpty(),
                        onValueChange = { configMap["workingDirectory"] = it },
                        label = { Text("工作目录（可选）") },
                        placeholder = { Text("留空默认为工程根目录") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                WorkflowNodeType.AGENT_INFERENCE -> {
                    OutlinedTextField(
                        value = configMap["prompt"].orEmpty(),
                        onValueChange = { configMap["prompt"] = it },
                        label = { Text("任务需求 / 提示词（必填）") },
                        placeholder = { Text("例如：审阅上一节点的差异并提出优化建议") },
                        supportingText = { Text("支持使用 \${previous.output} 引用上游节点输出") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = configMap["role"].orEmpty(),
                        onValueChange = { configMap["role"] = it },
                        label = { Text("智能体角色（可选）") },
                        placeholder = { Text("例如：Android 资深架构师") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = configMap["modelId"].orEmpty(),
                        onValueChange = { configMap["modelId"] = it },
                        label = { Text("固定模型配置 ID（可选）") },
                        placeholder = { Text("留空则使用运行时选择的 WORKFLOW_MODEL_ID") },
                        supportingText = { Text("优先于运行对话框所选模型；一般留空即可") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                WorkflowNodeType.SUBAGENT_DELEGATE -> {
                    OutlinedTextField(
                        value = configMap["prompt"].orEmpty(),
                        onValueChange = { configMap["prompt"] = it },
                        label = { Text("子任务需求（必填）") },
                        placeholder = { Text("例如：执行单元测试并尝试修复失败用例") },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = configMap["role"].orEmpty(),
                        onValueChange = { configMap["role"] = it },
                        label = { Text("子智能体角色（可选）") },
                        placeholder = { Text("例如：测试修复助手") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                WorkflowNodeType.TAIXU_BUILD -> {
                    var modeExpanded by remember { mutableStateOf(false) }
                    var typeExpanded by remember { mutableStateOf(false) }
                    val currentMode = configMap["mode"] ?: "build"
                    val currentType = configMap["projectType"] ?: "android"

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            RuntimeOutlinedButton(onClick = { modeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("模式：$currentMode", maxLines = 1)
                            }
                            DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                                listOf("build" to "标准构建", "doctor" to "环境体检", "analyze" to "静态分析").forEach { (m, l) ->
                                    DropdownMenuItem(text = { Text("$l ($m)") }, onClick = { configMap["mode"] = m; modeExpanded = false })
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            RuntimeOutlinedButton(onClick = { typeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("类型：$currentType", maxLines = 1)
                            }
                            DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                                listOf("android", "cmake", "cargo", "gradle").forEach { t ->
                                    DropdownMenuItem(text = { Text(t) }, onClick = { configMap["projectType"] = t; typeExpanded = false })
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = configMap["task"] ?: "assembleDebug",
                        onValueChange = { configMap["task"] = it },
                        label = { Text("构建 Task 任务") },
                        placeholder = { Text("例如：assembleDebug 或 build") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                WorkflowNodeType.HOST_ACTION -> {
                    HostActionConfigFields(configMap = configMap)
                }

                WorkflowNodeType.DELAY -> {
                    OutlinedTextField(
                        value = configMap["seconds"] ?: "1",
                        onValueChange = { configMap["seconds"] = it.filter { ch -> ch.isDigit() || ch == '.' }.take(6) },
                        label = { Text("等待秒数") },
                        supportingText = { Text("支持 0–600 秒，可写小数") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                WorkflowNodeType.SET_VARIABLE -> {
                    OutlinedTextField(
                        value = configMap["variables"].orEmpty(),
                        onValueChange = { configMap["variables"] = it },
                        label = { Text("变量赋值（每行 KEY=value）") },
                        placeholder = { Text("FOO=bar\nPATH=\${WORKSPACE_PATH}") },
                        supportingText = { Text("支持 \${VAR} / \${previous.output} / \${nodeId.output}") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                WorkflowNodeType.HUMAN_APPROVAL -> {
                    OutlinedTextField(
                        value = configMap["prompt"].orEmpty(),
                        onValueChange = { configMap["prompt"] = it },
                        label = { Text("审批确认说明（必填）") },
                        placeholder = { Text("例如：是否确认将修复推送到远程仓库？") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                WorkflowNodeType.TRIGGER -> {
                    Text(
                        text = "工作流启动入口。在顶部点击“运行调试”即可从此处触发整个 DAG 流程。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                WorkflowNodeType.TERMINAL_OUTPUT -> {
                    Text(
                        text = "工作流终点节点。将自动收集并归档上游所有步骤的标准输出、错误日志与产物文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvancedJson = !showAdvancedJson }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (showAdvancedJson) "收起高级配置 (JSON)" else "展开高级配置 (JSON)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                RuntimeIcon(
                    name = if (showAdvancedJson) RuntimeIconName.ChevronUp else RuntimeIconName.ChevronDown,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (showAdvancedJson) {
                OutlinedTextField(
                    value = rawJsonText,
                    onValueChange = {
                        rawJsonText = it
                        parsedConfig?.let { parsed ->
                            configMap.clear()
                            configMap.putAll(parsed)
                        }
                    },
                    label = { Text("原始 JSON 配置") },
                    isError = parsedConfig == null,
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = timeout,
                    onValueChange = { timeout = it.filter(Char::isDigit).take(4) },
                    label = { Text("超时（秒）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Column(Modifier.weight(1.2f)) {
                    RuntimeOutlinedButton(onClick = { policyExpanded = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("策略：${policy.editorLabel()}", maxLines = 1)
                    }
                    DropdownMenu(expanded = policyExpanded, onDismissRequest = { policyExpanded = false }) {
                        FailurePolicy.entries.forEach { item ->
                            DropdownMenuItem(text = { Text(item.editorLabel()) }, onClick = { policy = item; policyExpanded = false })
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeOutlinedButton(
                    onClick = if (isConnecting) onCancelConnection else onBeginConnection,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isConnecting) "取消连线" else "在画布连线…", maxLines = 1)
                }
                RuntimeButton(
                    onClick = {
                        val finalConfig = if (showAdvancedJson && parsedConfig != null) parsedConfig else configMap.toMap()
                        onApply(
                            node.copy(
                                title = title.trim().ifBlank { node.title },
                                description = description.trim(),
                                timeoutSeconds = timeout.toIntOrNull()?.coerceIn(1, 7200) ?: node.timeoutSeconds,
                                config = finalConfig,
                                failurePolicy = policy,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("应用参数更改", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun HostActionConfigFields(configMap: MutableMap<String, String>) {
    var categoryExpanded by remember { mutableStateOf(false) }
    var actionExpanded by remember { mutableStateOf(false) }
    val grouped = remember { HostWorkflowActions.grouped() }
    val currentActionId = configMap["action"] ?: "status"
    val currentDef = HostWorkflowActions.find(currentActionId)
    val currentCategory = currentDef?.category ?: grouped.keys.firstOrNull().orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                RuntimeOutlinedButton(onClick = { categoryExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("分类：$currentCategory", maxLines = 1)
                }
                DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                    grouped.keys.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category) },
                            onClick = {
                                categoryExpanded = false
                                val first = grouped[category]?.firstOrNull() ?: return@DropdownMenuItem
                                configMap["action"] = first.id
                            },
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1.4f)) {
                RuntimeOutlinedButton(onClick = { actionExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(currentDef?.label ?: currentActionId, maxLines = 1)
                }
                DropdownMenu(expanded = actionExpanded, onDismissRequest = { actionExpanded = false }) {
                    grouped[currentCategory].orEmpty().forEach { action ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    buildString {
                                        append(action.label)
                                        if (action.privilege == HostWorkflowPrivilege.PRIVILEGED) append(" · 需特权")
                                    },
                                )
                            },
                            onClick = {
                                configMap["action"] = action.id
                                actionExpanded = false
                            },
                        )
                    }
                }
            }
        }

        currentDef?.let { def ->
            Text(
                text = def.description + if (def.privilege == HostWorkflowPrivilege.PRIVILEGED) {
                    "\n⚠ 需要 Shizuku 或 Root 生效。"
                } else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            def.fields.forEach { field ->
                OutlinedTextField(
                    value = configMap[field.key].orEmpty(),
                    onValueChange = { configMap[field.key] = it },
                    label = { Text(if (field.required) "${field.label} *" else field.label) },
                    placeholder = { Text(field.hint) },
                    singleLine = !field.multiline,
                    minLines = if (field.multiline) 3 else 1,
                    maxLines = if (field.multiline) 6 else 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } ?: OutlinedTextField(
            value = configMap["command"].orEmpty(),
            onValueChange = { configMap["command"] = it },
            label = { Text("自定义宿主命令（逃逸舱）") },
            supportingText = { Text("未知 action 时将按特权 shell 执行此命令") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BranchRouterCard(
    state: WorkflowEditorUiState,
    node: WorkflowNode,
    onConnectNodes: (String, String, String, String?) -> Unit,
    onRemoveEdge: (String) -> Unit,
) {
    val branchEdges = state.definition.edges.filter { it.fromNodeId == node.id }
    val otherNodes = state.definition.nodes.filter { it.id != node.id }

    val successEdge = branchEdges.firstOrNull { it.conditionExpression?.contains("exitCode == 0") == true || it.fromPort == "success" }
    val failureEdge = branchEdges.firstOrNull { it.conditionExpression?.contains("exitCode !=") == true || it.fromPort == "failure" }

    var successTargetId by remember(otherNodes) { mutableStateOf(otherNodes.firstOrNull()?.id.orEmpty()) }
    var failureTargetId by remember(otherNodes) { mutableStateOf(otherNodes.getOrNull(1)?.id ?: otherNodes.firstOrNull()?.id.orEmpty()) }
    var successExpanded by remember { mutableStateOf(false) }
    var failureExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFFEAB308).copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RuntimeIcon(RuntimeIconName.Link, Modifier.size(16.dp), tint = Color(0xFFEAB308))
                Text("可视化条件分支路由器", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFFEAB308))
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "💡 节点会求值上方表达式：成立 exitCode=0，不成立 exitCode=1（节点本身仍成功，便于分流）。\n" +
                        "下面可快速把成功/失败分支连到目标节点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(shape = RoundedCornerShape(50), color = Color(0xFF10B981).copy(alpha = 0.18f)) {
                    Text("✔ 成功分支 (exitCode == 0)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                if (successEdge != null) {
                    val target = state.definition.nodes.firstOrNull { it.id == successEdge.toNodeId }
                    Row(
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("已连接至: ${target?.title ?: successEdge.toNodeId}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        RuntimeOutlinedButton(
                            onClick = { onRemoveEdge(successEdge.id) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("断开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else if (otherNodes.isNotEmpty()) {
                    val currentSuccessNode = otherNodes.firstOrNull { it.id == successTargetId } ?: otherNodes.first()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.weight(1f)) {
                            RuntimeOutlinedButton(onClick = { successExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("目标: ${currentSuccessNode.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = successExpanded, onDismissRequest = { successExpanded = false }) {
                                otherNodes.forEach { target ->
                                    DropdownMenuItem(
                                        text = { Text(target.title) },
                                        onClick = { successTargetId = target.id; successExpanded = false },
                                    )
                                }
                            }
                        }
                        RuntimeButton(
                            onClick = { onConnectNodes(node.id, currentSuccessNode.id, "output", "exitCode == 0") },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("连接", maxLines = 1)
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(shape = RoundedCornerShape(50), color = Color(0xFFF43F5E).copy(alpha = 0.18f)) {
                    Text("✘ 失败分支 (exitCode != 0)", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF43F5E), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                if (failureEdge != null) {
                    val target = state.definition.nodes.firstOrNull { it.id == failureEdge.toNodeId }
                    Row(
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("已连接至: ${target?.title ?: failureEdge.toNodeId}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        RuntimeOutlinedButton(
                            onClick = { onRemoveEdge(failureEdge.id) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("断开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else if (otherNodes.isNotEmpty()) {
                    val currentFailureNode = otherNodes.firstOrNull { it.id == failureTargetId } ?: otherNodes.first()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.weight(1f)) {
                            RuntimeOutlinedButton(onClick = { failureExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("目标: ${currentFailureNode.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = failureExpanded, onDismissRequest = { failureExpanded = false }) {
                                otherNodes.forEach { target ->
                                    DropdownMenuItem(
                                        text = { Text(target.title) },
                                        onClick = { failureTargetId = target.id; failureExpanded = false },
                                    )
                                }
                            }
                        }
                        RuntimeButton(
                            onClick = { onConnectNodes(node.id, currentFailureNode.id, "output", "exitCode != 0") },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("连接", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeConnectionsCard(
    state: WorkflowEditorUiState,
    node: WorkflowNode,
    onConnectNodes: (String, String, String, String?) -> Unit,
    onDisconnectAll: (String) -> Unit,
    onUpdateEdge: (WorkflowEdge) -> Unit,
    onRemoveEdge: (String) -> Unit,
) {
    val outgoingEdges = state.definition.edges.filter { it.fromNodeId == node.id }
    val incomingEdges = state.definition.edges.filter { it.toNodeId == node.id }
    val allConnected = outgoingEdges + incomingEdges

    val otherNodes = remember(state.definition.nodes, node.id) {
        state.definition.nodes.filter { it.id != node.id }
    }

    var selectedTargetId by remember(otherNodes) { mutableStateOf(otherNodes.firstOrNull()?.id.orEmpty()) }
    var selectedPort by remember { mutableStateOf("output") }
    var conditionText by remember { mutableStateOf("") }
    var targetExpanded by remember { mutableStateOf(false) }
    var portExpanded by remember { mutableStateOf(false) }

    RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RuntimeIcon(RuntimeIconName.Link, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("节点连线与拓扑 (${allConnected.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (allConnected.isNotEmpty()) {
                    Surface(
                        onClick = { onDisconnectAll(node.id) },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = "一键断开全部",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Text("传出连线（连向后续节点）：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (outgoingEdges.isEmpty()) {
                Text("暂无传出连线", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                outgoingEdges.forEach { edge ->
                    val targetNode = state.definition.nodes.firstOrNull { it.id == edge.toNodeId }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(50),
                                    ) {
                                        Text(
                                            text = edge.fromPort.portLabel(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    Text("──►", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = targetNode?.title ?: edge.toNodeId,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (!edge.conditionExpression.isNullOrBlank()) {
                                    Text(
                                        text = "条件: ${edge.conditionExpression}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.tertiary,
                                        maxLines = 1,
                                    )
                                }
                            }
                            RuntimeOutlinedButton(
                                onClick = { onRemoveEdge(edge.id) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                RuntimeIcon(RuntimeIconName.Close, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                                Text("断开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            Text("传入连线（来自前序节点）：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (incomingEdges.isEmpty()) {
                Text("暂无传入连线", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                incomingEdges.forEach { edge ->
                    val sourceNode = state.definition.nodes.firstOrNull { it.id == edge.fromNodeId }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = sourceNode?.title ?: edge.fromNodeId,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text("──►", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("本节点", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                if (!edge.conditionExpression.isNullOrBlank()) {
                                    Text(
                                        text = "条件: ${edge.conditionExpression}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.tertiary,
                                        maxLines = 1,
                                    )
                                }
                            }
                            RuntimeOutlinedButton(
                                onClick = { onRemoveEdge(edge.id) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                RuntimeIcon(RuntimeIconName.Close, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                                Text("断开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Text("➕ 添加连线到其他节点：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (otherNodes.isEmpty()) {
                Text("画布中尚无其他可用节点", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val currentTargetNode = otherNodes.firstOrNull { it.id == selectedTargetId } ?: otherNodes.first()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        RuntimeOutlinedButton(
                            onClick = { targetExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("目标: ${currentTargetNode.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = targetExpanded, onDismissRequest = { targetExpanded = false }) {
                            otherNodes.forEach { target ->
                                DropdownMenuItem(
                                    text = { Text("${target.title} (${target.type.editorLabel()})") },
                                    onClick = {
                                        selectedTargetId = target.id
                                        targetExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            RuntimeOutlinedButton(
                                onClick = { portExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("端口: ${selectedPort.portLabel()}", maxLines = 1)
                            }
                            DropdownMenu(expanded = portExpanded, onDismissRequest = { portExpanded = false }) {
                                EdgePorts.forEach { port ->
                                    DropdownMenuItem(
                                        text = { Text(port.portLabel()) },
                                        onClick = {
                                            selectedPort = port
                                            portExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = conditionText,
                            onValueChange = { conditionText = it },
                            label = { Text("条件（可选）") },
                            placeholder = { Text("如 exitCode == 0") },
                            singleLine = true,
                            modifier = Modifier.weight(1.5f),
                        )
                    }

                    RuntimeButton(
                        onClick = {
                            onConnectNodes(node.id, currentTargetNode.id, selectedPort, conditionText.trim().ifBlank { null })
                            conditionText = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RuntimeIcon(RuntimeIconName.Plus, Modifier.size(16.dp))
                        Text("立即建立连线", maxLines = 1)
                    }
                }
            }
        }
    }
}

data class NodeTypeMeta(
    val type: WorkflowNodeType,
    val label: String,
    val icon: RuntimeIconName,
    val summary: String,
    val guide: String,
)

fun WorkflowNodeType.metadata(): NodeTypeMeta = when (this) {
    WorkflowNodeType.TRIGGER -> NodeTypeMeta(
        type = this,
        label = "触发器",
        icon = RuntimeIconName.Play,
        summary = "流程启动入口，支持手动点击或外部事件触发",
        guide = "作为 DAG 图的起始节点。当您在顶部点击“运行调试”或接收到触发信号时，工作流从这里开始向下执行。",
    )
    WorkflowNodeType.BASH_COMMAND -> NodeTypeMeta(
        type = this,
        label = "Shell 命令行",
        icon = RuntimeIconName.Terminal,
        summary = "在 Linux PRoot 沙箱中执行一条 Shell 命令或脚本",
        guide = "可执行任何沙箱内已安装的命令（如 git, make, python, curl 等）。支持通过 \${WORKSPACE_PATH} 引用工程根目录，或使用 \${previous.output} 获取上游输出。",
    )
    WorkflowNodeType.PROCESS_SERVICE -> NodeTypeMeta(
        type = this,
        label = "后台常驻服务",
        icon = RuntimeIconName.Settings,
        summary = "启动沙箱后台常驻守护进程，不阻塞后续流程继续执行",
        guide = "适用于启动 http-server、Web 服务或监听器进程。进程 ID 会自动保存在变量 \${PROCESS_ID} 中供后续流程引用。",
    )
    WorkflowNodeType.AGENT_INFERENCE -> NodeTypeMeta(
        type = this,
        label = "AI 智能体推理",
        icon = RuntimeIconName.Sparkles,
        summary = "调度 AI 智能体结合任务指令与上游输出分析并生成结果",
        guide = "将任务指令（Prompt）与上游产出交给 AI 智能体处理。支持结合 \${previous.output} 进行代码审查、故障排查或生成修复补丁。",
    )
    WorkflowNodeType.SUBAGENT_DELEGATE -> NodeTypeMeta(
        type = this,
        label = "子智能体委派",
        icon = RuntimeIconName.Hub,
        summary = "委派独立专属子智能体执行更细粒度的任务",
        guide = "委派特定角色的子智能体（如单元测试生成器、构建错误分析员）处理专属任务，隔离主智能体上下文。",
    )
    WorkflowNodeType.TAIXU_BUILD -> NodeTypeMeta(
        type = this,
        label = "太墟离线构建",
        icon = RuntimeIconName.Package,
        summary = "调用沙箱内离线构建引擎编译打包 Android/C++/Rust",
        guide = "调用沙箱内置的 taixu-build 编译引擎。支持 android、cmake、cargo 等类型，可直接产出 APK 安装包或可执行二进制文件。",
    )
    WorkflowNodeType.CONDITION_BRANCH -> NodeTypeMeta(
        type = this,
        label = "条件分支路由",
        icon = RuntimeIconName.Link,
        summary = "求值表达式并按成立/不成立分流",
        guide = "支持 exitCode、output contains、\${变量} 比较，以及 && / ||。成立时 exitCode=0，否则为 1；请用边上的条件或 success/failure 端口承接分支。",
    )
    WorkflowNodeType.HUMAN_APPROVAL -> NodeTypeMeta(
        type = this,
        label = "人工审批把关",
        icon = RuntimeIconName.Alert,
        summary = "暂停流程并弹出确认对话框，等待人工确认或输入参数后继续",
        guide = "重要步骤（如发布、破坏性清理或上线）前的安全把关点。流程执行至此时将自动暂停并弹出弹窗，等待您手动确认或填写必要参数。",
    )
    WorkflowNodeType.HOST_ACTION -> NodeTypeMeta(
        type = this,
        label = "宿主系统动作",
        icon = RuntimeIconName.Android,
        summary = "打开应用、发广播、改设置、操控屏幕等 Android 宿主能力",
        guide = "穿透沙箱调用 Android：应用管理、Intent/广播、系统设置、GUI 自动化、通知与特权 shell。标「需特权」的动作要求 Shizuku 或 Root。",
    )
    WorkflowNodeType.DELAY -> NodeTypeMeta(
        type = this,
        label = "延时等待",
        icon = RuntimeIconName.Speed,
        summary = "暂停若干秒后再继续后续节点",
        guide = "用于等待界面切换、广播落地或给人工留出观察时间。",
    )
    WorkflowNodeType.SET_VARIABLE -> NodeTypeMeta(
        type = this,
        label = "设置变量",
        icon = RuntimeIconName.Tune,
        summary = "向后续节点注入 KEY=value 变量",
        guide = "每行一个赋值，右侧支持 \${VAR} 插值。写入的变量会进入全局上下文，可供条件与宿主动作引用。",
    )
    WorkflowNodeType.TERMINAL_OUTPUT -> NodeTypeMeta(
        type = this,
        label = "结果归档输出",
        icon = RuntimeIconName.Check,
        summary = "工作流终点，自动汇总所有输出日志、耗时与产出文件",
        guide = "DAG 流程的最终收尾节点。将自动收集并展示上游所有阶段的终端标准输出、退出码与生成的产物文件列表。",
    )
}

private fun WorkflowNodeType.editorLabel() = metadata().label

private fun FailurePolicy.editorLabel() = when (this) {
    FailurePolicy.ABORT -> "终止流程"
    FailurePolicy.CONTINUE -> "继续执行"
    FailurePolicy.RETRY_ONCE -> "重试一次"
    FailurePolicy.ASK_USER -> "询问用户"
}

private fun String.portLabel() = when (lowercase()) {
    "success" -> "成功 (success)"
    "failure" -> "失败 (failure)"
    else -> "任意输出 (output)"
}

private val EdgePorts = listOf("output", "success", "failure")
