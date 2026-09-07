package top.wanxiang.app.runtime.browser.hook

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.wanxiang.app.runtime.browser.BrowserEvent
import top.wanxiang.app.runtime.browser.BrowserEventBus
import top.wanxiang.app.runtime.browser.CapturedRequest
import top.wanxiang.app.runtime.browser.hook.HookEventParser.HookBridgeEvent

/**
 * 页内事件管道：桥的 onEvent → 有界 Channel → 消费协程解析分发。
 *
 * - enqueue 在 JavaBridge 线程调用：只做长度守卫 + trySend（DROP_OLDEST），立即返回；
 * - 消费协程跑在 [Dispatchers.Default]（SupervisorJob，[shutdown] 取消）；
 * - NetReq/NetRes 合并为单条完整 [CapturedRequest]（source="js"）发布；body 入 [NetworkBodyStore]；
 * - Hit/Ws → [BrowserEvent.HookHit]；hook_error → console WARNING（browser.console_list 可见）。
 */
class HookEventPipeline(
    private val store: HookRuleStore,
    private val bodyStore: NetworkBodyStore,
    private val eventBus: BrowserEventBus,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val dropped = AtomicInteger(0)
    private val lastDropWarning = AtomicInteger(0)
    private val channel = Channel<Pair<String, String>>(
        capacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { dropped.incrementAndGet() },
    )

    /** 进行中的请求（net_req 已到、net_res 未到），用于合并出完整 CapturedRequest。 */
    private val pendingReqs = ConcurrentHashMap<String, PendingReq>()

    private data class PendingReq(
        val tabId: String,
        val url: String,
        val method: String,
        val initiator: String,
        val headers: Map<String, String>,
        val body: String?,
        val startedAt: Long,
    )

    fun start() {
        scope.launch {
            for ((tabId, json) in channel) {
                runCatching { handle(tabId, json) }
                warnIfDropped()
            }
        }
    }

    /** JavaBridge 线程调用：拒收超大事件，其余 trySend 立即返回。 */
    fun enqueue(tabId: String, json: String) {
        if (json.length > MAX_EVENT_BYTES) return
        channel.trySend(tabId to json)
    }

    /** JavaBridge 线程同步调用：只读规则 payload 缓存串。 */
    fun rulesPayloadFor(tabId: String): String = store.payloadFor(tabId)

    fun shutdown() {
        channel.close()
        scope.cancel()
    }

    fun clearForTab(tabId: String) {
        pendingReqs.entries.removeIf { it.value.tabId == tabId }
        bodyStore.clearForTab(tabId)
    }

    private suspend fun handle(tabId: String, json: String) {
        when (val event = HookEventParser.parse(tabId, json) ?: return) {
            is HookBridgeEvent.Ready -> Unit // 仅作注入成功探针，不产生事件
            is HookBridgeEvent.NetReq -> pendingReqs[event.id] = PendingReq(
                tabId = event.tabId,
                url = event.url,
                method = event.method,
                initiator = event.initiator,
                headers = event.headers,
                body = event.body,
                startedAt = event.ts,
            ).also { trimPending() }
            is HookBridgeEvent.NetRes -> handleNetRes(event)
            is HookBridgeEvent.Hit -> {
                store.recordHit(event.hookId)
                eventBus.publish(BrowserEvent.HookHit(event.tabId, event.toRecord()))
            }
            is HookBridgeEvent.Ws -> eventBus.publish(
                BrowserEvent.HookHit(event.tabId, event.toRecord())
            )
            is HookBridgeEvent.HookError -> eventBus.publish(
                BrowserEvent.ConsoleLogged(
                    event.tabId, "WARNING",
                    "hook runtime error [${event.stage}]: ${event.message}",
                    event.ts,
                )
            )
        }
    }

    private suspend fun handleNetRes(event: HookBridgeEvent.NetRes) {
        val pending = pendingReqs.remove(event.id)
        val request = CapturedRequest(
            id = event.id,
            tabId = event.tabId,
            url = event.url,
            method = pending?.method ?: "GET",
            statusCode = event.status,
            requestHeaders = pending?.headers ?: emptyMap(),
            responseHeaders = event.headers,
            startedAt = pending?.startedAt ?: (event.ts - event.durationMs),
            finishedAt = event.ts,
            errorMessage = if (event.status == 0 && event.actionTaken != "block") "network error or aborted" else "",
            source = "js",
            initiator = pending?.initiator.orEmpty(),
            durationMs = event.durationMs,
            requestSize = pending?.body?.length?.toLong() ?: 0L,
            responseSize = event.body?.length?.toLong() ?: 0L,
            hasRequestBody = pending?.body != null,
            hasResponseBody = event.body != null,
            ruleId = event.ruleId.orEmpty(),
            actionTaken = event.actionTaken.orEmpty(),
        )
        eventBus.publish(BrowserEvent.NetworkCaptured(event.tabId, request))
        bodyStore.put(
            NetworkBodyStore.NetworkBody(
                id = event.id,
                tabId = event.tabId,
                requestBody = pending?.body ?: "",
                responseBody = event.body ?: "",
            )
        )
        // 命中计数只由 Hit 事件驱动（JS 侧对命中规则单独上报 hit），此处不重复 recordHit
    }

    private fun warnIfDropped() {
        val total = dropped.get()
        if (total - lastDropWarning.get() >= 100) {
            lastDropWarning.set(total)
            scope.launch {
                eventBus.publish(
                    BrowserEvent.ConsoleLogged(
                        "", "WARNING",
                        "hook event backlog: $total events dropped (page too chatty; narrow your hook targets)",
                        System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    /** pending 上限 256：防响应永不到达的请求无限累积。 */
    private fun trimPending() {
        if (pendingReqs.size > 256) pendingReqs.clear()
    }

    private fun HookBridgeEvent.Hit.toRecord() = HookHitRecord(
        tabId = tabId,
        hookId = hookId,
        type = type ?: HookType.FUNCTION,
        target = target,
        phase = phase,
        summary = summary,
        detailJson = detailJson,
        at = ts,
    )

    private fun HookBridgeEvent.Ws.toRecord() = HookHitRecord(
        tabId = tabId,
        hookId = "",
        type = HookType.WEBSOCKET,
        target = url,
        phase = event,
        summary = summary,
        detailJson = detailJson,
        at = ts,
    )

    companion object {
        private const val MAX_EVENT_BYTES = 512 * 1024
    }
}
