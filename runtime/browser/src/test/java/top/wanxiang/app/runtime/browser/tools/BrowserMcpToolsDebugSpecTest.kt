package top.wanxiang.app.runtime.browser.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.wanxiang.app.core.browser.BrowserDescriptor
import top.wanxiang.app.core.browser.BrowserFamily
import top.wanxiang.app.core.browser.BrowserPreferences
import top.wanxiang.app.core.browser.PageSnapshot
import top.wanxiang.app.core.model.ToolImageRef
import top.wanxiang.app.runtime.browser.BrowserEngine
import top.wanxiang.app.runtime.browser.BrowserEventBus
import top.wanxiang.app.runtime.browser.BrowserSessionToken
import top.wanxiang.app.runtime.browser.cdp.DebugBreakpoint
import top.wanxiang.app.runtime.browser.cdp.DebugStep

/**
 * debug_* 工具族的 MCP 面规约测试：门禁、参数解析、step 枚举、输出格式、hook 门禁放宽。
 * fake engine 模式（仿 BrowserMcpToolsHookSpecTest），纯 JVM。
 */
class BrowserMcpToolsDebugSpecTest {

    private lateinit var engine: FakeDebugEngine
    private val json = Json { ignoreUnknownKeys = true }

    private fun String.obj(): JsonObject = json.parseToJsonElement(this).jsonObject

    private fun tools(allowCdp: Boolean = true, allowHooks: Boolean = false) =
        BrowserMcpTools(
            listOf(engine),
            { _ -> null },
            BrowserPreferences(allowCdp = allowCdp, allowHooks = allowHooks),
        )

    @Before
    fun setup() {
        engine = FakeDebugEngine()
    }

    @Test
    fun `list contains 11 debug tools`() {
        val names = tools().list().map { it.name }
        listOf(
            "browser.debug_status", "browser.debug_attach", "browser.debug_detach",
            "browser.debug_set_breakpoint", "browser.debug_remove_breakpoint",
            "browser.debug_list_breakpoints", "browser.debug_resume", "browser.debug_step",
            "browser.debug_state", "browser.debug_eval", "browser.debug_scope",
        ).forEach { assertTrue("missing $it", it in names) }
    }

    @Test
    fun `debug tools gated when allowCdp false`() {
        val t = tools(allowCdp = false)
        val gated = listOf(
            "browser.debug_status" to "{}",
            "browser.debug_attach" to "{}",
            "browser.debug_detach" to "{}",
            "browser.debug_set_breakpoint" to """{"url":"https://x.test/app.js","line":41}""",
            "browser.debug_remove_breakpoint" to """{"id":"bp-1"}""",
            "browser.debug_list_breakpoints" to "{}",
            "browser.debug_resume" to "{}",
            "browser.debug_step" to """{"step":"over"}""",
            "browser.debug_state" to "{}",
            "browser.debug_eval" to """{"expression":"1+1"}""",
            "browser.debug_scope" to "{}",
        )
        for ((name, args) in gated) {
            val r = runBlocking { t.invoke(name, args.obj()) }
            assertFalse("$name should be gated", r.success)
            assertTrue("$name error should mention allowCdp: ${r.output}", r.output.contains("allowCdp"))
        }
        assertEquals(0, engine.calls.size)
    }

    @Test
    fun `allowCdp alone relaxes hook rule tools but keeps inject_script gated`() {
        val t = tools(allowCdp = true, allowHooks = false)
        val create = runBlocking {
            t.invoke("browser.hook_create", """{"type":"fetch","target":"*/api/*"}""".obj())
        }
        assertTrue(create.output, create.success)
        assertEquals(1, engine.installedHooks.size)

        val inject = runBlocking {
            t.invoke("browser.inject_script", """{"code":"1"}""".obj())
        }
        assertFalse(inject.success)
        assertTrue(inject.output.contains("allowHooks"))
    }

    @Test
    fun `debug_attach delegates to engine with default tab`() {
        val r = runBlocking { tools().invoke("browser.debug_attach", "{}".obj()) }
        assertTrue(r.output, r.success)
        assertEquals("debugAttach:t1", engine.calls.single())
        assertTrue(r.output.contains("attached t1"))
    }

    @Test
    fun `debug_set_breakpoint parses url line column condition`() {
        val r = runBlocking {
            tools().invoke(
                "browser.debug_set_breakpoint",
                """{"url":"https://x.test/app.js","line":41,"condition":"user !== null"}""".obj(),
            )
        }
        assertTrue(r.output, r.success)
        val (method, bp) = engine.setBpCalls.single()
        assertEquals("debugSetBreakpoint", method)
        assertEquals("https://x.test/app.js", bp.url)
        assertEquals(41, bp.lineNumber)
        assertEquals(0, bp.columnNumber)
        assertEquals("user !== null", bp.condition)
        assertTrue(r.output.contains("bp-1"))
        assertTrue(r.output.contains("if (user !== null)"))
    }

    @Test
    fun `debug_set_breakpoint requires url and line`() {
        val r1 = runBlocking { tools().invoke("browser.debug_set_breakpoint", """{"line":1}""".obj()) }
        assertFalse(r1.success)
        val r2 = runBlocking { tools().invoke("browser.debug_set_breakpoint", """{"url":"https://x.test/a.js"}""".obj()) }
        assertFalse(r2.success)
    }

    @Test
    fun `debug_step parses over into out and rejects unknown`() {
        val t = tools()
        listOf("over" to DebugStep.OVER, "into" to DebugStep.INTO, "out" to DebugStep.OUT).forEach { (raw, expect) ->
            engine.calls.clear()
            val r = runBlocking { t.invoke("browser.debug_step", """{"step":"$raw"}""".obj()) }
            assertTrue(r.output, r.success)
            assertEquals("debugStep:$expect", engine.calls.single())
        }
        val bad = runBlocking { t.invoke("browser.debug_step", """{"step":"sideways"}""".obj()) }
        assertFalse(bad.success)
    }

    @Test
    fun `debug_detach without tab detaches all`() {
        val r = runBlocking { tools().invoke("browser.debug_detach", "{}".obj()) }
        assertTrue(r.success)
        assertEquals("debugDetach:null", engine.calls.single())
        assertTrue(r.output.contains("detached 2"))
    }

    @Test
    fun `debug_state and debug_eval pass through engine output`() {
        val state = runBlocking { tools().invoke("browser.debug_state", "{}".obj()) }
        assertTrue(state.success)
        assertEquals("running", state.output)

        val eval = runBlocking {
            tools().invoke("browser.debug_eval", """{"expression":"user.name","frame":1}""".obj())
        }
        assertTrue(eval.success)
        assertEquals("eval:1:user.name", eval.output)

        val missing = runBlocking { tools().invoke("browser.debug_eval", "{}".obj()) }
        assertFalse(missing.success)
    }

    @Test
    fun `debug_list_breakpoints formats and debug_remove_breakpoint reports missing`() {
        engine.breakpoints = listOf(DebugBreakpoint("bp-9", "t1", "https://x.test/a.js", 7, 0, ""))
        val list = runBlocking { tools().invoke("browser.debug_list_breakpoints", "{}".obj()) }
        assertTrue(list.success)
        assertTrue(list.output.contains("bp-9"))
        assertTrue(list.output.contains("https://x.test/a.js:7:0"))

        val removed = runBlocking { tools().invoke("browser.debug_remove_breakpoint", """{"id":"bp-9"}""".obj()) }
        assertTrue(removed.success)
        val missing = runBlocking { tools().invoke("browser.debug_remove_breakpoint", """{"id":"bp-x"}""".obj()) }
        assertFalse(missing.success)
    }
}

private class FakeDebugEngine : BrowserEngine {
    override val eventBus = BrowserEventBus()
    override val descriptor = BrowserDescriptor(
        family = BrowserFamily.IN_APP,
        displayName = "fake-debug",
        healthy = true,
        capabilities = emptySet(),
    )
    private val active = BrowserSessionToken("t1", BrowserFamily.IN_APP, url = "https://example.test")

    val calls = mutableListOf<String>()
    val setBpCalls = mutableListOf<Pair<String, DebugBreakpoint>>()
    val installedHooks = mutableListOf<top.wanxiang.app.runtime.browser.hook.HookRule>()
    var breakpoints: List<DebugBreakpoint> = emptyList()

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

    override suspend fun hookInstall(rule: top.wanxiang.app.runtime.browser.hook.HookRule) = rule.also { installedHooks += it }
    override suspend fun hookRemove(id: String) = installedHooks.removeAll { it.id == id }
    override suspend fun hookList(tabId: String?) = emptyList<top.wanxiang.app.runtime.browser.hook.HookRuleInfo>()
    override suspend fun hookReset(tabId: String?) = true
    override suspend fun injectScript(tab: BrowserSessionToken, code: String, persistent: Boolean, name: String) = "ok"
    override suspend fun networkDetail(id: String): String? = null

    override suspend fun debugStatus(): String = "status-ok"
    override suspend fun debugAttach(tab: BrowserSessionToken): String {
        calls += "debugAttach:${tab.tabId}"
        return "attached ${tab.tabId}"
    }

    override suspend fun debugDetach(tab: BrowserSessionToken?): Int {
        calls += "debugDetach:${tab?.tabId}"
        return 2
    }

    override suspend fun debugSetBreakpoint(tab: BrowserSessionToken, url: String, line: Int, column: Int, condition: String?): DebugBreakpoint {
        val bp = DebugBreakpoint("bp-1", tab.tabId, url, line, column, condition ?: "")
        setBpCalls += "debugSetBreakpoint" to bp
        return bp
    }

    override suspend fun debugRemoveBreakpoint(tab: BrowserSessionToken, id: String): Boolean =
        breakpoints.any { it.id == id }.also { if (it) breakpoints = breakpoints.filterNot { b -> b.id == id } }

    override suspend fun debugListBreakpoints(tab: BrowserSessionToken): List<DebugBreakpoint> = breakpoints

    override suspend fun debugResume(tab: BrowserSessionToken?): Int {
        calls += "debugResume:${tab?.tabId}"
        return 1
    }

    override suspend fun debugStep(tab: BrowserSessionToken, step: DebugStep): Boolean {
        calls += "debugStep:$step"
        return true
    }

    override suspend fun debugState(tab: BrowserSessionToken): String = "running"

    override suspend fun debugEval(tab: BrowserSessionToken, frame: Int, expression: String): String {
        calls += "debugEval:$frame"
        return "eval:$frame:$expression"
    }

    override suspend fun debugScope(tab: BrowserSessionToken, frame: Int, scope: Int?): String {
        calls += "debugScope:$frame:$scope"
        return "scope:$frame:${scope ?: "all"}"
    }

    override suspend fun shutdown() = Unit
}
