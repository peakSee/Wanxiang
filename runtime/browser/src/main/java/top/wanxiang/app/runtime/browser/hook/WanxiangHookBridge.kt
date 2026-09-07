package top.wanxiang.app.runtime.browser.hook

import android.webkit.JavascriptInterface

/**
 * 页内 hook runtime 与宿主之间的原生桥（每 tab 一个实例，绑定 tabId 免查表路由）。
 *
 * 安全设计（刻意只有 2 个 string-in/out 方法，无任何执行能力）：
 * - [onEvent]：页内异步上报，内部 trySend 有界 Channel 立即返回，绝不阻塞 JavaBridge 线程；
 * - [getRules]：同步返回缓存好的规则 payload JSON —— fetch/XHR wrapper 页内同步决策
 *   block/mock 的关键（异步回调式桥做不到这一点，这也是不采用第三方 JsBridge 库的原因）。
 */
class WanxiangHookBridge(
    private val tabId: String,
    private val pipeline: HookEventPipeline,
) {

    @JavascriptInterface
    fun onEvent(json: String?) {
        if (!json.isNullOrEmpty()) pipeline.enqueue(tabId, json)
    }

    @JavascriptInterface
    fun getRules(): String = pipeline.rulesPayloadFor(tabId)
}
