package top.wanxiang.app.runtime.browser.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookEventParserTest {

    @Test
    fun `net_req parsed with bound tab`() {
        val e = HookEventParser.parse(
            "tabBound",
            """{"v":1,"tab":"fake","seq":1,"ts":1000,"kind":"net_req","data":{"id":"n1","initiator":"fetch","url":"https://x.test/api","method":"post","headers":{"a":"b"},"body":"k=v"}}""",
        )
        assertNotNull(e)
        val req = e as HookEventParser.HookBridgeEvent.NetReq
        assertEquals("tabBound", req.tabId)
        assertEquals("n1", req.id)
        assertEquals("POST", req.method)
        assertEquals(mapOf("a" to "b"), req.headers)
        assertEquals("k=v", req.body)
    }

    @Test
    fun `net_res parsed with action metadata`() {
        val e = HookEventParser.parse(
            "t",
            """{"v":1,"seq":2,"ts":1001,"kind":"net_res","data":{"id":"n1","url":"https://x.test/api","status":200,"statusText":"OK","headers":{"c":"d"},"durationMs":88,"ruleId":"hr_1","actionTaken":"mock"}}""",
        )
        val res = e as HookEventParser.HookBridgeEvent.NetRes
        assertEquals(200, res.status)
        assertEquals(88L, res.durationMs)
        assertEquals("hr_1", res.ruleId)
        assertEquals("mock", res.actionTaken)
        assertNull(res.body)
    }

    @Test
    fun `hit parsed with type coercion`() {
        val e = HookEventParser.parse(
            "t",
            """{"v":1,"seq":3,"ts":1002,"kind":"hit","data":{"hookId":"hr_1","type":"METHOD","target":"JSON.parse","phase":"call","summary":"JSON.parse(64 chars)","detail":"{\"args\":1}"}}""",
        )
        val hit = e as HookEventParser.HookBridgeEvent.Hit
        assertEquals(HookType.METHOD, hit.type)
        assertEquals("hr_1", hit.hookId)
        assertTrue(hit.summary.startsWith("JSON.parse"))
    }

    @Test
    fun `envelope tab field ignored in favor of bound tab`() {
        val e = HookEventParser.parse(
            "bound",
            """{"v":1,"tab":"spoofed","kind":"hit","data":{"hookId":"h","target":"x","phase":"call","summary":"s"}}""",
        )
        assertEquals("bound", e!!.tabId)
    }

    @Test
    fun `bad version rejected`() {
        assertNull(HookEventParser.parse("t", """{"v":2,"kind":"hit","data":{}}"""))
    }

    @Test
    fun `unknown kind rejected`() {
        assertNull(HookEventParser.parse("t", """{"v":1,"kind":"exec","data":{}}"""))
    }

    @Test
    fun `malformed json rejected`() {
        assertNull(HookEventParser.parse("t", "not json"))
        assertNull(HookEventParser.parse("t", ""))
    }

    @Test
    fun `oversized event rejected`() {
        val huge = "x".repeat(512 * 1024 + 1)
        assertNull(HookEventParser.parse("t", """{"v":1,"kind":"hit","data":{"summary":"$huge"}}"""))
    }

    @Test
    fun `ws and hook_error and ready parsed`() {
        val ws = HookEventParser.parse("t", """{"v":1,"kind":"ws","data":{"url":"wss://x","event":"send","summary":"42B"}}""")
        assertTrue(ws is HookEventParser.HookBridgeEvent.Ws)
        val err = HookEventParser.parse("t", """{"v":1,"kind":"hook_error","data":{"stage":"init","message":"boom"}}""")
        assertTrue(err is HookEventParser.HookBridgeEvent.HookError)
        val ready = HookEventParser.parse("t", """{"v":1,"kind":"ready","data":{"href":"https://x.test/"}}""")
        assertTrue(ready is HookEventParser.HookBridgeEvent.Ready)
    }

    @Test
    fun `missing data object tolerated`() {
        val e = HookEventParser.parse("t", """{"v":1,"kind":"hit"}""")
        val hit = e as HookEventParser.HookBridgeEvent.Hit
        assertEquals("", hit.summary)
        assertNull(hit.type)
    }
}
