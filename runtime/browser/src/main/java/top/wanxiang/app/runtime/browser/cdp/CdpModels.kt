package top.wanxiang.app.runtime.browser.cdp

import kotlinx.serialization.Serializable

/**
 * CDP（Chrome DevTools Protocol）域模型：target、断点、调用栈、暂停态。
 *
 * 与 [BrowserEvent] 的 DebugPaused/DebugResumed 事件共享这些类型
 * （BrowserEvent 侧以组合而非继承引用，避免跨文件 sealed 限制）。
 */
@Serializable
data class CdpTargetInfo(
    val id: String,
    val type: String,           // "page" / "service_worker" / "shared_worker" / "webview" ...
    val title: String = "",
    val url: String = "",
    val webSocketDebuggerUrl: String = "",
)

@Serializable
data class DebugScope(
    val type: String,           // local / closure / global / ...
    val name: String = "",
    val objectId: String,
)

@Serializable
data class DebugCallFrame(
    val callFrameId: String,
    val functionName: String,
    val url: String,
    val lineNumber: Int,        // 0-based（CDP 原生）
    val columnNumber: Int,      // 0-based（CDP 原生）
    val scopes: List<DebugScope> = emptyList(),
)

/** Debugger.paused 的解析结果；at 为宿主收到时刻。 */
@Serializable
data class DebugPausedState(
    val tabId: String,
    val reason: String,                     // breakpoint / exception / etc.
    val hitBreakpoints: List<String> = emptyList(),
    val callFrames: List<DebugCallFrame> = emptyList(),
    val at: Long = System.currentTimeMillis(),
)

@Serializable
data class DebugBreakpoint(
    val id: String,             // CDP 返回的 breakpointId
    val tabId: String,          // 本地归属（CDP 断点本身按 url 全局命中，归属用于多 tab 管理）
    val url: String,
    val lineNumber: Int,        // 0-based（CDP 原生）
    val columnNumber: Int = 0,
    val condition: String = "",
)

enum class DebugStep { OVER, INTO, OUT }

/** CdpSession.send 的命令失败；message 为 CDP error 原文，透传给 agent。 */
class CdpCommandException(val code: Int, message: String) : Exception(message)
