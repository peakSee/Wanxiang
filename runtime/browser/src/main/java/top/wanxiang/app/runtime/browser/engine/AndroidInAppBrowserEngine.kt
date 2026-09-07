package top.wanxiang.app.runtime.browser.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import top.wanxiang.app.core.browser.BrowserFamily
import top.wanxiang.app.core.browser.BrowserPreferences
import top.wanxiang.app.core.browser.PageSnapshot
import top.wanxiang.app.core.model.ToolImageRef
import top.wanxiang.app.runtime.browser.BrowserEngine
import top.wanxiang.app.runtime.browser.BrowserEventBus
import top.wanxiang.app.runtime.browser.BrowserSessionToken
import top.wanxiang.app.runtime.browser.InAppBrowserViewProvider
import top.wanxiang.app.runtime.browser.js.JsEvaluator
import top.wanxiang.app.runtime.browser.screenshot.ScreenshotRecorder
import top.wanxiang.app.runtime.browser.capabilities.EngineCapabilities
import top.wanxiang.app.runtime.browser.cdp.CdpManager
import top.wanxiang.app.runtime.browser.cdp.DebugBreakpoint
import top.wanxiang.app.runtime.browser.cdp.DebugStep
import top.wanxiang.app.runtime.browser.hook.HookRule
import top.wanxiang.app.runtime.browser.hook.HookRuleInfo
import top.wanxiang.app.runtime.browser.hook.InjectedScript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

/**
 * in-app WebView 引擎实现。所有 WebView 操作走主线程 [Handler]，协程侧用 [CompletableDeferred] 等待结果。
 *
 * SnapshotBuilder 与 per-tab scope 由 [WebViewTabPool] 统一持有（每 tab 唯一实例），
 * onPageFinished 自动刷新与 [snapshot] 共用同一 builder，避免并发双扫描重写 ref。
 */
class AndroidInAppBrowserEngine(
    private val context: Context,
    override val eventBus: BrowserEventBus,
    private val pool: WebViewTabPool,
) : BrowserEngine, InAppBrowserViewProvider {

    override val descriptor = top.wanxiang.app.core.browser.BrowserDescriptor(
        family = BrowserFamily.IN_APP,
        displayName = "WanXiang In-App WebView",
        healthy = true,
        capabilities = EngineCapabilities.IN_APP,
        versionTag = "1.0",
        notes = "内置 WebView；通过 mcp__browser__* 调用。",
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotRecorder = ScreenshotRecorder(context)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun openTab(url: String?, activate: Boolean): BrowserSessionToken =
        pool.create(url, activate)

    override suspend fun navigate(tab: BrowserSessionToken, url: String) {
        val view = pool.viewOf(tab) ?: error("tab ${tab.tabId} not found")
        // posted 执行时 tab 可能已被关闭 / 崩溃销毁：用池内成员关系守卫（WebView 无 isDestroyed API）
        postMain { if (pool.viewOf(tab) === view) view.loadUrl(url) }
    }

    override suspend fun snapshot(tab: BrowserSessionToken, maxElements: Int): PageSnapshot {
        checkNotPaused(tab)
        val view = pool.viewOf(tab) ?: error("tab ${tab.tabId} not found")
        val builder = pool.builderOf(tab) ?: error("tab ${tab.tabId} not found")
        // refresh 同步返回本次扫描结果，避免依赖全局 eventBus 的异步发布造成竞态
        return builder.refresh(view, maxElements)
            ?: PageSnapshot(tab.tabId, tab.url, "", emptyMap(), "empty", 0L)
    }

    override suspend fun click(
        tab: BrowserSessionToken,
        ref: String,
        refSelectorLookup: suspend (BrowserSessionToken, String) -> String?,
    ): Boolean {
        checkNotPaused(tab)
        val selector = refSelectorLookup(tab, ref) ?: return false
        val js = JS.wrapSelectorClick(selector)
        val view = pool.viewOf(tab) ?: return false
        return JsEvaluator.evaluate(view, js)?.let { it == "true" } ?: false
    }

    override suspend fun typeInto(
        tab: BrowserSessionToken,
        ref: String,
        text: String,
        refSelectorLookup: suspend (BrowserSessionToken, String) -> String?,
    ): Boolean {
        checkNotPaused(tab)
        val selector = refSelectorLookup(tab, ref) ?: return false
        val js = JS.wrapSelectorType(selector, text)
        val view = pool.viewOf(tab) ?: return false
        return JsEvaluator.evaluate(view, js)?.let { it == "true" } ?: false
    }

    override suspend fun press(
        tab: BrowserSessionToken,
        ref: String?,
        key: String,
        refSelectorLookup: suspend (BrowserSessionToken, String) -> String?,
    ): Boolean {
        checkNotPaused(tab)
        val view = pool.viewOf(tab) ?: return false
        val js = if (ref == null) {
            JS.wrapActiveElementPress(key)
        } else {
            val selector = refSelectorLookup(tab, ref) ?: return false
            JS.wrapSelectorPress(selector, key)
        }
        return JsEvaluator.evaluate(view, js)?.let { it == "true" } ?: false
    }

    override suspend fun scroll(tab: BrowserSessionToken, deltaY: Int): Boolean {
        checkNotPaused(tab)
        val view = pool.viewOf(tab) ?: return false
        return JsEvaluator.evaluate(view, JS.wrapScroll(deltaY))?.let { it == "true" } ?: false
    }

    override suspend fun screenshot(tab: BrowserSessionToken, prefs: BrowserPreferences): ToolImageRef? {
        val view = pool.viewOf(tab) ?: return null
        return screenshotRecorder.capture(view, tab.tabId)
    }

    override suspend fun evaluate(tab: BrowserSessionToken, script: String): String? {
        checkNotPaused(tab)
        val view = pool.viewOf(tab) ?: return null
        val raw = JsEvaluator.evaluate(view, script) ?: return null
        // evaluateJavascript 回调是 JSON 字面量：解码后再返回给模型
        return JsEvaluator.unwrap(raw)
    }

    override suspend fun pageSource(tab: BrowserSessionToken, maxBytes: Int): String {
        checkNotPaused(tab)
        val view = pool.viewOf(tab) ?: return ""
        val raw = JsEvaluator.evaluate(view, "document.documentElement.outerHTML") ?: return ""
        val html = JsEvaluator.unwrap(raw)
        return if (html.length > maxBytes) html.substring(0, maxBytes) + "\n[TRUNCATED]" else html
    }

    override suspend fun back(tab: BrowserSessionToken): Boolean {
        val view = pool.viewOf(tab) ?: error("tab ${tab.tabId} not found")
        return postMainValue {
            if (pool.viewOf(tab) !== view) false
            else view.canGoBack().also { if (it) view.goBack() }
        } ?: false
    }

    override suspend fun forward(tab: BrowserSessionToken): Boolean {
        val view = pool.viewOf(tab) ?: error("tab ${tab.tabId} not found")
        return postMainValue {
            if (pool.viewOf(tab) !== view) false
            else view.canGoForward().also { if (it) view.goForward() }
        } ?: false
    }

    override suspend fun refresh(tab: BrowserSessionToken) {
        val view = pool.viewOf(tab) ?: error("tab ${tab.tabId} not found")
        postMain { if (pool.viewOf(tab) === view) view.reload() }
    }

    override suspend fun closeTab(tab: BrowserSessionToken) {
        // 先捕获 WebView 引用再从池中移除：postMain 超时后主线程 block 仍会执行，
        // 若 block 内依赖 viewOf(tab) 查池则拿到 null，destroy 永远不会执行（WebView 泄漏）。
        val view = pool.viewOf(tab)
        pool.onClosed(tab) // 移除 slot / 取消 per-tab scope / 清 ref 映射 / 回退 activeTab / 重置事件
        screenshotRecorder.cleanup(tab.tabId)
        if (view != null) {
            postMain { AndroidWebViewFactory.destroy(view) }
        }
    }

    override suspend fun listTabs(): List<BrowserSessionToken> = pool.list()

    override fun activeTab(): BrowserSessionToken? = pool.activeTab.value

    override fun activeWebView(): WebView? = pool.activeWebView()

    override suspend fun setActiveTab(tab: BrowserSessionToken) {
        pool.attach(tab)
    }

    // ===== Cookies：走 CookieManager（document.cookie 拿不到 HttpOnly、且无视 url 参数）=====
    // CookieManager 需在有 Looper 的线程调用，与其它 WebView 操作一致经主线程派发。

    override suspend fun cookiesGet(tab: BrowserSessionToken, url: String?): String {
        val explicitUrl = url?.takeIf { it.isNotBlank() }
        val target: String = if (explicitUrl != null) {
            explicitUrl
        } else {
            val view = pool.viewOf(tab) ?: error("tab ${tab.tabId} not found")
            val current = postMainValue { if (pool.viewOf(tab) !== view) "" else view.url.orEmpty() } ?: ""
            if (current.isBlank()) return ""
            current
        }
        return postMainValue {
            runCatching { CookieManager.getInstance().getCookie(target) }.getOrNull() ?: ""
        } ?: ""
    }

    override suspend fun cookiesSet(tab: BrowserSessionToken, url: String, headerLine: String) {
        if (url.isBlank() || headerLine.isBlank()) return
        postMain {
            val cm = CookieManager.getInstance()
            cm.setCookie(url, headerLine)
            cm.flush()
        }
    }

    override suspend fun cookiesDelete(tab: BrowserSessionToken, url: String, name: String) {
        if (url.isBlank() || name.isBlank()) return
        postMain {
            val cm = CookieManager.getInstance()
            // 用立即过期的同名 cookie 覆盖（针对该 url），domain/path 由 CookieManager 自行推导
            cm.setCookie(url, "$name=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
            cm.flush()
        }
    }

    override suspend fun localGet(tab: BrowserSessionToken, key: String): String? {
        val view = pool.viewOf(tab) ?: return null
        val raw = JsEvaluator.evaluate(view, "localStorage.getItem(" + JS.q(key) + ")") ?: return null
        if (raw.trim() == "null") return null
        return JsEvaluator.unwrap(raw).takeIf { it.isNotEmpty() }
    }

    override suspend fun localSet(tab: BrowserSessionToken, key: String, value: String) {
        val view = pool.viewOf(tab) ?: return
        JsEvaluator.evaluate(view, "localStorage.setItem(" + JS.q(key) + ", " + JS.q(value) + ")")
    }

    override suspend fun localDelete(tab: BrowserSessionToken, key: String) {
        val view = pool.viewOf(tab) ?: return
        JsEvaluator.evaluate(view, "localStorage.removeItem(" + JS.q(key) + ")")
    }

    override suspend fun localKeys(tab: BrowserSessionToken): List<String> {
        val view = pool.viewOf(tab) ?: return emptyList()
        val raw = JsEvaluator.evaluate(view, "JSON.stringify(Object.keys(localStorage))") ?: return emptyList()
        // 回调是 JSON 字面量，先解码再 parse；直接 trim('"') 遇转义必失败 → 恒为空列表
        val arr = runCatching { json.parseToJsonElement(JsEvaluator.unwrap(raw)).jsonArray }.getOrNull()
            ?: return emptyList()
        return arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }

    override suspend fun sessionGet(tab: BrowserSessionToken, key: String): String? {
        val view = pool.viewOf(tab) ?: return null
        val raw = JsEvaluator.evaluate(view, "sessionStorage.getItem(" + JS.q(key) + ")") ?: return null
        if (raw.trim() == "null") return null
        return JsEvaluator.unwrap(raw).takeIf { it.isNotEmpty() }
    }

    override suspend fun sessionSet(tab: BrowserSessionToken, key: String, value: String) {
        val view = pool.viewOf(tab) ?: return
        JsEvaluator.evaluate(view, "sessionStorage.setItem(" + JS.q(key) + ", " + JS.q(value) + ")")
    }

    override suspend fun sessionDelete(tab: BrowserSessionToken, key: String) {
        val view = pool.viewOf(tab) ?: return
        JsEvaluator.evaluate(view, "sessionStorage.removeItem(" + JS.q(key) + ")")
    }

    override suspend fun sessionKeys(tab: BrowserSessionToken): List<String> {
        val view = pool.viewOf(tab) ?: return emptyList()
        val raw = JsEvaluator.evaluate(view, "JSON.stringify(Object.keys(sessionStorage))") ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(JsEvaluator.unwrap(raw)).jsonArray }.getOrNull()
            ?: return emptyList()
        return arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }

    override suspend fun shutdown() {
        val tabIds = pool.list().map { it.tabId }
        pool.shutdown()
        tabIds.forEach { screenshotRecorder.cleanup(it) }
        // 截图线程由 recorder 自己持有：不在这里 quit 就会随每次引擎重建各留一个常驻线程。
        screenshotRecorder.shutdown()
    }

    // ===== 注入式 Hook 引擎（阶段 1）=====

    private fun requireHookStore() =
        pool.hookStore ?: throw UnsupportedOperationException(
            "hooks disabled (allowHooks=false)，请在浏览器设置中开启后重启"
        )

    private fun requireHookInstaller() =
        pool.hookInstaller ?: throw UnsupportedOperationException(
            "hooks disabled (allowHooks=false)，请在浏览器设置中开启后重启"
        )

    /** 规则变更后推送到受影响的活跃 tab（主线程 evaluate）。 */
    private suspend fun pushRulesToTabs(scopeTabId: String?) {
        val installer = requireHookInstaller()
        pool.list()
            .filter { scopeTabId == null || it.tabId == scopeTabId }
            .forEach { token ->
                pool.viewOf(token)?.let { view ->
                    postMain { installer.pushRules(token.tabId, view) }
                }
            }
    }

    override suspend fun hookInstall(rule: HookRule): HookRule {
        requireHookStore().install(rule)
        pushRulesToTabs(rule.scopeTabId)
        pool.cdpManager?.onRulesChanged()
        return rule
    }

    override suspend fun hookRemove(id: String): Boolean {
        val removed = requireHookStore().remove(id)
        if (removed) {
            pushRulesToTabs(null)
            pool.cdpManager?.onRulesChanged()
        }
        return removed
    }

    override suspend fun hookList(tabId: String?): List<HookRuleInfo> {
        val store = requireHookStore()
        return store.list(tabId).map { HookRuleInfo(it, store.hitCount(it.id)) }
    }

    override suspend fun hookReset(tabId: String?): Boolean {
        val store = requireHookStore()
        store.reset(tabId)
        when (tabId) {
            null -> {
                pool.hookPipeline?.let { p ->
                    pool.list().forEach { p.clearForTab(it.tabId) }
                }
                pool.hookBodies?.clear()
                eventBus.clearHookHits()
            }
            else -> {
                pool.hookPipeline?.clearForTab(tabId)
                pool.hookBodies?.clearForTab(tabId)
            }
        }
        pushRulesToTabs(tabId)
        pool.cdpManager?.onRulesChanged()
        return true
    }

    override suspend fun injectScript(tab: BrowserSessionToken, code: String, persistent: Boolean, name: String): String {
        if (persistent) {
            val store = requireHookStore()
            val script = InjectedScript(
                id = "sc_" + java.util.UUID.randomUUID().toString().substring(0, 8),
                name = name.ifBlank { "script" },
                code = code,
                scopeTabId = tab.tabId,
            )
            store.addScript(script)
            pushRulesToTabs(tab.tabId)
            return "persistent script registered: ${script.id}（后续导航自动重放）"
        }
        val view = pool.viewOf(tab) ?: error("tab ${tab.tabId} not found")
        val raw = JsEvaluator.evaluate(view, code) ?: return "no result"
        return JsEvaluator.unwrap(raw).ifBlank { "ok" }
    }

    override suspend fun networkDetail(id: String): String? {
        val captured = eventBus.network.value.lastOrNull { it.id == id }
            ?: return null
        val bodies = pool.hookBodies?.get(id)
        return buildString {
            appendLine("[${captured.source}] ${captured.method} ${captured.url}")
            appendLine("status=${captured.statusCode} duration=${captured.durationMs}ms rule=${captured.ruleId.ifBlank { "-" }} action=${captured.actionTaken.ifBlank { "-" }}")
            if (captured.requestHeaders.isNotEmpty()) {
                appendLine("-- request headers --")
                captured.requestHeaders.forEach { (k, v) -> appendLine("$k: $v") }
            }
            if (captured.responseHeaders.isNotEmpty()) {
                appendLine("-- response headers --")
                captured.responseHeaders.forEach { (k, v) -> appendLine("$k: $v") }
            }
            if (bodies != null && bodies.requestBody.isNotEmpty()) {
                appendLine("-- request body --")
                appendLine(bodies.requestBody)
            }
            if (bodies != null && bodies.responseBody.isNotEmpty()) {
                appendLine("-- response body --")
                appendLine(bodies.responseBody)
            }
            if (bodies == null && captured.source == "js") {
                appendLine("(body 未捕获：规则需 captureBody=true 或已被 LRU 逐出)")
            }
        }
    }

    // ===== CDP 调试引擎（阶段 2：真断点 + Worker 级 Fetch 拦截）=====

    private fun requireCdp(): CdpManager =
        pool.cdpManager ?: throw UnsupportedOperationException(
            "cdp disabled (allowCdp=false)，请在浏览器设置中开启后重启"
        )

    private fun requireDebugConnection(tab: BrowserSessionToken) =
        requireCdp().connectionOf(tab.tabId)
            ?: throw IllegalStateException("tab ${tab.tabId} 未 attach CDP（先 browser.debug_attach）")

    /**
     * 页内工具前置守卫：paused 时 JS 执行被冻结，evaluateJavascript 回调永不到达（工具挂起）。
     * 改为立即报错引导 agent 先 resume。
     */
    private fun checkNotPaused(tab: BrowserSessionToken) {
        val state = eventBus.debugPausedOf(tab.tabId) ?: return
        val at = state.callFrames.firstOrNull()
            ?.let { " ${it.functionName.ifEmpty { "<anonymous>" }} @ ${it.url}:${it.lineNumber + 1}" }
            .orEmpty()
        throw IllegalStateException(
            "tab ${tab.tabId} 正在断点处暂停（$at）：页内工具被冻结，请先 browser.debug_resume"
        )
    }

    override suspend fun debugStatus(): String {
        val status = requireCdp().status()
        return buildString {
            appendLine("devtools socket: ${if (status.devToolsSocketActive) "active" else "inactive"}")
            if (status.attachedTabs.isEmpty()) {
                appendLine("attached: none（用 browser.debug_attach 附加当前 tab）")
            } else {
                appendLine("attached: ${status.attachedTabs.size} tab(s)")
                status.attachedTabs.forEach {
                    appendLine(
                        "- ${it.tabId}: ${it.targetUrl} paused=${it.paused} " +
                            "breakpoints=${it.breakpoints} workers=${it.workers}"
                    )
                }
            }
        }.trimEnd()
    }

    override suspend fun debugAttach(tab: BrowserSessionToken): String =
        when (val result = requireCdp().attach(tab.tabId)) {
            is CdpManager.AttachResult.Attached ->
                "attached ${tab.tabId} -> ${result.targetUrl}（断点已重放，Fetch 拦截已启用）"
            is CdpManager.AttachResult.AlreadyAttached ->
                "already attached: ${tab.tabId} -> ${result.targetUrl}"
        }

    override suspend fun debugDetach(tab: BrowserSessionToken?): Int = requireCdp().detach(tab?.tabId)

    override suspend fun debugSetBreakpoint(
        tab: BrowserSessionToken,
        url: String,
        line: Int,
        column: Int,
        condition: String?,
    ): DebugBreakpoint = requireDebugConnection(tab).debug.setBreakpoint(url, line, column, condition)

    override suspend fun debugRemoveBreakpoint(tab: BrowserSessionToken, id: String): Boolean =
        requireDebugConnection(tab).debug.removeBreakpoint(id)

    override suspend fun debugListBreakpoints(tab: BrowserSessionToken): List<DebugBreakpoint> =
        requireDebugConnection(tab).debug.breakpoints()

    override suspend fun debugResume(tab: BrowserSessionToken?): Int {
        val manager = requireCdp()
        if (tab != null) {
            requireDebugConnection(tab).debug.resume()
            return 1
        }
        var resumed = 0
        manager.attachedTabIds().forEach { id ->
            manager.connectionOf(id)?.let { conn ->
                if (conn.debug.paused.value != null) {
                    conn.debug.resume()
                    resumed++
                }
            }
        }
        return resumed
    }

    override suspend fun debugStep(tab: BrowserSessionToken, step: DebugStep): Boolean =
        requireDebugConnection(tab).debug.step(step)

    override suspend fun debugState(tab: BrowserSessionToken): String {
        requireDebugConnection(tab)
        val state = eventBus.debugPausedOf(tab.tabId) ?: return "running"
        return buildString {
            appendLine("paused (${state.reason}) at:")
            state.callFrames.forEachIndexed { i, f ->
                appendLine(
                    "[$i] ${f.functionName.ifEmpty { "<anonymous>" }} ${f.url}:" +
                        "${f.lineNumber + 1}:${f.columnNumber + 1}"
                )
                f.scopes.forEachIndexed { j, s ->
                    appendLine("    scope[$j] ${s.type}${if (s.name.isNotEmpty()) " (${s.name})" else ""}")
                }
            }
            if (state.hitBreakpoints.isNotEmpty()) {
                appendLine("hit breakpoints: ${state.hitBreakpoints.joinToString()}")
            }
        }.trimEnd()
    }

    override suspend fun debugEval(tab: BrowserSessionToken, frame: Int, expression: String): String {
        val state = eventBus.debugPausedOf(tab.tabId)
            ?: throw IllegalStateException("tab ${tab.tabId} not paused（断点触发后再求值）")
        val callFrame = state.callFrames.getOrNull(frame)
            ?: throw IllegalArgumentException("frame index out of range: $frame (0..${state.callFrames.size - 1})")
        return requireDebugConnection(tab).debug.evaluateOnCallFrame(callFrame.callFrameId, expression)
    }

    override suspend fun debugScope(tab: BrowserSessionToken, frame: Int, scope: Int?): String {
        val state = eventBus.debugPausedOf(tab.tabId)
            ?: throw IllegalStateException("tab ${tab.tabId} not paused（断点触发后再读作用域）")
        val callFrame = state.callFrames.getOrNull(frame)
            ?: throw IllegalArgumentException("frame index out of range: $frame (0..${state.callFrames.size - 1})")
        return requireDebugConnection(tab).debug.scopeProperties(callFrame.callFrameId, scope)
    }

    /**
     * 主线程执行并取回结果（带回值版 postMain）。超时后主线程 block 仍会照常执行
     * （WebView/主线程无法撤销已派发的操作），调用方超时返回后不应重试同副作用操作。
     * 所有 block 内对 WebView 的调用都带 isDestroyed / 捕获引用守卫。
     */
    private suspend fun <T : Any> postMainValue(timeoutMs: Long = 2_000L, block: () -> T): T? {
        val deferred = CompletableDeferred<T>()
        mainHandler.post {
            try {
                deferred.complete(block())
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) {
            android.util.Log.w("WanXiangBrowserEngine", "postMain 超时（${timeoutMs}ms）：操作可能已在页面执行，请勿重试同参数操作")
        }
        return result
    }

    private suspend fun postMain(block: () -> Unit) {
        postMainValue { block() }
    }
}

/** JS 字符串与脚本片段构造：所有引号 / 换行 / Unicode 经 [q] 转义，避免 here-string 解析陷阱。 */
private object JS {

    fun q(value: String): String {
        val sb = StringBuilder("'")
        for (c in value) when (c) {
            '\\' -> sb.append("\\\\")
            '\'' -> sb.append("\\'")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            else -> sb.append(c)
        }
        sb.append('\'')
        return sb.toString()
    }

    fun wrapSelectorClick(selector: String): String =
        "(function(){var n=document.querySelector(${q(selector)});if(!n)return false;n.click();return true;})()"

    /** input + change 两个事件都派发：React 受控组件依赖 change 提交，只派 input 会被状态回滚。 */
    fun wrapSelectorType(selector: String, text: String): String =
        "(function(){var n=document.querySelector(${q(selector)});if(!n)return false;n.focus();n.value=${q(text)};n.dispatchEvent(new Event('input',{bubbles:true}));n.dispatchEvent(new Event('change',{bubbles:true}));return true;})()"

    /** keydown（可打印键加 keypress）+ keyup，带 key/code/keyCode/which（legacy 监听只认 keyCode）。 */
    fun wrapActiveElementPress(key: String): String =
        "(function(){var el=document.activeElement;if(!el)return false;var opt={key:${q(key)},code:${q(codeOf(key))},keyCode:${keyCodeOf(key)},which:${keyCodeOf(key)},bubbles:true,cancelable:true};el.dispatchEvent(new KeyboardEvent('keydown',opt));${keypressOf(key, "el")}el.dispatchEvent(new KeyboardEvent('keyup',opt));return true;})()"

    fun wrapSelectorPress(selector: String, key: String): String =
        "(function(){var n=document.querySelector(${q(selector)});if(!n)return false;var opt={key:${q(key)},code:${q(codeOf(key))},keyCode:${keyCodeOf(key)},which:${keyCodeOf(key)},bubbles:true,cancelable:true};n.dispatchEvent(new KeyboardEvent('keydown',opt));${keypressOf(key, "n")}n.dispatchEvent(new KeyboardEvent('keyup',opt));return true;})()"

    fun wrapScroll(deltaY: Int): String =
        "(function(){window.scrollBy(0,${deltaY});return true;})()"

    /** 可打印字符额外派 keypress（legacy 输入监听依赖）。 */
    private fun keypressOf(key: String, targetVar: String): String =
        if (key.length == 1) "$targetVar.dispatchEvent(new KeyboardEvent('keypress',opt));" else ""

    /** 常见键的 legacy keyCode 映射；可打印字符取大写字符码。 */
    private fun keyCodeOf(key: String): Int = when (key) {
        "Enter" -> 13
        "Escape" -> 27
        "Tab" -> 9
        "Backspace" -> 8
        "Delete" -> 46
        "ArrowUp" -> 38
        "ArrowDown" -> 40
        "ArrowLeft" -> 37
        "ArrowRight" -> 39
        "Home" -> 36
        "End" -> 35
        "PageUp" -> 33
        "PageDown" -> 34
        "Shift" -> 16
        "Control" -> 17
        "Alt" -> 18
        "Meta" -> 91
        " " -> 32
        else -> if (key.length == 1) key[0].uppercaseChar().code else 0
    }

    /** KeyboardEvent.code：命名键与 key 同名，字母 KeyX、数字 DigitN、空格 Space。 */
    private fun codeOf(key: String): String = when {
        key == " " -> "Space"
        key.length == 1 && key[0].isLetter() -> "Key" + key.uppercase()
        key.length == 1 && key[0].isDigit() -> "Digit" + key
        else -> key
    }
}
