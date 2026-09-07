package top.wanxiang.app.runtime.browser

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.wanxiang.app.runtime.browser.cdp.DebugPausedState
import top.wanxiang.app.runtime.browser.hook.HookHitRecord

/**
 * 浏览器事件总线（StateFlow-backed）。任意 [BrowserEngine] 实例向其 publish 事件，
 * UI 与 harness 通过 [snapshot] / [console] / [network] / [url] / [title] 订阅。
 *
 * 说明：
 * - 保持最近 N 条 console / network（默认 200 / 200）；
 * - url / title / snapshot 按 tabId 分片保存（ConcurrentHashMap：写侧经 Mutex 串行，
 *   读侧 urlOf / titleOf / snapshotOf 被 MCP 工作线程无锁调用也安全）；
 * - 全局 StateFlow 始终反映当前活跃 tab 的视图，避免多 tab 导航互相覆盖；
 *   工具侧请用 [urlOf] / [titleOf] / [snapshotOf] 按 tab 精确取值；
 * - 因为引擎可能在主线程派发事件，所以 publish 用 suspend + Mutex 保证复合更新串行。
 */
class BrowserEventBus(
    private val consoleCapacity: Int = 200,
    private val networkCapacity: Int = 200,
    private val hookHitCapacity: Int = 200,
    private val debugEventCapacity: Int = 200,
) {
    private val mutex = Mutex()

    // —— 按 tab 分片状态（无锁读线程安全）——
    private val tabUrls = ConcurrentHashMap<String, String>()
    private val tabTitles = ConcurrentHashMap<String, String>()
    private val tabSnapshots = ConcurrentHashMap<String, BrowserEvent.SnapshotUpdated>()

    // —— 全局视图（始终等于当前活跃 tab 的状态）——
    private val _snapshot = MutableStateFlow<BrowserEvent.SnapshotUpdated?>(null)
    val snapshot: StateFlow<BrowserEvent.SnapshotUpdated?> = _snapshot.asStateFlow()

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _activeTab = MutableStateFlow<BrowserSessionToken?>(null)
    val activeTab: StateFlow<BrowserSessionToken?> = _activeTab.asStateFlow()

    private val _console = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val console: StateFlow<List<ConsoleLine>> = _console.asStateFlow()

    private val _network = MutableStateFlow<List<CapturedRequest>>(emptyList())
    val network: StateFlow<List<CapturedRequest>> = _network.asStateFlow()

    private val _hookHits = MutableStateFlow<List<HookHitRecord>>(emptyList())
    val hookHits: StateFlow<List<HookHitRecord>> = _hookHits.asStateFlow()

    // —— CDP 调试事件（debug_events 工具读）——
    /** 调试事件环内单条记录（paused/resumed 摘要）。 */
    @kotlinx.serialization.Serializable
    data class DebugEventRecord(
        val tabId: String,
        val kind: String,           // "paused" / "resumed"
        val reason: String = "",
        val summary: String = "",
        val at: Long = System.currentTimeMillis(),
    )

    private val _debugEvents = MutableStateFlow<List<DebugEventRecord>>(emptyList())
    val debugEvents: StateFlow<List<DebugEventRecord>> = _debugEvents.asStateFlow()

    // —— CDP 暂停态分片（debug_state 工具读；debugPausedOf 无锁）——
    private val tabDebugPaused = ConcurrentHashMap<String, DebugPausedState>()

    /** 按 tab 读取 URL（不含 fallback）。 */
    fun urlOf(tabId: String): String? = tabUrls[tabId]

    /** 按 tab 读取标题（不含 fallback）。 */
    fun titleOf(tabId: String): String? = tabTitles[tabId]

    /** 按 tab 读取最近一次快照。 */
    fun snapshotOf(tabId: String): BrowserEvent.SnapshotUpdated? = tabSnapshots[tabId]

    /** 按 tab 读取 CDP 暂停态（null = running / 未 attach）。 */
    fun debugPausedOf(tabId: String): DebugPausedState? = tabDebugPaused[tabId]

    private fun isActiveTab(tabId: String): Boolean = _activeTab.value?.tabId == tabId

    suspend fun publish(event: BrowserEvent) = mutex.withLock {
        when (event) {
            is BrowserEvent.PageChanged -> {
                tabUrls[event.tabId] = event.url
                tabTitles[event.tabId] = event.title
                if (isActiveTab(event.tabId)) {
                    _url.value = event.url
                    _title.value = event.title
                }
            }
            is BrowserEvent.SnapshotUpdated -> {
                tabSnapshots[event.tabId] = event
                if (isActiveTab(event.tabId)) _snapshot.value = event
            }
            is BrowserEvent.ConsoleLogged -> {
                val line = ConsoleLine(event.tabId, event.level, event.message, event.at)
                _console.update { prev ->
                    val next = (prev + line)
                    if (next.size > consoleCapacity) next.takeLast(consoleCapacity) else next
                }
            }
            is BrowserEvent.NetworkCaptured -> {
                _network.update { prev ->
                    val next = (prev + event.request)
                    if (next.size > networkCapacity) next.takeLast(networkCapacity) else next
                }
            }
            is BrowserEvent.HookHit -> {
                _hookHits.update { prev ->
                    val next = (prev + event.hit)
                    if (next.size > hookHitCapacity) next.takeLast(hookHitCapacity) else next
                }
            }
            is BrowserEvent.DebugPaused -> {
                tabDebugPaused[event.tabId] = event.state
                val record = DebugEventRecord(
                    tabId = event.tabId,
                    kind = "paused",
                    reason = event.state.reason,
                    summary = event.state.callFrames.firstOrNull()
                        ?.let { "${it.functionName.ifEmpty { "<anonymous>" }} @ ${it.url}:${it.lineNumber + 1}" }
                        ?: "no frames",
                    at = event.at,
                )
                _debugEvents.update { prev ->
                    val next = (prev + record)
                    if (next.size > debugEventCapacity) next.takeLast(debugEventCapacity) else next
                }
            }
            is BrowserEvent.DebugResumed -> {
                tabDebugPaused.remove(event.tabId)
                val record = DebugEventRecord(tabId = event.tabId, kind = "resumed", at = event.at)
                _debugEvents.update { prev ->
                    val next = (prev + record)
                    if (next.size > debugEventCapacity) next.takeLast(debugEventCapacity) else next
                }
            }
            is BrowserEvent.RenderProcessGone -> {
                // 渲染进程崩溃：转成 console ERROR 行，agent 经 browser.console_list 可见
                val line = ConsoleLine(
                    event.tabId,
                    "ERROR",
                    "WebView render process gone (crash=${event.didCrash}); tab ${event.tabId} closed, reopen with browser.open",
                    event.at,
                )
                _console.update { prev ->
                    val next = (prev + line)
                    if (next.size > consoleCapacity) next.takeLast(consoleCapacity) else next
                }
            }
            is BrowserEvent.ScreenshotSaved, is BrowserEvent.UserInteractionHappened -> {
                /* handled by snapshot flow / ignored */
            }
        }
        Unit
    }

    suspend fun clearConsole() = mutex.withLock {
        _console.value = emptyList()
    }

    suspend fun clearHookHits() = mutex.withLock {
        _hookHits.value = emptyList()
    }

    suspend fun clearDebugEvents() = mutex.withLock {
        _debugEvents.value = emptyList()
    }

    suspend fun resetForTab(tabId: String) = mutex.withLock {
        tabUrls.remove(tabId)
        tabTitles.remove(tabId)
        tabSnapshots.remove(tabId)
        if (isActiveTab(tabId)) {
            _snapshot.value = null
            _url.value = ""
            _title.value = ""
        }
        _console.value = _console.value.filter { it.tabId != tabId }
        _network.value = _network.value.filter { it.tabId != tabId }
        _hookHits.value = _hookHits.value.filter { it.tabId != tabId }
        _debugEvents.value = _debugEvents.value.filter { it.tabId != tabId }
        tabDebugPaused.remove(tabId)
    }

    suspend fun setActiveTab(tab: BrowserSessionToken?) = mutex.withLock {
        _activeTab.value = tab
        val id = tab?.tabId
        _url.value = id?.let { tabUrls[it] } ?: ""
        _title.value = id?.let { tabTitles[it] } ?: ""
        _snapshot.value = id?.let { tabSnapshots[it] }
    }
}
