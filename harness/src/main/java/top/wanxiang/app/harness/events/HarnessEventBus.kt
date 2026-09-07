package top.wanxiang.app.harness.events

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Harness 运行时结构化事件。
 *
 * 事件即遥测数据源：UI 面板、用量统计、开发者日志均订阅同一条流，
 * 而不是各自在运行链路里插桩。字段刻意保持最小——订阅方需要的
 * 细节（参数、输出、usage 明细）从各自持久化表读，事件只携带路由键。
 */
sealed interface HarnessEvent {
    val sessionId: String
    val timestamp: Long

    data class OperationStarted(
        override val sessionId: String,
        override val timestamp: Long,
        val operationId: String,
        val laneName: String,
    ) : HarnessEvent

    data class OperationFinished(
        override val sessionId: String,
        override val timestamp: Long,
        val operationId: String,
        val laneName: String,
        val outcome: String,
        val detail: String? = null,
    ) : HarnessEvent

    data class ProviderRoundStarted(
        override val sessionId: String,
        override val timestamp: Long,
        val operationId: String,
        val round: Int,
        val attempt: Int,
        val modelId: String? = null,
    ) : HarnessEvent

    data class ProviderRoundSettled(
        override val sessionId: String,
        override val timestamp: Long,
        val operationId: String,
        val round: Int,
        val entryId: String?,
        val inputTokens: Long,
        val outputTokens: Long,
    ) : HarnessEvent

    data class ToolCallStarted(
        override val sessionId: String,
        override val timestamp: Long,
        val operationId: String,
        val toolCallId: String,
        val toolName: String,
    ) : HarnessEvent

    data class ToolCallSettled(
        override val sessionId: String,
        override val timestamp: Long,
        val operationId: String,
        val toolCallId: String,
        val toolName: String,
        val success: Boolean,
        val durationMs: Long? = null,
    ) : HarnessEvent

    data class ApprovalRequested(
        override val sessionId: String,
        override val timestamp: Long,
        val operationId: String?,
        val approvalRequestId: String,
        val toolName: String,
        val riskLevel: String,
    ) : HarnessEvent

    data class RecoveryApplied(
        override val sessionId: String,
        override val timestamp: Long,
        val operationId: String?,
        val outcome: String,
        val detail: String? = null,
    ) : HarnessEvent

    /**
     * 工具执行时检测到缺少 Android 运行时权限，需要引导用户到系统设置授权。
     * UI 层订阅此事件后弹出引导（如 Snackbar + 跳转按钮），不阻塞工具执行链路。
     */
    data class PermissionRequired(
        override val sessionId: String,
        override val timestamp: Long,
        val permission: String,
        val reason: String,
    ) : HarnessEvent

    /**
     * 双智能体 Planner 规划步骤进度事件。
     * UI 规划树/时间线卡片订阅此事件进行实时状态呈现（未开始、进行中、已完成、失败）。
     */
    data class PlanStepProgress(
        override val sessionId: String,
        override val timestamp: Long,
        val stepId: String,
        val title: String,
        val status: String,
        val dependencies: List<String> = emptyList(),
        val resultSummary: String? = null,
    ) : HarnessEvent
}

/**
 * 进程内事件总线。尽力投递：订阅者处理慢不阻塞运行链路，
 * 溢出时丢弃最旧事件（事件是观察侧数据，不是控制面信号）。
 */
@Singleton
class HarnessEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<HarnessEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<HarnessEvent> = _events.asSharedFlow()

    fun emit(event: HarnessEvent) {
        _events.tryEmit(event)
    }
}
