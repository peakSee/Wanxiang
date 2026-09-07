package top.wanxiang.app.runtime.browser

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.wanxiang.app.core.browser.BrowserCapability
import top.wanxiang.app.core.browser.BrowserDescriptor
import top.wanxiang.app.core.browser.BrowserFamily
import top.wanxiang.app.core.browser.BrowserPreferences
import top.wanxiang.app.core.browser.BrowserSelectionPolicy

/**
 * 默认实现：以 in-app WebView 单一家族为 MVP；External CT / Remote CDP 留接口桩位。
 *
 * 选择策略使用 [BrowserSelectionPolicy]（详见 core/browser）。
 *
 * 多引擎：v1 不实现多实例；保留接口 [BrowserEngine] 与 [list] 多条目的扩展空间。
 */
class BrowserRegistryImpl(override val eventBus: BrowserEventBus) : BrowserRegistry {
    // CopyOnWriteArrayList：registerEngine 写在 @Synchronized 内，get/verify/start 等无锁读安全
    private val inAppEngine = CopyOnWriteArrayList<BrowserEngine>()
    private val _descriptors = MutableStateFlow<List<BrowserDescriptor>>(emptyList())
    override val descriptors: StateFlow<List<BrowserDescriptor>> = _descriptors.asStateFlow()

    @Synchronized
    fun registerEngine(engine: BrowserEngine) {
        when (engine.family) {
            BrowserFamily.IN_APP -> {
                if (inAppEngine.any { it === engine }) return
                inAppEngine.clear()
                inAppEngine += engine
            }
            else -> { /* reserved for v1.1+ */ }
        }
        refreshDescriptors()
    }

    private fun refreshDescriptors() {
        val all = buildList {
            inAppEngine.map { add(it.descriptor) }
            add(
                BrowserDescriptor(
                    family = BrowserFamily.EXTERNAL_CT,
                    displayName = "Chrome Custom Tabs",
                    healthy = false,
                    capabilities = setOf(BrowserCapability.OPEN),
                    notes = "v1.1+ 计划项；当前不可用。"
                )
            )
            add(
                BrowserDescriptor(
                    family = BrowserFamily.REMOTE_CDP,
                    displayName = "Remote Chromium DevTools",
                    healthy = false,
                    capabilities = emptySet(),
                    notes = "v1.1+ 计划项；当前不可用。"
                )
            )
        }
        _descriptors.value = all
    }

    override fun list(): List<BrowserDescriptor> = _descriptors.value

    override fun get(family: BrowserFamily): BrowserEngine? = when (family) {
        BrowserFamily.IN_APP -> inAppEngine.firstOrNull()
        BrowserFamily.EXTERNAL_CT -> null
        BrowserFamily.REMOTE_CDP -> null
    }

    override fun getForUrl(url: String?): BrowserEngine {
        val families = _descriptors.value.associate { it.family to it.healthy }
        val selection = BrowserSelectionPolicy.decide(
            requested = null, urlHint = url, prefs = BrowserPreferences.DEFAULT, families = families
        )
        return get(selection.family) ?: inAppEngine.firstOrNull() ?: error("in-app engine 未注册")
    }

    override fun getDefault(prefs: BrowserPreferences): BrowserEngine {
        val families = _descriptors.value.associate { it.family to it.healthy }
        val selection = BrowserSelectionPolicy.decide(
            requested = null, urlHint = null, prefs = prefs, families = families
        )
        return get(selection.family) ?: inAppEngine.firstOrNull() ?: error("in-app engine 未注册")
    }

    override suspend fun verify(family: BrowserFamily): Boolean = when (family) {
        BrowserFamily.IN_APP -> inAppEngine.isNotEmpty()
        BrowserFamily.EXTERNAL_CT -> false
        BrowserFamily.REMOTE_CDP -> false
    }

    override suspend fun start() {
        inAppEngine.forEach { /* engines bootstrap lazily in their constructor */ }
    }

    override suspend fun shutdown() {
        inAppEngine.forEach { it.shutdown() }
        inAppEngine.clear()
        refreshDescriptors()
    }
}
