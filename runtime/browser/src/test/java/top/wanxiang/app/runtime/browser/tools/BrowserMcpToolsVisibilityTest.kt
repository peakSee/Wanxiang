package top.wanxiang.app.runtime.browser.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.browser.BrowserPreferences

/**
 * 工具清单注入门禁规约：list() 必须与 invoke 侧门禁同源——
 * 被关闭的能力不从 tools/list 注入模型（省掉每轮请求重复携带的 schema tokens），
 * 基础工具与无门禁的 network_detail 恒可见。
 */
class BrowserMcpToolsVisibilityTest {

    private fun names(prefs: BrowserPreferences): List<String> =
        BrowserMcpTools(engines = emptyList(), engineSelector = { _ -> null }, prefs = prefs)
            .list().map { it.name }

    @Test
    fun `default prefs hide all gated tools and keep base toolset`() {
        val names = names(BrowserPreferences.DEFAULT)
        listOf(
            "browser.evaluate", "browser.inject_script",
            "browser.hook_create", "browser.hook_list", "browser.hook_remove",
            "browser.hook_reset", "browser.hook_hits", "browser.debug_status",
        ).forEach { assertFalse("默认门禁下不应注入 $it", it in names) }
        listOf(
            "browser.open", "browser.snapshot", "browser.network_list", "browser.network_detail",
        ).forEach { assertTrue("无门禁工具应保留 $it", it in names) }
        // 31 个基础工具 - evaluate + network_detail = 31
        assertEquals(31, names.size)
    }

    @Test
    fun `allowCdp alone exposes debug and hook rule tools but not inject_script`() {
        val names = names(BrowserPreferences(allowCdp = true))
        assertTrue("browser.debug_attach" in names)
        assertTrue("browser.hook_create" in names)
        assertFalse("browser.inject_script" in names)
        assertFalse("browser.evaluate" in names)
        // 31 + 11 debug + 5 hook_* = 47
        assertEquals(47, names.size)
    }

    @Test
    fun `allowHooks alone exposes inject_script and hook tools but not debug`() {
        val names = names(BrowserPreferences(allowHooks = true))
        assertTrue("browser.inject_script" in names)
        assertTrue("browser.hook_hits" in names)
        assertFalse("browser.debug_state" in names)
        assertFalse("browser.evaluate" in names)
        // 31 + 5 hook_* + 1 inject_script = 37
        assertEquals(37, names.size)
    }

    @Test
    fun `all gates open expose the full 49-tool schema`() {
        val names = names(BrowserPreferences(allowEvalJs = true, allowHooks = true, allowCdp = true))
        assertEquals(49, names.size)
        assertTrue("browser.evaluate" in names)
        assertTrue("browser.debug_scope" in names)
    }
}
