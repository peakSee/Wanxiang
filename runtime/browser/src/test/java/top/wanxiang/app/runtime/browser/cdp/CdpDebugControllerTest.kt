package top.wanxiang.app.runtime.browser.cdp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.runtime.browser.BrowserEvent
import top.wanxiang.app.runtime.browser.BrowserEventBus

/** Fake 命令通道 + 状态机迁移测试（纯 JVM，无网络）。 */
class CdpDebugControllerTest {

    /** 录制式 fake：按 method 回放预设响应，记录发出的命令序列。 */
    private class FakeCdpApi : CdpCommandApi {
        val sent = ArrayList<Pair<String, JsonObject>>()
        val responses = HashMap<String, () -> JsonObject>()

        override suspend fun send(method: String, params: JsonObject, sessionId: String?, timeoutMs: Long): JsonObject {
            sent += method to params
            return responses[method]?.invoke()
                ?: Json.parseToJsonElement("""{"result":{}}""").jsonObject
        }
    }

    private val api = FakeCdpApi()
    private val eventBus = BrowserEventBus()
    private val controller = CdpDebugController("t1", api, eventBus)

    private fun pausedEvent(frameId: String = "cf-1"): String = """
        {"callFrames":[{"callFrameId":"$frameId","functionName":"encrypt","url":"https://x.test/app.js",
          "lineNumber":41,"columnNumber":8,
          "scopeChain":[{"type":"local","object":{"objectId":"obj-1"}},
                        {"type":"global","object":{"objectId":"obj-2"}}]}],
         "reason":"breakpoint","hitBreakpoints":["bp-1"]}
    """.trimIndent()

    @Test
    fun `setup enables domains and replays breakpoints`() = runBlocking {
        api.responses["Debugger.setBreakpointByUrl"] = {
            buildJsonObject { put("breakpointId", "bp-100") }
        }
        controller.setup(
            listOf(DebugBreakpoint("bp-old", "t1", "https://x.test/app.js", 41, 0, "")),
        )
        val methods = api.sent.map { it.first }
        assertTrue("Runtime.enable" in methods)
        assertTrue("Debugger.enable" in methods)
        assertTrue("Debugger.setSkipAllPauses" in methods)
        assertTrue(methods.count { it == "Debugger.setBreakpointByUrl" } == 1)
        // 重放后本地断点表以 CDP 返回的新 id 为准
        assertTrue(controller.breakpoints().any { it.id == "bp-100" })
    }

    @Test
    fun `set and remove breakpoint`() = runBlocking {
        api.responses["Debugger.setBreakpointByUrl"] = {
            buildJsonObject { put("breakpointId", "bp-1:0:41") }
        }
        val bp = controller.setBreakpoint("https://x.test/app.js", 41, 0, "user !== null")
        assertEquals("bp-1:0:41", bp.id)
        assertEquals("user !== null", bp.condition)
        assertEquals(1, controller.breakpoints().size)

        assertTrue(controller.removeBreakpoint("bp-1:0:41"))
        assertFalse(controller.removeBreakpoint("bp-1:0:41")) // 重复删返回 false
        assertTrue(controller.breakpoints().isEmpty())
        assertTrue(api.sent.any { it.first == "Debugger.removeBreakpoint" })
    }

    @Test
    fun `paused and resumed state transitions publish events`() = runBlocking {
        controller.onPaused(Json.parseToJsonElement(pausedEvent()).jsonObject)
        val state = controller.paused.value
        assertEquals("breakpoint", state!!.reason)
        assertEquals(listOf("bp-1"), state.hitBreakpoints)
        assertEquals(1, state.callFrames.size)
        assertEquals("encrypt", state.callFrames[0].functionName)
        assertEquals(41, state.callFrames[0].lineNumber)
        assertEquals(2, state.callFrames[0].scopes.size)
        // 事件总线侧
        assertEquals(state, eventBus.debugPausedOf("t1"))
        assertEquals(1, eventBus.debugEvents.value.count { it.kind == "paused" })

        controller.onResumed()
        assertNull(controller.paused.value)
        assertNull(eventBus.debugPausedOf("t1"))
        assertTrue(eventBus.debugEvents.value.any { it.kind == "resumed" })
    }

    @Test
    fun `resume and step require paused state`() = runBlocking {
        try {
            controller.resume()
            throw AssertionError("expected ISE")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not paused"))
        }
        try {
            controller.step(DebugStep.OVER)
            throw AssertionError("expected ISE")
        } catch (_: IllegalStateException) {
        }
        controller.onPaused(Json.parseToJsonElement(pausedEvent()).jsonObject)
        assertTrue(controller.resume())
        assertTrue(api.sent.any { it.first == "Debugger.resume" })

        controller.onPaused(Json.parseToJsonElement(pausedEvent()).jsonObject)
        assertTrue(controller.step(DebugStep.INTO))
        assertTrue(api.sent.any { it.first == "Debugger.stepInto" })
    }

    @Test
    fun `evaluateOnCallFrame formats values and exceptions`() = runBlocking {
        controller.onPaused(Json.parseToJsonElement(pausedEvent()).jsonObject)
        api.responses["Runtime.evaluateOnCallFrame"] = {
            Json.parseToJsonElement(
                """{"result":{"type":"string","value":"hello"},"exceptionDetails":{"text":"uncaught","exception":{"description":"ReferenceError: x is not defined"}}}""",
            ).jsonObject
        }
        val out = controller.evaluateOnCallFrame("cf-1", "secret")
        // 有 exceptionDetails 时优先输出异常
        assertTrue(out.contains("ReferenceError: x is not defined"))

        api.responses["Runtime.evaluateOnCallFrame"] = {
            Json.parseToJsonElement("""{"result":{"type":"number","value":42}}""").jsonObject
        }
        assertEquals("42", controller.evaluateOnCallFrame("cf-1", "answer"))

        api.responses["Runtime.evaluateOnCallFrame"] = {
            Json.parseToJsonElement("""{"result":{"type":"undefined"}}""").jsonObject
        }
        assertEquals("undefined", controller.evaluateOnCallFrame("cf-1", "nothing"))
    }

    @Test
    fun `scopeProperties summary and detailed views`() = runBlocking {
        controller.onPaused(Json.parseToJsonElement(pausedEvent()).jsonObject)
        api.responses["Runtime.getProperties"] = {
            Json.parseToJsonElement(
                """{"result":[{"name":"key","value":{"type":"string","value":"abc123"}},
                             {"name":"count","value":{"type":"number","value":7}}]}""",
            ).jsonObject
        }
        // 摘要（scopeIndex=null）：两个作用域的变量名
        val summary = controller.scopeProperties("cf-1", null)
        assertTrue(summary.contains("[0] local"))
        assertTrue(summary.contains("key, count"))
        assertTrue(summary.contains("[1] global"))

        // 详情（scopeIndex=0）
        val detail = controller.scopeProperties("cf-1", 0)
        assertTrue(detail.contains("key = \"abc123\""))
        assertTrue(detail.contains("count = 7"))
    }

    @Test
    fun `unknown callFrameId and out of range scope rejected`() = runBlocking {
        controller.onPaused(Json.parseToJsonElement(pausedEvent()).jsonObject)
        try {
            controller.scopeProperties("cf-nope", null)
            throw AssertionError("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cf-nope"))
        }
        try {
            controller.scopeProperties("cf-1", 9)
            throw AssertionError("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("out of range"))
        }
    }

    @Test
    fun `long eval result truncated`() = runBlocking {
        controller.onPaused(Json.parseToJsonElement(pausedEvent()).jsonObject)
        val longValue = "x".repeat(70_000)
        api.responses["Runtime.evaluateOnCallFrame"] = {
            Json.parseToJsonElement("""{"result":{"type":"string","value":"$longValue"}}""").jsonObject
        }
        val out = controller.evaluateOnCallFrame("cf-1", "big")
        assertTrue(out.contains("[TRUNCATED"))
        assertTrue(out.length < 66_000)
    }

    @Test
    fun `cleanup clears breakpoints and paused state`() = runBlocking {
        api.responses["Debugger.setBreakpointByUrl"] = { buildJsonObject { put("breakpointId", "bp-x") } }
        controller.setBreakpoint("https://x.test/a.js", 1, 0, null)
        controller.onPaused(Json.parseToJsonElement(pausedEvent()).jsonObject)
        controller.cleanup()
        assertTrue(controller.breakpoints().isEmpty())
        assertNull(controller.paused.value)
    }
}
