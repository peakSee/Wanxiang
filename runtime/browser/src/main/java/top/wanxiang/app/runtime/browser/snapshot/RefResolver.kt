package top.wanxiang.app.runtime.browser.snapshot

import top.wanxiang.app.runtime.browser.BrowserSessionToken

/**
 * ref → 实际 selector 解析器。
 *
 * 委托给 [SnapshotBuilder] 内部的 ResolverRegistry；UI 与 AI 共用同一张 refMap，
 * key 是 (tabId, ref)，value 是动态注入的 CSS selector + `[data-wanxiang-ref='eN']` 兜底。
 */
class RefResolver {
    fun resolve(builder: SnapshotBuilder, tab: BrowserSessionToken, ref: String): String? =
        builder.resolve(tab.tabId, ref)

    fun forget(builder: SnapshotBuilder, tabId: String) = builder.clear(tabId)
}
