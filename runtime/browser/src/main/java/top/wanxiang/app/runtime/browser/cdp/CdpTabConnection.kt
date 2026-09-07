package top.wanxiang.app.runtime.browser.cdp

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.wanxiang.app.runtime.browser.BrowserEventBus
import top.wanxiang.app.runtime.browser.hook.HookRuleStore
import top.wanxiang.app.runtime.browser.hook.NetworkBodyStore

/**
 * 单 tab 的完整 CDP 连接：一个 WebSocket 会话承载 page 会话 + auto-attach 的 worker 子会话。
 *
 * 组成：
 * - [debug]：Debugger 域状态机（断点 / paused / 调用栈 / 求值）——仅 page 会话；
 * - [fetch]：Fetch 域拦截（page + worker 子会话；worker 只挂 Fetch 不挂 Debugger）；
 * - [workerSessions]：Target.setAutoAttach(flatten) 派生的 worker 子会话表。
 *
 * 事件路由：sessionId == null → page 事件（Debugger/Fetch）；
 * sessionId 非空 → worker 事件（仅 Fetch.requestPaused）+ target 生灭管理。
 */
class CdpTabConnection(
    val tabId: String,
    val target: CdpTargetInfo,
    private val session: CdpSession,
    private val store: HookRuleStore,
    private val bodyStore: NetworkBodyStore,
    private val eventBus: BrowserEventBus,
) : CdpSession.EventListener {

    val debug = CdpDebugController(tabId, session, eventBus)
    val fetch = CdpFetchInterceptor(tabId, session, store, bodyStore, eventBus)

    /** worker 子会话：sessionId → target 信息。 */
    private val workerSessions = ConcurrentHashMap<String, CdpTargetInfo>()

    val workerCount: Int get() = workerSessions.size

    /**
     * attach 后装配：Debugger 域 + 断点重放 + worker auto-attach + Fetch 拦截。
     * [existingBreakpoints] 为重 attach 时宿主保存的断点快照。
     */
    suspend fun setup(existingBreakpoints: List<DebugBreakpoint>) {
        // 页面主会话：Runtime/Debugger + 断点重放
        debug.setup(existingBreakpoints)
        // worker / 子 target 自动挂载（flatten：事件经同一 WS 的 sessionId 路由）
        runCatching {
            session.send(
                "Target.setAutoAttach",
                buildJsonObject {
                    put("autoAttach", true)
                    put("waitForDebuggerOnStart", false)
                    put("flatten", true)
                },
            )
        }
        // Fetch 拦截（规则空时内部 disable 零开销）
        fetch.enable()
    }

    /**
     * 分离连接。[resumeFirst]=true 时先恢复执行再拆卸（防止页面永久冻结）；
     * 断点表返回给宿主供重 attach 重放。
     */
    suspend fun detach(resumeFirst: Boolean = true): List<DebugBreakpoint> {
        val breakpoints = debug.breakpoints()
        if (resumeFirst) {
            // paused 状态下 resume；未暂停 / 失败均忽略（拆卸兜底）
            runCatching {
                if (debug.paused.value != null) session.send("Debugger.resume")
            }
        }
        // worker 子会话逐个停 Fetch（best-effort，会话随主 WS 关闭整体失效）
        workerSessions.keys.forEach { sid -> runCatching { fetch.disable(sid) } }
        runCatching { fetch.disable() }
        runCatching { session.send("Target.setAutoAttach", buildJsonObject { put("autoAttach", false) }) }
        debug.cleanup()
        session.close()
        return breakpoints
    }

    /** 规则变更：重载 Fetch patterns（page + 全部 worker 子会话）。 */
    suspend fun onRulesChanged() {
        fetch.enable()
        workerSessions.keys.forEach { sid -> runCatching { fetch.enable(sid) } }
    }

    override suspend fun onEvent(method: String, params: JsonObject, sessionId: String?) {
        if (sessionId == null) {
            handlePageEvent(method, params)
        } else {
            handleWorkerEvent(method, params, sessionId)
        }
    }

    override suspend fun onClosed() {
        // 断连（WebView 销毁 / devtools 关闭）：状态清理由 CdpManager 感知并重建语义
    }

    private suspend fun handlePageEvent(method: String, params: JsonObject) {
        when (method) {
            "Debugger.paused" -> debug.onPaused(params)
            "Debugger.resumed" -> debug.onResumed()
            "Fetch.requestPaused" -> fetch.onRequestPaused(params, sessionId = null)
            "Target.attachedToTarget" -> onWorkerAttached(params)
            "Target.detachedFromTarget" -> onWorkerDetached(params)
        }
    }

    private suspend fun handleWorkerEvent(method: String, params: JsonObject, sessionId: String) {
        when (method) {
            // worker 只挂 Fetch：请求拦截复用同一决策逻辑（rulesFor 按 page 的 tabId 归属）
            "Fetch.requestPaused" -> fetch.onRequestPaused(params, sessionId)
        }
    }

    private suspend fun onWorkerAttached(params: JsonObject) {
        val sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
        val info = params["targetInfo"] as? JsonObject ?: return
        val target = CdpTargetInfo(
            id = info["targetId"]?.jsonPrimitive?.contentOrNull
                ?: info["id"]?.jsonPrimitive?.contentOrNull ?: "",
            type = info["type"]?.jsonPrimitive?.contentOrNull ?: "",
            title = info["title"]?.jsonPrimitive?.contentOrNull ?: "",
            url = info["url"]?.jsonPrimitive?.contentOrNull ?: "",
        )
        if (target.type == "page") return // page 自身的 auto-attach 回声，忽略
        workerSessions[sessionId] = target
        // worker 子会话只挂 Fetch（不挂 Debugger，避免 worker 冻结）
        runCatching { fetch.enable(sessionId) }
    }

    private fun onWorkerDetached(params: JsonObject) {
        val sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
        workerSessions.remove(sessionId)
    }
}
