package top.wanxiang.app.runtime.browser.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.View
import android.webkit.WebView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.wanxiang.app.core.model.ToolImageRef
import androidx.core.graphics.createBitmap
import kotlin.time.Duration.Companion.milliseconds

/**
 * 把当前 WebView 内容截图落盘到 `cacheDir/wanxiang-browser/screenshots/<tabId>/<ts>.png`。
 *
 * 实现策略：在主线程上 `view.draw(canvas)` 软渲到 ARGB_8888 Bitmap，然后切到 IO 线程压缩为 PNG。
 *
 * 注：MVP 不做 PixelCopy（API 24+ 可加），原因是它需要宿主 Activity Window；软渲对
 * 多数简单 HTML 页面已足够清晰，复杂动画场景 v1.1 升级。
 */
class ScreenshotRecorder(private val context: Context) {

    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()

    /**
     * 资源归属：本类创建 HandlerThread，就必须自己 quit —— 引擎每次注销重建都会 new 一个
     * Recorder（[top.wanxiang.app.runtime.browser.BrowserRegistryImpl.shutdown] 会清空引擎列表），
     * 不回收就是一轮一个常驻线程。用显式 [Lazy] 而不是 `by lazy` 是为了让 [shutdown]
     * 能区分“线程真的起过”和“从未截图”，后者不该被 shutdown 反向唤起。
     */
    private val ioThreadDelegate = lazy {
        HandlerThread("wanxiang-browser-screenshot").apply {
            isDaemon = true
            start()
        }
    }
    private val ioThread by ioThreadDelegate
    private val ioHandler by lazy { Handler(ioThread.looper) }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 超时后迟到的 Bitmap 回收兜底：独立于调用方协程，不随其取消。 */
    private val lateRecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    suspend fun capture(
        view: WebView,
        tabId: String,
        preferredWidth: Int? = null,
        preferredHeight: Int? = null,
        timeoutMs: Long = 8_000L,
    ): ToolImageRef? {
        if (closed.get()) return null
        val deferred = CompletableDeferred<Bitmap?>()
        mainHandler.post {
            // WebView 无 isDestroyed API：视图已销毁时 measure/layout/draw 可能抛异常，整体兜底为 null
            val bmp: Bitmap? = try {
                val w = preferredWidth ?: view.width.takeIf { it > 0 } ?: 1080
                val h = preferredHeight ?: view.height.takeIf { it > 0 } ?: 1920
                // 离屏 / 从未 layout 的 WebView 先强制 measure+layout，避免软渲空白
                if (view.width <= 0 || view.height <= 0) {
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                    )
                    view.layout(0, 0, w, h)
                }
                val b = createBitmap(w, h)
                try {
                    view.draw(Canvas(b))
                    b
                } catch (t: Throwable) {
                    b.recycle()
                    null
                }
            } catch (t: Throwable) {
                null
            }
            // capture() 与引擎 shutdown 可能并发：关闭后才完成的主线程绘制不能再进入 IO 阶段。
            if (closed.get()) {
                bmp?.recycle()
                deferred.complete(null)
            } else {
                deferred.complete(bmp)
            }
        }
        val bmp = withTimeoutOrNull(timeoutMs.milliseconds) { deferred.await() }
        if (bmp == null) {
            // 超时放弃等待，但主线程 block 稍后仍可能完成回调并产生 Bitmap：到达即回收，避免泄漏
            lateRecycleScope.launch { deferred.await()?.recycle() }
            return null
        }
        if (closed.get()) {
            bmp.recycle()
            return null
        }
        val file = writePngAsync(tabId, bmp) ?: return null
        return ToolImageRef(
            id = UUID.randomUUID().toString(),
            uri = file,
            mime = "image/png",
            width = bmp.width,
            height = bmp.height,
            caption = "browser screenshot tab=$tabId",
        )
    }

    private suspend fun writePngAsync(tabId: String, bmp: Bitmap): String? {
        val dir = File(context.cacheDir, "wanxiang-browser/screenshots/$tabId").apply { mkdirs() }
        val filename = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".png"
        val file = File(dir, filename)
        val done = CompletableDeferred<String>()
        // shutdown 之后 looper 已退出，post 返回 false：必须在这里回收并放弃，
        // 否则 done 永远不会完成，调用方协程会永久挂起。
        // Handler 的取得也必须与 shutdown 串行化，否则“先检查、后初始化”仍会在关闭后起线程。
        val handler = synchronized(lifecycleLock) {
            if (closed.get()) null else ioHandler
        }
        if (handler == null) {
            bmp.recycle()
            return null
        }
        val posted = handler.post {
            runCatching {
                FileOutputStream(file).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
            }
            bmp.recycle()
            done.complete(file.absolutePath)
        }
        if (!posted) {
            bmp.recycle()
            return null
        }
        // 挂起等待压缩完成：不再 runBlocking 阻塞 MCP 工作线程，也保留取消传播
        return done.await()
    }

    /** closeTab / shutdown 时删除该 tab 的截图目录，防止 cacheDir 无限增长。 */
    suspend fun cleanup(tabId: String) {
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "wanxiang-browser/screenshots/$tabId")
            if (dir.exists()) dir.deleteRecursively()
        }
    }

    /**
     * 释放本类持有的线程与协程作用域。引擎 shutdown 时调用；未截过图时不会唤起 lazy 线程。
     *
     * 正在进行的压缩会被 [HandlerThread.quitSafely] 保留到执行完（已入队的消息照常处理），
     * 之后再 post 会被拒绝并由 [writePngAsync] 回收 Bitmap。
     */
    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lifecycleLock) {
            if (ioThreadDelegate.isInitialized()) {
                ioThread.quitSafely()
            }
        }
        lateRecycleScope.cancel()
    }

    internal fun isIoThreadInitializedForTest(): Boolean = ioThreadDelegate.isInitialized()
}
