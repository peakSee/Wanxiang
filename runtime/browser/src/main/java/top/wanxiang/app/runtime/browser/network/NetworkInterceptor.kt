package top.wanxiang.app.runtime.browser.network

import android.webkit.WebResourceRequest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.wanxiang.app.runtime.browser.BrowserEvent
import top.wanxiang.app.runtime.browser.BrowserEventBus
import top.wanxiang.app.runtime.browser.BrowserSessionToken
import top.wanxiang.app.runtime.browser.CapturedRequest

/**
 * `shouldInterceptRequest` 拦截到的请求被 [NetworkInterceptor] 收下，
 * 直接 publish 到 [BrowserEventBus]，由上层 mcp__browser__network_list 查询。
 *
 * 注意：WebView 主线程调用，回调快、不阻塞 IO；拦截深度仅 headers，不截获 body（避免大对象开销）。
 */
class NetworkInterceptor(
    private val token: BrowserSessionToken,
    private val eventBus: BrowserEventBus,
    private val scope: CoroutineScope,
) {
    fun onRequestStart(request: WebResourceRequest) {
        val startedAt = System.currentTimeMillis()
        val req = CapturedRequest(
            id = UUID.randomUUID().toString(),
            tabId = token.tabId,
            url = request.url.toString(),
            method = request.method,
            // shouldInterceptRequest 只见请求、无响应回调可挂：此处恒为 0，
            // 展示侧（browser.network_list）不应把它当作 HTTP 状态码呈现
            statusCode = 0,
            requestHeaders = request.requestHeaders.orEmpty().toMap(),
            startedAt = startedAt,
        )
        scope.launch {
            eventBus.publish(BrowserEvent.NetworkCaptured(token.tabId, req))
        }
    }
}
