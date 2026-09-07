package top.wanxiang.app.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.webkit.WebView
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.wanxiang.app.core.browser.BrowserDescriptor
import top.wanxiang.app.core.browser.BrowserFamily
import top.wanxiang.app.core.browser.PageSnapshot
import top.wanxiang.app.core.datastore.BrowserPreferences
import top.wanxiang.app.runtime.browser.BrowserEventBus
import top.wanxiang.app.runtime.browser.BrowserRegistry
import top.wanxiang.app.runtime.browser.BrowserSessionToken
import top.wanxiang.app.runtime.browser.CapturedRequest
import top.wanxiang.app.runtime.browser.InAppBrowserViewProvider
import top.wanxiang.app.runtime.browser.cdp.DebugPausedState
import top.wanxiang.app.runtime.browser.hook.HookHitRecord
import top.wanxiang.app.ui.browser.snapshot.SnapshotSheetState

@HiltViewModel
class BrowserViewModel @Inject constructor(
    val registry: BrowserRegistry,
    val eventBus: BrowserEventBus,
    private val browserPreferences: BrowserPreferences,
) : ViewModel() {

    private val _urlInput = MutableStateFlow("")
    private val _coBrowsingEnabled = MutableStateFlow(true)
    private val _toolMessage = MutableStateFlow<String?>(null)
    private val _snapshotSheet = MutableStateFlow<SnapshotSheetState?>(null)
    private val _tabs = MutableStateFlow<List<BrowserTabUi>>(emptyList())
    private val _canGoBack = MutableStateFlow(false)
    private val _canGoForward = MutableStateFlow(false)

    // 浏览器活动信号：agent/用户导航、tab 切换时递增，供聊天页提示"浏览器有新动态"
    private val _activityTick = MutableStateFlow(0L)
    // 面板可见性：只有浏览器面板真正被展示时才确保/补建活跃 tab，避免 app 启动即常驻 WebView
    private val _paneVisible = MutableStateFlow(false)

    private val coreState = combine(
        eventBus.snapshot,
        eventBus.url,
        eventBus.title,
        registry.descriptors,
        eventBus.activeTab,
    ) { snapEvent, url, title, descriptors, activeTab ->
        BrowserCoreState(snapEvent?.snapshot, url, title, descriptors, activeTab)
    }

    private val panelState = combine(
        _urlInput,
        _coBrowsingEnabled,
        _toolMessage,
        _snapshotSheet,
        _tabs,
    ) { urlInput, coBrowsing, toolMessage, snapshotSheet, tabs ->
        BrowserPanelState(urlInput, coBrowsing, toolMessage, snapshotSheet, tabs)
    }

    val uiState: StateFlow<BrowserUiState> = combine(
        coreState,
        panelState,
        _canGoBack,
        _canGoForward,
        _activityTick,
    ) { core, panel, canGoBack, canGoForward, activityTick ->
        BrowserUiState(
            url = core.url,
            urlInput = panel.urlInput,
            title = core.title,
            descriptors = core.descriptors,
            lastSnapshot = core.snapshot,
            coBrowsingEnabled = panel.coBrowsingEnabled,
            toolMessage = panel.toolMessage,
            snapshotSheet = panel.snapshotSheet,
            activeTab = core.activeTab,
            tabs = panel.tabs,
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            activityTick = activityTick,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BrowserUiState.Empty)

    // ===== 网络时间线 / Hook 命中 / CDP 暂停（独立流，避免撑爆 uiState 的 combine 上限） =====

    /** 网络时间线：native 捕获与 js hook 捕获去重合并（js 条目带状态码与 body，优先保留）。 */
    val network: StateFlow<List<CapturedRequest>> = eventBus.network
        .map(::dedupeNetwork)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** hook 命中记录（函数/属性/WebSocket/网络规则），直接透出 EventBus 环形缓冲。 */
    val hookHits: StateFlow<List<HookHitRecord>> = eventBus.hookHits

    /** 当前活跃 tab 的 CDP 暂停态（null = 运行中/未 attach）；debugEvents 变化时重读分片。 */
    val debugPaused: StateFlow<DebugPausedState?> = combine(
        eventBus.debugEvents,
        eventBus.activeTab,
    ) { _, tab -> tab?.let { eventBus.debugPausedOf(it.tabId) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _networkSheetVisible = MutableStateFlow(false)
    val networkSheetVisible: StateFlow<Boolean> = _networkSheetVisible.asStateFlow()

    private val _networkDetail = MutableStateFlow<String?>(null)
    val networkDetail: StateFlow<String?> = _networkDetail.asStateFlow()

    fun showNetworkSheet() { _networkSheetVisible.value = true }

    fun dismissNetworkSheet() {
        _networkSheetVisible.value = false
        _networkDetail.value = null
    }

    /** 拉取单条请求的完整详情（元数据 + 请求/响应头与 body），由引擎 networkDetail 输出。 */
    fun loadNetworkDetail(id: String) = viewModelScope.launch {
        _networkDetail.value = runCatching {
            registry.getDefault().networkDetail(id)
        }.getOrNull() ?: "（无详情：引擎未就绪，或 body 未捕获）"
    }

    /** 手动放行 CDP 暂停（断点/异常挂起）；恢复全部已暂停的 tab。 */
    fun resumeDebugger() = viewModelScope.launch {
        runCatching { registry.getDefault().debugResume(null) }
            .onFailure { _toolMessage.value = "恢复失败：${it.message ?: "CDP 未启用"}" }
    }

    /**
     * native（shouldInterceptRequest，无状态码）与 js（hook 引擎，有状态码/body）
     * 对同一请求会各出一条：同 method+url 且 2.5s 时间窗内视为重复，隐藏 native 条目。
     */
    private fun dedupeNetwork(all: List<CapturedRequest>): List<CapturedRequest> {
        val jsEntries = all.filter { it.source != "native" }
        if (jsEntries.isEmpty()) return all
        return all.filterNot { native ->
            native.source == "native" && jsEntries.any {
                it.method == native.method && it.url == native.url &&
                    kotlin.math.abs(it.startedAt - native.startedAt) < 2_500
            }
        }
    }

    init {
        viewModelScope.launch {
            eventBus.url.collect { loadedUrl ->
                if (loadedUrl.isNotBlank()) _urlInput.value = loadedUrl
            }
        }
        // 共浏览开关：从 DataStore 读取并保持同步（设置页等其他入口的修改也会生效）
        viewModelScope.launch {
            runCatching {
                browserPreferences.coBrowsingEnabled().collect { _coBrowsingEnabled.value = it }
            }
        }
        // 浏览器活动信号：活跃 tab URL 变化 / tab 切换时递增（聊天页据此显示"浏览器有新动态"）
        viewModelScope.launch {
            var lastUrl = ""
            eventBus.url.collect { url ->
                if (url != lastUrl) {
                    lastUrl = url
                    _activityTick.value++
                }
            }
        }
        viewModelScope.launch {
            eventBus.activeTab.collect { tab ->
                if (tab != null) _activityTick.value++
            }
        }
        // 引擎异步注册的竞态修复：descriptors 出现健康的 IN_APP 引擎时（重新）确保活跃 tab 存在
        // 仅在面板可见时确保，避免 app 启动即创建常驻 WebView
        viewModelScope.launch {
            registry.descriptors.collect { descriptors ->
                if (_paneVisible.value &&
                    descriptors.any { it.family == BrowserFamily.IN_APP && it.healthy }
                ) {
                    ensureActiveTab()
                }
            }
        }
        // 轻量轮询：WebView 历史栈状态 + tab 列表（覆盖 AI agent 侧的开关 tab / 前后台导航）
        viewModelScope.launch {
            var tick = 0
            while (isActive) {
                if (_paneVisible.value) {
                    refreshNavState()
                    if (tick % 2 == 0) syncTabs()
                }
                tick++
                delay(500)
            }
        }
    }

    /** 浏览器面板进入/离开组合时回调；可见时才补建活跃 tab 与轮询状态。 */
    fun onPaneVisible(visible: Boolean) {
        _paneVisible.value = visible
        if (visible) viewModelScope.launch { ensureActiveTab() }
    }

    fun onUrlInputChanged(value: String) { _urlInput.value = value }

    fun onNavigate() {
        val raw = _urlInput.value.trim()
        if (raw.isBlank()) return
        val target = normalizeUrl(raw) ?: run {
            _toolMessage.value = "不支持的链接：仅允许 http/https（或 about:blank）"
            return
        }
        viewModelScope.launch {
            runCatching {
                val engine = registry.getDefault()
                val active = engine.activeTab()
                if (active == null) engine.openTab(target, activate = true)
                else engine.navigate(active, target)
                _urlInput.value = target
                _toolMessage.value = "已打开 $target"
            }.onFailure { _toolMessage.value = it.message ?: "导航失败" }
            syncTabs()
        }
    }

    /**
     * 后退。引擎历史耗尽（或引擎不可用）时回调 [onExhausted]，
     * 供屏幕级 BackHandler 在 WebView 无法后退时退出浏览器页。
     */
    fun onBack(onExhausted: (() -> Unit)? = null) = viewModelScope.launch {
        val engine = runCatching { registry.getDefault() }.getOrNull()
        val tab = engine?.activeTab()
        val moved = engine != null && tab != null &&
            runCatching { engine.back(tab) }.getOrDefault(false)
        if (!moved) onExhausted?.invoke()
        refreshNavState()
        syncTabs()
    }
    /** 同步返回处理：WebView 可后退则消费（异步执行后退）并返回 true；耗尽返回 false 交由上层（如切回对话页）。 */
    fun handleBackImmediate(): Boolean {
        if (!_canGoBack.value) return false
        onBack()
        return true
    }

    fun onForward() = viewModelScope.launch {
        val engine = runCatching { registry.getDefault() }.getOrNull() ?: return@launch
        engine.activeTab()?.let { runCatching { engine.forward(it) } }
        refreshNavState()
    }
    fun onRefresh() = viewModelScope.launch {
        val engine = runCatching { registry.getDefault() }.getOrNull() ?: return@launch
        engine.activeTab()?.let { runCatching { engine.refresh(it) } }
    }

    fun onCoBrowsingToggle(enabled: Boolean) {
        if (_coBrowsingEnabled.value == enabled) return
        _coBrowsingEnabled.value = enabled
        viewModelScope.launch { runCatching { browserPreferences.setCoBrowsingEnabled(enabled) } }
    }

    fun selectTab(tabId: String) = viewModelScope.launch {
        val engine = runCatching { registry.getDefault() }.getOrNull() ?: return@launch
        if (engine.activeTab()?.tabId == tabId) return@launch
        runCatching { engine.listTabs() }.getOrDefault(emptyList())
            .firstOrNull { it.tabId == tabId }
            ?.let { token -> runCatching { engine.setActiveTab(token) } }
        refreshNavState()
        syncTabs()
    }

    fun closeTab(tabId: String) = viewModelScope.launch {
        val engine = runCatching { registry.getDefault() }.getOrNull() ?: return@launch
        val tab = runCatching { engine.listTabs() }.getOrDefault(emptyList())
            .firstOrNull { it.tabId == tabId } ?: return@launch
        runCatching { engine.closeTab(tab) }
        // 关掉唯一 tab 后保留一个空白页，避免视图永久空白
        if (engine.activeTab() == null) {
            runCatching { engine.openTab("about:blank", activate = true) }
        }
        refreshNavState()
        syncTabs()
    }

    fun onOpenSnapshot() {
        val s = eventBus.snapshot.value?.snapshot
        if (s != null) _snapshotSheet.value = SnapshotSheetState(url = s.url, title = s.title, snapshot = s)
    }
    fun dismissSnapshot() { _snapshotSheet.value = null }
    fun dismissToolMessage() { _toolMessage.value = null }

    fun activeWebView(): WebView? =
        (runCatching { registry.getDefault() }.getOrNull() as? InAppBrowserViewProvider)?.activeWebView()

    private fun ensureActiveTab() = viewModelScope.launch {
        runCatching {
            val engine = registry.getDefault()
            if (engine.activeTab() == null) engine.openTab("about:blank", activate = true)
        }.onFailure { _toolMessage.value = it.message ?: "浏览器引擎尚未就绪" }
        syncTabs()
    }

    /** 轮询同步 tab 列表；引擎已注册但无 tab 时静默补一个空白 tab（兜底竞态/全部关闭）。 */
    private suspend fun syncTabs() {
        val engine = runCatching { registry.getDefault() }.getOrNull() ?: return
        if (engine.activeTab() == null) {
            runCatching { engine.openTab("about:blank", activate = true) }
        }
        val tokens = runCatching { engine.listTabs() }.getOrDefault(emptyList())
        _tabs.value = tokens.toTabUi(engine.activeTab()?.tabId)
    }

    private fun refreshNavState() {
        val view = activeWebView()
        _canGoBack.value = view?.canGoBack() == true
        _canGoForward.value = view?.canGoForward() == true
    }

    private fun List<BrowserSessionToken>.toTabUi(activeId: String?): List<BrowserTabUi> = map { token ->
        val liveUrl = eventBus.urlOf(token.tabId).orEmpty().ifBlank { token.url }
        val liveTitle = eventBus.titleOf(token.tabId).orEmpty()
            .ifBlank { token.title }
            .ifBlank { liveUrl }
        BrowserTabUi(tabId = token.tabId, title = liveTitle, url = liveUrl, active = token.tabId == activeId)
    }

    /** URL 归一化：仅放行 http/https 与 about:blank；其余协议（javascript:/file:/intent: 等）拒绝。 */
    private fun normalizeUrl(value: String): String? {
        if (value.equals("about:blank", ignoreCase = true)) return "about:blank"
        val schemeEnd = value.indexOf("://")
        if (schemeEnd <= 0) return "https://$value"
        val scheme = value.take(schemeEnd).lowercase()
        return if (scheme == "http" || scheme == "https") value else null
    }
}

private data class BrowserCoreState(
    val snapshot: PageSnapshot?,
    val url: String,
    val title: String,
    val descriptors: List<BrowserDescriptor>,
    val activeTab: BrowserSessionToken?,
)

private data class BrowserPanelState(
    val urlInput: String,
    val coBrowsingEnabled: Boolean,
    val toolMessage: String?,
    val snapshotSheet: SnapshotSheetState?,
    val tabs: List<BrowserTabUi>,
)

/** 标签栏单条的 UI 模型（title/url 取 eventBus 实时值，token 值兜底）。 */
data class BrowserTabUi(
    val tabId: String,
    val title: String,
    val url: String,
    val active: Boolean,
)

data class BrowserUiState(
    val url: String,
    val urlInput: String,
    val title: String,
    val descriptors: List<BrowserDescriptor>,
    val lastSnapshot: PageSnapshot?,
    val coBrowsingEnabled: Boolean,
    val toolMessage: String?,
    val snapshotSheet: SnapshotSheetState?,
    val activeTab: BrowserSessionToken?,
    val tabs: List<BrowserTabUi>,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    /** 浏览器活动信号（单调递增）：供聊天页在浏览器面板隐藏时提示"有新动态"。 */
    val activityTick: Long = 0L,
) {
    companion object {
        val Empty = BrowserUiState(
            url = "",
            urlInput = "",
            title = "",
            descriptors = emptyList(),
            lastSnapshot = null,
            coBrowsingEnabled = true,
            toolMessage = null,
            snapshotSheet = null,
            activeTab = null,
            tabs = emptyList(),
            canGoBack = false,
            canGoForward = false,
            activityTick = 0L,
        )
    }
}
