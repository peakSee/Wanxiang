package top.wanxiang.app.runtime.browser.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookModelsTest {

    private fun rule(
        type: HookType = HookType.FETCH,
        target: String = "api/*",
        actions: List<HookAction> = listOf(HookAction.Log()),
    ) = HookRule(id = "hr_test", type = type, target = target, actions = actions)

    @Test
    fun `valid fetch rule with full action set passes`() {
        assertNull(
            rule(
                actions = listOf(
                    HookAction.Log(),
                    HookAction.Block(),
                    HookAction.Redirect("https://x.test/"),
                    HookAction.Mock(200, mapOf("Content-Type" to "application/json"), "{}"),
                    HookAction.ModifyHeaders(request = mapOf("X-Trace" to "1"), response = mapOf("Set-Cookie" to "!")),
                )
            ).validate()
        )
    }

    @Test
    fun `mock on function type rejected`() {
        val err = rule(type = HookType.FUNCTION, target = "JSON.parse", actions = listOf(HookAction.Mock())).validate()
        assertNotNull(err)
        assertTrue(err!!.contains("Mock"))
    }

    @Test
    fun `replace on fetch type rejected`() {
        val err = rule(actions = listOf(HookAction.Replace("return 1"))).validate()
        assertNotNull(err)
    }

    @Test
    fun `fake_value on non-property rejected`() {
        assertNotNull(rule(type = HookType.FUNCTION, target = "a.b", actions = listOf(HookAction.FakeValue("x"))).validate())
        assertNull(rule(type = HookType.PROPERTY, target = "document.cookie", actions = listOf(HookAction.FakeValue("x"))).validate())
    }

    @Test
    fun `bad path target rejected for function type`() {
        assertNotNull(rule(type = HookType.FUNCTION, target = "JSON..parse").validate())
        assertNotNull(rule(type = HookType.PROPERTY, target = "a-b.c").validate())
        assertNull(rule(type = HookType.METHOD, target = "JSON.parse").validate())
    }

    @Test
    fun `bad glob target rejected for network type`() {
        assertNotNull(rule(target = "api/ ").validate())
        assertNull(rule(target = "https://*.example.com/v?/*").validate())
    }

    @Test
    fun `blank target rejected`() {
        assertNotNull(rule(target = "").validate())
    }

    @Test
    fun `maxHits out of range rejected`() {
        assertNotNull(rule().copy(maxHits = 0).validate())
        assertNotNull(rule().copy(maxHits = 1_000_000).validate())
    }

    @Test
    fun `hook type round trip via name`() {
        HookType.entries.forEach { t ->
            assertEquals(t, HookType.entries.firstOrNull { it.name.equals(t.name, true) })
        }
        assertEquals(HookType.FETCH, HookType.entries.firstOrNull { it.name.equals("fetch", true) })
    }
}
