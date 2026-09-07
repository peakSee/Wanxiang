package top.wanxiang.app.runtime.browser.cdp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.runtime.browser.hook.HookAction
import top.wanxiang.app.runtime.browser.hook.HookRule
import top.wanxiang.app.runtime.browser.hook.HookType

/** 决策矩阵：合并语义、优先级、method 过滤、glob、patterns 提取。 */
class CdpFetchDecisionTest {

    private fun rule(
        id: String,
        type: HookType = HookType.FETCH,
        target: String = "*",
        method: String = "*",
        actions: List<HookAction> = listOf(HookAction.Log()),
        captureBody: Boolean = false,
        enabled: Boolean = true,
    ) = HookRule(id = id, type = type, target = target, method = method, actions = actions, enabled = enabled, captureBody = captureBody)

    @Test
    fun `no rules yields pass`() {
        assertEquals(FetchDecision.Pass, CdpFetchDecision.decide(emptyList(), "https://x.test/api", "GET"))
    }

    @Test
    fun `glob wildcard and question semantics match js runtime`() {
        assertTrue(CdpGlob.matches("https://api.example.com/v1/*", "https://api.example.com/v1/user?id=1"))
        assertTrue(!CdpGlob.matches("https://api.example.com/v2/*", "https://api.example.com/v1/user"))
        assertTrue(CdpGlob.matches("*/api/????", "https://x.test/api/user"))
        // 特殊字符转义：'.' 是字面量
        assertTrue(CdpGlob.matches("a.b", "a.b"))
        assertTrue(!CdpGlob.matches("a.b", "aXb"))
    }

    @Test
    fun `non-network rules ignored`() {
        val r = rule("hr_fn", type = HookType.FUNCTION, target = "JSON.parse")
        assertEquals(FetchDecision.Pass, CdpFetchDecision.decide(listOf(r), "https://x.test/api", "GET"))
    }

    @Test
    fun `method filter`() {
        val r = rule("hr_post", method = "POST")
        assertEquals(FetchDecision.Pass, CdpFetchDecision.decide(listOf(r), "https://x.test/api", "GET"))
        val d = CdpFetchDecision.decide(listOf(r), "https://x.test/api", "POST")
        assertTrue(d is FetchDecision.Log)
    }

    @Test
    fun `priority block over mock over redirect over modify over log`() {
        val all = rule(
            "hr_all",
            actions = listOf(
                HookAction.Log(),
                HookAction.Redirect("https://r.test"),
                HookAction.Mock(status = 200, body = "m"),
                HookAction.Block(),
                HookAction.ModifyHeaders(request = mapOf("X-A" to "1")),
            ),
        )
        assertEquals(FetchDecision.Block("hr_all"), CdpFetchDecision.decide(listOf(all), "https://x.test", "GET"))

        val noBlock = rule("hr_b", actions = listOf(HookAction.Log(), HookAction.Redirect("https://r.test"), HookAction.Mock(status = 201, body = "m")))
        assertEquals(
            FetchDecision.Mock("hr_b", 201, emptyMap(), "m"),
            CdpFetchDecision.decide(listOf(noBlock), "https://x.test", "GET"),
        )

        val onlyRedirect = rule("hr_c", actions = listOf(HookAction.Log(), HookAction.Redirect("https://r.test")))
        assertEquals(
            FetchDecision.Redirect("hr_c", "https://r.test"),
            CdpFetchDecision.decide(listOf(onlyRedirect), "https://x.test", "GET"),
        )

        val onlyModify = rule("hr_d", actions = listOf(HookAction.Log(), HookAction.ModifyHeaders(request = mapOf("X-A" to "1", "Cookie" to "!"))))
        assertEquals(
            FetchDecision.ModifyRequestHeaders("hr_d", mapOf("X-A" to "1", "Cookie" to "!"), false),
            CdpFetchDecision.decide(listOf(onlyModify), "https://x.test", "GET"),
        )

        val onlyLog = rule("hr_e")
        assertEquals(FetchDecision.Log("hr_e", false), CdpFetchDecision.decide(listOf(onlyLog), "https://x.test", "GET"))
    }

    @Test
    fun `multiple matching rules merge actions with later override`() {
        val first = rule("hr_1", actions = listOf(HookAction.Mock(status = 200, body = "first")))
        val second = rule("hr_2", actions = listOf(HookAction.Mock(status = 500, headers = mapOf("X" to "y"), body = "second")))
        // 后写入的 mock 覆盖先写入的（镜像 netDecide 的 out.mock = a 语义）
        val d = CdpFetchDecision.decide(listOf(first, second), "https://x.test/api", "GET")
        assertEquals(FetchDecision.Mock("hr_1", 500, mapOf("X" to "y"), "second"), d)
    }

    @Test
    fun `captureBody starts from rule and log action overrides`() {
        val ruleLevel = rule("hr_r", captureBody = true)
        assertTrue((CdpFetchDecision.decide(listOf(ruleLevel), "https://x.test", "GET") as FetchDecision.Log).captureBody)

        val explicitOff = rule("hr_o", captureBody = true, actions = listOf(HookAction.Log(captureBody = false)))
        assertEquals(false, (CdpFetchDecision.decide(listOf(explicitOff), "https://x.test", "GET") as FetchDecision.Log).captureBody)

        val explicitOn = rule("hr_on", captureBody = false, actions = listOf(HookAction.Log(captureBody = true)))
        assertEquals(true, (CdpFetchDecision.decide(listOf(explicitOn), "https://x.test", "GET") as FetchDecision.Log).captureBody)
    }

    @Test
    fun `disabled rule skipped`() {
        val r = rule("hr_off", enabled = false, actions = listOf(HookAction.Block()))
        assertEquals(FetchDecision.Pass, CdpFetchDecision.decide(listOf(r), "https://x.test", "GET"))
    }

    @Test
    fun `non-network action only yields pass`() {
        // FUNCTION 类规则误混入（Replace 动作）不应产生网络拦截
        val r = rule("hr_rep", actions = listOf(HookAction.Replace(code = "")))
        assertEquals(FetchDecision.Pass, CdpFetchDecision.decide(listOf(r), "https://x.test", "GET"))
    }

    @Test
    fun `fetchPatterns dedupes network rule targets`() {
        val rules = listOf(
            rule("a", target = "*/api/*"),
            rule("b", target = "*/api/*", method = "POST"),
            rule("c", type = HookType.XHR, target = "*/xhr/*"),
            rule("d", type = HookType.FUNCTION, target = "JSON.parse"),
            rule("e", target = "*/disabled/*", enabled = false),
        )
        assertEquals(listOf("*/api/*", "*/xhr/*"), CdpFetchDecision.fetchPatterns(rules))
    }

    @Test
    fun `actionTaken labels`() {
        assertEquals("block", FetchDecision.Block("x").actionTaken())
        assertEquals("mock", FetchDecision.Mock("x", 200, emptyMap(), "").actionTaken())
        assertEquals("pass", FetchDecision.Pass.actionTaken())
    }
}

/** JSON helper（测试内构造 CDP 参数）。 */
private fun String.obj(): JsonObject = Json.parseToJsonElement(this).jsonObject
