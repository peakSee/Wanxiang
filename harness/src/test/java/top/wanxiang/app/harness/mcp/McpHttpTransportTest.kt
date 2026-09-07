package top.wanxiang.app.harness.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.core.model.McpServerConfig
import top.wanxiang.app.core.model.McpTransportType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpHttpTransportTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `tools call transport failure is not retried`() = runBlocking {
        val toolsCalls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                return when (payload.getValue("method").jsonPrimitive.content) {
                    "initialize" -> MockResponse().setBody(
                        """{"jsonrpc":"2.0","id":"${payload.getValue("id").jsonPrimitive.content}","result":{"protocolVersion":"$MCP_PROTOCOL_VERSION"}}""",
                    )
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/call" -> {
                        toolsCalls.incrementAndGet()
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transport = McpHttpTransport(
            OkHttpClient(),
            Json { ignoreUnknownKeys = true },
            AppLogger(context) { it },
        )
        val config = McpServerConfig(
            id = "side-effecting",
            name = "side-effecting",
            transportType = McpTransportType.SSE,
            serverUrl = server.url("/mcp").toString(),
        )

        val failure = runCatching {
            transport.execute(config, "write_record", buildJsonObject {})
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("未自动重试"))
        assertEquals(1, toolsCalls.get())
    }
}
