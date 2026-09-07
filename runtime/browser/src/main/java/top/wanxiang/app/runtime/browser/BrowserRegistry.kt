package top.wanxiang.app.runtime.browser

import kotlinx.coroutines.flow.StateFlow
import top.wanxiang.app.core.browser.BrowserDescriptor
import top.wanxiang.app.core.browser.BrowserFamily
import top.wanxiang.app.core.browser.BrowserPreferences

/**
 * 浏览器注册中心：浏览器家族的注册表 + 选择策略入口。
 *
 * - 单例（@Singleton），由 [di.BrowserModule] 提供；
 * - 启动时由 [start] 拉起所有"可启动"的引擎（当前 MVP 仅 [BrowserFamily.IN_APP]）；
 * - 由 [getDefault] / [get] / [getForUrl] 三种入口暴露给 harness 与 UI；
 * - 全局事件总线：[eventBus]；多引擎共用。
 */
interface BrowserRegistry {
    val eventBus: BrowserEventBus
    val descriptors: StateFlow<List<BrowserDescriptor>>

    /** Sync-list: get descriptors. */
    fun list(): List<BrowserDescriptor>

    /** Get a specific [family] engine; null when unhealthy or absent. */
    fun get(family: BrowserFamily): BrowserEngine?

    /** Choose engine best-fit for [urlHint] (URL-based heuristic). */
    fun getForUrl(url: String?): BrowserEngine

    /** Default engine honoring [prefs.defaultFamily] with fallback. */
    fun getDefault(prefs: BrowserPreferences = BrowserPreferences.DEFAULT): BrowserEngine

    /** Health probe: cheapest possible check. */
    suspend fun verify(family: BrowserFamily): Boolean

    /** Bring all healthy engines online; idempotent. */
    suspend fun start()

    /** Stop every engine and clear resources; idempotent. */
    suspend fun shutdown()
}
