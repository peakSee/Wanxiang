package top.wanxiang.app.runtime.browser

import kotlinx.serialization.Serializable
import top.wanxiang.app.core.browser.PageSnapshot
import top.wanxiang.app.core.model.ToolImageRef
import top.wanxiang.app.runtime.browser.cdp.DebugPausedState
import top.wanxiang.app.runtime.browser.hook.HookHitRecord

/**
 * 浏览器侧事件总线传输的事件（密封接口）。
 *
 * 设计要点：
 * - 全部事件不可变；
 * - 由 [BrowserEventBus] 统一 dispatch，订阅方可用 [kotlinx.coroutines.flow.StateFlow] 拿到最近一次状态；
 * - 跨模块：UI 订阅 [SnapshotUpdated] 实时显示 ref；harness 可订阅用于 session 摘要回灌。
 */
sealed interface BrowserEvent {
    val tabId: String
    val at: Long

    @Serializable
    data class PageChanged(
        override val tabId: String,
        val url: String,
        val title: String,
        override val at: Long = System.currentTimeMillis()
    ) : BrowserEvent

    @Serializable
    data class SnapshotUpdated(
        override val tabId: String,
        val snapshot: PageSnapshot,
        override val at: Long = System.currentTimeMillis()
    ) : BrowserEvent

    @Serializable
    data class ConsoleLogged(
        override val tabId: String,
        val level: String,
        val message: String,
        override val at: Long = System.currentTimeMillis()
    ) : BrowserEvent

    @Serializable
    data class NetworkCaptured(
        override val tabId: String,
        val request: CapturedRequest,
        override val at: Long = System.currentTimeMillis()
    ) : BrowserEvent

    /** 注入式 hook 命中（fetch/XHR/函数/属性/WebSocket 等）。 */
    @Serializable
    data class HookHit(
        override val tabId: String,
        val hit: HookHitRecord,
        override val at: Long = System.currentTimeMillis()
    ) : BrowserEvent

    /** CDP Debugger 暂停：断点命中 / 异常等；调用栈见 [state]。 */
    @Serializable
    data class DebugPaused(
        override val tabId: String,
        val state: DebugPausedState,
        override val at: Long = System.currentTimeMillis()
    ) : BrowserEvent

    /** CDP Debugger 恢复执行（resume/step 之后）。 */
    @Serializable
    data class DebugResumed(
        override val tabId: String,
        override val at: Long = System.currentTimeMillis()
    ) : BrowserEvent

    @Serializable
    data class UserInteractionHappened(
        override val tabId: String,
        val ref: String,
        val kind: String,
        override val at: Long = System.currentTimeMillis()
    ) : BrowserEvent

    @Serializable
    data class ScreenshotSaved(
        override val tabId: String,
        val imageRef: ToolImageRef,
        override val at: Long = System.currentTimeMillis()
    ) : BrowserEvent

    /** WebView 渲染进程崩溃 / 被系统回收；对应 tab 已被销毁，需要重新 openTab。 */
    @Serializable
    data class RenderProcessGone(
        override val tabId: String,
        val didCrash: Boolean,
        override val at: Long = System.currentTimeMillis()
    ) : BrowserEvent
}

/** 单条捕获的网络请求（限制字段避免过度暴露）。 */
@Serializable
data class CapturedRequest(
    val id: String,
    val tabId: String,
    val url: String,
    val method: String,
    val statusCode: Int = 0,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val startedAt: Long,
    val finishedAt: Long = 0L,
    val errorMessage: String = "",
    /** "native"（shouldInterceptRequest）/"js"（hook 引擎）/"cdp"（CDP Fetch 域，覆盖 Worker 与子资源）。 */
    val source: String = "native",
    val initiator: String = "",
    val durationMs: Long = 0L,
    val requestSize: Long = 0L,
    val responseSize: Long = 0L,
    val hasRequestBody: Boolean = false,
    val hasResponseBody: Boolean = false,
    val ruleId: String = "",
    val actionTaken: String = "",
)

@Serializable
data class ConsoleLine(
    val tabId: String,
    val level: String,
    val message: String,
    val at: Long = System.currentTimeMillis()
)
