package top.wanxiang.app.harness.mcp.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import top.wanxiang.app.runtime.browser.BrowserEventBus
import top.wanxiang.app.runtime.browser.BrowserSessionToken
import top.wanxiang.app.runtime.browser.tools.BrowserMcpTools

class McpToolDispatcherTest {

    @Test
    fun `tools call uses standard MCP content envelope`() = runBlocking {
        val engine = FakeBrowserEngine()
        val dispatcher = dispatcher(engine)

        val result = dispatcher.dispatch("browser.current_url", JsonObject(emptyMap()))

        assertFalse(result.getValue("isError").jsonPrimitive.boolean)
        val content = result.getValue("content").jsonArray
        assertEquals("text", content.single().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("https://example.test", content.single().jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `missing tab targets current active tab`() = runBlocking {
        val engine = FakeBrowserEngine()
        val dispatcher = dispatcher(engine)

        dispatcher.dispatch(
            "browser.navigate",
            buildJsonObject { put("url", JsonPrimitive("https://openai.com")) },
        )

        assertEquals(engine.active.tabId, engine.lastNavigatedTab?.tabId)
    }

    @Test
    fun `tool list declares arguments and hides unimplemented file tools`() {
        val tools = dispatcher(FakeBrowserEngine()).listTools()
        val navigate = tools.single { it.getValue("name").jsonPrimitive.content == "browser.navigate" }
        val schema = navigate.getValue("inputSchema").jsonObject

        assertTrue(schema.getValue("required").jsonArray.any { it.jsonPrimitive.content == "url" })
        assertTrue(schema.getValue("properties").jsonObject.containsKey("url"))
        assertFalse(tools.any { it.getValue("name").jsonPrimitive.content.startsWith("browser.file_") })
    }

    @Test
    fun `evaluate is blocked when allowEvalJs is disabled`() = runBlocking {
        val engine = FakeBrowserEngine()
        val dispatcher = dispatcher(engine, BrowserPreferences.DEFAULT.copy(allowEvalJs = false))

        val result = dispatcher.dispatch(
            "browser.evaluate",
            buildJsonObject { put("expression", JsonPrimitive("1+1")) },
        )

        assertTrue(result.getValue("isError").jsonPrimitive.boolean)
        val text = result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        assertTrue(text.contains("allowEvalJs"))
    }

    @Test
    fun `evaluate reports failure when engine returns null`() = runBlocking {
        val engine = FakeBrowserEngine().apply { evaluateResult = null }
        val dispatcher = dispatcher(engine, BrowserPreferences.DEFAULT.copy(allowEvalJs = true))

        val result = dispatcher.dispatch(
            "browser.evaluate",
            buildJsonObject { put("expression", JsonPrimitive("1+1")) },
        )

        assertTrue(result.getValue("isError").jsonPrimitive.boolean)
        val text = result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        assertTrue(text.contains("timed out"))
    }

    @Test
    fun `cookie values are redacted in tool output`() = runBlocking {
        val engine = FakeBrowserEngine().apply { cookies = "session=abc123; theme=dark" }
        val dispatcher = dispatcher(engine)

        val result = dispatcher.dispatch("browser.cookies_get", JsonObject(emptyMap()))

        val text = result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        assertFalse(text.contains("abc123"))
        assertTrue(text.contains("session=[REDACTED]"))
        assertTrue(text.contains("theme=dark"))
    }

    private fun dispatcher(
        engine: FakeBrowserEngine,
        prefs: BrowserPreferences = BrowserPreferences.DEFAULT,
    ): McpToolDispatcher {
        val tools = BrowserMcpTools(
            engines = emptyList(),
            engineSelector = { engine },
            prefs = prefs,
        )
        return McpToolDispatcher(tools)
    }
}

private class FakeBrowserEngine : BrowserEngine {
    override val eventBus = BrowserEventBus()
    override val descriptor = BrowserDescriptor(
        family = BrowserFamily.IN_APP,
        displayName = "fake",
        healthy = true,
        capabilities = emptySet(),
    )
    val active = BrowserSessionToken("active-tab", BrowserFamily.IN_APP, url = "https://example.test")
    var lastNavigatedTab: BrowserSessionToken? = null
    var evaluateResult: String? = ""
    var cookies: String = ""

    init {
        runBlocking { eventBus.publish(top.wanxiang.app.runtime.browser.BrowserEvent.PageChanged(active.tabId, active.url, "Example")) }
    }

    override suspend fun openTab(url: String?, activate: Boolean) = active
    override suspend fun navigate(tab: BrowserSessionToken, url: String) { lastNavigatedTab = tab }
    override suspend fun snapshot(tab: BrowserSessionToken, maxElements: Int) =
        PageSnapshot(tab.tabId, active.url, "Example", emptyMap(), "fake", 0L)
    override suspend fun click(tab: BrowserSessionToken, ref: String, refSelectorLookup: suspend (BrowserSessionToken, String) -> String?) = true
    override suspend fun typeInto(tab: BrowserSessionToken, ref: String, text: String, refSelectorLookup: suspend (BrowserSessionToken, String) -> String?) = true
    override suspend fun press(tab: BrowserSessionToken, ref: String?, key: String, refSelectorLookup: suspend (BrowserSessionToken, String) -> String?) = true
    override suspend fun scroll(tab: BrowserSessionToken, deltaY: Int) = true
    override suspend fun screenshot(tab: BrowserSessionToken, prefs: BrowserPreferences): ToolImageRef? = null
    override suspend fun evaluate(tab: BrowserSessionToken, script: String): String? = evaluateResult
    override suspend fun pageSource(tab: BrowserSessionToken, maxBytes: Int) = ""
    override suspend fun back(tab: BrowserSessionToken) = true
    override suspend fun forward(tab: BrowserSessionToken) = true
    override suspend fun refresh(tab: BrowserSessionToken) = Unit
    override suspend fun closeTab(tab: BrowserSessionToken) = Unit
    override suspend fun listTabs() = listOf(active)
    override fun activeTab(): BrowserSessionToken = active
    override suspend fun setActiveTab(tab: BrowserSessionToken) = Unit
    override suspend fun cookiesGet(tab: BrowserSessionToken, url: String?) = cookies
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
    override suspend fun shutdown() = Unit
}
