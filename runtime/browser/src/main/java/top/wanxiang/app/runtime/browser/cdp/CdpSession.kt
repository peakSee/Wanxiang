package top.wanxiang.app.runtime.browser.cdp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单 target 的 CDP 会话：JSON-RPC 2.0 over WebSocket。
 *
 * - 命令：`send(method, params)` → 自增 id → 挂起等待响应（[CompletableDeferred] 关联）；
 *   错误响应抛 [CdpCommandException]（CDP error 原文直达 agent）。
 * - 事件：入帧含 `method` → 有序 Channel（容量 256，DROP_OLDEST + 丢弃告警，仿
 *   HookEventPipeline 风格）→ 单协程顺序回调 listener，保证 paused/resumed 状态机不乱序。
 * - flat 子会话：`sessionId` 非空的命令/事件归属 Target.setAutoAttach 的 worker 会话。
 * - 断连：所有 pending 命令异常完成 + `onClosed` 回调。
 */
class CdpSession(
    private val ws: WsConnection,
    private val scope: CoroutineScope,
) : CdpCommandApi {
    interface EventListener {
        /** 事件回调已在 IO 协程；sessionId 非空表示来自 auto-attach 的 worker 子会话。 */
        suspend fun onEvent(method: String, params: JsonObject, sessionId: String?)

        suspend fun onClosed()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val eventChannel = Channel<Pair<JsonObject, String?>>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var droppedEvents = 0L
    @Volatile private var listener: EventListener? = null
    @Volatile private var closedByUs = false
    private var pumpJob: Job? = null

    fun start(listener: EventListener) {
        this.listener = listener
        ws.setListener(object : WsConnection.Listener {
            override fun onText(text: String) = handleText(text)
            override fun onClosed(code: Int, reason: String) = handleClosed()
            override fun onFailure(t: Throwable) = handleClosed()
        })
        pumpJob = scope.launch {
            for ((event, sessionId) in eventChannel) {
                val method = event["method"]?.jsonPrimitive?.contentOrNull ?: continue
                val params = (event["params"] as? JsonObject) ?: JsonObject(emptyMap())
                runCatching { listener.onEvent(method, params, sessionId) }
                if (droppedEvents > 0) {
                    val d = droppedEvents
                    droppedEvents = 0
                    android.util.Log.w(TAG, "CDP 事件溢出丢弃 $d 条")
                }
            }
        }
    }

    private fun handleText(text: String) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = obj["id"]?.jsonPrimitive?.int
        if (id != null) {
            // 命令响应：result 或 error
            val deferred = pending.remove(id) ?: return
            val error = obj["error"] as? JsonObject
            if (error != null) {
                val code = error["code"]?.jsonPrimitive?.int ?: -1
                val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "unknown cdp error"
                deferred.completeExceptionally(CdpCommandException(code, message))
            } else {
                deferred.complete(obj)
            }
            return
        }
        if (obj.containsKey("method")) {
            val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull
            eventChannel.trySend(obj to sessionId)
        }
    }

    private fun handleClosed() {
        val err = CdpCommandException(-1, "cdp session closed")
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
        val l = listener
        pumpJob?.let { job -> if (job.isActive) scope.launch { runCatching { l?.onClosed() } } }
        pumpJob?.cancel()
    }

    /** 发命令并等响应；sessionId 非空时路由到 flat 子会话。 */
    override suspend fun send(
        method: String,
        params: JsonObject,
        sessionId: String?,
        timeoutMs: Long,
    ): JsonObject {
        if (closedByUs) throw CdpCommandException(-1, "cdp session closed")
        val id = nextId.incrementAndGet()
        val request = buildString {
            append("{\"id\":").append(id)
            append(",\"method\":\"").append(method).append('"')
            if (sessionId != null) append(",\"sessionId\":\"").append(sessionId).append('"')
            if (params.isNotEmpty()) append(",\"params\":").append(params.toString())
            append('}')
        }
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            ws.sendText(request)
        } catch (e: Exception) {
            pending.remove(id)
            throw CdpCommandException(-1, "cdp send failed: ${e.message}")
        }
        val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run {
                pending.remove(id)
                throw CdpCommandException(-2, "cdp command timeout after ${timeoutMs}ms: $method")
            }
        return response
    }

    fun close(code: Int = 1000, reason: String = "client detach") {
        closedByUs = true
        ws.close(code, reason)
        val err = CdpCommandException(-1, "cdp session closed")
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
        pumpJob?.cancel()
        eventChannel.cancel()
    }

    private companion object {
        const val TAG = "CdpSession"
    }
}
