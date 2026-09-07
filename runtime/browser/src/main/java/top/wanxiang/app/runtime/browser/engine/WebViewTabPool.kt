package top.wanxiang.app.runtime.browser.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import top.wanxiang.app.core.browser.BrowserFamily
import top.wanxiang.app.runtime.browser.BrowserEvent
import top.wanxiang.app.runtime.browser.BrowserEventBus
import top.wanxiang.app.runtime.browser.BrowserSessionToken
import top.wanxiang.app.runtime.browser.cdp.CdpManager
import top.wanxiang.app.runtime.browser.hook.HookEventPipeline
import top.wanxiang.app.runtime.browser.hook.HookInstaller
import top.wanxiang.app.runtime.browser.hook.HookRuleStore
import top.wanxiang.app.runtime.browser.hook.NetworkBodyStore
import top.wanxiang.app.runtime.browser.snapshot.SnapshotBuilder

/**
 * 浏览器 tab 池：线程安全地持有 WebView 实例与 token 的映射。WebView 必须主线程创建，
 * 所以 WebView 实例放在主线程 Handler 持有的 buckets 中。
 *
 * 每个 tab 同时持有：
 * - WebView（主线程创建）；
 * - per-tab 协程 scope：closeTab / shutdown / 渲染进程崩溃时取消，
 *   防止 WebView 回调协程在视图销毁后继续触发；
 * - 唯一的 [SnapshotBuilder]：engine.snapshot 与 onPageFinished 自动刷新共用同一实例，
 *   避免两套扫描并发重写 data-wanxiang-ref 属性导致模型侧 ref 与实际元素错位。
 *
 * Hook 引擎栈（[hooksEnabled]=true 时创建，否则全部为 null 零开销）：
 * [hookStore]（规则）+ [hookPipeline]（页内事件管道）+ [hookBodies]（网络 body）+ [hookInstaller]（注入器）。
 * 与 desktopUserAgent 一样，切换开关需新 tab 或重启生效。
 *
 * CDP 栈（[cdpEnabled]=true 时创建）：[cdpManager]（断点 + Worker 级 Fetch 拦截）。
 * 规则存储与 body 存储在 hooksEnabled||cdpEnabled 任一开启时创建（同一规则双层生效）；
 * hookPipeline / hookInstaller 仍仅 hooksEnabled 创建。
 * 每个 tab 注入 `window.__wanxiangTabId` marker（document-start + 即时求值），
 * 供 CdpTargetMatcher 在同 URL 多 tab 时精确定位。
 */
class WebViewTabPool(
    private val context: Context,
    private val eventBus: BrowserEventBus,
    private val desktopUserAgent: Boolean = false,
    hooksEnabled: Boolean = false,
    cdpEnabled: Boolean = false,
    maxCaptureBytes: Long = 6L * 1024 * 1024,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutex = Mutex()

    /** tab 数上限：防止 agent 无限开 tab 打爆内存。 */
    private val maxTabs = 8

    // ===== hook / CDP 共享存储（hooksEnabled||cdpEnabled 任一开启即创建） =====
    val hookStore: HookRuleStore? =
        if (hooksEnabled || cdpEnabled) HookRuleStore(maxBodyBytes = minOf(maxCaptureBytes, 256L * 1024).toInt()) else null
    val hookBodies: NetworkBodyStore? =
        if (hooksEnabled || cdpEnabled) NetworkBodyStore(totalBudgetBytes = maxCaptureBytes) else null

    // ===== hook 引擎栈（仅 hooksEnabled；注入式页内拦截） =====
    val hookPipeline: HookEventPipeline? =
        if (hooksEnabled && hookStore != null && hookBodies != null) HookEventPipeline(hookStore, hookBodies, eventBus) else null
    val hookInstaller: HookInstaller? =
        if (hookPipeline != null && hookStore != null) {
            HookInstaller(context, hookPipeline, hookStore).also { hookPipeline.start() }
        } else null

    // ===== CDP 引擎栈（仅 cdpEnabled；真断点 + Worker 级 Fetch 拦截） =====
    val cdpManager: CdpManager? =
        if (cdpEnabled && hookStore != null && hookBodies != null) CdpManager(hookStore, hookBodies, eventBus) else null

    /** __wanxiangTabId marker 的 document-start 句柄（仅 cdpEnabled 时注册）。 */
    private val markerHandles = ConcurrentHashMap<String, ScriptHandler>()

    private data class Slot(
        val token: BrowserSessionToken,
        val view: WebView,
        val scope: CoroutineScope,
        val builder: SnapshotBuilder,
    )

    private val byToken = ConcurrentHashMap<String, Slot>()
    private val _activeTab = MutableStateFlow<BrowserSessionToken?>(null)
    val activeTab: StateFlow<BrowserSessionToken?> = _activeTab.asStateFlow()

    /** tab 生命周期之外的事件发布通道（[publishFromMain]），不随单个 tab 的关闭而取消。 */
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 创建 tab。`activate=false`（agent 后台 tab）不抢占当前活跃 tab；
     * 超过 [maxTabs] 时抛出带说明的异常（错误信息直达 agent 工具调用方）。
     */
    suspend fun create(initialUrl: String?, activate: Boolean = true): BrowserSessionToken = mutex.withLock {
        if (byToken.size >= maxTabs) {
            throw IllegalStateException(
                "browser tab limit reached ($maxTabs): close existing tabs with browser.close_tab first"
            )
        }
        val token = BrowserSessionToken(
            tabId = "t:" + System.nanoTime().toString(36),
            family = BrowserFamily.IN_APP,
            title = "",
            url = initialUrl ?: "about:blank",
        )
        // per-tab scope 与该 tab 唯一的 SnapshotBuilder 由池统一创建持有
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // isAlive：builder 扫描前确认 tab 仍在池中（closeTab / 崩溃 / shutdown 后变 false）
        val builder = SnapshotBuilder(token, eventBus, scope) { byToken[token.tabId] != null }
        withContext(Dispatchers.Main.immediate) {
            val view = AndroidWebViewFactory.create(context, desktopUserAgent)
            WebViewClients.attach(view, eventBus, token, this@WebViewTabPool, builder, scope, hookInstaller)
            // 桥与 document-start 脚本必须在首个 loadUrl 之前装好，最早的请求才不会漏
            hookInstaller?.onWebViewCreated(token.tabId, view)
            if (cdpManager != null) installTabMarker(token.tabId, view)
            byToken[token.tabId] = Slot(token, view, scope, builder)
            initialUrl?.let {
                view.loadUrl(it)
            }
        }
        if (activate) {
            _activeTab.value = token
            eventBus.setActiveTab(token)
        }
        token
    }

    suspend fun attach(token: BrowserSessionToken) = mutex.withLock {
        require(byToken.containsKey(token.tabId)) { "tab ${token.tabId} not found" }
        _activeTab.value = token
        eventBus.setActiveTab(token)
        mainHandler.post {
            // posted 执行时 tab 可能已关闭：byToken 查不到即跳过
            byToken[token.tabId]?.view?.requestFocus()
        }
    }

    /** 取得主线程上的 WebView；UI / engine 实现需要 view 来 evaluateJavascript / loadUrl 等。 */
    fun viewOf(token: BrowserSessionToken): WebView? = byToken[token.tabId]?.view

    fun activeWebView(): WebView? = viewOf(_activeTab.value ?: return null)

    /** 该 tab 唯一的 SnapshotBuilder（engine.snapshot 与 onPageFinished 刷新共用）。 */
    fun builderOf(token: BrowserSessionToken): SnapshotBuilder? = byToken[token.tabId]?.builder

    /** 正常关闭后的清理：移除 slot、取消 per-tab scope、清 ref 映射、回退活跃 tab。 */
    suspend fun onClosed(token: BrowserSessionToken) = mutex.withLock {
        val slot = byToken.remove(token.tabId)
        slot?.scope?.cancel()
        slot?.builder?.clear(token.tabId)
        slot?.let { s ->
            hookInstaller?.onWebViewDestroyed(token.tabId, s.view)
            hookPipeline?.clearForTab(token.tabId)
            removeTabMarker(token.tabId)
        }
        cdpManager?.onTabClosed(token.tabId)
        if (_activeTab.value?.tabId == token.tabId) {
            _activeTab.value = byToken.values.firstOrNull()?.token
        }
        eventBus.setActiveTab(_activeTab.value)
        eventBus.resetForTab(token.tabId)
    }

    /**
     * 渲染进程崩溃后的清理（[WebViewClients.onRenderProcessGone] 主线程回调调用）。
     * 与 [onClosed] 等效但可在回调中同步使用；保留该 tab 的 console 历史（崩溃证据）供 agent 排查。
     */
    fun onRenderCrashed(token: BrowserSessionToken) {
        val slot = byToken.remove(token.tabId)
        slot?.scope?.cancel()
        slot?.builder?.clear(token.tabId)
        slot?.let { s ->
            hookInstaller?.onWebViewDestroyed(token.tabId, s.view)
            hookPipeline?.clearForTab(token.tabId)
            removeTabMarker(token.tabId)
        }
        cdpManager?.onTabClosed(token.tabId)
        if (_activeTab.value?.tabId == token.tabId) {
            _activeTab.value = byToken.values.firstOrNull()?.token
        }
        lifecycleScope.launch {
            eventBus.setActiveTab(_activeTab.value)
        }
    }

    suspend fun shutdown() = mutex.withLock {
        val all = byToken.values.toList()
        byToken.clear()
        _activeTab.value = null
        eventBus.setActiveTab(null)
        all.forEach { slot ->
            slot.scope.cancel()
            slot.builder.clear(slot.token.tabId)
            mainHandler.post {
                hookInstaller?.onWebViewDestroyed(slot.token.tabId, slot.view)
                removeTabMarker(slot.token.tabId)
                AndroidWebViewFactory.destroy(slot.view)
            }
        }
        hookPipeline?.shutdown()
        cdpManager?.shutdown()
        lifecycleScope.cancel()
    }

    fun list(): List<BrowserSessionToken> = byToken.values.map { it.token }

    /** 主线程回调发布事件（经 lifecycleScope 派发，不随 per-tab scope 取消而丢失）。 */
    fun publishFromMain(event: BrowserEvent) {
        lifecycleScope.launch { eventBus.publish(event) }
    }

    /**
     * 注入 `window.__wanxiangTabId` marker（仅 cdpEnabled）：document-start 覆盖后续导航，
     * 即时求值覆盖当前文档（about:blank 初始页不走 document-start）。
     * 必须主线程调用。
     */
    private fun installTabMarker(tabId: String, view: WebView) {
        val script = "window.__wanxiangTabId=${JsonPrimitive(tabId)};"
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                markerHandles[tabId] =
                    WebViewCompat.addDocumentStartJavaScript(view, script, setOf("*"))
            }
        }
        runCatching { view.evaluateJavascript(script, null) }
    }

    /** 移除 marker 句柄；任意线程可调（内部保证主线程执行）。 */
    private fun removeTabMarker(tabId: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            markerHandles.remove(tabId)?.let { runCatching { it.remove() } }
        } else {
            mainHandler.post {
                markerHandles.remove(tabId)?.let { runCatching { it.remove() } }
            }
        }
    }
}
