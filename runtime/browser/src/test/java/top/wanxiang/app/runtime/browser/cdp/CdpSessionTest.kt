package top.wanxiang.app.runtime.browser.cdp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CdpSession over MockWebServer 真实 WebSocket：命令关联、错误透传、事件分发、断连清理。
 * 走 [TcpCdpTransport] → [WsHandshake] → [CdpSession] 全链路（与真机 LocalSocket 同一代码路径）。
 */
class CdpSessionTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        // WS 升级连接未走完关闭握手时 MockWebServer.shutdown 会断言失败；
        // 主体断言此时已执行完，清理交给进程退出（每个测试独立 fixture，无泄漏累积）
        runCatching { server.shutdown() }
    }

    /** 服务器端：回显式 WebSocket（按请求 JSON 的 id 回响应）。 */
    private class EchoWsListener(
        private val onRequest: (String) -> String? = { null },
        private val onOpen: (okhttp3.WebSocket) -> Unit = {},
    ) : okhttp3.WebSocketListener() {
        override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
            onOpen(webSocket)
        }

        override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
            onRequest(text)?.let { webSocket.send(it) }
        }
    }

    private fun upgrade(listener: okhttp3.WebSocketListener): MockResponse =
        MockResponse().withWebSocketUpgrade(listener)

    private fun openSession(listener: okhttp3.WebSocketListener): CdpSession {
        server.enqueue(upgrade(listener))
        val transport = TcpCdpTransport(server.hostName, server.port)
        val ws = WsHandshake.open(transport.open(), "/devtools/page/test")
        return CdpSession(ws, scope)
    }

    @Test
    fun `send correlates response by id`() = runBlocking {
        val session = openSession(EchoWsListener(onRequest = { reqText ->
            val req = Json.parseToJsonElement(reqText).jsonObject
            val id = req["id"]!!.jsonPrimitive.content
            when (req["method"]!!.jsonPrimitive.content) {
                "Test.hello" -> """{"id":$id,"result":{"greeting":"world"}}"""
                else -> null
            }
        }))
        val events = CopyOnWriteArrayList<String>()
        session.start(object : CdpSession.EventListener {
            override suspend fun onEvent(method: String, params: kotlinx.serialization.json.JsonObject, sessionId: String?) {
                events += method
            }
            override suspend fun onClosed() {}
        })
        val result = session.send("Test.hello", buildJsonObject { put("name", "agent") }, null, 10_000)
        assertEquals("world", result["result"]!!.jsonObject.jsonObject["greeting"]!!.jsonPrimitive.content)
        session.close()
    }

    @Test
    fun `error response throws CdpCommandException with original message`() = runBlocking {
        val session = openSession(EchoWsListener(onRequest = { reqText ->
            val id = Json.parseToJsonElement(reqText).jsonObject["id"]!!.jsonPrimitive.content
            """{"id":$id,"error":{"code":-32000,"message":"Breakpoint not found"}}"""
        }))
        session.start(object : CdpSession.EventListener {
            override suspend fun onEvent(method: String, params: kotlinx.serialization.json.JsonObject, sessionId: String?) {}
            override suspend fun onClosed() {}
        })
        try {
            session.send("Debugger.removeBreakpoint", JsonObject(emptyMap()), null, 10_000)
            throw AssertionError("expected CdpCommandException")
        } catch (e: CdpCommandException) {
            assertEquals(-32000, e.code)
            assertEquals("Breakpoint not found", e.message)
        }
        session.close()
    }

    @Test
    fun `events dispatched in order with sessionId`() = runBlocking {
        val session = openSession(object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"method":"Debugger.paused","params":{"reason":"breakpoint"}}""")
                webSocket.send("""{"method":"Debugger.resumed","params":{},"sessionId":"ws-1"}""")
            }
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {}
        })
        val received = CopyOnWriteArrayList<Pair<String, String?>>()
        session.start(object : CdpSession.EventListener {
            override suspend fun onEvent(method: String, params: kotlinx.serialization.json.JsonObject, sessionId: String?) {
                received += method to sessionId
            }
            override suspend fun onClosed() {}
        })
        withTimeout(5_000) {
            while (received.size < 2) kotlinx.coroutines.delay(20)
        }
        assertEquals("Debugger.paused" to null, received[0])
        assertEquals("Debugger.resumed" to "ws-1", received[1])
        session.close()
    }
    @Test
    fun `server close fails pending commands and notifies listener`() = runBlocking {
        lateinit var serverWs: okhttp3.WebSocket
        val session = openSession(EchoWsListener(
            onOpen = { serverWs = it },
            onRequest = { null }, // 不回响应，让命令挂起
        ))
        val closed = java.util.concurrent.CountDownLatch(1)
        session.start(object : CdpSession.EventListener {
            override suspend fun onEvent(method: String, params: kotlinx.serialization.json.JsonObject, sessionId: String?) {}
            override suspend fun onClosed() { closed.countDown() }
        })
        // 发一个不会被响应的命令，然后服务器断开
        val sendJob = scope.launch {
            runCatching { session.send("Test.never", JsonObject(emptyMap()), null, 5_000) }
        }
        kotlinx.coroutines.delay(200) // 等命令真正发出
        serverWs.close(1000, "server going away")
        assertTrue("onClosed should fire", closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // pending 命令应异常完成（而非 5s 超时）：等 sendJob 结束应远快于超时
        withTimeout(3_000) { sendJob.join() }
        assertTrue(sendJob.isCompleted)
    }

    @Test
    fun `timeout throws CdpCommandException`() = runBlocking {
        val session = openSession(EchoWsListener(onRequest = { null }))
        session.start(object : CdpSession.EventListener {
            override suspend fun onEvent(method: String, params: kotlinx.serialization.json.JsonObject, sessionId: String?) {}
            override suspend fun onClosed() {}
        })
        try {
            session.send("Test.slow", JsonObject(emptyMap()), null, 300)
            throw AssertionError("expected timeout")
        } catch (e: CdpCommandException) {
            assertEquals(-2, e.code)
            assertTrue(e.message!!.contains("timeout"))
        }
        session.close()
    }
}
