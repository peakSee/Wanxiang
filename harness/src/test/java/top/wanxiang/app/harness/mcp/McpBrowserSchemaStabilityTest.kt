package top.wanxiang.app.harness.mcp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.wanxiang.app.core.browser.BrowserPreferences
import top.wanxiang.app.core.model.BuiltinMcpPresets
import top.wanxiang.app.harness.mcp.server.BuiltinBrowserMcpAccess
import top.wanxiang.app.harness.mcp.server.McpToolDispatcher
import top.wanxiang.app.runtime.browser.tools.BrowserMcpTools

/**
 * 阶段四：浏览器 MCP 动态端口 → provider 可见 schema 收敛。
 *
 * 断言的边界：provider 可见工具 schema（发现期注入模型）在"未绑定 / 端口 A / 端口 B"
 * 三种运行时状态下字节级一致，且不携带任何端口/地址字样；真实端口只在 tools/call
 * 连接阶段经 [resolveBrowserEffectiveUrl] 运行时解析（见 McpHttpTransport.effectiveUrlOf）。
 */
class McpBrowserSchemaStabilityTest {

    private lateinit var dispatcher: McpToolDispatcher

    @Before
    fun setUp() {
        // 仅用到 list()（静态工具清单），engine 与 prefs 不参与 provider 可见 schema。
        val tools = BrowserMcpTools(
            engines = emptyList(),
            engineSelector = { _ -> null },
            prefs = BrowserPreferences.DEFAULT,
        )
        dispatcher = McpToolDispatcher(tools)
    }

    @After
    fun tearDown() {
        BuiltinBrowserMcpAccess.port = null
    }

    // ---- 发现与调用分离：URL 路由回归 ----

    @Test
    fun `unbound browser server keeps the static preset url untouched`() {
        assertEquals(
            "http://127.0.0.1:8787/mcp",
            resolveBrowserEffectiveUrl("http://127.0.0.1:8787/mcp", BuiltinMcpPresets.BROWSER_BUILTIN_ID, null),
        )
    }

    @Test
    fun `non browser server never gets its port rewritten`() {
        assertEquals(
            "http://127.0.0.1:9000/mcp",
            resolveBrowserEffectiveUrl("http://127.0.0.1:9000/mcp", "mcp_sqlite", 8788),
        )
    }

    @Test
    fun `browser server port shift rewires only the authority port`() {
        assertEquals(
            "http://127.0.0.1:8788/mcp",
            resolveBrowserEffectiveUrl("http://127.0.0.1:8787/mcp", BuiltinMcpPresets.BROWSER_BUILTIN_ID, 8788),
        )
        assertEquals(
            "http://127.0.0.1:8791/mcp",
            resolveBrowserEffectiveUrl("http://127.0.0.1:8787/mcp", BuiltinMcpPresets.BROWSER_BUILTIN_ID, 8791),
        )
    }

    // ---- provider 可见 schema 稳定快照 ----

    private fun schemaSnapshot(): String =
        dispatcher.listTools().joinToString("\n") { it.toString() }

    @Test
    fun `provider schema stays byte-identical across unbound and both shifted ports`() {
        val schemaUnbound = schemaSnapshot()
        assertTrue(schemaUnbound.isNotBlank())

        BuiltinBrowserMcpAccess.port = 8787
        val schemaPortA = schemaSnapshot()

        BuiltinBrowserMcpAccess.port = 8793
        val schemaPortB = schemaSnapshot()

        assertEquals("端口 A 不得改变 provider 可见 schema", schemaUnbound, schemaPortA)
        assertEquals("端口 B 不得改变 provider 可见 schema", schemaUnbound, schemaPortB)
    }

    @Test
    fun `provider schema never embeds the browser servers own runtime bind address`() {
        // 只禁止内置浏览器自身绑定地址（loopback + 预设/顺延端口）泄漏；
        // 描述里的文档示例 URL（如 https://api.example.com）属稳定静态文案，不在此列。
        val leaky = listOf("127.0.0.1", "localhost", "127.0.0.1:8787", ":8787", ":8793")
        val snapshot = schemaSnapshot()

        leaky.forEach { token ->
            assertFalse("provider 可见 schema 不应出现 '$token' 字样", snapshot.contains(token))
        }
    }
}