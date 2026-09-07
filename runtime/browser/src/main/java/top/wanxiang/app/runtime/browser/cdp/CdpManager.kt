package top.wanxiang.app.runtime.browser.cdp

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.wanxiang.app.runtime.browser.BrowserEventBus
import top.wanxiang.app.runtime.browser.hook.HookRuleStore
import top.wanxiang.app.runtime.browser.hook.NetworkBodyStore

/**
 * CDP 生命周期总管：attach / detach / 规则联动 / 引用计数管理 devtools socket。
 *
 * - `setWebContentsDebuggingEnabled` 是 **app 级静态开关**（开启后同设备其他进程理论上
 *   可连 devtools socket），因此按需开关：首个 attach 前开（主线程 post + latch 等待），
 *   全部 detach 后关；引用计数 [debugSocketRefs] 管理；
 * - socket 发现：pid 直连 → /proc/net/unix 扫描；失败重试 3×300ms，错误原文透传 agent；
 * - attach 数上限 4（防泄漏）；
 * - 断点在 detach 时快照（[persistedBreakpoints]），重 attach 经 setup 重放。
 */
class CdpManager(
    private val store: HookRuleStore,
    private val bodyStore: NetworkBodyStore,
    private val eventBus: BrowserEventBus,
) {
    sealed interface AttachResult {
        data class Attached(val targetUrl: String) : AttachResult
        data class AlreadyAttached(val targetUrl: String) : AttachResult
    }

    data class AttachedTabStatus(
        val tabId: String,
        val targetUrl: String,
        val paused: Boolean,
        val breakpoints: Int,
        val workers: Int,
    )

    data class CdpStatus(
        val attachedTabs: List<AttachedTabStatus>,
        val devToolsSocketActive: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val attachMutex = Mutex()

    private val connections = ConcurrentHashMap<String, CdpTabConnection>()
    private val persistedBreakpoints = ConcurrentHashMap<String, List<DebugBreakpoint>>()
    private val debugSocketRefs = AtomicInteger(0)

    /** tab 数上限（与 WebViewTabPool 的 8 错开：CDP 会话更重）。 */
    private val maxAttach = 4

    fun connectionOf(tabId: String): CdpTabConnection? = connections[tabId]

    fun attachedTabIds(): List<String> = connections.keys.sorted()

    /**
     * attach tab：发现 socket → 匹配 target → 开 WS → 装配（断点重放 + auto-attach + Fetch）。
     * 失败抛异常（原文直达 agent），并回滚引用计数。
     */
    suspend fun attach(tabId: String): AttachResult = attachMutex.withLock {
        connections[tabId]?.let { return AttachResult.AlreadyAttached(it.target.url) }
        if (connections.size >= maxAttach) {
            throw IllegalStateException(
                "cdp attach limit reached ($maxAttach): browser.debug_detach other tabs first"
            )
        }
        acquireDevTools()
        try {
            val transport = discoverTransport()
            val matcher = CdpTargetMatcher(transport, scope)
            val tabUrl = eventBus.urlOf(tabId)
            val match = matcher.matchPageTarget(tabId, tabUrl)
            val target = when (match) {
                is CdpTargetMatcher.TargetMatch.Matched -> match.target
                is CdpTargetMatcher.TargetMatch.NotFound -> throw IllegalStateException(
                    buildString {
                        append("cdp attach 失败（tab $tabId）: ").append(match.message)
                        if (match.candidates.isNotEmpty()) {
                            append("; 候选: ").append(
                                match.candidates.joinToString { "${it.type} ${it.url}" }
                            )
                        }
                    },
                )
            }
            val ws = openWebSocket(transport, target)
            val session = CdpSession(ws, scope)
            val conn = CdpTabConnection(tabId, target, session, store, bodyStore, eventBus)
            session.start(conn)
            connections[tabId] = conn
            try {
                conn.setup(persistedBreakpoints[tabId] ?: emptyList())
            } catch (e: Exception) {
                connections.remove(tabId)
                runCatching { conn.detach() }
                throw IllegalStateException("cdp setup 失败（tab $tabId）: ${e.message}", e)
            }
            AttachResult.Attached(target.url)
        } catch (e: Exception) {
            releaseDevTools()
            throw e
        }
    }

    /**
     * detach：tabId 省略 = 全部。返回 detach 数量；指定 tab 未 attach 时抛错。
     * detach 前先 resume（防页面冻结），断点快照供重 attach 重放。
     */
    suspend fun detach(tabId: String?): Int = attachMutex.withLock {
        if (tabId == null) {
            val ids = connections.keys.toList()
            ids.forEach { detachLocked(it) }
            ids.size
        } else {
            require(connections.containsKey(tabId)) { "tab $tabId 未 attach CDP（先 browser.debug_attach）" }
            detachLocked(tabId)
            1
        }
    }

    /** tab 关闭 / 崩溃时联动（可从任意线程调用；异步强制 detach）。 */
    fun onTabClosed(tabId: String) {
        persistedBreakpoints.remove(tabId)
        val conn = connections.remove(tabId) ?: return
        scope.launch {
            runCatching { conn.detach() }
            releaseDevTools()
        }
    }

    /** 规则变更（hook_install/remove/reset 后由引擎调用）：重载全部连接的 Fetch patterns。 */
    suspend fun onRulesChanged() {
        connections.values.forEach { runCatching { it.onRulesChanged() } }
    }

    suspend fun shutdown() = attachMutex.withLock {
        connections.values.forEach { runCatching { it.detach() } }
        connections.clear()
        persistedBreakpoints.clear()
        if (debugSocketRefs.getAndSet(0) > 0) {
            mainHandler.post { runCatching { WebView.setWebContentsDebuggingEnabled(false) } }
        }
        scope.cancel()
    }

    fun status(): CdpStatus = CdpStatus(
        attachedTabs = connections.map { (tabId, conn) ->
            AttachedTabStatus(
                tabId = tabId,
                targetUrl = conn.target.url,
                paused = conn.debug.paused.value != null,
                breakpoints = conn.debug.breakpoints().size,
                workers = conn.workerCount,
            )
        }.sortedBy { it.tabId },
        devToolsSocketActive = debugSocketRefs.get() > 0,
    )

    // ===== 内部 =====

    private suspend fun detachLocked(tabId: String) {
        val conn = connections.remove(tabId) ?: return
        val breakpoints = runCatching { conn.detach() }.getOrDefault(emptyList())
        if (breakpoints.isNotEmpty()) persistedBreakpoints[tabId] = breakpoints
        else persistedBreakpoints.remove(tabId)
        releaseDevTools()
    }

    /** 首个引用时开启 app 级 devtools（主线程执行 + latch 等待，确保 socket 尽快创建）。 */
    private fun acquireDevTools() {
        if (debugSocketRefs.incrementAndGet() == 1) {
            val latch = CountDownLatch(1)
            mainHandler.post {
                runCatching { WebView.setWebContentsDebuggingEnabled(true) }
                latch.countDown()
            }
            latch.await(3, TimeUnit.SECONDS)
        }
    }

    private fun releaseDevTools() {
        if (debugSocketRefs.updateAndGet { if (it > 0) it - 1 else 0 } == 0) {
            mainHandler.post { runCatching { WebView.setWebContentsDebuggingEnabled(false) } }
        }
    }

    /** socket 发现：候选逐个试连；全部失败延迟 300ms 重试，共 3 次。 */
    private suspend fun discoverTransport(): LocalSocketCdpTransport {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            for (name in DevToolsSocketResolver.candidates()) {
                val transport = LocalSocketCdpTransport(name)
                try {
                    transport.open().close()
                    return transport
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (attempt < 2) delay(300)
        }
        throw java.io.IOException(
            "webview devtools socket 未发现（已重试 3 次；" +
                "候选=${DevToolsSocketResolver.candidates()}）: ${lastError?.message}",
            lastError,
        )
    }

    private fun openWebSocket(transport: CdpTransport, target: CdpTargetInfo): WsConnection {
        // webSocketDebuggerUrl 形如 ws://127.0.0.1/devtools/page/<id> → 取 path 部分
        val path = target.webSocketDebuggerUrl.substringAfter("devtools", "")
        if (path.isEmpty()) throw java.io.IOException("target 无 webSocketDebuggerUrl: ${target.id}")
        val conn = transport.open()
        return try {
            WsHandshake.open(conn, path)
        } catch (e: Exception) {
            conn.close()
            throw e
        }
    }
}
