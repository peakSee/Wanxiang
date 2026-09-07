package top.wanxiang.app.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ResponsesApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ResponsesApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ResponsesApi(OkHttpClient(), Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun model(): ModelConfig = ModelConfig(
        name = "测试",
        provider = "OpenAI",
        model = "gpt-4o-mini",
        baseUrl = server.url("/v1").toString().removeSuffix("/"),
        apiKey = "sk-test-key",
        responseApiEnabled = true,
    )

    @Test
    fun `posts to responses endpoint with bearer auth and input`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"object":"response","output":[]}"""))
        api.chat(model(), listOf(ApiMessage(role = "user", content = "hi")))
        val recorded = server.takeRequest()
        assertEquals("/v1/responses", recorded.path)
        assertEquals("Bearer sk-test-key", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(body.contains("\"input\""))
        assertTrue(body.contains("\"type\":\"input_text\""))
    }

    @Test
    fun `maps system to instructions and max_tokens to max_output_tokens`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"object":"response","output":[]}"""))
        api.chat(
            model().copy(maxTokens = 512),
            listOf(
                ApiMessage(role = "system", content = "你是助手"),
                ApiMessage(role = "user", content = "你好"),
            ),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"instructions\":\"你是助手\""))
        assertTrue(body.contains("\"max_output_tokens\":512"))
        assertFalse(body.contains("\"max_tokens\""))
    }

    @Test
    fun `maps tool history to function_call and function_call_output items`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"object":"response","output":[]}"""))
        api.chat(
            model(),
            listOf(
                ApiMessage(
                    role = "assistant",
                    content = "让我查一下",
                    tool_calls = listOf(
                        ApiToolCall(
                            id = "call_1",
                            function = ApiFunctionCall(name = "read", arguments = """{"path":"a.txt"}"""),
                        ),
                    ),
                ),
                ApiMessage(role = "tool", content = "file content", tool_call_id = "call_1"),
                ApiMessage(role = "user", content = "继续"),
            ),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"type\":\"function_call\""))
        assertTrue(body.contains("\"call_id\":\"call_1\""))
        assertTrue(body.contains("\"type\":\"function_call_output\""))
        assertTrue(body.contains("\"output\":\"file content\""))
        assertTrue(body.contains("\"type\":\"output_text\""))
    }

    @Test
    fun `injects flat tools schema when native tool call mode`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"object":"response","output":[]}"""))
        api.chat(model(), emptyList())
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"tools\""))
        assertTrue(body.contains("\"type\":\"function\""))
        assertTrue(body.contains("\"name\":\"read\""))
        assertTrue(body.contains("\"parameters\""))
        assertTrue(body.contains("\"tool_choice\":\"auto\""))
    }

    @Test
    fun `parses text tool calls and usage from non-stream response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "object": "response",
                  "output": [
                    {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "好的，我来执行。"}]},
                    {"type": "function_call", "id": "fc_1", "call_id": "call_9", "name": "base", "arguments": "{\"command\":\"uname -m\"}"}
                  ],
                  "usage": {
                    "input_tokens": 120,
                    "input_tokens_details": {"cached_tokens": 80},
                    "output_tokens": 45,
                    "output_tokens_details": {"reasoning_tokens": 12},
                    "total_tokens": 165
                  }
                }
                """.trimIndent(),
            ),
        )
        val result = api.chat(model(), emptyList())
        assertEquals("好的，我来执行。", result.content)
        assertTrue(result.hasToolCalls)
        val call = result.toolCalls.single()
        assertEquals("call_9", call.id)
        assertEquals("base", call.name)
        assertEquals("""{"command":"uname -m"}""", call.argumentsJson)
        assertEquals(120L, result.usage.inputTokens)
        assertEquals(45L, result.usage.outputTokens)
        assertEquals(12L, result.usage.reasoningTokens)
        assertEquals(80L, result.usage.cacheReadTokens)
        assertTrue(result.usage.hasData)
    }

    @Test
    fun `parses reasoning summary from non-stream response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "object": "response",
                  "output": [
                    {"type": "reasoning", "summary": [{"type": "summary_text", "text": "思考过程"}]},
                    {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "结论"}]}
                  ]
                }
                """.trimIndent(),
            ),
        )
        val result = api.chat(model(), emptyList())
        assertEquals("结论", result.content)
        assertEquals("思考过程", result.reasoningContent)
    }

    @Test
    fun `streams text and reasoning deltas`() = runBlocking {
        val body = """
            data: {"type":"response.created","response":{"id":"resp_1"}}
            data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"delta":"你"}
            data: {"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"delta":"在思考"}
            data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"delta":"好。"}
            data: {"type":"response.completed","response":{"id":"resp_1","usage":{"input_tokens":31,"output_tokens":7,"output_tokens_details":{"reasoning_tokens":3}}}}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(body))
        val deltas = mutableListOf<String>()
        val reasoning = mutableListOf<String>()
        val result = api.chatStream(model(), emptyList(), onReasoning = { reasoning += it }) { deltas += it }
        assertEquals(listOf("你", "好。"), deltas)
        assertEquals(listOf("在思考"), reasoning)
        assertEquals("你好。", result.content)
        assertEquals("在思考", result.reasoningContent)
        assertEquals(31L, result.usage.inputTokens)
        assertEquals(7L, result.usage.outputTokens)
        assertEquals(3L, result.usage.reasoningTokens)
    }

    @Test
    fun `streams function call arguments and usage`() = runBlocking {
        val body = """
            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"fc_1","call_id":"call_2","type":"function_call","name":"base","arguments":"","status":"in_progress"}}
            data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"{\"command\":\""}
            data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"uname -m\"}"}
            data: {"type":"response.completed","response":{"id":"resp_1","usage":{"input_tokens":10,"output_tokens":5}}}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(body))
        val result = api.chatStream(model(), emptyList()) { }
        assertTrue(result.hasToolCalls)
        val call = result.toolCalls.single()
        assertEquals("call_2", call.id)
        assertEquals("base", call.name)
        assertEquals("""{"command":"uname -m"}""", call.argumentsJson)
        assertEquals(10L, result.usage.inputTokens)
    }

    @Test
    fun `backfills tool arguments from output_item_done when deltas absent`() = runBlocking {
        val body = """
            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"fc_1","call_id":"call_5","type":"function_call","name":"process","arguments":"","status":"in_progress"}}
            data: {"type":"response.output_item.done","output_index":0,"item":{"id":"fc_1","call_id":"call_5","type":"function_call","name":"process","arguments":"{\"action\":\"list\"}","status":"completed"}}
            data: {"type":"response.completed","response":{"id":"resp_1"}}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(body))
        val result = api.chatStream(model(), emptyList()) { }
        assertTrue(result.hasToolCalls)
        val call = result.toolCalls.single()
        assertEquals("call_5", call.id)
        assertEquals("process", call.name)
        assertEquals("""{"action":"list"}""", call.argumentsJson)
    }

    @Test
    fun `http error throws with code`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val thrown = runCatching { runBlocking { api.chat(model(), emptyList()) } }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertTrue(thrown!!.message!!.contains("401"))
    }

    @Test
    fun `response failed event throws with message`() = runBlocking {
        val body = """
            data: {"type":"response.failed","response":{"id":"resp_1","status":"failed","error":{"code":"server_error","message":"模型内部错误"}}}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(body))
        var thrown: Throwable? = null
        try {
            api.chatStream(model(), emptyList()) { }
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue(thrown is IllegalStateException)
        assertTrue(thrown!!.message!!.contains("模型内部错误"))
    }

    @Test
    fun `uses reasoning effort for responses when enabled`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"object":"response","output":[]}"""))
        api.chat(
            model().copy(reasoningMode = ReasoningMode.ENABLED, reasoningEffort = ReasoningEffort.HIGH),
            emptyList(),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reasoning\":{\"effort\":\"high\"}"))
    }

    @Test
    fun `does not inject reasoning effort when auto or disabled`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"object":"response","output":[]}"""))
        api.chat(model().copy(reasoningMode = ReasoningMode.DISABLED), emptyList())
        assertFalse(server.takeRequest().body.readUtf8().contains("\"reasoning\""))
    }
}
