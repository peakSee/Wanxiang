package top.wanxiang.app.runtime.browser.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookRuleStoreTest {

    private fun store() = HookRuleStore(maxBodyBytes = 131072)

    private fun fetchRule(id: String, tabId: String? = null) =
        HookRule(id = id, type = HookType.FETCH, target = "api/*", scopeTabId = tabId)

    @Test
    fun `install then payload contains rule`() {
        val s = store()
        s.install(fetchRule("hr_1"))
        val payload = s.payloadFor("tabA")
        assertTrue(payload.contains("\"hr_1\""))
        assertTrue(payload.contains("\"maxBodyBytes\":131072"))
    }

    @Test
    fun `tab scoped rule excluded from other tab`() {
        val s = store()
        s.install(fetchRule("hr_scoped", tabId = "tabA"))
        assertTrue(s.payloadFor("tabA").contains("hr_scoped"))
        assertFalse(s.payloadFor("tabB").contains("hr_scoped"))
        assertEquals(1, s.rulesFor("tabA").size)
        assertEquals(0, s.rulesFor("tabB").size)
    }

    @Test
    fun `payload cached per version and tab`() {
        val s = store()
        s.install(fetchRule("hr_1"))
        val first = s.payloadFor("tabA")
        val second = s.payloadFor("tabA")
        assertTrue(first === second)
        val otherTab = s.payloadFor("tabB")
        assertFalse(first === otherTab)
    }

    @Test
    fun `mutation invalidates cache and bumps version`() {
        val s = store()
        s.install(fetchRule("hr_1"))
        val v1 = s.currentVersion()
        val p1 = s.payloadFor("tabA")
        s.install(fetchRule("hr_2"))
        assertTrue(s.currentVersion() > v1)
        val p2 = s.payloadFor("tabA")
        assertFalse(p1 === p2)
        assertTrue(p2.contains("hr_2"))
    }

    @Test
    fun `disabled rule excluded from payload`() {
        val s = store()
        s.install(fetchRule("hr_off").copy(enabled = false))
        assertFalse(s.payloadFor("tabA").contains("hr_off"))
        assertEquals(0, s.rulesFor("tabA").size)
        assertEquals(1, s.list().size)
    }

    @Test
    fun `remove resets hit count`() {
        val s = store()
        s.install(fetchRule("hr_1"))
        s.recordHit("hr_1"); s.recordHit("hr_1")
        assertEquals(2, s.hitCount("hr_1"))
        assertTrue(s.remove("hr_1"))
        assertEquals(0, s.hitCount("hr_1"))
        assertFalse(s.remove("hr_1"))
    }

    @Test
    fun `scripts merged into payload`() {
        val s = store()
        s.addScript(InjectedScript(id = "sc_1", name = "patch", code = "console.log(1)"))
        val payload = s.payloadFor("tabA")
        assertTrue(payload.contains("sc_1"))
        assertTrue(payload.contains("console.log(1)"))
    }

    @Test
    fun `reset clears rules but version still bumps`() {
        val s = store()
        s.install(fetchRule("hr_1"))
        s.recordHit("hr_1")
        val v1 = s.currentVersion()
        s.reset(null)
        assertTrue(s.currentVersion() > v1)
        assertEquals(0, s.list().size)
        assertEquals(0, s.hitCount("hr_1"))
        assertFalse(s.payloadFor("tabA").contains("hr_1"))
    }

    @Test
    fun `payload is valid JSON parseable back`() {
        val s = store()
        s.install(fetchRule("hr_1").copy(actions = listOf(HookAction.Mock(201, mapOf("X-A" to "b"), "hello"))))
        s.addScript(InjectedScript(id = "sc_1", name = "n", code = "void 0"))
        val payload = s.payloadFor("t")
        // 反序列化回 payload 模型（验证 JSON 合法且结构无损）
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(HookRulesPayload.serializer(), payload)
        assertEquals(1, decoded.rules.size)
        assertEquals("hr_1", decoded.rules[0].id)
        assertTrue(decoded.rules[0].actions[0] is HookAction.Mock)
        assertEquals(1, decoded.scripts.size)
    }
}
