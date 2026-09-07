package top.wanxiang.app.runtime.browser.engine

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.wanxiang.app.runtime.browser.BrowserEvent
import top.wanxiang.app.runtime.browser.BrowserEventBus
import top.wanxiang.app.runtime.browser.BrowserSessionToken
import top.wanxiang.app.runtime.browser.hook.HookInstaller
import top.wanxiang.app.runtime.browser.network.NetworkInterceptor
import top.wanxiang.app.runtime.browser.snapshot.SnapshotBuilder

/**
 * 给 WebView 装好 [WebViewClient]/[WebChromeClient]，把所有事件经 per-tab [scope] 派发到 [eventBus]。
 *
 * 注：
 * - WebView 回调线程就是主线程，scope 用 [kotlinx.coroutines.Dispatchers.Main.immediate]；
 * - [scope] 与 [snapshotBuilder] 由 [WebViewTabPool] 创建并持有：closeTab / shutdown 时统一
 *   取消 scope，避免回调协程在视图销毁后继续触发刷新；builder 为该 tab 唯一实例，
 *   与 engine.snapshot 共用（防止双扫描并发重写 data-wanxiang-ref 导致 ref 错位）。
 */
object WebViewClients {

    /** WebView 默认可处理的 web 类 scheme；其余（app 深链）一律在 shouldOverrideUrlLoading 拦截。 */
    private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")

    fun attach(
        view: WebView,
        eventBus: BrowserEventBus,
        token: BrowserSessionToken,
        pool: WebViewTabPool,
        snapshotBuilder: SnapshotBuilder,
        scope: CoroutineScope,
        hookInstaller: HookInstaller? = null,
    ): WebView {
        val networkInterceptor = NetworkInterceptor(token, eventBus, scope)

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): Boolean = handleUrlLoading(view, request.url)

            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION", "OverridingDeprecatedMember")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleUrlLoading(view, Uri.parse(url))

            /**
             * 拦截非 web scheme 的导航：移动站常带"唤起 App"深链（baiduboxapp:// 等），
             * WebView 默认尝试加载会失败并可能把页面劫持跳走。
             *  - http/https/about/data/blob/javascript 交给 WebView 默认处理（返回 false）；
             *  - intent:// 提取 browser_fallback_url 兜底加载；
             *  - 其余深链尝试还原 query 中的 url= 参数（如百度 baiduboxapp://v1/browser/open?url=…），
             *    还原失败则直接吞掉（返回 true，页面留在原地）。
             */
            fun handleUrlLoading(view: WebView, uri: Uri): Boolean {
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme in WEB_SCHEMES) return false
                scope.launch {
                    eventBus.publish(
                        BrowserEvent.ConsoleLogged(
                            token.tabId, "WARNING",
                            "已拦截深链跳转: $scheme (页面留在原地)",
                            System.currentTimeMillis(),
                        )
                    )
                }
                // intent://…#Intent;scheme=…;S.browser_fallback_url=<encoded>;end
                if (scheme == "intent") {
                    val fallback = uri.fragment
                        ?.split(";")
                        ?.firstOrNull { it.startsWith("S.browser_fallback_url=") }
                        ?.removePrefix("S.browser_fallback_url=")
                        ?.takeIf { it.startsWith("http") }
                    if (fallback != null) {
                        view.loadUrl(fallback)
                        return true
                    }
                }
                // 深链带原始页面参数（baiduboxapp://…?url=<encoded>）：还原回原页面
                val embedded = runCatching { uri.getQueryParameter("url") }.getOrNull()
                if (embedded != null && embedded.startsWith("http")) {
                    view.loadUrl(embedded)
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): android.webkit.WebResourceResponse? {
                networkInterceptor.onRequestStart(request)
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 导航开始：清空该 tab 的 ref→selector 映射，旧 ref 不得解析到上一页元素
                snapshotBuilder.clear(token.tabId)
                // hook 降级路径：无 document-start 支持的古董 WebView 在此补种（幂等）
                hookInstaller?.injectFallback(view)
                scope.launch {
                    eventBus.publish(BrowserEvent.PageChanged(token.tabId, url ?: "", view.title ?: ""))
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // tab 已被关闭 / 崩溃销毁（移出池）则不再触发刷新
                if (pool.viewOf(token) !== view) return
                // hook runtime 验证：缺失则补种（IIFE 幂等守卫使双注无害）
                hookInstaller?.verifyInstalled(view)
                scope.launch {
                    eventBus.publish(BrowserEvent.PageChanged(token.tabId, url ?: "", view.title ?: ""))
                    // refresh 内部还有 isAlive 守卫
                    snapshotBuilder.refresh(view)
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // 渲染进程崩溃 / 被系统回收：发布错误事件（bus 会转成 console ERROR 行）、
                // 从池中移除 tab、摘除并销毁视图。返回 true 表示宿主已自行处理。
                pool.publishFromMain(BrowserEvent.RenderProcessGone(token.tabId, detail.didCrash()))
                pool.onRenderCrashed(token)
                AndroidWebViewFactory.destroy(view)
                return true
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                val msg = message ?: return true
                scope.launch {
                    eventBus.publish(
                        BrowserEvent.ConsoleLogged(
                            token.tabId, msg.messageLevel().name, msg.message(), System.currentTimeMillis()
                        )
                    )
                }
                return true
            }

            // WebView 以 applicationContext 创建，没有可用的窗口 token：
            // 默认实现弹系统对话框会 BadTokenException 崩溃，这里消费掉并给页面确定的返回值。
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.confirm()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean {
                result?.cancel()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?,
            ): Boolean {
                // 无 Activity 窗口可用：不接管文件选择
                return false
            }
        }
        return view
    }
}
