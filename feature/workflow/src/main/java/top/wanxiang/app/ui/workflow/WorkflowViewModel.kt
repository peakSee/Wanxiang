package top.wanxiang.app.ui.workflow

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import top.wanxiang.app.core.database.AiModelRepository
import top.wanxiang.app.core.database.WorkflowRepository
import top.wanxiang.app.core.model.workflow.BuiltinWorkflows
import top.wanxiang.app.core.model.workflow.FailurePolicy
import top.wanxiang.app.core.model.workflow.NodeRunStatus
import top.wanxiang.app.core.model.workflow.WorkflowApprovalRequest
import top.wanxiang.app.core.model.workflow.WorkflowDefinition
import top.wanxiang.app.core.model.workflow.WorkflowEdge
import top.wanxiang.app.core.model.workflow.WorkflowNode
import top.wanxiang.app.core.model.workflow.WorkflowNodeType
import top.wanxiang.app.core.model.workflow.WorkflowRunStatus
import top.wanxiang.app.core.model.workflow.WorkflowRuntimeState
import top.wanxiang.app.core.model.workflow.WorkflowTrigger
import top.wanxiang.app.harness.workflow.WorkflowRunHandle
import top.wanxiang.app.harness.workflow.WorkflowScheduler
import top.wanxiang.app.runtime.RuntimePathManager
import top.wanxiang.app.runtime.gui.WorkflowGuiHudBridge
import top.wanxiang.app.ui.workflow.hud.WorkflowHudService
import java.io.File

data class DiscoveredApk(
    val file: File,
    val name: String,
    val relativePath: String,
    val sandboxPath: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

@HiltViewModel
class WorkflowViewModel @Inject constructor(
    private val repository: WorkflowRepository,
    private val scheduler: WorkflowScheduler,
    private val hud: WorkflowGuiHudBridge,
    @ApplicationContext private val appContext: Context,
    aiModelRepository: AiModelRepository,
    private val pathManager: RuntimePathManager,
) : ViewModel() {
    val definitions: StateFlow<List<WorkflowDefinition>> = repository.observeDefinitions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BuiltinWorkflows.all)
    val history = repository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val models = aiModelRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    fun clearError() { _error.value = null }

    private val _activeState = MutableStateFlow<WorkflowRuntimeState?>(null)
    val activeState = _activeState.asStateFlow()
    private val _approvalRequest = MutableStateFlow<WorkflowApprovalRequest?>(null)
    val approvalRequest = _approvalRequest.asStateFlow()
    private val _editorState = MutableStateFlow<WorkflowEditorUiState?>(null)
    val editorState = _editorState.asStateFlow()

    private var activeHandle: WorkflowRunHandle? = null
    private var observerJob: Job? = null
    private var editHistory: WorkflowEditHistory? = null

    init {
        viewModelScope.launch { repository.ensureBuiltins() }
    }

    /**
     * 自动扫描工程目录或工作区下的 APK 构建产物，按最后修改时间倒序排列（最新在前）。
     */
    fun scanWorkspaceApks(projectName: String): List<DiscoveredApk> =
        scanApksInWorkspace(pathManager.workspaceDir, projectName)

    fun start(definition: WorkflowDefinition, projectName: String, variables: Map<String, String> = emptyMap()) {
        activeHandle?.cancel()
        observerJob?.cancel()
        val safeProject = projectName.trim().trim('/').takeIf { it.isNotEmpty() }
        val workspace = variables["TARGET_WORKSPACE"]?.takeIf { it.startsWith('/') }
            ?: safeProject?.let { "/workspace/$it" } ?: "/workspace"
        val handle = scheduler.execute(definition, variables, workspace, viewModelScope)
        activeHandle = handle
        val executionId = handle.state.value.executionId
        hud.start(executionId, definition.name)
        hud.bindCancel(executionId) { handle.cancel() }
        if (Settings.canDrawOverlays(appContext)) {
            WorkflowHudService.start(appContext)
        } else {
            _error.value = "未授予悬浮窗权限，工作流进度仅在应用内显示。可在系统设置中开启「显示在其他应用上层」。"
        }
        // Persistence must outlive the UI observer when the user closes a run.
        viewModelScope.launch {
            var completed: WorkflowRuntimeState? = null
            try {
                completed = handle.state.first { it.status in TERMINAL }
            } finally {
                withContext(NonCancellable) {
                    val snapshot = completed ?: handle.state.value.let {
                        if (it.status in TERMINAL) it else it.copy(
                            status = WorkflowRunStatus.CANCELLED,
                            finishedAt = System.currentTimeMillis(),
                            error = "执行页面已关闭，工作流已取消",
                        )
                    }
                    runCatching { repository.saveExecution(snapshot) }.onFailure {
                        _error.value = "运行历史保存失败：${it.message ?: "存储不可用"}"
                    }
                }
            }
        }
        observerJob = viewModelScope.launch {
            launch { handle.approvalRequest.collect { _approvalRequest.value = it?.takeIf { request -> request.executionId == handle.state.value.executionId } } }
            handle.state.collect { state ->
                _activeState.value = state
                publishHud(definition, state)
            }
        }
    }

    private fun publishHud(definition: WorkflowDefinition, state: WorkflowRuntimeState) {
        when (state.status) {
            WorkflowRunStatus.SUCCESS -> hud.finish(true, "全部节点完成")
            WorkflowRunStatus.FAILED -> hud.finish(false, state.error ?: "工作流失败")
            WorkflowRunStatus.CANCELLED -> {
                if (!hud.isStopRequested(state.executionId)) {
                    hud.cancelled(state.error ?: "已取消")
                }
            }
            else -> {
                val active = state.nodeStates.entries.firstOrNull { (_, node) ->
                    node.status in HUD_ACTIVE_NODE
                }
                val title = active?.let { (id, _) ->
                    definition.nodes.firstOrNull { it.id == id }?.title ?: id
                }.orEmpty()
                val message = active?.value?.progressMessage?.takeIf { it.isNotBlank() } ?: "运行中"
                hud.updateNode(title, message)
            }
        }
    }

    fun decide(nodeId: String, approved: Boolean, variables: Map<String, String> = emptyMap()) {
        activeHandle?.decide(nodeId, approved, variables)
    }

    fun cancel() = activeHandle?.cancel()
    fun showHistory(state: WorkflowRuntimeState) {
        closeRun()
        _activeState.value = state
    }
    fun closeRun() {
        if (_activeState.value?.status !in TERMINAL) activeHandle?.cancel()
        observerJob?.cancel()
        activeHandle = null
        _activeState.value = null
        _approvalRequest.value = null
        // Keep HUD until user dismisses or auto-timeout; only stop service if no session
        if (hud.session.value == null) {
            WorkflowHudService.stop(appContext)
        }
    }

    fun createWorkflow() {
        val suffix = UUID.randomUUID().toString().take(8)
        openEditor(
            WorkflowDefinition(
                id = "workflow_$suffix",
                name = "新工作流",
                description = "在画布中添加并连接节点",
                category = "自定义",
                trigger = WorkflowTrigger.Manual(),
                nodes = listOf(
                    WorkflowNode("start", WorkflowNodeType.TRIGGER, "手动触发", canvasX = 80f, canvasY = 160f),
                    WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "完成", canvasX = 420f, canvasY = 160f),
                ),
                edges = listOf(WorkflowEdge("edge_start_done", "start", "output", "done")),
            ),
            initiallySaved = false,
        )
    }

    fun edit(definition: WorkflowDefinition) {
        if (definition.isBuiltin) {
            val suffix = UUID.randomUUID().toString().take(8)
            openEditor(
                definition.copy(
                    id = "workflow_$suffix",
                    name = "${definition.name} 副本",
                    isBuiltin = false,
                    trigger = WorkflowTrigger.Manual(),
                    createdAt = 0L,
                    updatedAt = 0L,
                ),
                initiallySaved = false,
            )
        } else {
            openEditor(definition, initiallySaved = true)
        }
    }

    private fun openEditor(definition: WorkflowDefinition, initiallySaved: Boolean) {
        val history = WorkflowEditHistory(definition, initiallySaved)
        editHistory = history
        publishEditor(history, selectedNodeId = definition.nodes.firstOrNull()?.id)
    }

    fun closeEditor() {
        editHistory = null
        _editorState.value = null
    }

    fun selectNode(nodeId: String?) {
        _editorState.value = _editorState.value?.copy(selectedNodeId = nodeId, message = null)
    }

    fun beginConnection(nodeId: String) {
        _editorState.value = _editorState.value?.copy(connectionSourceId = nodeId, selectedNodeId = nodeId, message = "请选择目标节点")
    }

    fun cancelConnection() {
        _editorState.value = _editorState.value?.copy(connectionSourceId = null, message = null)
    }

    fun connectTo(targetNodeId: String) {
        val source = _editorState.value?.connectionSourceId ?: return
        mutate {
            WorkflowGraphEditor.connect(
                it,
                WorkflowEdge("edge_${UUID.randomUUID().toString().take(8)}", source, "output", targetNodeId),
            )
        }
        _editorState.value = _editorState.value?.copy(connectionSourceId = null)
    }

    fun addNode(type: WorkflowNodeType) {
        val state = _editorState.value ?: return
        val suffix = UUID.randomUUID().toString().take(8)
        val (nextX, nextY) = WorkflowGraphEditor.calculateNextNodePosition(
            existingNodes = state.definition.nodes,
            selectedNodeId = state.selectedNodeId,
        )
        val node = WorkflowNode(
            id = "node_$suffix",
            type = type,
            title = type.defaultTitle(),
            canvasX = nextX,
            canvasY = nextY,
            config = type.defaultConfig(),
            failurePolicy = if (type == WorkflowNodeType.CONDITION_BRANCH) FailurePolicy.CONTINUE else FailurePolicy.ABORT,
        )
        mutate(selectedNodeId = node.id) { WorkflowGraphEditor.addNode(it, node) }
    }

    fun updateMetadata(name: String, description: String, category: String) = mutate {
        it.copy(name = name, description = description, category = category, updatedAt = System.currentTimeMillis())
    }

    fun updateNode(node: WorkflowNode) = mutate(selectedNodeId = node.id) { WorkflowGraphEditor.updateNode(it, node) }

    fun autoLayout() = mutate { top.wanxiang.app.core.model.workflow.WorkflowLayout.arrange(it) }

    fun moveNode(nodeId: String, x: Float, y: Float) = mutate(selectedNodeId = nodeId) { definition ->
        val node = definition.nodes.firstOrNull { it.id == nodeId } ?: return@mutate definition
        WorkflowGraphEditor.updateNode(definition, node.copy(canvasX = x.coerceAtLeast(0f), canvasY = y.coerceAtLeast(0f)))
    }

    fun removeSelectedNode() {
        val nodeId = _editorState.value?.selectedNodeId ?: return
        mutate(selectedNodeId = null) { WorkflowGraphEditor.removeNode(it, nodeId) }
    }

    fun removeNode(nodeId: String) {
        mutate(selectedNodeId = if (_editorState.value?.selectedNodeId == nodeId) null else _editorState.value?.selectedNodeId) {
            WorkflowGraphEditor.removeNode(it, nodeId)
        }
    }

    fun connectNodes(fromNodeId: String, toNodeId: String, port: String = "output", condition: String? = null) {
        val edge = WorkflowEdge(
            id = "edge_${UUID.randomUUID().toString().take(8)}",
            fromNodeId = fromNodeId,
            fromPort = port,
            toNodeId = toNodeId,
            conditionExpression = condition?.trim()?.ifBlank { null },
        )
        mutate { WorkflowGraphEditor.connect(it, edge) }
    }

    fun disconnectNodes(fromNodeId: String, toNodeId: String) {
        mutate { definition ->
            val toRemove = definition.edges.filter { it.fromNodeId == fromNodeId && it.toNodeId == toNodeId }
            toRemove.fold(definition) { acc, edge -> WorkflowGraphEditor.removeEdge(acc, edge.id) }
        }
    }

    fun disconnectAllForNode(nodeId: String) {
        mutate { definition ->
            val toRemove = definition.edges.filter { it.fromNodeId == nodeId || it.toNodeId == nodeId }
            toRemove.fold(definition) { acc, edge -> WorkflowGraphEditor.removeEdge(acc, edge.id) }
        }
    }

    fun updateEdge(edge: WorkflowEdge) = mutate { WorkflowGraphEditor.updateEdge(it, edge) }

    fun removeEdge(edgeId: String) = mutate { WorkflowGraphEditor.removeEdge(it, edgeId) }

    fun undoEdit() {
        val history = editHistory ?: return
        if (history.undo()) publishEditor(history, selectedNodeId = _editorState.value?.selectedNodeId)
    }

    fun redoEdit() {
        val history = editHistory ?: return
        if (history.redo()) publishEditor(history, selectedNodeId = _editorState.value?.selectedNodeId)
    }

    fun saveEditor() {
        val history = editHistory ?: return
        _editorState.value = _editorState.value?.copy(isSaving = true, message = null)
        viewModelScope.launch {
            runCatching { repository.upsert(history.current.copy(updatedAt = System.currentTimeMillis())) }
                .onSuccess {
                    history.markSaved()
                    publishEditor(history, selectedNodeId = _editorState.value?.selectedNodeId, message = "已保存")
                }
                .onFailure { error ->
                    _editorState.value = _editorState.value?.copy(isSaving = false, message = error.message ?: "保存失败")
                }
        }
    }

    fun deleteWorkflow(definition: WorkflowDefinition) {
        if (definition.isBuiltin) return
        viewModelScope.launch { repository.deleteCustom(definition.id) }
    }

    private fun mutate(selectedNodeId: String? = _editorState.value?.selectedNodeId, block: (WorkflowDefinition) -> WorkflowDefinition) {
        val history = editHistory ?: return
        runCatching { block(history.current) }
            .onSuccess { next ->
                history.commit(next)
                publishEditor(history, selectedNodeId)
            }
            .onFailure { error ->
                _editorState.value = _editorState.value?.copy(message = error.message ?: "编辑失败")
            }
    }

    private fun publishEditor(history: WorkflowEditHistory, selectedNodeId: String?, message: String? = null) {
        val connectionSourceId = _editorState.value?.connectionSourceId
            ?.takeIf { id -> history.current.nodes.any { it.id == id } }
        _editorState.value = WorkflowEditorUiState(
            definition = history.current,
            selectedNodeId = selectedNodeId?.takeIf { id -> history.current.nodes.any { it.id == id } },
            connectionSourceId = connectionSourceId,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            isDirty = history.isDirty,
            message = message,
        )
    }

    private companion object {
        val TERMINAL = setOf(WorkflowRunStatus.SUCCESS, WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED)
        val HUD_ACTIVE_NODE = setOf(
            NodeRunStatus.RUNNING,
            NodeRunStatus.STREAMING,
            NodeRunStatus.WAITING_APPROVAL,
        )
    }
}

private fun WorkflowNodeType.defaultTitle(): String = when (this) {
    WorkflowNodeType.TRIGGER -> "触发器"
    WorkflowNodeType.BASH_COMMAND -> "执行命令"
    WorkflowNodeType.PROCESS_SERVICE -> "启动服务"
    WorkflowNodeType.AGENT_INFERENCE -> "智能体推理"
    WorkflowNodeType.SUBAGENT_DELEGATE -> "委派子智能体"
    WorkflowNodeType.TAIXU_BUILD -> "万象构建"
    WorkflowNodeType.CONDITION_BRANCH -> "条件分支"
    WorkflowNodeType.HUMAN_APPROVAL -> "人工审批"
    WorkflowNodeType.HOST_ACTION -> "宿主动作"
    WorkflowNodeType.DELAY -> "延时等待"
    WorkflowNodeType.SET_VARIABLE -> "设置变量"
    WorkflowNodeType.TERMINAL_OUTPUT -> "输出结果"
}

private fun WorkflowNodeType.defaultConfig(): Map<String, String> = when (this) {
    WorkflowNodeType.AGENT_INFERENCE -> mapOf(
        "prompt" to "请分析以下上游结果并给出明确结论：\n\${previous.output}",
    )
    WorkflowNodeType.SUBAGENT_DELEGATE -> mapOf(
        "taskName" to "工作流子任务",
        "prompt" to "请完成以下任务并返回结果：\n\${previous.output}",
        "role" to "",
        "writePaths" to "",
    )
    WorkflowNodeType.HOST_ACTION -> mapOf("action" to "status")
    WorkflowNodeType.CONDITION_BRANCH -> mapOf("expression" to "exitCode == 0")
    WorkflowNodeType.DELAY -> mapOf("seconds" to "1")
    WorkflowNodeType.SET_VARIABLE -> mapOf("variables" to "EXAMPLE_KEY=example_value")
    else -> emptyMap()
}

internal fun scanApksInWorkspace(workspaceDir: File, projectName: String): List<DiscoveredApk> {
    val safeProject = projectName.trim().trim('/').takeIf { it.isNotEmpty() }
    val projectDir = if (safeProject != null) File(workspaceDir, safeProject) else null

    // 优先在当前工程目录搜索，如果当前工程下未找到，再扩大至整个工作区根目录
    val targetDirs = listOfNotNull(
        projectDir?.takeIf { it.isDirectory },
        workspaceDir.takeIf { it.isDirectory },
    ).distinct()

    val results = mutableListOf<DiscoveredApk>()
    val seenPaths = mutableSetOf<String>()

    for (dir in targetDirs) {
        val isProjectScope = dir == projectDir
        runCatching {
            dir.walkTopDown()
                .maxDepth(8)
                .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                .forEach { file ->
                    val relPath = file.relativeTo(dir).path.replace('\\', '/')
                    val sandboxPath = if (isProjectScope && safeProject != null) {
                        "/workspace/$safeProject/$relPath"
                    } else {
                        val relToWorkspace = file.relativeTo(workspaceDir).path.replace('\\', '/')
                        "/workspace/$relToWorkspace"
                    }
                    if (seenPaths.add(file.absolutePath)) {
                        results.add(
                            DiscoveredApk(
                                file = file,
                                name = file.name,
                                relativePath = if (isProjectScope) relPath else file.relativeTo(workspaceDir).path.replace('\\', '/'),
                                sandboxPath = sandboxPath,
                                sizeBytes = file.length(),
                                lastModified = file.lastModified(),
                            ),
                        )
                    }
                }
        }
        if (results.isNotEmpty()) break
    }

    return results.sortedByDescending { it.lastModified }
}
