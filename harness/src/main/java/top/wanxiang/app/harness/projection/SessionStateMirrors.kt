package top.wanxiang.app.harness.projection

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import top.wanxiang.app.core.model.SessionRunState
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.ToolCall

/**
 * 会话级运行状态与前台聚焦镜像：状态机（就绪/运行中/等待审批…）、动作描述、
 * 错误文案、思考中的实时指示，以及推理模型是否进入思考模式的标记。
 *
 * - 每会话一份底层存储；前台会话的值同时镜像到全局单值流供 UI / 通知消费。
 * - 纯内存组件，不持有任何业务依赖；写入语义与原 HarnessLoop 内联实现一致。
 */
@Singleton
class SessionStateMirrors @Inject constructor(
    private val tracker: CurrentSessionTracker,
) {

    private val _sessionRunStates = MutableStateFlow<Map<String, SessionRunState>>(emptyMap())
    /** 全局所有会话的运行状态映射（供会话抽屉、状态点等观察） */
    val sessionRunStates: StateFlow<Map<String, SessionRunState>> = _sessionRunStates.asStateFlow()

    private val _sessionStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 全局各会话当前的动作描述状态 */
    val sessionStatuses: StateFlow<Map<String, String>> = _sessionStatuses.asStateFlow()

    private val sessionErrors = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    private val sessionThinkingLives = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()

    /** 推理模型思考标记（reasoning 是否出现过），用于下一轮请求的上下文组装。 */
    private val thinkingModes = ConcurrentHashMap<String, Boolean>()

    // ---- 当前前台聚焦会话的全局响应式镜像 ----
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _thinkingLive = MutableStateFlow(false)
    /** 推理模型思考中（reasoning 正在流式上屏）。 */
    val thinkingLive: StateFlow<Boolean> = _thinkingLive.asStateFlow()

    fun ensureFlows(sessionId: String) {
        sessionErrors[sessionId] = MutableStateFlow(null)
        sessionThinkingLives[sessionId] = MutableStateFlow(false)
    }

    fun removeSession(sessionId: String) {
        sessionErrors.remove(sessionId)
        sessionThinkingLives.remove(sessionId)
        thinkingModes.remove(sessionId)
        _sessionRunStates.update { it - sessionId }
        _sessionStatuses.update { it - sessionId }
    }

    fun setStatus(sessionId: String, statusText: String?) {
        val normalized = statusText?.takeIf { it.isNotBlank() }
        if (_sessionStatuses.value[sessionId] == normalized) {
            if (tracker.isForeground(sessionId) && _status.value != normalized) _status.value = normalized
            return
        }
        _sessionStatuses.update { map ->
            if (normalized == null) map - sessionId else map + (sessionId to normalized)
        }
        if (tracker.isForeground(sessionId)) {
            _status.value = normalized
        }
    }

    fun lastStatus(sessionId: String): String? = _sessionStatuses.value[sessionId]

    fun setError(sessionId: String, errorText: String?) {
        errorFlowOf(sessionId).value = errorText
        if (tracker.isForeground(sessionId)) {
            _error.value = errorText
        }
    }

    private fun errorFlowOf(sessionId: String): MutableStateFlow<String?> =
        sessionErrors.getOrPut(sessionId) { MutableStateFlow(null) }

    fun errorOf(sessionId: String): String? = sessionErrors[sessionId]?.value

    /** 该会话思考指示的当前读数（未初始化视为 false）。 */
    fun thinkingLiveOf(sessionId: String): Boolean = sessionThinkingLives[sessionId]?.value ?: false

    fun setRunState(sessionId: String, state: SessionRunState) {
        _sessionRunStates.update { it + (sessionId to state) }
        if (tracker.isForeground(sessionId)) {
            _running.value = (state == SessionRunState.RUNNING)
        }
    }

    fun runStateOf(sessionId: String): SessionRunState? = _sessionRunStates.value[sessionId]

    fun isWaitingApproval(sessionId: String): Boolean =
        _sessionRunStates.value[sessionId] == SessionRunState.WAITING_APPROVAL

    /**
     * 流式思考指示的开关：更新会话级 flow，并对前台会话同步全局镜像。
     */
    fun setThinkingLive(sessionId: String, live: Boolean) {
        thinkingLiveFlowOf(sessionId).value = live
        if (tracker.isForeground(sessionId)) {
            _thinkingLive.value = live
        }
    }

    /**
     * 运行收尾：折叠会话级思考指示并复位前台镜像。
     * @return 结束前是否处于 WAITING_APPROVAL（调用方据此跳过后续排队排空）。
     */
    fun onRunFinished(sessionId: String): Boolean {
        val waitingApproval = isWaitingApproval(sessionId)
        if (!waitingApproval) setStatus(sessionId, null)
        if (tracker.isForeground(sessionId)) {
            _running.value = false
            if (!waitingApproval) _status.value = null
            _thinkingLive.value = false
        }
        sessionThinkingLives[sessionId]?.value = false
        return waitingApproval
    }

    /** loadSession 恢复前台错误镜像（无对应的空闲态不产生副作用）。 */
    fun restoreForegroundError(sessionId: String, value: String?) {
        setError(sessionId, value)
    }

    fun setForegroundRunning(active: Boolean) {
        _running.value = active
    }

    fun resetForeground() {
        _running.value = false
        _error.value = null
        _status.value = null
        _thinkingLive.value = false
    }

    private fun thinkingLiveFlowOf(sessionId: String): MutableStateFlow<Boolean> =
        sessionThinkingLives.getOrPut(sessionId) { MutableStateFlow(false) }

    /** 从历史消息推断该会话是否启用过推理输出（loadSession 时恢复）。 */
    fun recordThinkingModeFromHistory(sessionId: String, history: List<HarnessMessage>) {
        thinkingModes[sessionId] = history.any { message ->
            (message as? AssistantText)?.reasoning != null || (message as? ToolCall)?.reasoning != null
        }
    }

    /**
     * 运行中实时观测到 reasoning 输出时即时翻转标记。
     * 若只在 loadSession 时从历史推断，本进程内新对话的第二轮仍读不到，
     * 思考链占位（reasoning_content）会断供。
     */
    fun recordThinkingObserved(sessionId: String) {
        if (thinkingModes[sessionId] != true) thinkingModes[sessionId] = true
    }

    /** 下一轮请求上下文组装所需的思考模式读数。 */
    fun requestThinkingMode(sessionId: String): Boolean = thinkingModes[sessionId] ?: false
}
