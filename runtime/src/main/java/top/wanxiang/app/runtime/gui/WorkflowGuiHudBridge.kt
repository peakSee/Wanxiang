package top.wanxiang.app.runtime.gui

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Cross-module bridge for the workflow HUD overlay.
 * Hide the overlay while touching / dumping the screen so uiautomator does not
 * include floating-window nodes; show again while the model thinks or the node waits.
 */
@Singleton
class WorkflowGuiHudBridge @Inject constructor() {
    enum class Phase {
        IDLE,
        THINKING,
        ACTING,
        SUCCESS,
        FAILED,
        CANCELLED,
    }

    data class Session(
        val executionId: String,
        val workflowName: String,
        val nodeTitle: String = "",
        val phase: Phase = Phase.THINKING,
        val stepLabel: String = "准备中",
        val detail: String = "",
        /** When false the overlay window must be detached (screen ops in progress). */
        val overlayVisible: Boolean = true,
        val active: Boolean = true,
    )

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val screenOpDepth = AtomicInteger(0)
    private val stopRequestedFor = AtomicReference<String?>(null)
    private val cancelAction = AtomicReference<(() -> Unit)?>(null)

    fun start(executionId: String, workflowName: String) {
        stopRequestedFor.set(null)
        cancelAction.set(null)
        screenOpDepth.set(0)
        _session.value = Session(
            executionId = executionId,
            workflowName = workflowName,
            phase = Phase.THINKING,
            stepLabel = "工作流已启动",
            overlayVisible = true,
            active = true,
        )
    }

    fun bindCancel(executionId: String, cancel: () -> Unit) {
        val current = _session.value
        if (current != null && current.executionId == executionId) {
            cancelAction.set(cancel)
        }
    }

    fun updateNode(nodeTitle: String, stepLabel: String, detail: String = "") {
        _session.update { current ->
            current?.copy(
                nodeTitle = nodeTitle,
                stepLabel = stepLabel,
                detail = detail.take(240),
                phase = if (current.phase == Phase.ACTING) Phase.ACTING else Phase.THINKING,
                overlayVisible = screenOpDepth.get() == 0,
            )
        }
    }

    fun thinking(stepLabel: String, detail: String = "") {
        _session.update { current ->
            current?.copy(
                phase = Phase.THINKING,
                stepLabel = stepLabel,
                detail = detail.take(240),
                overlayVisible = screenOpDepth.get() == 0,
            )
        }
    }

    /**
     * Hide overlay before screen dump / input. Nested calls are refcounted.
     * First enter waits briefly so WindowManager can detach the view.
     */
    suspend fun beginScreenOp(stepLabel: String = "操作屏幕中…") {
        val depth = screenOpDepth.incrementAndGet()
        _session.update { current ->
            current?.copy(
                phase = Phase.ACTING,
                stepLabel = stepLabel,
                overlayVisible = false,
            )
        }
        if (depth == 1) delay(OVERLAY_DETACH_MS)
    }

    fun endScreenOp(resumeLabel: String? = null) {
        val depth = screenOpDepth.updateAndGet { (it - 1).coerceAtLeast(0) }
        _session.update { current ->
            if (current == null) return@update null
            if (depth > 0) {
                current.copy(overlayVisible = false, phase = Phase.ACTING)
            } else {
                current.copy(
                    overlayVisible = true,
                    phase = Phase.THINKING,
                    stepLabel = resumeLabel ?: current.stepLabel,
                )
            }
        }
    }

    fun finish(success: Boolean, message: String) {
        screenOpDepth.set(0)
        _session.update { current ->
            current?.copy(
                phase = if (success) Phase.SUCCESS else Phase.FAILED,
                stepLabel = if (success) "已完成" else "失败",
                detail = message.take(280),
                overlayVisible = true,
                active = false,
            )
        }
        cancelAction.set(null)
    }

    fun cancelled(message: String = "已停止") {
        screenOpDepth.set(0)
        _session.update { current ->
            current?.copy(
                phase = Phase.CANCELLED,
                stepLabel = "已停止",
                detail = message.take(280),
                overlayVisible = true,
                active = false,
            )
        }
        cancelAction.set(null)
    }

    fun requestStop() {
        val id = _session.value?.executionId ?: return
        stopRequestedFor.set(id)
        cancelAction.getAndSet(null)?.invoke()
        cancelled("用户点击停止")
    }

    fun isStopRequested(): Boolean {
        val session = _session.value ?: return false
        return stopRequestedFor.get() == session.executionId
    }

    fun isStopRequested(executionId: String): Boolean =
        stopRequestedFor.get() == executionId

    fun dismiss() {
        cancelAction.set(null)
        screenOpDepth.set(0)
        _session.value = null
    }

    private companion object {
        const val OVERLAY_DETACH_MS = 120L
    }
}
