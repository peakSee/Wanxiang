package top.wanxiang.app.runtime.browser.engine

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * WebView 构造工厂，集中处理调试开关 / DOM storage / JavaScript / Cache。
 *
 * 所有调用必须发生在主线程（Android 限制）。
 */
object AndroidWebViewFactory {

    /** 已销毁视图标记（WeakHashMap 弱引用不泄漏视图，使 destroy 幂等）。 */
    private val destroyedViews = java.util.Collections.newSetFromMap(
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<WebView, Boolean>())
    )

    fun create(
        context: Context,
        desktopUserAgent: Boolean = false,
    ): WebView {
        val view = WebView(context.applicationContext)
        val s: WebSettings = view.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = true
        if (desktopUserAgent) {
            s.userAgentString = s.userAgentString.replace("Mobile", "") + " WanXiangDesktop/1.0"
        }
        return view
    }

    /**
     * 主线程调用：销毁 WebView（closeTab / shutdown / 渲染进程崩溃共用）。
     *
     * 销毁前必须先从视图树摘除（agent 可能正在关闭用户正在看的 tab，
     * Compose AndroidView 仍持有引用），否则 destroy 会打崩仍 attached 的视图。
     * WebView 没有公开的 isDestroyed 查询，用弱引用集合保证幂等。
     */
    fun destroy(view: WebView) {
        // add 返回 false = 已销毁过（closeTab 与崩溃清理并发时不会二次 destroy）
        if (!destroyedViews.add(view)) return
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }
}

