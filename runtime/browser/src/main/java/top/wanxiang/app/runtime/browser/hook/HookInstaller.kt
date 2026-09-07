package top.wanxiang.app.runtime.browser.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonPrimitive

/**
 * 每引擎一个安装器：为每个 tab 的 WebView 装 `WanxiangBridge`（addJavascriptInterface）
 * 与 document-start 脚本（[WebViewCompat.addDocumentStartJavaScript]，WebView 105+）。
 *
 * 线程约定：所有方法都必须在主线程调用（[WebViewTabPool] 的 create / close 均在主线程上下文）。
 * 降级路径：不支持 document-start 时由 [injectFallback]（onPageStarted）补种，
 * [verifyInstalled]（onPageFinished）验证；IIFE 幂等守卫使双注无害。
 */
class HookInstaller(
    context: Context,
    private val pipeline: HookEventPipeline,
    private val store: HookRuleStore,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val docStartHandles = ConcurrentHashMap<String, ScriptHandler>()

    /** 懒加载并缓存 assets/hook_runtime.js。 */
    val runtimeScript: String by lazy {
        appContext.assets.open("hook_runtime.js").bufferedReader().use { it.readText() }
    }

    /** WebView 创建后、loadUrl 之前调用（主线程）。 */
    fun onWebViewCreated(tabId: String, view: WebView) {
        view.addJavascriptInterface(WanxiangHookBridge(tabId, pipeline), "WanxiangBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                docStartHandles[tabId] =
                    WebViewCompat.addDocumentStartJavaScript(view, runtimeScript, setOf("*"))
            }
        }
    }

    /** tab 关闭 / 崩溃 / 引擎 shutdown 时调用（内部保证主线程执行）：移除 document-start handle 与桥。 */
    fun onWebViewDestroyed(tabId: String, view: WebView) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doDestroy(tabId, view)
        } else {
            mainHandler.post { doDestroy(tabId, view) }
        }
    }

    private fun doDestroy(tabId: String, view: WebView) {
        docStartHandles.remove(tabId)?.let { ref -> runCatching { ref.remove() } }
        runCatching { view.removeJavascriptInterface("WanxiangBridge") }
    }

    /** onPageStarted：仅降级路径需要（无 document-start 支持的古董 WebView）。 */
    fun injectFallback(view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching { view.evaluateJavascript(runtimeScript, null) }
    }

    /** onPageFinished：验证 runtime 存在，缺失则补种（幂等）。 */
    fun verifyInstalled(view: WebView) {
        runCatching {
            view.evaluateJavascript("!!window.__wanxiangHooks") { present ->
                if (present != "true") view.evaluateJavascript(runtimeScript, null)
            }
        }
    }

    /** 规则变更后推送到已加载页面（主线程）。payload 嵌入用 JSON 字符串字面量（= 合法 JS 字面量）。 */
    fun pushRules(tabId: String, view: WebView) {
        val payload = store.payloadFor(tabId)
        val literal = JsonPrimitive(payload).toString()
        runCatching {
            view.evaluateJavascript(
                "window.__wanxiangApplyRules&&window.__wanxiangApplyRules($literal)", null
            )
        }
    }
}
