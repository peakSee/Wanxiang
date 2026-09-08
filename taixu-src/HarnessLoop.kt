package top.wkbin.taixu.harness

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.HarnessSessionEntity
import top.wkbin.taixu.core.model.SessionRunState
import top.wkbin.taixu.harness.mcp.McpToolApiName
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.taixu.harness.validation.ToolSchemaValidator
import top.wkbin.taixu.harness.validation.ToolCallLoopDetector
import top.wkbin.taixu.harness.metrics.RunMetrics
import top.wkbin.taixu.harness.task.AgentStateMachine

import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.harness.session.SessionTreeStore
import top.wkbin.taixu.harness.effects.RetryPolicy
import top.wkbin.taixu.harness.effects.ToolReplayPolicy
import top.wkbin.taixu.harness.operation.OperationCoordinator
import top.wkbin.taixu.harness.recovery.RecoveryManager
import top.wkbin.taixu.harness.recovery.RecoveryOutcome
import top.wkbin.taixu.harness.queue.PromptQueue
import top.wkbin.taixu.harness.queue.PromptQueueManager
import top.wkbin.taixu.harness.compaction.CompactionManager
import top.wkbin.taixu.harness.effects.DanglingToolCallPlanner
import top.wkbin.taixu.harness.events.AgentEventLogger
import top.wkbin.taixu.harness.events.CapabilityEventWriter
import top.wkbin.taixu.harness.projection.CurrentSessionTracker
import top.wkbin.taixu.harness.projection.SessionMessageProjector
import top.wkbin.taixu.harness.projection.SessionStateMirrors
import top.wkbin.taixu.harness.projection.ToolStatusDescriber
import top.wkbin.taixu.harness.session.ApiContextAssembler
import kotlin.time.Duration.Companion.milliseconds

/** Agent 单次运行的结构化结果，外层据此设置会话状态，避免内部失败被误标为 COMPLETED。 */
private sealed interface RunResult {
    data object Completed : RunResult
    data object WaitingApproval : RunResult
    data object Cancelled : RunResult
    data class Failed(val message: String) : RunResult
}

/** 已通过串行校验、待并发执行的工具调用。 */
private data class ExecutableToolCall(
    val spec: ApiToolCallSpec,
    val tool: HarnessTool,
    val toolName: String,
    val args: JsonObject,
)

/**
 * Harness 多智能体会话并发引擎：
 * 支持多会话后台并行运行、实时状态机追踪（就绪/运行中/完成/失败）、
 * 独立的流式消息队列与前台服务多通知分发。
 */
@Singleton
class HarnessLoop @Inject constructor(
    @ApplicationContext private val context: Context,
    private val foregroundLauncher: AgentForegroundLauncher,
    private val providerClient: ProviderClient,
    private val toolExecutor: ToolExecutor,
    private val messageStore: SessionTreeStore,
    private val sessionDao: HarnessSessionRepository,
    private val modelRepository: top.wkbin.taixu.core.database.AiModelRepository,
    private val settingsDataStore: AgentPreferences,
    private val json: Json,
    private val logger: AppLogger,
    private val approvalRepository: top.wkbin.taixu.core.database.AgentApprovalRepository,
    private val operationCoordinator: OperationCoordinator,
    private val recoveryManager: RecoveryManager,
    private val promptQueueManager: PromptQueueManager,
    private val sessionTracker: CurrentSessionTracker,
    private val stateMirrors: SessionStateMirrors,
    private val messageProjector: SessionMessageProjector,
    private val capabilityWriter: CapabilityEventWriter,
    private val agentEventLogger: AgentEventLogger,
    private val systemPromptBuilder: top.wkbin.taixu.harness.prompt.SystemPromptBuilder,
    private val contextAssembler: ApiContextAssembler,
    private val resumePolicy: top.wkbin.taixu.harness.approval.ApprovalResumePolicy,
    private val toolRoundDispatcher: ToolRoundDispatcher,
    private val mcpRecommender: top.wkbin.taixu.harness.mcp.McpWorkspaceRecommender,
    private val mcpServerRepository: top.wkbin.taixu.core.database.McpServerRepository,
    private val pathManager: top.wkbin.taixu.runtime.RuntimePathManager,
    private val agentTaskStateMachine: AgentStateMachine,
    private val turnRunner: TurnRunner,
    private val rewindController: top.wkbin.taixu.harness.checkpoint.RewindController,
) {
    private val loopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val currentSessionId: StateFlow<String> get() = sessionTracker.currentSessionId

    private val sessionJobs = ConcurrentHashMap<String, Job>()
    private val sessionMutexes = ConcurrentHashMap<String, Mutex>()
    private val sessionCancelEpochs = ConcurrentHashMap<String, AtomicLong>()
    private val foregroundLoadGeneration = AtomicLong()
    private val cancellingSessions = ConcurrentHashMap.newKeySet<String>()
    private val sessionLoopDetectors = ConcurrentHashMap<String, ToolCallLoopDetector>()
    /** Sessions being deleted; reject new runs and skip pending drainage. */
    private val tombstonedSessions = ConcurrentHashMap.newKeySet<String>()
    private val recoveredSessions = ConcurrentHashMap.newKeySet<String>()

    private val _sessionPendingMessages = ConcurrentHashMap<String, MutableStateFlow<List<PendingMessage>>>()

    /** 全局所有会话的运行状态映射（供会话抽屉、状态点等观察）——委托给状态镜像器。 */
    val sessionRunStates: StateFlow<Map<String, SessionRunState>> get() = stateMirrors.sessionRunStates
    /** 全局各会话当前的动作描述状态。 */
    val sessionStatuses: StateFlow<Map<String, String>> get() = stateMirrors.sessionStatuses

    // ---- 当前前台聚焦会话的响应式镜像（全部委托给投影协作类） ----
    val messages: StateFlow<List<HarnessMessage>> get() = messageProjector.foregroundMessages

    /** Session-scoped message stream used by trusted secondary surfaces such as TaiXu WebChat. */
    fun messagesForSession(sessionId: String): StateFlow<List<HarnessMessage>> =
        messageProjector.messagesFlow(sessionId)

    // ---- Checkpoints & Rewind（每轮文件快照安全网，供 UI / 未来 MCP 调用） ----
    fun sessionCheckpoints(sessionId: String): List<top.wkbin.taixu.harness.checkpoint.CheckpointMeta> =
        rewindController.checkpoints(sessionId)

    fun prepareRewind(
        sessionId: String,
        turn: Int,
        scope: top.wkbin.taixu.harness.checkpoint.RewindScope,
    ): top.wkbin.taixu.harness.checkpoint.RewindPlan = rewindController.prepare(sessionId, turn, scope)

    suspend fun commitRewind(
        plan: top.wkbin.taixu.harness.checkpoint.RewindPlan,
        workspace: String = "",
    ): top.wkbin.taixu.harness.checkpoint.RewindResult = rewindController.commit(plan, workspace)

    /** Loads persisted history without changing the Android UI's foreground session. */
    suspend fun prepareRemoteSession(sessionId: String): List<HarnessMessage> {
        val flow = messageProjector.preparedForLoad(sessionId)
        return flow.value
    }

    val running: StateFlow<Boolean> get() = stateMirrors.running

    private val _workspace = MutableStateFlow("")
    /** 当前会话关联的工作区 Linux 路径（"" = 未关联）。 */
    val workspace: StateFlow<String> = _workspace.asStateFlow()

    private val _projectType = MutableStateFlow("")
    /** 当前会话显式选择的工程类型；空值表示由工作区内容自动识别。 */
    val projectType: StateFlow<String> = _projectType.asStateFlow()

    private val _mcpRecommendations = MutableStateFlow<List<top.wkbin.taixu.harness.mcp.McpWorkspaceRecommender.Recommendation>>(emptyList())
    /**
     * 基于当前会话工作区内容自动推荐的 MCP 预设（已启用的会被过滤）。
     * 仅提示、不自动启用；用户可一键启用或忽略。
     */
    val mcpRecommendations: StateFlow<List<top.wkbin.taixu.harness.mcp.McpWorkspaceRecommender.Recommendation>> =
        _mcpRecommendations.asStateFlow()

    /** 用户确认启用推荐中的 MCP 预设。 */
    fun enableRecommendedMcp(presetId: String) {
        loopScope.launch {
            runCatching { mcpServerRepository.setEnabled(presetId, true) }
                .onFailure { throwable -> logger.e("启用推荐 MCP 失败：$presetId", throwable) }
            _mcpRecommendations.update { current -> current.filterNot { it.presetId == presetId } }
        }
    }

    /** 用户忽略该推荐（本次会话加载内不再提示）。 */
    fun dismissMcpRecommendation(presetId: String) {
        _mcpRecommendations.update { current -> current.filterNot { it.presetId == presetId } }
    }

    /** 扫描工作区内容并刷新推荐列表；未关联工作区或目录不存在时清空。 */
    private fun refreshMcpRecommendations(workspacePath: String) {
        loopScope.launch {
            val dir = resolveWorkspaceDir(workspacePath)
            val recommendations = if (dir == null) emptyList() else {
                mcpRecommender.recommend(dir)
            }
            val enabledIds = runCatching { mcpServerRepository.servers.first() }
                .getOrDefault(emptyList())
                .filter { it.isEnabled }
                .map { it.id }
                .toSet()
            _mcpRecommendations.value = recommendations.filter { it.presetId !in enabledIds }
        }
    }

    private fun resolveWorkspaceDir(workspacePath: String): java.io.File? {
        val trimmed = workspacePath.trim()
        if (trimmed.isEmpty()) return null
        val relative = trimmed.removePrefix("/workspace/").removePrefix("/workspace").removePrefix("/")
        val root = pathManager.workspaceDir
        val dir = if (relative.isBlank()) root else java.io.File(root, relative)
        return dir.takeIf { it.isDirectory }
    }

    val error: StateFlow<String?> get() = stateMirrors.error

    /** 当前执行状态（供 UI / 后台通知显示进度）。运行结束或出错时置空。 */
    val status: StateFlow<String?> get() = stateMirrors.status

    /** 推理模型思考中（reasoning 正在流式上屏）。开始思考置 true，本回合结束时置 false。 */
    val thinkingLive: StateFlow<Boolean> get() = stateMirrors.thinkingLive

    private val _pendingMessages = MutableStateFlow<List<PendingMessage>>(emptyList())
    /**
     * 运行中排队等待发送的用户消息。当前任务结束后自动按序接续执行；
     * 用户点"停止"时清空。UI 可观察此列表展示排队状态。
     */
    val pendingMessages: StateFlow<List<PendingMessage>> = _pendingMessages.asStateFlow()

    private val _queuedPrompts = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    /** Current session's durable queues, including steering and follow-up semantics. */
    val queuedPrompts: StateFlow<List<QueuedPrompt>> = _queuedPrompts.asStateFlow()

    private fun getOrCreatePendingFlow(sessId: String): MutableStateFlow<List<PendingMessage>> {
        return _sessionPendingMessages.getOrPut(sessId) { MutableStateFlow(emptyList()) }
    }

    private fun isSessionBusy(sessId: String): Boolean =
        sessionJobs[sessId]?.isCompleted == false ||
            cancellingSessions.contains(sessId) ||
            stateMirrors.isWaitingApproval(sessId)

    /** 新建会话。workspace 为关联的工作区 Linux 路径（如 /workspace/proj），空串表示不关联。 */
    suspend fun newSession(title: String, workspace: String = "", projectType: String = ""): String {
        val id = UUID.randomUUID().toString()
        val defaultModel = modelRepository.activeModel()
        foregroundLoadGeneration.incrementAndGet()
        tombstonedSessions.remove(id)
        sessionTracker.setCurrent(id)
        _workspace.value = workspace
        _projectType.value = projectType
        sessionDao.upsert(
            HarnessSessionEntity(
                id = id,
                title = title.ifBlank { "新会话" },
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                modelId = defaultModel?.id,
                modelVariant = defaultModel?.model?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() },
                workspace = workspace,
                projectType = projectType,
                approvalMode = approvalRepository.currentMode().id,
            ),
        )
        messageProjector.seedEmpty(id)
        messageProjector.resetForegroundProjection(emptyList())
        _sessionPendingMessages[id] = MutableStateFlow(emptyList())
        stateMirrors.ensureFlows(id)
        stateMirrors.setRunState(id, SessionRunState.IDLE)
        stateMirrors.setStatus(id, null)

        stateMirrors.resetForeground()
        _pendingMessages.value = emptyList()
        _queuedPrompts.value = emptyList()
        _mcpRecommendations.value = emptyList()
        refreshMcpRecommendations(workspace)
        return id
    }

    /** 恢复已有会话的历史消息与工作区关联，不中断正在后台运行的任何会话。 */
    suspend fun loadSession(id: String) {
        val generation = foregroundLoadGeneration.incrementAndGet()
        sessionTracker.setCurrent(id)
        val sessionEntity = withContext(Dispatchers.IO) { sessionDao.findById(id) }
        if (!isCurrentLoad(id, generation)) return
        _workspace.value = sessionEntity?.workspace.orEmpty()
        _projectType.value = sessionEntity?.projectType.orEmpty()
        refreshMcpRecommendations(_workspace.value)

        val liveFlow = messageProjector.preparedForLoad(id)
        if (!isCurrentLoad(id, generation)) return

        messageProjector.resetForegroundProjection(liveFlow.value)
        stateMirrors.setForegroundRunning(sessionJobs[id]?.isActive == true)
        stateMirrors.restoreForegroundError(id, stateMirrors.errorOf(id))
        stateMirrors.setStatus(id, stateMirrors.lastStatus(id))
        stateMirrors.setThinkingLive(id, stateMirrors.thinkingLiveOf(id))
        refreshPendingProjection(id)

        if (withContext(Dispatchers.IO) { approvalRepository.pendingNow(id).isNotEmpty() }) {
            if (!isCurrentLoad(id, generation)) return
            stateMirrors.setRunState(id, SessionRunState.WAITING_APPROVAL)
            stateMirrors.setStatus(id, "等待用户批准")
        }

        stateMirrors.recordThinkingModeFromHistory(id, liveFlow.value)
        recoverSessionIfInterrupted(id, liveFlow)
    }

    /**
     * 应用进程重启后批量恢复所有被中断的 Agent 会话。
     *
     * 遍历数据库中所有会话，对存在未完成操作（活跃 operation）的会话执行恢复策略：
     * - 等待审批：保持 WAITING_APPROVAL 状态
     * - 工具中断 / 运行挂起：先应用 replay policy，再由 durable task 的授权与尝试预算
     *   决定自动续跑或保持 SUSPENDED；不可重放工具永不自动再次执行。
     *
     * @return 实际执行了恢复处理的会话数量
     */
    suspend fun recoverAllInterruptedSessions(): Int {
        val sessions = withContext(Dispatchers.IO) { sessionDao.listAll() }
        var recovered = 0
        for (session in sessions) {
            val hasActiveOperation = withContext(Dispatchers.IO) {
                operationCoordinator.active(session.id) != null
            }
            if (hasActiveOperation && recoverSessionIfInterrupted(session.id, null)) {
                recovered++
            }
        }

        // Tasks whose restart budget/authority is exhausted remain visible and resumable by the
        // user, but are never silently executed again.
        agentTaskStateMachine.exhaustedRecoverable().forEach { task ->
            agentTaskStateMachine.markSuspended(
                task.id,
                when {
                    task.sessionId.isBlank() -> "旧任务未绑定会话，需手动重新发起"
                    !task.autoResume -> "任务未授权进程重启后自动继续"
                    else -> "已达到进程恢复尝试上限（${task.attemptCount}/${task.maxAttempts}）"
                },
            )
        }

        val recoverableBySession = agentTaskStateMachine.recoverable().groupBy { it.sessionId }
        for ((sessionId, tasks) in recoverableBySession) {
            val task = tasks.first()
            tasks.drop(1).forEach { duplicate ->
                agentTaskStateMachine.markSuspended(duplicate.id, "同一会话存在更早的活动任务，已暂停以保持顺序")
            }
            if (sessions.none { it.id == sessionId }) {
                agentTaskStateMachine.markSuspended(task.id, "关联会话不存在，无法恢复")
                continue
            }
            if (approvalRepository.pendingNow(sessionId).isNotEmpty()) {
                agentTaskStateMachine.markWaitingApproval(task.id)
                continue
            }

            val activeOperation = operationCoordinator.active(sessionId)
            if (activeOperation == null && task.operationId != null) {
                // Normal shutdown writes the task terminal state before it removes the operation.
                // A recoverable task with no operation therefore has no trustworthy outcome.
                // Fail closed: never replay a potentially side-effecting prompt and never invent
                // a successful result that was not durably recorded.
                val detail = "运行结果未知；关联操作已结束，为防止副作用重放，任务已终止"
                agentTaskStateMachine.markFailed(task.id, detail)
                agentEventLogger.log(sessionId, "DurableTaskRecovered", "taskId=${task.id}, outcome=unknown")
                recovered++
                continue
            }

            if (!agentTaskStateMachine.markRecovering(task.id, "应用进程重启，正在从持久化检查点恢复")) continue
            val mutex = sessionMutexes.getOrPut(sessionId) { Mutex() }
            mutex.withLock {
                if (isSessionBusy(sessionId) || tombstonedSessions.contains(sessionId)) return@withLock
                launchSessionJobLocked(
                    sessId = sessionId,
                    taskId = task.id,
                    operationId = activeOperation?.id,
                ) {
                    if (activeOperation == null) {
                        runLoop(sessionId, task.description, taskId = task.id)
                    } else {
                        runLoopInternal(sessionId, now(), activeOperation.id, task.id)
                    }
                }
                recovered++
                agentEventLogger.log(
                    sessionId,
                    "DurableTaskRecovered",
                    "taskId=${task.id}, attempt=${task.attemptCount + 1}/${task.maxAttempts}",
                )
            }
        }

        // A process can die after one operation finishes but before finishRun drains NEXT_RUN.
        // Restart the first durable queue item for otherwise-idle sessions.
        for (sessionId in agentTaskStateMachine.queued().map { it.sessionId }.filter { it.isNotBlank() }.distinct()) {
            if (sessions.none { it.id == sessionId } || approvalRepository.pendingNow(sessionId).isNotEmpty()) continue
            val mutex = sessionMutexes.getOrPut(sessionId) { Mutex() }
            mutex.withLock {
                if (!isSessionBusy(sessionId) && startNextQueuedLocked(sessionId)) recovered++
            }
        }
        if (recovered > 0) startForegroundServiceSafe()
        return recovered
    }

    /**
     * 对单个会话执行中断恢复。从 loadSession 中提取，供批量恢复复用。
     *
     * @param id 会话 ID
     * @param existingLiveFlow loadSession 中已初始化的消息流；批量恢复时传 null，内部按需创建
     * @return 是否执行了非 Clean 的恢复处理
     */
    private suspend fun recoverSessionIfInterrupted(
        id: String,
        existingLiveFlow: MutableStateFlow<List<HarnessMessage>>?,
    ): Boolean {
        if (sessionJobs[id]?.isActive == true) return false
        if (!recoveredSessions.add(id)) return false

        val liveFlow = existingLiveFlow ?: messageProjector.preparedForLoad(id)

        return when (val recovery = recoveryManager.recoverSession(id)) {
            RecoveryOutcome.Clean -> false
            RecoveryOutcome.WaitingApproval -> {
                stateMirrors.setRunState(id, SessionRunState.WAITING_APPROVAL)
                stateMirrors.setStatus(id, "等待用户批准")
                true
            }
            is RecoveryOutcome.ToolInterrupted -> {
                val restored = messageProjector.loadHistory(id)
                messageProjector.replaceAll(id, restored)
                stateMirrors.setRunState(id, SessionRunState.IDLE)
                stateMirrors.setStatus(id, "上次工具执行被中断，发送消息即可继续")
                true
            }
            is RecoveryOutcome.Suspended -> {
                stateMirrors.setRunState(id, SessionRunState.IDLE)
                stateMirrors.setStatus(id, "上次运行已暂停（${recovery.reason}），发送消息即可重新开始")
                true
            }
        }
    }

    suspend fun renameSession(id: String, title: String) {
        sessionDao.rename(id, title, System.currentTimeMillis())
    }

    suspend fun deleteSession(id: String) {
        // Mark tombstoned first so finishRun on the dying job cannot drain pending
        // messages and start a fresh run after we have already begun cleanup.
        tombstonedSessions.add(id)
        _sessionPendingMessages[id]?.value = emptyList()
        sessionJobs[id]?.cancelAndJoin()
        _sessionPendingMessages.remove(id)
        sessionMutexes.remove(id)
        messageProjector.removeSession(id)
        stateMirrors.removeSession(id)

        messageStore.deleteSession(id)
        approvalRepository.deleteForSession(id)
        agentTaskStateMachine.deleteForSession(id)
        sessionDao.deleteSession(id)
        rewindController.dropSession(id)
        sessionLoopDetectors.remove(id)
        sessionCancelEpochs.remove(id)
        cancellingSessions.remove(id)
        tombstonedSessions.remove(id)
        if (sessionTracker.currentSessionId.value == id) {
            val remaining = sessionDao.observeAll().first()
            val nextSession = remaining.firstOrNull { it.id != id }
            if (nextSession != null) {
                loadSession(nextSession.id)
            } else {
                newSession("新会话")
            }
        }
    }

    fun send(text: String, targetSessionId: String? = null, imageUrls: List<String> = emptyList()) {
        val trimmed = text.trim()
        val sessId = targetSessionId?.ifBlank { null } ?: sessionTracker.currentSessionId.value
        if (trimmed.isEmpty() && imageUrls.isEmpty()) return
        if (sessId.isBlank()) return

        val pending = PendingMessage(text = trimmed, imageUrls = imageUrls, taskId = newId())
        startSessionRun(sessId, enqueueOnBusy = pending) {
            runLoop(sessId, pending.text, pending.imageUrls, pending.taskId)
        }
        startForegroundServiceSafe()
    }

    fun steer(text: String, targetSessionId: String? = null, imageUrls: List<String> = emptyList()) {
        enqueueExplicit(PromptQueue.STEER, text, targetSessionId, imageUrls)
    }

    fun followUp(text: String, targetSessionId: String? = null, imageUrls: List<String> = emptyList()) {
        enqueueExplicit(PromptQueue.FOLLOW_UP, text, targetSessionId, imageUrls)
    }

    private fun enqueueExplicit(queue: PromptQueue, text: String, targetSessionId: String?, imageUrls: List<String>) {
        val sessId = targetSessionId?.ifBlank { null } ?: sessionTracker.currentSessionId.value
        val trimmed = text.trim()
        if (sessId.isBlank() || (trimmed.isBlank() && imageUrls.isEmpty())) return
        loopScope.launch {
            val mutex = sessionMutexes.getOrPut(sessId) { Mutex() }
            mutex.withLock {
                if (tombstonedSessions.contains(sessId)) return@withLock
                if (isSessionBusy(sessId)) {
                    // Steering/follow-up belongs to the currently active durable task.
                    promptQueueManager.enqueue(sessId, queue, PendingMessage(trimmed, imageUrls))
                } else {
                    val pending = PendingMessage(trimmed, imageUrls, taskId = newId())
                    createDurableTask(sessId, pending)
                    launchSessionJobLocked(sessId, pending.taskId) {
                        runLoop(sessId, pending.text, pending.imageUrls, pending.taskId)
                    }
                }
            }
            refreshPendingProjection(sessId)
        }
    }

    /**
     * 重新生成最后一次回复
     */
    fun regenerateLast(targetSessionId: String? = null) {
        val sessId = targetSessionId?.ifBlank { null } ?: sessionTracker.currentSessionId.value
        if (sessId.isBlank()) return

        startSessionRun(sessId) {
            val current = messageProjector.messagesFlow(sessId).value
            val lastUserIndex = current.indexOfLast { it is UserMessage }
            if (lastUserIndex < 0) return@startSessionRun RunResult.Completed
            val lastUserMessage = current[lastUserIndex] as UserMessage
            val toKeep = current.subList(0, lastUserIndex + 1)
            val liveFlow = messageProjector.messagesFlow(sessId)
            messageProjector.replaceAll(sessId, toKeep)
            messageStore.moveTo(sessId, lastUserMessage.id)
            runLoopInternal(sessId, startedAt = now())
        }
        startForegroundServiceSafe()
    }

    /** Rewinds before a tool call and asks the model to continue again on a preserved new branch. */
    fun retryToolCall(toolCallId: String, targetSessionId: String? = null) {
        val sessId = targetSessionId?.ifBlank { null } ?: sessionTracker.currentSessionId.value
        if (sessId.isBlank()) return
        startSessionRun(sessId) {
            val current = messageProjector.messagesFlow(sessId).value
            val targetIndex = current.indexOfFirst { it.id == toolCallId && it is ToolCall }
            if (targetIndex < 0) return@startSessionRun RunResult.Completed
            val target = current[targetIndex]
            val updated = current.take(targetIndex)
            messageProjector.replaceAll(sessId, updated)
            messageStore.rewindBefore(sessId, target.id)
            runLoopInternal(sessId, startedAt = now())
        }
        startForegroundServiceSafe()
    }

    /** Moves the main conversation cursor to an existing immutable-tree leaf. */
    suspend fun activateBranch(leafId: String?, targetSessionId: String? = null): Boolean {
        val sessId = targetSessionId?.ifBlank { null } ?: sessionTracker.currentSessionId.value
        if (sessId.isBlank() || isSessionBusy(sessId)) return false
        messageStore.moveTo(sessId, leafId)
        val history = messageProjector.loadHistory(sessId)
        messageProjector.replaceAll(sessId, history)
        return true
    }

    /**
     * 编辑并重发指定用户消息
     */
    fun truncateAndResend(userMessageId: String, newText: String, targetSessionId: String? = null) {
        val trimmed = newText.trim()
        val sessId = targetSessionId?.ifBlank { null } ?: sessionTracker.currentSessionId.value
        if (trimmed.isEmpty() || sessId.isBlank()) return

        startSessionRun(sessId) {
            val current = messageProjector.messagesFlow(sessId).value
            val targetIndex = current.indexOfFirst { it.id == userMessageId }
            if (targetIndex < 0) {
                return@startSessionRun runLoop(sessId, trimmed)
            }
            val targetMessage = current[targetIndex]
            val toKeep = current.subList(0, targetIndex)
            val liveFlow = messageProjector.messagesFlow(sessId)
            messageProjector.replaceAll(sessId, toKeep)
            messageStore.rewindBefore(sessId, targetMessage.id)
            runLoop(sessId, trimmed)
        }
        startForegroundServiceSafe()
    }

    /** Navigate the active branch to immediately before this message. */
    suspend fun deleteMessage(messageId: String, targetSessionId: String? = null) {
        val sessId = targetSessionId?.ifBlank { null } ?: sessionTracker.currentSessionId.value
        if (isSessionBusy(sessId) || sessId.isBlank()) return
        val liveFlow = messageProjector.messagesFlow(sessId)
        val current = liveFlow.value
        val target = current.firstOrNull { it.id == messageId } ?: return
        val targetIndex = current.indexOf(target)
        val updated = current.take(targetIndex)
        messageProjector.replaceAll(sessId, updated)
        messageStore.rewindBefore(sessId, target.id)
    }

    private suspend fun repairDanglingToolCalls(sessId: String, interrupted: Boolean, workspace: String = "") {
        val actions = DanglingToolCallPlanner.plan(messageProjector.messagesFlow(sessId).value, interrupted)
        if (actions.isEmpty()) return
        actions.forEach { action ->
            val result = when (action) {
                is DanglingToolCallPlanner.Replay -> {
                    agentEventLogger.log(
                        sessId,
                        "ToolReplay",
                        "重放中断的只读工具 ${action.call.rawToolName ?: action.call.tool.name.lowercase()}",
                    )
                    try {
                        toolExecutor.execute(action.call, sessId, workspace)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        ToolResult(
                            id = newId(),
                            createdAt = now(),
                            toolCallId = action.call.id,
                            success = false,
                            output = "重放中断的只读工具失败：${friendly(throwable)}",
                        )
                    }
                }
                is DanglingToolCallPlanner.Stubbed -> ToolResult(
                    id = newId(),
                    createdAt = now(),
                    toolCallId = action.call.id,
                    success = false,
                    output = action.note,
                )
            }
            messageProjector.append(sessId, result)
        }
    }

    fun cancel(targetSessionId: String? = null) {
        val sessId = targetSessionId?.ifBlank { null } ?: sessionTracker.currentSessionId.value
        if (sessId.isBlank()) return
        stateMirrors.setStatus(sessId, "正在停止…")
        loopScope.launch {
            val mutex = sessionMutexes.getOrPut(sessId) { Mutex() }
            val job = mutex.withLock {
                cancellingSessions += sessId
                sessionCancelEpochs.getOrPut(sessId) { AtomicLong() }.incrementAndGet()
                val queuedTaskIds = promptQueueManager.list(sessId, PromptQueue.NEXT_RUN)
                    .mapNotNull { it.second.taskId }
                PromptQueue.entries.forEach { promptQueueManager.clear(sessId, it) }
                queuedTaskIds.forEach { agentTaskStateMachine.markCancelled(it, "会话运行已停止") }
                sessionJobs[sessId]?.also { it.cancel() }
            }
            job?.cancelAndJoin()
            val restarted = mutex.withLock {
                var approvalsSettled = true
                try {
                    rejectPendingApprovalsForCancel(sessId)
                    agentTaskStateMachine.activeForSession(sessId)?.let { active ->
                        agentTaskStateMachine.markCancelled(active.id)
                    }
                } catch (throwable: Throwable) {
                    approvalsSettled = false
                    logger.e("Failed to settle pending approvals while cancelling $sessId", throwable)
                } finally {
                    cancellingSessions -= sessId
                }
                approvalsSettled && !tombstonedSessions.contains(sessId) && startNextQueuedLocked(sessId)
            }
            refreshPendingProjection(sessId)
            if (!restarted) {
                val stillWaiting = runCatching { approvalRepository.pendingNow(sessId).isNotEmpty() }
                    .getOrDefault(true)
                if (stillWaiting) {
                    stateMirrors.setRunState(sessId, SessionRunState.WAITING_APPROVAL)
                    stateMirrors.setStatus(sessId, "等待用户批准")
                } else {
                    stateMirrors.setRunState(sessId, SessionRunState.IDLE)
                    stateMirrors.setStatus(sessId, null)
                    if (sessId == sessionTracker.foregroundId) stateMirrors.setForegroundRunning(false)
                }
            }
        }
    }

    /** 移除某会话排队中的消息 */
    fun removePendingMessage(index: Int, targetSessionId: String? = null) {
        val sessId = targetSessionId?.ifBlank { null } ?: sessionTracker.currentSessionId.value
        if (sessId.isBlank()) return
        loopScope.launch {
            val taskId = promptQueueManager.list(sessId, PromptQueue.NEXT_RUN)
                .getOrNull(index)?.second?.taskId
            promptQueueManager.cancel(sessId, PromptQueue.NEXT_RUN, index)
            taskId?.let { agentTaskStateMachine.markCancelled(it, "已从等待队列移除") }
            refreshPendingProjection(sessId)
        }
    }

    fun removeQueuedPrompt(queue: PromptQueue, index: Int, targetSessionId: String? = null) {
        val sessId = targetSessionId?.ifBlank { null } ?: sessionTracker.currentSessionId.value
        if (sessId.isBlank()) return
        loopScope.launch {
            val taskId = if (queue == PromptQueue.NEXT_RUN) {
                promptQueueManager.list(sessId, queue).getOrNull(index)?.second?.taskId
            } else {
                null
            }
            promptQueueManager.cancel(sessId, queue, index)
            taskId?.let { agentTaskStateMachine.markCancelled(it, "已从等待队列移除") }
            refreshPendingProjection(sessId)
        }
    }

    /** 清空某会话全部排队消息 */
    fun clearPendingMessages(targetSessionId: String? = null) {
        val sessId = targetSessionId?.ifBlank { null } ?: sessionTracker.currentSessionId.value
        if (sessId.isBlank()) return
        loopScope.launch {
            val queuedTaskIds = promptQueueManager.list(sessId, PromptQueue.NEXT_RUN)
                .mapNotNull { it.second.taskId }
            promptQueueManager.clear(sessId, PromptQueue.NEXT_RUN)
            queuedTaskIds.forEach { agentTaskStateMachine.markCancelled(it, "等待队列已清空") }
            refreshPendingProjection(sessId)
        }
    }

    private suspend fun refreshPendingProjection(sessId: String) {
        val all = promptQueueManager.listAll(sessId)
        val pending = all.filter { it.queue == PromptQueue.NEXT_RUN }.map { it.message }
        getOrCreatePendingFlow(sessId).value = pending
        if (sessId == sessionTracker.currentSessionId.value) {
            _pendingMessages.value = pending
            _queuedPrompts.value = all
        }
    }

    private suspend fun finishRun(sessId: String, job: Job, runEpoch: Long) = withContext(NonCancellable) {
        val mutex = sessionMutexes.getOrPut(sessId) { Mutex() }
        mutex.withLock {
            sessionJobs.remove(sessId, job)
            val waitingApproval = stateMirrors.onRunFinished(sessId)
            if (waitingApproval || tombstonedSessions.contains(sessId)) return@withLock
            if (sessionCancelEpochs[sessId]?.get() != runEpoch || cancellingSessions.contains(sessId)) return@withLock
            startNextQueuedLocked(sessId)
        }
        refreshPendingProjection(sessId)
    }

    /** Caller holds the session mutex. Consumes and starts exactly one durable next-run item. */
    private suspend fun startNextQueuedLocked(sessId: String): Boolean {
        val (queueItemId, next) = promptQueueManager.first(sessId, PromptQueue.NEXT_RUN) ?: return false
        val taskId = next.taskId ?: newId().also { generated ->
            createDurableTask(sessId, next.copy(taskId = generated))
        }
        val userMessage = UserMessage(newId(), now(), next.text, next.imageUrls)
        val operationId = operationCoordinator.acceptQueuedRun(sessId, queueItemId, userMessage)
        messageProjector.publishPersisted(sessId, userMessage)
        launchSessionJobLocked(sessId, taskId, operationId = operationId) {
            runLoopInternal(sessId, now(), operationId, taskId)
        }
        return true
    }

    /** Caller holds the session mutex. A lazy Job counts as busy as soon as it enters the map. */
    private suspend fun launchSessionJobLocked(
        sessId: String,
        taskId: String? = null,
        operationId: String? = null,
        incrementTaskAttempt: Boolean = true,
        block: suspend () -> RunResult,
    ) {
        if (taskId != null && !agentTaskStateMachine.markRunning(
                id = taskId,
                operationId = operationId,
                incrementAttempt = incrementTaskAttempt,
            )
        ) {
            logger.w("Durable task $taskId could not claim RUNNING; session launch skipped")
            return
        }
        val epoch = sessionCancelEpochs.getOrPut(sessId) { AtomicLong() }.get()
        val job = loopScope.launch(start = CoroutineStart.LAZY) {
            executeSessionRun(sessId, epoch, taskId, block)
        }
        sessionJobs[sessId] = job
        job.start()
    }

    fun clearError(targetSessionId: String? = null) {
        val sessId = targetSessionId?.ifBlank { null } ?: sessionTracker.currentSessionId.value
        if (sessId.isBlank()) return
        stateMirrors.setError(sessId, null)
    }

    /**
     * Atomically check-and-occupy the session slot under a per-session Mutex,
     * then run [block] as the single active run. If the session is busy the
     * optional [enqueueOnBusy] message is queued for ordered execution.
     */
    private fun startSessionRun(
        sessId: String,
        enqueueOnBusy: PendingMessage? = null,
        block: suspend () -> RunResult,
    ) {
        if (tombstonedSessions.contains(sessId)) return
        loopScope.launch {
            enqueueOnBusy?.taskId?.let { createDurableTask(sessId, enqueueOnBusy) }
            val mutex = sessionMutexes.getOrPut(sessId) { Mutex() }
            var refreshQueue = false
            mutex.withLock {
                if (tombstonedSessions.contains(sessId)) return@withLock
                if (isSessionBusy(sessId)) {
                    enqueueOnBusy?.let {
                        promptQueueManager.enqueue(sessId, PromptQueue.NEXT_RUN, it)
                        refreshQueue = true
                    }
                    return@withLock
                }
                launchSessionJobLocked(sessId, enqueueOnBusy?.taskId, block = block)
            }
            if (refreshQueue) refreshPendingProjection(sessId)
        }
    }

    /**
     * Claim the session slot unconditionally. Used by approval resumption, which
     * already holds an exclusive claim via claimPending() and is the legitimate
     * successor to a WAITING_APPROVAL run (which still reports busy).
     */
    private fun startClaimedSessionRun(
        sessId: String,
        taskId: String? = null,
        block: suspend () -> RunResult,
    ) {
        if (tombstonedSessions.contains(sessId)) return
        loopScope.launch {
            val mutex = sessionMutexes.getOrPut(sessId) { Mutex() }
            mutex.withLock {
                if (tombstonedSessions.contains(sessId)) return@withLock
                launchSessionJobLocked(
                    sessId = sessId,
                    taskId = taskId,
                    incrementTaskAttempt = false,
                    block = block,
                )
            }
        }
    }

    private suspend fun createDurableTask(sessId: String, pending: PendingMessage) {
        val taskId = pending.taskId ?: return
        agentTaskStateMachine.createQueued(
            id = taskId,
            sessionId = sessId,
            title = pending.text.lineSequence().firstOrNull().orEmpty(),
            description = pending.text,
            nowMs = pending.createdAt,
        )
        agentEventLogger.log(sessId, "DurableTaskQueued", "taskId=$taskId")
    }

    /** Resolve a waiting approval as a cancelled tool call before allowing a new run. */
    private suspend fun rejectPendingApprovalsForCancel(sessId: String) {
        var finalEntryId: String? = null
        for (request in approvalRepository.pendingNow(sessId)) {
            if (!approvalRepository.claimPending(
                    request.id,
                    top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_REJECTED,
                )
            ) {
                continue
            }
            val result = ToolResult(
                id = newId(),
                createdAt = now(),
                toolCallId = request.toolCallId,
                success = false,
                output = "用户已停止本次运行，待审批工具未执行。",
            )
            val active = operationCoordinator.active(sessId)
            if (active != null && (request.operationId == null || request.operationId == active.id)) {
                operationCoordinator.toolSettled(active.id, result, round = 0, toolName = request.toolName)
                messageProjector.publishPersisted(sessId, result)
            } else {
                messageProjector.append(sessId, result)
            }
            finalEntryId = result.id
        }
        if (finalEntryId != null) {
            operationCoordinator.finish(
                sessId,
                "aborted",
                finalEntryId = finalEntryId,
                details = "cancelled while waiting for approval",
            )
        }
    }

    private fun isCurrentLoad(sessionId: String, generation: Long): Boolean =
        foregroundLoadGeneration.get() == generation && sessionTracker.currentSessionId.value == sessionId

    private suspend fun executeSessionRun(
        sessId: String,
        runEpoch: Long,
        taskId: String?,
        block: suspend () -> RunResult,
    ) {
        val selfJob = requireNotNull(currentCoroutineContext()[Job])
        stateMirrors.setRunState(sessId, SessionRunState.RUNNING)
        stateMirrors.setError(sessId, null)
        try {
            when (val result = block()) {
                RunResult.Completed -> {
                    taskId?.let { agentTaskStateMachine.markCompleted(it) }
                    operationCoordinator.finish(sessId, "completed", messageProjector.messagesFlow(sessId).value.lastOrNull()?.id)
                    stateMirrors.setRunState(sessId, SessionRunState.COMPLETED)
                }
                RunResult.WaitingApproval -> {
                    taskId?.let { agentTaskStateMachine.markWaitingApproval(it) }
                    stateMirrors.setRunState(sessId, SessionRunState.WAITING_APPROVAL)
                }
                RunResult.Cancelled -> {
                    taskId?.let { agentTaskStateMachine.markCancelled(it) }
                    operationCoordinator.finish(sessId, "aborted")
                    stateMirrors.setRunState(sessId, SessionRunState.IDLE)
                }
                is RunResult.Failed -> {
                    taskId?.let { agentTaskStateMachine.markFailed(it, result.message) }
                    operationCoordinator.finish(sessId, "failed", details = result.message)
                    stateMirrors.setError(sessId, result.message)
                    stateMirrors.setRunState(sessId, SessionRunState.FAILED)
                    // 确保错误在前台消息流中明确展示，消除发消息无回复的卡死假象
                    messageProjector.append(
                        sessId,
                        AssistantText(
                            id = newId(),
                            createdAt = now(),
                            text = "❌ 执行失败：${result.message}",
                        ),
                    )
                }
            }
        } catch (_: CancellationException) {
            withContext(NonCancellable) {
                repairDanglingToolCalls(sessId, interrupted = true)
                taskId?.let { agentTaskStateMachine.markCancelled(it) }
                operationCoordinator.finish(sessId, "aborted", details = "cancelled")
            }
            logger.i("Harness loop cancelled for session $sessId")
            stateMirrors.setRunState(sessId, SessionRunState.IDLE)
        } catch (_: ApprovalPauseException) {
            taskId?.let { agentTaskStateMachine.markWaitingApproval(it) }
            stateMirrors.setRunState(sessId, SessionRunState.WAITING_APPROVAL)
        } catch (throwable: Throwable) {
            logger.e("Harness loop failed for session $sessId", throwable)
            val msg = throwable.message ?: "执行失败"
            stateMirrors.setError(sessId, msg)
            taskId?.let { agentTaskStateMachine.markFailed(it, msg) }
            runCatching { operationCoordinator.finish(sessId, "failed", details = msg) }
            stateMirrors.setRunState(sessId, SessionRunState.FAILED)
            messageProjector.append(
                sessId,
                AssistantText(
                    id = newId(),
                    createdAt = now(),
                    text = "❌ 执行异常：$msg",
                ),
            )
        } finally {
            finishRun(sessId, selfJob, runEpoch)
        }
    }

    private suspend fun runLoop(
        sessId: String,
        userText: String,
        imageUrls: List<String> = emptyList(),
        taskId: String? = null,
    ): RunResult {
        sessionLoopDetectors.getOrPut(sessId) { ToolCallLoopDetector() }.reset()
        agentEventLogger.log(sessId, "UserPrompt", userText)
        val userMessage = UserMessage(id = newId(), createdAt = now(), text = userText, imageUrls = imageUrls)
        rewindController.beginTurn(sessId, userText, userMessage.id)
        val operationId = operationCoordinator.acceptRun(sessId, userMessage)
        taskId?.let { agentTaskStateMachine.checkpoint(it, operationId, 0, 0, "任务已受理") }
        messageProjector.publishPersisted(sessId, userMessage)
        return runLoopInternal(sessId, startedAt = now(), operationId = operationId, taskId = taskId)
    }

    private suspend fun runLoopInternal(
        sessId: String,
        startedAt: Long,
        operationId: String? = null,
        taskId: String? = null,
    ): RunResult {
        // Phase 0 基线埋点：每次运行汇总过程指标并写入 Agent 日志（不受日志开关影响），
        // 为"自主完成率 / 自恢复率 / 人工干预次数"等 2.0 目标指标提供 1.0 真实基线。
        val metrics = RunMetrics(startedAt = startedAt)
        try {
            val result = runLoopRounds(sessId, startedAt, operationId, taskId, metrics)
            metrics.finish(
                when (result) {
                    RunResult.Completed -> "completed"
                    RunResult.WaitingApproval -> "waiting_approval"
                    RunResult.Cancelled -> "cancelled"
                    is RunResult.Failed -> "failed"
                },
            )
            return result
        } catch (cancellation: CancellationException) {
            metrics.finish("cancelled")
            throw cancellation
        } catch (pause: ApprovalPauseException) {
            metrics.finish("waiting_approval")
            throw pause
        } catch (throwable: Throwable) {
            metrics.finish("error")
            throw throwable
        } finally {
            logger.logAgent(sessId, "RunMetrics", metrics.summary())
        }
    }

    private suspend fun runLoopRounds(
        sessId: String,
        startedAt: Long,
        operationId: String?,
        taskId: String?,
        metrics: RunMetrics,
    ): RunResult {
        val activeOperationId = operationId ?: operationCoordinator.beginRun(sessId)
        val maxRounds = runCatching { settingsDataStore.maxToolRounds.first() }.getOrDefault(MAX_ROUNDS)
        val autoCwd = runCatching { settingsDataStore.autoWorkspaceCwd.first() }.getOrDefault(true)
        val sessionEntity = sessionDao.findById(sessId)
        val sessionWorkspace = sessionEntity?.workspace.orEmpty()
        // 悬空调用修复须带 workspace：SAFE 工具在此重放，读操作需要正确的工作目录
        repairDanglingToolCalls(sessId, interrupted = false, workspace = sessionWorkspace)

        val maxToolsPerRound = runCatching { settingsDataStore.maxToolsPerRound.first() }.getOrDefault(12)
        val maxConsecutiveFailures = runCatching { settingsDataStore.maxConsecutiveFailures.first() }.getOrDefault(8)
        val retryPolicy = RetryPolicy.NETWORK_DEFAULT
        var consecutiveFailures = 0

        var round = 0
        while (round < maxRounds) {
            taskId?.let {
                agentTaskStateMachine.checkpoint(
                    id = it,
                    operationId = activeOperationId,
                    round = round,
                    maxRounds = maxRounds,
                    detail = "第 ${round + 1} 轮 · 思考中",
                )
            }
            metrics.roundStarted()
            metrics.steeringInjected(drainSteeringMessages(sessId))
            stateMirrors.setStatus(sessId, "思考中")
            val model = try {
                providerClient.resolveConfigured(sessionEntity?.modelId, sessionEntity?.modelVariant)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                agentEventLogger.log(sessId, "ModelResolveError", "无法获取模型配置", throwable)
                return RunResult.Failed("无法获取模型配置：${friendly(throwable)}")
            }
            agentEventLogger.log(sessId, "ModelRequest", "Round=$round, Model=${model.name}, Provider=${model.provider}")
            val effectiveModel = resolveEffectiveModel(sessId, model)
            val assistantId = newId()
            val assistantAt = now()

            val turn = turnRunner.run(
                toolsEnabled = !effectiveModel.pureChatMode &&
                    effectiveModel.toolCallMode != ToolCallMode.DISABLED,
                callProvider = {
                    callProviderWithRetry(
                        sessId = sessId,
                        model = effectiveModel,
                        sessionEntity = sessionEntity,
                        sessionWorkspace = sessionWorkspace,
                        operationId = activeOperationId,
                        assistantId = assistantId,
                        assistantAt = assistantAt,
                        round = round,
                        startedAt = startedAt,
                        retryPolicy = retryPolicy,
                        metrics = metrics,
                    )
                },
                observeResponse = { normalized ->
                    agentEventLogger.log(
                        sessId,
                        "ModelResponse",
                        "TextLength=${normalized.rawText.length}, " +
                            "ReasoningLength=${normalized.result.reasoningContent?.length ?: 0}, " +
                            "ToolCallsCount=${normalized.result.toolCalls.size}, " +
                            "TextToolCalls=${normalized.textToolCallCount}, " +
                            "InvalidTextMarkers=${normalized.invalidMarkerCount}",
                    )
                },
                persistAssistant = { normalized ->
                    persistAssistantOutput(
                        sessId = sessId,
                        assistantId = assistantId,
                        assistantAt = assistantAt,
                        round = round,
                        startedAt = startedAt,
                        operationId = activeOperationId,
                        result = normalized.result,
                        effectiveModel = effectiveModel,
                        displayText = normalized.displayText,
                        hasToolCalls = normalized.toolCalls.isNotEmpty(),
                    )
                    metrics.recordUsage(normalized.result.usage)
                    stateMirrors.setThinkingLive(sessId, false)
                },
                consumeFollowUps = {
                    val followUps = promptQueueManager.consume(sessId, PromptQueue.FOLLOW_UP)
                    refreshPendingProjection(sessId)
                    followUps.forEach { messageProjector.publishPersisted(sessId, it) }
                    metrics.followUpConsumed(followUps.size)
                    followUps.size
                },
                enforceToolLimit = { allCalls, result ->
                    val effectiveCalls = enforceToolRoundLimit(
                        sessId,
                        allCalls,
                        maxToolsPerRound,
                        result.reasoningContent,
                    )
                    metrics.toolCallsDropped(allCalls.size - effectiveCalls.size)
                    effectiveCalls
                },
                executeTools = { effectiveCalls, result ->
                    executeToolCalls(
                        sessId = sessId,
                        specs = effectiveCalls,
                        reasoning = result.reasoningContent,
                        sessionWorkspace = sessionWorkspace,
                        autoCwd = autoCwd,
                        effectiveModel = effectiveModel,
                        operationId = activeOperationId,
                        round = round,
                        metrics = metrics,
                    )
                },
            )
            when (turn) {
                is TurnOutcome.Failed -> return RunResult.Failed(turn.message)
                TurnOutcome.Complete -> return RunResult.Completed
                is TurnOutcome.Continue -> {
                    // 连续失败熔断：当一轮内所有工具调用均失败时计数。
                    if (turn.effectiveToolCallCount > 0 && !turn.toolsHadSuccess) {
                        consecutiveFailures++
                        metrics.consecutiveFailuresObserved(consecutiveFailures)
                        if (consecutiveFailures >= maxConsecutiveFailures) {
                            metrics.circuitBreaker()
                            messageProjector.append(
                                sessId,
                                AssistantText(
                                    id = newId(),
                                    createdAt = now(),
                                    text = "连续 $consecutiveFailures 轮工具调用均失败，已主动停止以避免陷入死循环。" +
                                        "请检查：命令是否正确、工作区路径是否存在、依赖是否已安装，或简化任务后重试。",
                                    totalMs = now() - startedAt,
                                ),
                            )
                            return RunResult.Failed("连续 $consecutiveFailures 轮工具调用均失败，已主动停止")
                        }
                    } else {
                        consecutiveFailures = 0
                    }
                }
            }
            round++
        }
        messageProjector.append(
            sessId,
            AssistantText(
                id = newId(),
                createdAt = now(),
                text = "已达到最大工具轮数（$maxRounds），请简化任务或分步进行。",
                totalMs = now() - startedAt,
            ),
        )
        return RunResult.Completed
    }

    /** 按最新用户消息中的 @提及 过滤动态 MCP 工具，并写入能力挂载记录 */
    private suspend fun resolveEffectiveModel(sessId: String, model: ModelConfig): ModelConfig {
        val msgs = messageProjector.messagesFlow(sessId).value
        val latestUserMessage = msgs.filterIsInstance<UserMessage>().lastOrNull()
        val latestUserText = latestUserMessage?.text.orEmpty()
        val mentionedNames = MentionExtractor.parse(latestUserText)
        val effectiveModel = if (mentionedNames.isNotEmpty()) {
            val matchedTools = model.dynamicMcpTools.filter { tool ->
                val sName = tool.serverName.lowercase()
                val sId = tool.serverId.lowercase()
                val tName = tool.name.lowercase()
                sName in mentionedNames || sId in mentionedNames || tName in mentionedNames
            }
            if (matchedTools.isNotEmpty()) model.copy(dynamicMcpTools = matchedTools) else model
        } else {
            model
        }
        capabilityWriter.writeIfMentioned(sessId, latestUserMessage?.id.orEmpty(), mentionedNames, effectiveModel)
        return effectiveModel
    }

    /** 流式调用 + 限流/网络退避重试。恢复不了的失败以 Failed 终态返回；取消与超过重试上限的原样抛出 */
    private suspend fun callProviderWithRetry(
        sessId: String,
        model: ModelConfig,
        sessionEntity: HarnessSessionEntity?,
        sessionWorkspace: String,
        operationId: String,
        assistantId: String,
        assistantAt: Long,
        round: Int,
        startedAt: Long,
        retryPolicy: RetryPolicy,
        metrics: RunMetrics,
    ): TurnProviderOutcome {
        val streamText = StreamBuffer()
        val streamReasoning = StreamBuffer(maxChars = ProviderClient.MAX_STREAM_REASONING_CHARS)
        var streamed: ChatResult? = null
        var netRetry = 0
        suspend fun assembleFor(requestModel: ModelConfig) = contextAssembler.assemble(
            sessId = sessId,
            model = requestModel,
            workspacePath = sessionWorkspace,
            projectTypeOverride = sessionEntity?.projectType.orEmpty(),
            thinkingMode = stateMirrors.requestThinkingMode(sessId),
        )
        fun estimateTokens(messages: List<ApiMessage>) = messages.sumOf { message ->
            ContextWindowPolicy.estimateTokens(message.content.orEmpty()) +
                ContextWindowPolicy.estimateTokens(message.reasoning_content.orEmpty()) +
                message.tool_calls.orEmpty().sumOf { call ->
                    ContextWindowPolicy.estimateTokens(call.function.name) +
                        ContextWindowPolicy.estimateTokens(call.function.arguments)
                } +
                message.imageUrls.size * ESTIMATED_IMAGE_TOKENS
        }
        // Context and prompt remain immutable during network retries. The configured model
        // window is authoritative: a transport heuristic must never persistently compact a
        // valid 128k/200k conversation down to 64k.
        val requestMessages = assembleFor(model)
        val originalEstimatedTokens = estimateTokens(requestMessages)
        val estimatedRequestTokens = estimateTokens(requestMessages)
        val maxNetworkRetries = maxNetworkRetriesFor(originalEstimatedTokens, retryPolicy.maxRetries)
        val maxAttempts = maxNetworkRetries + 1
        if (maxNetworkRetries < retryPolicy.maxRetries) {
            agentEventLogger.log(
                sessId,
                "LargeContextRetryPolicy",
                "估算输入约 $estimatedRequestTokens tokens，大上下文网络重试限制为 $maxNetworkRetries 次",
            )
        }
        while (streamed == null) {
            try {
                stateMirrors.setStatus(sessId, "等待模型首个响应（${netRetry + 1}/$maxAttempts）")
                operationCoordinator.providerIntent(
                    operationId = operationId,
                    effectId = assistantId,
                    round = round,
                    attempt = netRetry + 1,
                    maxAttempts = maxAttempts,
                )
                streamed = providerClient.chatStream(
                    model,
                    requestMessages,
                    onReasoning = { chunk ->
                        streamReasoning.append(chunk)
                        stateMirrors.setThinkingLive(sessId, true)
                        stateMirrors.recordThinkingObserved(sessId)
                        streamReasoning.publishIfDue(now(), ProviderClient.STREAM_PUBLISH_INTERVAL_MS)?.let {
                            messageProjector.streamReasoning(sessId, assistantId, assistantAt, it)
                        }
                    },
                    onToolProgress = { progress ->
                        stateMirrors.setThinkingLive(sessId, false)
                        stateMirrors.setStatus(
                            sessId,
                            if (progress.name == "write") {
                                "正在生成 write · +${progress.addedLines}"
                            } else {
                                "正在生成 edit · +${progress.addedLines} -${progress.deletedLines}"
                            },
                        )
                    },
                ) { chunk ->
                    stateMirrors.setStatus(sessId, "回复中")
                    streamText.append(chunk)
                    streamText.publishIfDue(now(), ProviderClient.STREAM_PUBLISH_INTERVAL_MS)?.let {
                        messageProjector.streamText(sessId, assistantId, assistantAt, it)
                    }
                }
                // 流式传输完毕，无条件刷新一次完整内容
                if (streamReasoning.length > 0) {
                    messageProjector.streamReasoning(sessId, assistantId, assistantAt, streamReasoning.toString())
                }
                if (streamText.length > 0) {
                    messageProjector.streamText(sessId, assistantId, assistantAt, streamText.toString())
                }
            } catch (cancellation: CancellationException) {
                agentEventLogger.log(sessId, "Cancelled", "用户主动取消执行")
                messageProjector.remove(sessId, assistantId)
                throw cancellation
            } catch (rateLimit: LlmRateLimitException) {
                currentCoroutineContext().ensureActive()
                if (rateLimit.quotaExhausted) {
                    stateMirrors.setThinkingLive(sessId, false)
                    agentEventLogger.log(sessId, "QuotaExhausted", rateLimit.message.orEmpty(), rateLimit)
                    // 移除空的流式气泡；错误通过 error state 展示，不写入消息历史，避免下一轮注入模型上下文
                    messageProjector.remove(sessId, assistantId)
                    val detail = rateLimit.message?.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
                    return TurnProviderOutcome.Failed("模型服务商额度已耗尽，无法继续执行。请充值、切换可用模型或更新 API Key。$detail")
                }
                netRetry++
                if (netRetry > maxNetworkRetries) throw rateLimit
                metrics.streamRetry()
                stateMirrors.setThinkingLive(sessId, false)
                val waitSeconds = rateLimit.retryAfterSeconds ?: (netRetry * RETRY_BACKOFF_SEC).coerceAtMost(60L)
                stateMirrors.setStatus(sessId, "请求受限，${waitSeconds} 秒后自动重试（$netRetry/$maxNetworkRetries）")
                agentEventLogger.log(sessId, "RateLimitRetry", "限流退避 ${waitSeconds}s，重试 $netRetry/$maxNetworkRetries", rateLimit)
                streamText.clear()
                streamReasoning.clear()
                messageProjector.streamText(sessId, assistantId, assistantAt, "")
                for (remaining in waitSeconds downTo 1L) {
                    currentCoroutineContext().ensureActive()
                    stateMirrors.setStatus(sessId, "请求受限，${remaining} 秒后自动重试（$netRetry/$maxNetworkRetries）")
                    delay(1000L.milliseconds)
                }
            } catch (io: IOException) {
                currentCoroutineContext().ensureActive()
                netRetry++
                agentEventLogger.log(sessId, "NetworkRetry", "网络中断重试 $netRetry/$maxNetworkRetries: ${io.message}", io)
                if (netRetry > maxNetworkRetries) throw io
                metrics.streamRetry()
                stateMirrors.setThinkingLive(sessId, false)
                stateMirrors.setStatus(sessId, "网络中断，重试中（$netRetry/$maxNetworkRetries）")
                streamText.clear()
                streamReasoning.clear()
                messageProjector.streamText(sessId, assistantId, assistantAt, "")
                delay(retryPolicy.delayForRetry(netRetry).milliseconds)
            } catch (throwable: Throwable) {
                stateMirrors.setThinkingLive(sessId, false)
                agentEventLogger.log(sessId, "ModelError", "LLM 调用失败: ${throwable.message}", throwable)
                if (streamText.length > 0) {
                    persistAssistant(
                        sessId,
                        assistantId,
                        assistantAt,
                        streamText.toString(),
                        streamReasoning.toString().ifBlank { null },
                        totalMs = now() - startedAt,
                        operationId = operationId,
                        round = round,
                    )
                } else {
                    // 移除空的流式气泡；错误通过 error state 展示，不写入消息历史
                    messageProjector.remove(sessId, assistantId)
                }
                return TurnProviderOutcome.Failed(friendly(throwable))
            }
        }
        messageProjector.endStreaming(sessId)
        return TurnProviderOutcome.Success(streamed, streamText.toString())
    }

    /** 回合结束后落库助手回复；无文本时只结算 usage 记录 */
    private suspend fun persistAssistantOutput(
        sessId: String,
        assistantId: String,
        assistantAt: Long,
        round: Int,
        startedAt: Long,
        operationId: String,
        result: ChatResult,
        effectiveModel: ModelConfig,
        displayText: String,
        hasToolCalls: Boolean,
    ) {
        if (displayText.isNotEmpty()) {
            persistAssistant(
                sessId,
                assistantId,
                assistantAt,
                displayText,
                result.reasoningContent,
                totalMs = if (!hasToolCalls) now() - startedAt else null,
                operationId = operationId,
                round = round,
                usage = result.usage,
                model = effectiveModel,
            )
        } else {
            val usageEntity = result.usage.takeIf { it.hasData }?.let {
                operationCoordinator.usageEntity(
                    sessionId = sessId,
                    operationId = operationId,
                    entryId = null,
                    provider = effectiveModel.provider,
                    modelId = effectiveModel.model,
                    usage = it,
                )
            }
            operationCoordinator.providerSettled(operationId, null, usage = usageEntity, round = round)
        }
    }

    /** 单轮工具数上限：超出部分回填空结果并提示模型，返回保留执行的前 maxToolsPerRound 个调用 */
    private suspend fun enforceToolRoundLimit(
        sessId: String,
        allCalls: List<ApiToolCallSpec>,
        maxToolsPerRound: Int,
        reasoning: String?,
    ): List<ApiToolCallSpec> {
        if (allCalls.size <= maxToolsPerRound) return allCalls
        val dropped = allCalls.size - maxToolsPerRound
        allCalls.drop(maxToolsPerRound).forEach { spec ->
            appendToolCallAndResult(
                sessId = sessId,
                spec = spec,
                tool = HarnessApiMapper.toolByName(spec.name),
                args = buildJsonObject {},
                reasoning = reasoning,
                rawToolName = spec.name.trim(),
                output = "本回合工具调用数量（${allCalls.size}）超过单轮上限（$maxToolsPerRound），已跳过本次多余的 $dropped 个调用。" +
                    "请拆分任务、分步调用工具，避免一次性发起过多工具请求。",
            )
        }
        return allCalls.take(maxToolsPerRound)
    }

    /** 失败工具调用的统一样板：先回放 ToolCall 气泡，再写回失败 ToolResult 让模型自我纠正 */
    private suspend fun appendToolCallAndResult(
        sessId: String,
        spec: ApiToolCallSpec,
        tool: HarnessTool,
        args: JsonObject,
        reasoning: String?,
        rawToolName: String?,
        output: String,
    ) {
        val toolCallId = ToolCallIdNormalizer.normalize(spec.id)
        messageProjector.append(
            sessId,
            ToolCall(
                id = toolCallId,
                createdAt = now(),
                tool = tool,
                args = args,
                reasoning = reasoning,
                rawToolName = rawToolName,
            ),
        )
        messageProjector.append(
            sessId,
            ToolResult(
                id = newId(),
                createdAt = now(),
                toolCallId = toolCallId,
                success = false,
                output = output,
            ),
        )
    }

    /** 参数解析 → 未知工具 → Schema 校验 → 死循环检测（串行 Phase A）→ 受限并发执行与结果落盘（Phase B）；返回本回合是否有成功调用 */
    private suspend fun executeToolCalls(
        sessId: String,
        specs: List<ApiToolCallSpec>,
        reasoning: String?,
        sessionWorkspace: String,
        autoCwd: Boolean,
        effectiveModel: ModelConfig,
        operationId: String,
        round: Int,
        metrics: RunMetrics,
    ): Boolean {
        val loopDetector = sessionLoopDetectors.getOrPut(sessId) { ToolCallLoopDetector() }

        // —— Phase A：串行校验。失败立即回写结构化错误让模型自纠；
        // 通过校验的调用收集后进入 Phase B 并发执行。
        val executable = mutableListOf<ExecutableToolCall>()
        specs.forEach { spec ->
            val tool = HarnessApiMapper.toolByName(spec.name)
            val toolNameTrimmed = spec.name.trim()
            // 工具名校验必须在参数解析之前：名字未知时（哪怕参数为空/非法）
            // 也要第一时间回写真实工具清单，否则模型会在"解析失败"上盲目重试。
            if (toolNameTrimmed.lowercase() !in KNOWN_TOOL_NAMES && !toolNameTrimmed.startsWith("mcp__")) {
                appendToolCallAndResult(
                    sessId = sessId,
                    spec = spec,
                    tool = tool,
                    args = buildJsonObject {},
                    reasoning = reasoning,
                    rawToolName = toolNameTrimmed,
                    output = unknownToolGuidance(toolNameTrimmed, effectiveModel),
                )
                loopDetector.recordSettled(toolNameTrimmed, buildJsonObject {}, success = false)
                metrics.toolCallRecorded(failed = true)
                return@forEach
            }
            val parsedArgs = try {
                // 无参数调用（arguments 为空/空白）是合法形态，兜底为空对象而不是解析失败
                if (spec.argumentsJson.isBlank()) {
                    buildJsonObject {}
                } else {
                    json.parseToJsonElement(spec.argumentsJson) as? JsonObject
                        ?: throw IllegalArgumentException("参数不是 JSON 对象")
                }
            } catch (parseError: Throwable) {
                // 尝试自动修复因 Token 截断或网络抖动未闭合的 JSON
                val repaired = runCatching {
                    json.parseToJsonElement(repairTruncatedJson(spec.argumentsJson)) as? JsonObject
                }.getOrNull()
                if (repaired != null && repaired.isNotEmpty()) {
                    repaired
                } else {
                    appendToolCallAndResult(
                        sessId = sessId,
                        spec = spec,
                        tool = tool,
                        args = buildJsonObject {},
                        reasoning = reasoning,
                        rawToolName = toolNameTrimmed,
                    output = "工具参数 JSON 解析失败（${friendly(parseError)}），参数可能被截断。" +
                        "请重新发起完整的工具调用，参数必须是合法的 JSON 对象。",
                )
                loopDetector.recordSettled(toolNameTrimmed, buildJsonObject {}, success = false)
                metrics.toolCallRecorded(failed = true)
                return@forEach
                }
            }
            var args = parsedArgs
            if (tool == HarnessTool.BASE && autoCwd && sessionWorkspace.isNotBlank() && args["cwd"] == null) {
                args = buildJsonObject {
                    put("cwd", sessionWorkspace)
                    args.forEach { (key, value) -> put(key, value) }
                }
            }
            // 执行前 JSON Schema 校验：必填/枚举/范围/格式/组合约束。
            // 失败时写回可读问题清单，让模型按 schema 自我纠正，而不是带着坏参数进入执行层。
            val schemaProblems = ToolSchemaValidator.problemsFor(toolNameTrimmed, args, effectiveModel.dynamicMcpTools)
            if (schemaProblems.isNotEmpty()) {
                // 常见错配定向提示：模型想把 url 交给通用 shell 时，直接指向正确的专用工具
                val urlHint = if (args.containsKey("url") && tool != HarnessTool.DOWNLOAD) {
                    "提示：url 是 download 工具的参数，下载网页/图片/文件请调用 download(url, destination)。"
                } else ""
                appendToolCallAndResult(
                    sessId = sessId,
                    spec = spec,
                    tool = tool,
                    args = args,
                    reasoning = reasoning,
                    rawToolName = toolNameTrimmed,
                    output = "工具参数校验未通过：${schemaProblems.joinToString("；")}。" +
                        "请按工具定义修正参数后重新调用，必填字段不可省略。$urlHint",
                )
                loopDetector.recordSettled(toolNameTrimmed, args, success = false)
                metrics.toolCallRecorded(failed = true)
                return@forEach
            }

            // 执行前死循环与重复无进展调用检测：阻断重复错误重试与空转
            val loopVerdict = loopDetector.evaluate(toolNameTrimmed, args)
            if (loopVerdict is ToolCallLoopDetector.LoopVerdict.Block) {
                appendToolCallAndResult(
                    sessId = sessId,
                    spec = spec,
                    tool = tool,
                    args = args,
                    reasoning = reasoning,
                    rawToolName = toolNameTrimmed,
                    output = loopVerdict.guidance,
                )
                loopDetector.recordSettled(toolNameTrimmed, args, success = false)
                metrics.toolCallRecorded(failed = true)
                return@forEach
            }

            loopDetector.recordIntent(toolNameTrimmed, args)
            executable += ExecutableToolCall(spec, tool, toolNameTrimmed, args)
            metrics.toolCallRecorded(failed = false)
        }

        if (executable.isEmpty()) return false

        // —— Phase B：受限并发执行。消息树落库（toolIntent / publishPersisted / toolSettled）
        // 依赖 lane.leafId 串链，必须串行，由 publicationMutex 保证；
        // 只读工具在并发许可内同时执行，变更类工具全局互斥。
        val publicationMutex = Mutex()
        val roundHadSuccess = AtomicBoolean(false)
        val approvalPauseRequested = AtomicBoolean(false)
        toolRoundDispatcher.dispatch(
            items = executable,
            isParallelSafe = { it.tool in PARALLEL_SAFE_TOOLS },
        ) { item, pause ->
            if (pause.isAborted()) return@dispatch
            val toolCall = ToolCall(
                // Preserve the provider protocol id prefix with a unique suffix across
                // execution, approval, persistence and the subsequent tool result.
                id = ToolCallIdNormalizer.normalize(item.spec.id),
                createdAt = now(),
                tool = item.tool,
                args = item.args,
                reasoning = reasoning,
                rawToolName = item.toolName,
            )
            val toolStart = now()
            val outcome = try {
                publicationMutex.withLock {
                    agentEventLogger.log(sessId, "ToolCall", "Tool=${item.tool.name}, RawName=${item.toolName}, Args=${item.args}")
                    operationCoordinator.toolIntent(
                        operationId = operationId,
                        message = toolCall,
                        payloadJson = item.spec.argumentsJson,
                        replay = ToolReplayPolicy.forTool(item.tool, item.toolName),
                        round = round,
                    )
                    messageProjector.publishPersisted(sessId, toolCall)
                    stateMirrors.setStatus(sessId, ToolStatusDescriber.describe(item.tool, item.args, item.toolName))
                }
                toolExecutor.execute(
                    toolCall,
                    sessId,
                    sessionWorkspace,
                    progressReporter = { progress -> stateMirrors.setStatus(sessId, progress) },
                    operationId = operationId,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                ToolResult(
                    id = newId(),
                    createdAt = now(),
                    toolCallId = toolCall.id,
                    success = false,
                    output = "工具执行异常：${friendly(throwable)}",
                )
            }
            val duration = now() - toolStart
            publicationMutex.withLock {
                agentEventLogger.log(sessId, "ToolResult", "Tool=${item.tool.name}, Success=${outcome.success}, Duration=${duration}ms, Output=${outcome.output.take(300)}")
                loopDetector.recordSettled(item.toolName, item.args, success = outcome.success)
                if (outcome.awaitingApproval) {
                    metrics.approvalRequested()
                    operationCoordinator.waitingApproval(operationId)
                    stateMirrors.setStatus(sessId, "等待用户批准")
                    // 触发审批暂停：中止本回合尚未开始的调用，在途调用自然完成后统一暂停，
                    // 与原串行实现"中途暂停、后续调用不执行"的语义一致。
                    pause.abort()
                    approvalPauseRequested.set(true)
                }
                val settledOutcome = outcome.copy(durationMs = duration)
                operationCoordinator.toolSettled(operationId, settledOutcome, round, toolName = toolCall.rawToolName ?: item.tool.name)
                messageProjector.publishPersisted(sessId, settledOutcome)
                if (outcome.success) roundHadSuccess.set(true)
                metrics.toolCallRecorded(failed = !outcome.success)
                touchSession(sessId)
            }
        }
        if (approvalPauseRequested.get()) throw ApprovalPauseException()
        return roundHadSuccess.get()
    }

    private suspend fun drainSteeringMessages(sessId: String): Int {
        val queued = promptQueueManager.consume(sessId, PromptQueue.STEER)
        refreshPendingProjection(sessId)
        queued.forEach { message ->
            agentEventLogger.log(sessId, "SteeringMessage", message.text)
            messageProjector.publishPersisted(sessId, message)
        }
        return queued.size
    }

    private fun repairTruncatedJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "{}"
        var inString = false
        var escape = false
        val stack = mutableListOf<Char>()
        for (ch in trimmed) {
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\') {
                escape = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == '{' || ch == '[') stack.add(ch)
                else if (ch == '}' && stack.isNotEmpty() && stack.last() == '{') stack.removeAt(stack.lastIndex)
                else if (ch == ']' && stack.isNotEmpty() && stack.last() == '[') stack.removeAt(stack.lastIndex)
            }
        }
        val builder = StringBuilder(trimmed)
        if (inString) builder.append('"')
        while (stack.isNotEmpty()) {
            val open = stack.removeAt(stack.lastIndex)
            if (open == '{') builder.append('}')
            else if (open == '[') builder.append(']')
        }
        return builder.toString()
    }

    private fun friendly(throwable: Throwable): String =
        throwable.message?.take(200) ?: throwable::class.simpleName.orEmpty()

    private suspend fun persistAssistant(
        sessId: String,
        id: String,
        createdAt: Long,
        text: String,
        reasoning: String? = null,
        totalMs: Long? = null,
        operationId: String? = null,
        round: Int = 0,
        usage: ChatUsage? = null,
        model: ModelConfig? = null,
    ) {
        val message = AssistantText(
            id = id,
            createdAt = createdAt,
            text = text,
            reasoning = reasoning,
            totalMs = totalMs,
            modelId = model?.model,
            providerId = model?.provider,
            promptTokens = usage?.inputTokens?.takeIf { it > 0 }?.toInt(),
            completionTokens = usage?.outputTokens?.takeIf { it > 0 }?.toInt(),
            cachedTokens = usage?.cacheReadTokens?.takeIf { it > 0 }?.toInt(),
        )
        if (operationId != null) {
            val usageEntity = usage?.takeIf { it.hasData }?.let {
                operationCoordinator.usageEntity(
                    sessionId = sessId,
                    operationId = operationId,
                    entryId = id,
                    provider = model?.provider,
                    modelId = model?.model,
                    usage = it,
                )
            }
            operationCoordinator.providerSettled(operationId, message, usage = usageEntity, round = round)
        } else {
            messageStore.append(sessId, message)
        }
        messageProjector.publishPersisted(sessId, message)
    }

    private suspend fun touchSession(sessId: String) {
        sessionDao.touch(sessId, System.currentTimeMillis())
    }

    private fun startForegroundServiceSafe() {
        foregroundLauncher.start()
    }

    private fun newId(): String = UUID.randomUUID().toString()
    private fun now(): Long = System.currentTimeMillis()

    /** Approve or reject a frozen tool call, then resume the same Agent session. */
    fun resolveApproval(requestId: String, approved: Boolean) {
        logger.i("resolveApproval called: requestId=$requestId approved=$approved")
        loopScope.launch {
            val request = approvalRepository.find(requestId)
            if (request == null) {
                logger.w("resolveApproval: request not found: $requestId")
                return@launch
            }
            val sessId = request.sessionId
            if (request.status != top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_PENDING) {
                logger.w("resolveApproval: request not pending (status=${request.status}), ignoring: $requestId")
                return@launch
            }
            logger.i("resolveApproval: waiting for prior session job, sessId=$sessId")
            // The original loop may still be unwinding after it persisted the request.
            // Wait for it before claiming the session slot.
            sessionJobs[sessId]?.takeIf { it.isActive }?.join()

            // —— 审批有效性校验：过期 / 参数摘要 / 工作区 / operation 归属 ——
            // 防止“用户批准的是旧参数、旧环境下的请求，实际执行的却是别的东西”。
            val verdict = resumePolicy.evaluate(request, approved)
            if (!approvalRepository.claimPending(request.id, verdict.claimStatus)) return@launch
            val durableTaskId = agentTaskStateMachine.activeForSession(sessId)
                ?.takeIf { it.status == top.wkbin.taixu.core.database.task.AgentTaskStatus.WAITING_APPROVAL }
                ?.id

            // Approval resumption is the legitimate successor to a WAITING_APPROVAL run;
            // claim the slot unconditionally (that state still reports busy to senders).
            startClaimedSessionRun(sessId, durableTaskId) {
                var approvalResultPersisted = false
                try {
                    val result = if (verdict.isInvalid) {
                        ToolResult(
                            id = newId(),
                            createdAt = now(),
                            toolCallId = request.toolCallId,
                            success = false,
                            output = resumePolicy.invalidationResultMessage(verdict.invalidationReason.orEmpty()),
                        )
                    } else if (approved) {
                        val args = json.parseToJsonElement(request.argumentsJson) as? JsonObject
                            ?: error("审批参数不是 JSON 对象")
                        val tool = HarnessApiMapper.toolByName(request.toolName)
                        toolExecutor.execute(
                            ToolCall(request.toolCallId, request.createdAt, tool, args, rawToolName = request.toolName),
                            sessId,
                            request.workspace,
                            bypassApproval = true,
                            operationId = request.operationId,
                        )
                    } else {
                        ToolResult(
                            id = newId(),
                            createdAt = now(),
                            toolCallId = request.toolCallId,
                            success = false,
                            output = resumePolicy.rejectionResultMessage(),
                        )
                    }
                    val activeOperation = operationCoordinator.active(sessId)
                    if (activeOperation != null) {
                        operationCoordinator.toolSettled(activeOperation.id, result, round = 0, toolName = request.toolName)
                        messageProjector.publishPersisted(sessId, result)
                    } else {
                        messageProjector.append(sessId, result)
                    }
                    if (!verdict.isInvalid) {
                        approvalRepository.mark(
                            request.id,
                            resumePolicy.finalStatus(approved, result.success),
                        )
                    }
                    approvalResultPersisted = true
                    runLoopInternal(sessId, startedAt = now(), taskId = durableTaskId)
                } catch (cancellation: CancellationException) {
                    if (!approvalResultPersisted) {
                        withContext(NonCancellable) {
                            approvalRepository.mark(
                                request.id,
                                if (approved) top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_FAILED
                                else top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_REJECTED,
                            )
                            repairDanglingToolCalls(sessId, interrupted = true)
                        }
                    }
                    throw cancellation
                } catch (_: ApprovalPauseException) {
                    // 批准后继续循环，下一个工具又触发了审批门控——这是正常流程，不是失败。
                    // 外层 executeSessionRun 会把状态置为 WAITING_APPROVAL，等待用户下一次批准。
                    RunResult.WaitingApproval
                } catch (throwable: Throwable) {
                    logger.e("Approval resolution failed for request ${request.id}", throwable)
                    if (!approvalResultPersisted) {
                        approvalRepository.mark(request.id, top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_FAILED)
                        messageProjector.append(
                            sessId,
                            ToolResult(
                                id = newId(),
                                createdAt = now(),
                                toolCallId = request.toolCallId,
                                success = false,
                                output = "批准操作执行失败：${friendly(throwable)}",
                            ),
                        )
                    }
                    RunResult.Failed(throwable.message ?: "审批操作执行失败：${throwable::class.simpleName}")
                }
            }
        }
    }

    /**
     * 未知工具的可纠正错误：不写死固定话术，而是列出当前真实可用的全部工具名
     * （原生工具 + 已启用 MCP 的实际 API 名），并按编辑距离提示最接近的候选。
     * 模型幻觉出工具名（如 fetchWebContent）时能一次拿到正确名字，不再反复编造。
     */
    private fun unknownToolGuidance(called: String, model: ModelConfig): String {
        val nativeTools = listOf(
            "read", "write", "edit", "base", "process", "host", "download", "memory",
            "plan", "scratchpad", "history_search", "history_read", "build_script",
            "invoke_subagent", "load_rule",
        )
        val mcpTools = model.dynamicMcpTools
        val mcpList = if (mcpTools.isEmpty()) {
            "（当前没有已启用的 MCP 工具）"
        } else {
            mcpTools.joinToString("；") { tool ->
                "${McpToolApiName.encode(tool)}（${tool.serverName}·${tool.name}）"
            }
        }
        val target = called.lowercase()
        val nearest = (nativeTools + mcpTools.map { McpToolApiName.encode(it) })
            .mapNotNull { candidate ->
                val distance = levenshtein(target, candidate.lowercase())
                if (distance <= (target.length / 2).coerceAtLeast(3)) candidate to distance else null
            }
            .minByOrNull { it.second }
            ?.first
        return buildString {
            append("未知工具：$called。工具名不可编造或猜测，必须从下列清单中原样选取。")
            append("原生工具：${nativeTools.joinToString(" / ")}。")
            append("已启用 MCP 工具：$mcpList。")
            nearest?.let { append("最接近的候选是 $it，是否想调用它？") }
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(
                    prev[j] + 1,
                    current[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            prev = current
        }
        return prev[b.length]
    }

    companion object {
        const val MAX_ROUNDS = 200
        // MCP 的 apiName "mcp" 只是历史回放别名，不是模型可直接调用的工具；
        // 剔除后模型误调 "mcp" 会落入 unknownToolGuidance，拿到真实 mcp__ 工具清单自我纠正。
        val KNOWN_TOOL_NAMES: Set<String> = HarnessTool.entries
            .filter { it != HarnessTool.MCP }
            .map { HarnessApiMapper.apiName(it) }
            .toSet() + "subagent"
        private const val LARGE_REQUEST_TOKEN_THRESHOLD = 64_000
        private const val LARGE_REQUEST_MAX_RETRIES = 1
        private const val ESTIMATED_IMAGE_TOKENS = 1_000

        internal fun maxNetworkRetriesFor(estimatedRequestTokens: Int, configuredRetries: Int): Int =
            if (estimatedRequestTokens >= LARGE_REQUEST_TOKEN_THRESHOLD) {
                minOf(configuredRetries, LARGE_REQUEST_MAX_RETRIES)
            } else {
                configuredRetries
            }
        const val RETRY_BACKOFF_MS = 1_000L
        const val RETRY_BACKOFF_SEC = 2L

        /**
         * 可并发执行的只读/低风险工具白名单：互不共享可变状态（Room 由 SQLite 串行化写入）。
         * 其余工具（write/edit/base/process/host/download/build_script/subagent/mcp）具有
         * 外部副作用，执行时全局互斥。
         */
        private val PARALLEL_SAFE_TOOLS: Set<HarnessTool> = setOf(
            HarnessTool.READ,
            HarnessTool.HISTORY_SEARCH,
            HarnessTool.HISTORY_READ,
            HarnessTool.LOAD_RULE,
            HarnessTool.MEMORY,
            HarnessTool.PLAN,
            HarnessTool.SCRATCHPAD,
        )

    }
}

private class ApprovalPauseException : RuntimeException()
