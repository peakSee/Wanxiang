package top.wanxiang.app.runtime.browser.cdp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.wanxiang.app.runtime.browser.BrowserEventBus
import top.wanxiang.app.runtime.browser.hook.HookAction
import top.wanxiang.app.runtime.browser.hook.HookRule
import top.wanxiang.app.runtime.browser.hook.HookRuleStore
import top.wanxiang.app.runtime.browser.hook.HookType
import top.wanxiang.app.runtime.browser.hook.NetworkBodyStore

/** Fake 命令通道 + 动作→CDP 命令映射测试（纯 JVM）。 */
class CdpFetchInterceptorTest {

    private class FakeCdpApi : CdpCommandApi {
        val sent = ArrayList<Pair<String, JsonObject>>()
        override suspend fun send(method: String, params: JsonObject, sessionId: String?, timeoutMs: Long): JsonObject {
            sent += method to params
            return Json.parseToJsonElement("""{"result":{}}""").jsonObject
        }
    }

    private lateinit var api: FakeCdpApi
    private lateinit var store: HookRuleStore
    private lateinit var bodies: NetworkBodyStore
    private lateinit var eventBus: BrowserEventBus
    private lateinit var interceptor: CdpFetchInterceptor

    @Before
    fun setup() {
        api = FakeCdpApi()
        store = HookRuleStore(maxBodyBytes = 64 * 1024)
        bodies = NetworkBodyStore(totalBudgetBytes = 1024 * 1024)
        eventBus = BrowserEventBus()
        interceptor = CdpFetchInterceptor("t1", api, store, bodies, eventBus)
    }

    private fun installRule(vararg actions: HookAction, captureBody: Boolean = false) {
        store.install(
            HookRule(
                id = "r1",
                type = HookType.FETCH,
                target = "*api*",
                actions = actions.toList(),
                captureBody = captureBody,
            )
        )
    }

    private fun pausedParams(url: String = "https://x.test/api/data", method: String = "GET"): String = """
        {"requestId":"req-1","request":{"url":"$url","method":"$method",
          "headers":{"User-Agent":"ua","X-Old":"v1"}}}
    """.trimIndent()

    private suspend fun fire(url: String = "https://x.test/api/data", method: String = "GET") {
        interceptor.onRequestPaused(
            Json.parseToJsonElement(pausedParams(url, method)).jsonObject,
            sessionId = null,
        )
    }

    private fun paramsOf(method: String): List<JsonObject> =
        api.sent.filter { it.first == method }.map { it.second }

    @Test
    fun `enable sends Fetch-enable with deduped patterns`() = runBlocking {
        installRule(HookAction.Log())
        store.install(
            HookRule(id = "r2", type = HookType.XHR, target = "*api*", actions = listOf(HookAction.Block()))
        )
        store.install(HookRule(id = "r3", type = HookType.FUNCTION, target = "JSON.parse")) // 非网络类不进 patterns
        interceptor.enable()
        val params = paramsOf("Fetch.enable")
        assertEquals(1, params.size)
        val patterns = params[0]["patterns"]!!.jsonArray
        assertEquals(1, patterns.size) // *api* 去重
        assertEquals("*api*", patterns[0].jsonObject["urlPattern"]!!.jsonPrimitive.content)
        assertEquals("Request", patterns[0].jsonObject["requestStage"]!!.jsonPrimitive.content)
    }

    @Test
    fun `enable with no net rules disables fetch`() = runBlocking {
        interceptor.enable()
        assertTrue(paramsOf("Fetch.disable").isNotEmpty())
        assertTrue(paramsOf("Fetch.enable").isEmpty())
    }

    @Test
    fun `no matching rule passes through with plain continue`() = runBlocking {
        installRule(HookAction.Block()) // target *api* 不命中 /other
        fire(url = "https://x.test/other")
        val cont = paramsOf("Fetch.continueRequest")
        assertEquals(1, cont.size)
        assertEquals("req-1", cont[0]["requestId"]!!.jsonPrimitive.content)
        assertTrue(cont[0]["url"] == null) // 无改写
        assertTrue(paramsOf("Fetch.failRequest").isEmpty())
    }

    @Test
    fun `log action continues and publishes cdp capture and hit`() = runBlocking {
        installRule(HookAction.Log())
        fire(method = "POST")
        assertTrue(paramsOf("Fetch.continueRequest").isNotEmpty())
        val net = eventBus.network.value.last()
        assertEquals("cdp", net.source)
        assertEquals("r1", net.ruleId)
        assertEquals("log", net.actionTaken)
        assertEquals(1, eventBus.hookHits.value.size)
        assertEquals("cdp_request", eventBus.hookHits.value[0].phase)
        assertEquals(1, store.hitCount("r1"))
    }

    @Test
    fun `block action fails request with BlockedByClient`() = runBlocking {
        installRule(HookAction.Block())
        fire()
        val fail = paramsOf("Fetch.failRequest")
        assertEquals(1, fail.size)
        assertEquals("BlockedByClient", fail[0]["errorReason"]!!.jsonPrimitive.content)
        assertEquals("block", eventBus.network.value.last().actionTaken)
    }

    @Test
    fun `redirect action continues with new url`() = runBlocking {
        installRule(HookAction.Redirect("https://mock.test/done"))
        fire()
        val cont = paramsOf("Fetch.continueRequest")
        assertEquals(1, cont.size)
        assertEquals("https://mock.test/done", cont[0]["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mock action fulfills with base64 body and default content-type`() = runBlocking {
        installRule(HookAction.Mock(status = 201, body = "hello"))
        fire()
        val fulfill = paramsOf("Fetch.fulfillRequest")
        assertEquals(1, fulfill.size)
        assertEquals(201, fulfill[0]["responseCode"]!!.jsonPrimitive.content.toInt())
        assertEquals("aGVsbG8=", fulfill[0]["body"]!!.jsonPrimitive.content)
        val headers = fulfill[0]["responseHeaders"]!!.jsonArray
        val ct = headers.first { it.jsonObject["name"]!!.jsonPrimitive.content == "Content-Type" }
        assertEquals("text/plain; charset=utf-8", ct.jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `modify headers merges overwrites and deletes`() = runBlocking {
        installRule(
            HookAction.ModifyHeaders(
                request = mapOf("X-Old" to "!", "X-New" to "v2", "user-agent" to "bot"),
            )
        )
        fire()
        val cont = paramsOf("Fetch.continueRequest")
        assertEquals(1, cont.size)
        val headers = cont[0]["headers"]!!.jsonArray.associate {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonPrimitive.content
        }
        // 删除：X-Old 不在；新增：X-New；大小写不敏感覆盖：合并进原键 User-Agent
        assertEquals(null, headers["X-Old"])
        assertEquals("v2", headers["X-New"])
        assertEquals("bot", headers["User-Agent"])
        assertEquals(2, headers.size) // User-Agent + X-New
    }

    @Test
    fun `worker session events route with sessionId`() = runBlocking {
        installRule(HookAction.Log())
        interceptor.onRequestPaused(
            Json.parseToJsonElement(pausedParams()).jsonObject,
            sessionId = "worker-1",
        )
        val cont = paramsOf("Fetch.continueRequest")
        assertEquals(1, cont.size)
        assertEquals("cdp", eventBus.network.value.last().source)
    }
}
