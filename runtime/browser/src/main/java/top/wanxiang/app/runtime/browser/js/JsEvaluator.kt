package top.wanxiang.app.runtime.browser.js

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

/**
 * JavaScript 注入与执行工具；协程视角封装 `WebView.evaluateJavascript`。
 *
 * 任何 evaluateJavascript 都受超时保护（默认 5s），避免 target 页面 JS 死循环卡住 UI 流。
 */
object JsEvaluator {

    suspend fun evaluate(view: WebView, script: String, timeoutMs: Long = 5_000L): String? {
        val main = Handler(Looper.getMainLooper())
        val deferred = CompletableDeferred<String?>()
        main.post {
            try {
                view.evaluateJavascript(script) { result ->
                    deferred.complete(result)
                }
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }
        return try {
            withTimeoutOrNull(timeoutMs.milliseconds) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            // #12：超时放弃等待；WebView 无法中止已派发的 evaluateJavascript，页面内副作用可能仍执行，
            // 回调结果因 deferred 已超时而被丢弃（不写回任何共享状态）。
            android.util.Log.w("JsEvaluator", "evaluateJavascript 超时(${timeoutMs}ms)，页面内脚本可能仍在执行")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 调用方协程被取消：正常传播，不得吞掉
            throw e
        } catch (t: Throwable) {
            // 视图已销毁 / WebView 内部异常：返回 null（调用方各自按"不可用"处理）
            android.util.Log.w("JsEvaluator", "evaluateJavascript 失败: ${t.message}")
            null
        }
    }

    /** 全局 `JSON.stringify` 后注入 payload；模型仅看 base64-free 文本结果。 */
    suspend fun evaluateJson(view: WebView, jsExpression: String, timeoutMs: Long = 5_000L): String? {
        val wrapped = "(function(){ try { return JSON.stringify($jsExpression) } catch(e){ return '{\"error\":\"'+e.message+'\"}' } })()"
        return evaluate(view, wrapped, timeoutMs)
    }

    /**
     * 解码 `evaluateJavascript` 回调结果：回调给的是 JSON 字面量（`"null"` / `"\"text\""` / `"123"`）。
     * 字符串字面量必须经 JSON 解码还原真实内容（`\"` / `\n` / `\uXXXX` 等转义），
     * 直接 `trim('"')` 会留下转义序列导致上层 JSON / 文本解析失败。
     */
    fun unwrap(raw: String): String {
        val s = raw.trim()
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return runCatching { Json.parseToJsonElement(s).jsonPrimitive.content }
                .getOrElse {
                    // JSON 解析失败的兜底：手工还原常见转义
                    s.substring(1, s.length - 1)
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\\\", "\\")
                }
        }
        return s
    }
}
