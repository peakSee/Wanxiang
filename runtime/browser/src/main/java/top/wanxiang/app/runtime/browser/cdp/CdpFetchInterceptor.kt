package top.wanxiang.app.runtime.browser.cdp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.wanxiang.app.runtime.browser.BrowserEvent
import top.wanxiang.app.runtime.browser.BrowserEventBus
import top.wanxiang.app.runtime.browser.CapturedRequest
import top.wanxiang.app.runtime.browser.hook.HookHitRecord
import top.wanxiang.app.runtime.browser.hook.HookRuleStore
import top.wanxiang.app.runtime.browser.hook.HookType
import top.wanxiang.app.runtime.browser.hook.NetworkBodyStore

/**
 * Fetch 域拦截器：CDP 引擎级网络改写（覆盖 Worker 与子资源——注入层够不到的范围）。
 *
 * - 规则复用阶段 1 [HookRuleStore]：同一规则双层生效（页内 JS 层 + CDP 引擎层）；
 * - patterns = 网络类规则 target 去重透传（CDP urlPattern 同为 `*`/`?` 语义）；
 *   规则空时 Fetch.disable 零开销；
 * - 每条 requestPaused 必须终结（continue/fail/fulfill），绝不挂起请求；
 * - Worker 子会话只挂 Fetch 不挂 Debugger（避免 worker 冻结与断点归因复杂化）。
 *
 * 动作映射：
 * - log → NetworkCaptured(source="cdp") + HookHit(phase="cdp_request") + continue 放行；
 *   captureBody=true 时 Fetch.getRequestPostData 存 bodyStore；
 * - block → Fetch.failRequest("BlockedByClient")；
 * - redirect → Fetch.continueRequest(url=…)；
 * - mock → Fetch.fulfillRequest(status, headers, body=Base64)；
 * - modify_headers → 请求头合并（`"!"`=删除）后 continue（**响应头改写 CDP 层不支持**，仍由注入层承担）。
 */
class CdpFetchInterceptor(
    private val tabId: String,
    private val session: CdpCommandApi,
    private val store: HookRuleStore,
    private val bodyStore: NetworkBodyStore,
    private val eventBus: BrowserEventBus,
) {

    /** 启用（或按当前规则重载）拦截；sessionId 非空时作用于 worker 子会话。 */
    suspend fun enable(sessionId: String? = null) {
        val patterns = CdpFetchDecision.fetchPatterns(store.rulesFor(tabId))
        if (patterns.isEmpty()) {
            // 无网络类规则：disable，不再产生 requestPaused（零开销）
            session.send("Fetch.disable", sessionId = sessionId)
            return
        }
        session.send(
            "Fetch.enable",
            buildJsonObject {
                put(
                    "patterns",
                    buildJsonArray {
                        patterns.forEach { p ->
                            add(
                                buildJsonObject {
                                    put("urlPattern", p)
                                    put("requestStage", "Request")
                                }
                            )
                        }
                    },
                )
            },
            sessionId = sessionId,
        )
    }

    suspend fun disable(sessionId: String? = null) {
        runCatching { session.send("Fetch.disable", sessionId = sessionId) }
    }

    /** Fetch.requestPaused 事件入口（CdpTabConnection 转发；sessionId 非空 = worker 会话）。 */
    suspend fun onRequestPaused(params: JsonObject, sessionId: String?) {
        val requestId = params["requestId"]?.jsonPrimitive?.contentOrNull ?: return
        val request = params["request"] as? JsonObject ?: JsonObject(emptyMap())
        val url = request["url"]?.jsonPrimitive?.contentOrNull ?: ""
        val method = request["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val headers = (request["headers"] as? JsonObject)
            ?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }
            ?.toMap()
            ?: emptyMap()
        val startedAt = System.currentTimeMillis()

        val decision = CdpFetchDecision.decide(store.rulesFor(tabId), url, method)
        when (decision) {
            is FetchDecision.Pass -> continueRequest(requestId, sessionId)

            is FetchDecision.Log -> {
                val body = if (decision.captureBody) fetchPostData(requestId, sessionId) else null
                store.recordHit(decision.ruleId)
                publishHit(decision.ruleId, url, "cdp_request", "log $method $url")
                publishCapture(requestId, url, method, headers, body, startedAt, decision.ruleId, "log")
                continueRequest(requestId, sessionId)
            }

            is FetchDecision.Block -> {
                store.recordHit(decision.ruleId)
                publishHit(decision.ruleId, url, "cdp_request", "block $method $url")
                publishCapture(requestId, url, method, headers, null, startedAt, decision.ruleId, "block")
                session.send(
                    "Fetch.failRequest",
                    buildJsonObject {
                        put("requestId", requestId)
                        put("errorReason", "BlockedByClient")
                    },
                    sessionId = sessionId,
                )
            }

            is FetchDecision.Redirect -> {
                store.recordHit(decision.ruleId)
                publishHit(decision.ruleId, url, "cdp_request", "redirect $method $url -> ${decision.url}")
                publishCapture(requestId, url, method, headers, null, startedAt, decision.ruleId, "redirect")
                continueRequest(requestId, sessionId, url = decision.url)
            }

            is FetchDecision.Mock -> {
                store.recordHit(decision.ruleId)
                publishHit(decision.ruleId, url, "cdp_request", "mock $method $url -> ${decision.status}")
                publishCapture(requestId, url, method, headers, null, startedAt, decision.ruleId, "mock")
                val responseHeaders = decision.headers.toMutableMap()
                responseHeaders.putIfAbsent("Content-Type", "text/plain; charset=utf-8")
                session.send(
                    "Fetch.fulfillRequest",
                    buildJsonObject {
                        put("requestId", requestId)
                        put("responseCode", decision.status)
                        put(
                            "responseHeaders",
                            headersToJsonArray(responseHeaders),
                        )
                        put("body", encodeBase64(decision.body))
                    },
                    sessionId = sessionId,
                )
            }

            is FetchDecision.ModifyRequestHeaders -> {
                store.recordHit(decision.ruleId)
                val merged = mergeHeaders(headers, decision.headers)
                publishHit(decision.ruleId, url, "cdp_request", "modify_headers $method $url")
                publishCapture(
                    requestId, url, method, merged,
                    if (decision.captureBody) fetchPostData(requestId, sessionId) else null,
                    startedAt, decision.ruleId, "modify_headers",
                )
                continueRequest(requestId, sessionId, headers = merged)
            }
        }
    }

    // ===== CDP 命令封装 =====

    private suspend fun continueRequest(
        requestId: String,
        sessionId: String?,
        url: String? = null,
        headers: Map<String, String>? = null,
    ) {
        val params = buildJsonObject {
            put("requestId", requestId)
            if (url != null) put("url", url)
            if (headers != null) put("headers", headersToJsonArray(headers))
        }
        session.send("Fetch.continueRequest", params, sessionId = sessionId)
    }

    /** 读请求体（存在时）；无 body 或读取失败返回 null。 */
    private suspend fun fetchPostData(requestId: String, sessionId: String?): String? = runCatching {
        val resp = session.send(
            "Fetch.getRequestPostData",
            buildJsonObject { put("requestId", requestId) },
            sessionId = sessionId,
        )
        resp["postData"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    // ===== 事件发布 =====

    private suspend fun publishCapture(
        id: String,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        startedAt: Long,
        ruleId: String,
        actionTaken: String,
    ) {
        val at = System.currentTimeMillis()
        eventBus.publish(
            BrowserEvent.NetworkCaptured(
                tabId,
                CapturedRequest(
                    id = id,
                    tabId = tabId,
                    url = url,
                    method = method,
                    requestHeaders = headers,
                    startedAt = startedAt,
                    finishedAt = at,
                    source = "cdp",
                    ruleId = ruleId,
                    actionTaken = actionTaken,
                    requestSize = body?.length?.toLong() ?: 0L,
                    hasRequestBody = body != null,
                ),
            )
        )
        if (body != null) {
            bodyStore.put(
                NetworkBodyStore.NetworkBody(
                    id = id,
                    tabId = tabId,
                    requestBody = body,
                    responseBody = "",
                    at = at,
                )
            )
        }
    }

    private suspend fun publishHit(ruleId: String, url: String, phase: String, summary: String) {
        val rule = store.list().firstOrNull { it.id == ruleId }
        eventBus.publish(
            BrowserEvent.HookHit(
                tabId,
                HookHitRecord(
                    tabId = tabId,
                    hookId = ruleId,
                    type = rule?.type ?: HookType.FETCH,
                    target = rule?.target ?: url,
                    phase = phase,
                    summary = summary,
                ),
            )
        )
    }

    // ===== 纯函数 =====

    private fun headersToJsonArray(headers: Map<String, String>): JsonArray = buildJsonArray {
        headers.forEach { (k, v) ->
            add(
                buildJsonObject {
                    put("name", k)
                    put("value", v)
                }
            )
        }
    }

    /** 合并请求头：改写值覆盖；值 `"!"` 表示删除。 */
    private fun mergeHeaders(
        original: Map<String, String>,
        modifications: Map<String, String>,
    ): Map<String, String> {
        // header 名大小写不敏感：改写/删除需与原始键对齐
        val result = original.toMutableMap()
        for ((name, value) in modifications) {
            val existingKey = result.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            when {
                value == "!" -> existingKey?.let { result.remove(it) }
                existingKey != null -> result[existingKey] = value
                else -> result[name] = value
            }
        }
        return result
    }

    private fun encodeBase64(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
}
