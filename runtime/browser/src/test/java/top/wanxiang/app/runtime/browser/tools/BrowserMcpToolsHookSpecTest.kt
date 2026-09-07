package top.wanxiang.app.runtime.browser.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.browser.BrowserDescriptor
import top.wanxiang.app.core.browser.BrowserFamily
import top.wanxiang.app.core.browser.BrowserPreferences
import top.wanxiang.app.core.browser.PageSnapshot
import top.wanxiang.app.core.model.ToolImageRef
import top.wanxiang.app.runtime.browser.BrowserEngine
import top.wanxiang.app.runtime.browser.BrowserEvent
import top.wanxiang.app.runtime.browser.BrowserEventBus
import top.wanxiang.app.runtime.browser.BrowserSessionToken
import top.wanxiang.app.runtime.browser.CapturedRequest
import top.wanxiang.app.runtime.browser.hook.HookAction
import top.wanxiang.app.runtime.browser.hook.HookHitRecord
import top.wanxiang.app.runtime.browser.hook.HookRule
import top.wanxiang.app.runtime.browser.hook.HookRuleInfo
import top.wanxiang.app.runtime.browser.hook.HookType

/**
 * hook 工具族的 MCP 面规约测试：门禁、参数解析、动作校验、输出格式。
 * fake engine 模式（仿 harness 的 McpToolDispatcherTest.FakeBrowserEngine），纯 JVM。
 */
class BrowserMcpToolsHookSpecTest {

    private val engine = FakeHookEngine()
    private val json = Json { ignoreUnknownKeys = true }

    private fun String.obj(): JsonObject = json.parseToJsonElement(this).jsonObject

    private fun tools(allowHooks: Boolean = true) =
        BrowserMcpTools(listOf(engine), { _ -> null }, BrowserPreferences(allowHooks = allowHooks))

    @Test
    fun `list contains new hook tools`() {
        val names = tools().list().map { it.name }
        listOf(
            "browser.hook_create", "browser.hook_list", "browser.hook_remove",
            "browser.hook_reset", "browser.hook_hits", "browser.inject_script",
            "browser.network_detail",
        ).forEach { assertTrue("missing $it", it in names) }
    }

    @Test
    fun `hook tools gated when allowHooks false`() {
        val t = tools(allowHooks = false)
        val gated = listOf(
            "browser.hook_create" to """{"type":"fetch","target":"*"}""",
            "browser.hook_list" to "{}",
            "browser.hook_remove" to """{"id":"hr_x"}""",
            "browser.hook_reset" to "{}",
            "browser.hook_hits" to "{}",
            "browser.inject_script" to """{"code":"1"}""",
        )
        for ((name, args) in gated) {
            val r = runBlocking { t.invoke(name, args .obj()) }
            assertFalse("$name should be gated", r.success)
            assertTrue("$name error should mention allowHooks: ${r.output}", r.output.contains("allowHooks"))
        }
        assertTrue(engine.installed.isEmpty())
        assertEquals(null, engine.lastInjected)
    }

    @Test
    fun `network_detail not gated when allowHooks false`() {
        val r = runBlocking {
            tools(allowHooks = false).invoke("browser.network_detail", """{"id":"req_1"}""" .obj())
        }
        assertTrue(r.success)
        assertTrue(r.output.contains("req_1"))
    }

    @Test
    fun `hook_create parses actions and installs rule`() {
        val r = runBlocking {
            tools().invoke(
                "browser.hook_create",
                """
                {"type":"fetch","target":"https://api.example.com/v1/*","method":"POST",
                 "tab":"t1","captureBody":true,
                 "actions":[{"type":"log","captureBody":true},{"type":"mock","status":200,"body":"{\"ok\":1}"}]}
                """ .obj(),
            )
        }
        assertTrue(r.output, r.success)
        assertEquals(1, engine.installed.size)
        val rule = engine.installed.single()
        assertEquals(HookType.FETCH, rule.type)
        assertEquals("https://api.example.com/v1/*", rule.target)
        assertEquals("POST", rule.method)
        assertEquals("t1", rule.scopeTabId)
        assertTrue(rule.captureBody)
        assertEquals(2, rule.actions.size)
        val log = rule.actions[0] as HookAction.Log
        assertEquals(true, log.captureBody)
        val mock = rule.actions[1] as HookAction.Mock
        assertEquals(200, mock.status)
        assertEquals("{\"ok\":1}", mock.body)
    }

    @Test
    fun `hook_create defaults to log action`() {
        val r = runBlocking {
            tools().invoke("browser.hook_create", """{"type":"xhr","target":"*/api/user"}""" .obj())
        }
        assertTrue(r.output, r.success)
        val rule = engine.installed.single()
        assertEquals(HookType.XHR, rule.type)
        assertEquals(1, rule.actions.size)
        assertTrue(rule.actions.single() is HookAction.Log)
        assertTrue(rule.id.startsWith("hr_"))
    }

    @Test
    fun `hook_create rejects action invalid for type`() {
        val r = runBlocking {
            tools().invoke(
                "browser.hook_create",
                """{"type":"function","target":"JSON.parse","actions":[{"type":"redirect","url":"https://x.test"}]}"""
                     .obj(),
            )
        }
        assertFalse(r.success)
        assertTrue(r.output, r.output.contains("不适用"))
        assertTrue(engine.installed.isEmpty())
    }

    @Test
    fun `hook_create rejects missing target and bad type`() {
        val r1 = runBlocking { tools().invoke("browser.hook_create", """{"type":"fetch"}""" .obj()) }
        assertFalse(r1.success)
        val r2 = runBlocking { tools().invoke("browser.hook_create", """{"type":"nope","target":"*"}""" .obj()) }
        assertFalse(r2.success)
    }

    @Test
    fun `hook_list hook_remove roundtrip`() {
        runBlocking {
            tools().invoke("browser.hook_create", """{"type":"fetch","target":"*"}""" .obj())
            val list = tools().invoke("browser.hook_list", "{}" .obj())
            assertTrue(list.success)
            val id = engine.installed.single().id
            assertTrue(list.output.contains(id))

            val removed = tools().invoke("browser.hook_remove", """{"id":"$id"}""" .obj())
            assertTrue(removed.success)
            assertTrue(engine.installed.isEmpty())

            val missing = tools().invoke("browser.hook_remove", """{"id":"$id"}""" .obj())
            assertFalse(missing.success)
        }
    }

    @Test
    fun `hook_hits reads event bus and filters by tab`() {
        runBlocking {
            engine.eventBus.publish(
                BrowserEvent.HookHit(
                    "t1",
                    HookHitRecord("t1", "hr_a", HookType.FUNCTION, "JSON.parse", "call", "JSON.parse(<33 chars>)"),
                )
            )
            engine.eventBus.publish(
                BrowserEvent.HookHit(
                    "t2",
                    HookHitRecord("t2", "hr_b", HookType.PROPERTY, "document.cookie", "get", "sid=xyz"),
                )
            )
            val all = tools().invoke("browser.hook_hits", "{}" .obj())
            assertTrue(all.success)
            assertTrue(all.output.contains("hr_a"))
            assertTrue(all.output.contains("hr_b"))

            val scoped = tools().invoke("browser.hook_hits", """{"tab":"t1"}""" .obj())
            assertTrue(scoped.success)
            assertTrue(scoped.output.contains("hr_a"))
            assertFalse(scoped.output.contains("hr_b"))
        }
    }

    @Test
    fun `network_list upgraded format includes source rule action and id`() {
        runBlocking {
            engine.eventBus.publish(
                BrowserEvent.NetworkCaptured(
                    "t1",
                    CapturedRequest(
                        id = "req_9", tabId = "t1", url = "https://api.example.com/v1/user",
                        method = "POST", statusCode = 200, startedAt = 1L,
                        source = "js", durationMs = 88, requestSize = 123, responseSize = 4608,
                        ruleId = "hr_x", actionTaken = "mock",
                    ),
                )
            )
            val r = tools().invoke("browser.network_list", "{}" .obj())
            assertTrue(r.success)
            assertTrue(r.output.contains("[js] [POST] https://api.example.com/v1/user"))
            assertTrue(r.output.contains("rule=hr_x"))
            assertTrue(r.output.contains("action=mock"))
            assertTrue(r.output.contains("id=req_9"))
        }
    }

    @Test
    fun `inject_script passes persistent flag and unknown id fails network_detail`() {
        runBlocking {
            val r = tools().invoke(
                "browser.inject_script",
                """{"code":"console.log(1)","persistent":true,"name":"probe"}""" .obj(),
            )
            assertTrue(r.output, r.success)
            assertEquals(Triple("console.log(1)", true, "probe"), engine.lastInjected)

            val miss = tools().invoke("browser.network_detail", """{"id":"nope"}""" .obj())
            assertFalse(miss.success)
        }
    }
}

private class FakeHookEngine : BrowserEngine {
    override val eventBus = BrowserEventBus()
    override val descriptor = BrowserDescriptor(
        family = BrowserFamily.IN_APP,
        displayName = "fake-hook",
        healthy = true,
        capabilities = emptySet(),
    )
    val active = BrowserSessionToken("t1", BrowserFamily.IN_APP, url = "https://example.test")
    val installed = mutableListOf<HookRule>()
    var lastInjected: Triple<String, Boolean, String>? = null

    override suspend fun openTab(url: String?, activate: Boolean) = active
    override suspend fun navigate(tab: BrowserSessionToken, url: String) = Unit
    override suspend fun snapshot(tab: BrowserSessionToken, maxElements: Int) =
        PageSnapshot(tab.tabId, active.url, "Example", emptyMap(), "fake", 0L)
    override suspend fun click(tab: BrowserSessionToken, ref: String, refSelectorLookup: suspend (BrowserSessionToken, String) -> String?) = true
    override suspend fun typeInto(tab: BrowserSessionToken, ref: String, text: String, refSelectorLookup: suspend (BrowserSessionToken, String) -> String?) = true
    override suspend fun press(tab: BrowserSessionToken, ref: String?, key: String, refSelectorLookup: suspend (BrowserSessionToken, String) -> String?) = true
    override suspend fun scroll(tab: BrowserSessionToken, deltaY: Int) = true
    override suspend fun screenshot(tab: BrowserSessionToken, prefs: BrowserPreferences): ToolImageRef? = null
    override suspend fun evaluate(tab: BrowserSessionToken, script: String): String? = ""
    override suspend fun pageSource(tab: BrowserSessionToken, maxBytes: Int) = ""
    override suspend fun back(tab: BrowserSessionToken) = true
    override suspend fun forward(tab: BrowserSessionToken) = true
    override suspend fun refresh(tab: BrowserSessionToken) = Unit
    override suspend fun closeTab(tab: BrowserSessionToken) = Unit
    override suspend fun listTabs() = listOf(active)
    override fun activeTab(): BrowserSessionToken = active
    override suspend fun setActiveTab(tab: BrowserSessionToken) = Unit
    override suspend fun cookiesGet(tab: BrowserSessionToken, url: String?) = ""
    override suspend fun cookiesSet(tab: BrowserSessionToken, url: String, headerLine: String) = Unit
    override suspend fun cookiesDelete(tab: BrowserSessionToken, url: String, name: String) = Unit
    override suspend fun localGet(tab: BrowserSessionToken, key: String): String? = null
    override suspend fun localSet(tab: BrowserSessionToken, key: String, value: String) = Unit
    override suspend fun localDelete(tab: BrowserSessionToken, key: String) = Unit
    override suspend fun localKeys(tab: BrowserSessionToken) = emptyList<String>()
    override suspend fun sessionGet(tab: BrowserSessionToken, key: String): String? = null
    override suspend fun sessionSet(tab: BrowserSessionToken, key: String, value: String) = Unit
    override suspend fun sessionDelete(tab: BrowserSessionToken, key: String) = Unit
    override suspend fun sessionKeys(tab: BrowserSessionToken) = emptyList<String>()

    override suspend fun hookInstall(rule: HookRule): HookRule {
        installed += rule
        return rule
    }

    override suspend fun hookRemove(id: String): Boolean = installed.removeAll { it.id == id }

    override suspend fun hookList(tabId: String?): List<HookRuleInfo> =
        installed.filter { tabId == null || it.scopeTabId == tabId }.map { HookRuleInfo(it, 0) }

    override suspend fun hookReset(tabId: String?): Boolean {
        installed.clear()
        return true
    }

    override suspend fun injectScript(tab: BrowserSessionToken, code: String, persistent: Boolean, name: String): String {
        lastInjected = Triple(code, persistent, name)
        return "ok"
    }

    override suspend fun networkDetail(id: String): String? =
        if (id == "req_1") "detail of $id" else null

    override suspend fun shutdown() = Unit
}
