package top.wanxiang.app.harness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTextToolCallCodecTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `extract parses single valid marker`() {
        val text = """前言 [[tool_call]]{"name":"read","arguments":{"path":"/a.txt"}}[[/tool_call]] 后记"""
        val calls = TextToolCallCodec.extract(json, text)
        assertEquals(1, calls.size)
        assertEquals("read", calls[0].name)
        assertEquals("""{"path":"/a.txt"}""", calls[0].argumentsJson)
        assertTrue(calls[0].id.startsWith("json-"))
    }

    @Test
    fun `extract keeps order and skips invalid payloads`() {
        val text = """
            [[tool_call]]{"name":"edit","arguments":{}}[[/tool_call]]
            这段是普通文本 not a marker
            [[tool_call]]{"broken"[[/tool_call]]
            [[tool_call]]{"name":"","arguments":{}}[[/tool_call]]
            [[tool_call]]{"name":"base","arguments":{"command":"ls"}}[[/tool_call]]
        """.trimIndent()
        val calls = TextToolCallCodec.extract(json, text)
        assertEquals(listOf("edit", "base"), calls.map { it.name })
    }

    @Test
    fun `missing arguments json defaults to empty object`() {
        val calls = TextToolCallCodec.extract(json, """[[tool_call]]{"name":"write"}[[/tool_call]]""")
        assertEquals(1, calls.size)
        assertEquals("{}", calls[0].argumentsJson)
    }

    @Test
    fun `blank text yields no calls`() {
        assertTrue(TextToolCallCodec.extract(json, "").isEmpty())
        assertTrue(TextToolCallCodec.extract(json, "   ").isEmpty())
    }

    @Test
    fun `stripMarkers removes protocol markers only`() {
        val stripped = TextToolCallCodec.stripMarkers(
            """
            你好[[tool_call]]{"name":"read","arguments":{}}[[/tool_call]]
            第二段内容
            """.trimIndent(),
        )
        assertEquals("你好\n第二段内容", stripped)
    }

    @Test
    fun `normalizer parses arbitrary namespaced json marker without model knowledge`() {
        val normalized = TextToolCallCodec.normalize(
            json,
            """准备读取<provider_v2_tool_call>{"name":"read","arguments":{"path":"C176k.java"}}</provider_v2_tool_call>完成""",
        )

        assertEquals(1, normalized.calls.size)
        assertEquals("read", normalized.calls.single().name)
        assertEquals("""{"path":"C176k.java"}""", normalized.calls.single().argumentsJson)
        assertEquals("准备读取完成", normalized.displayText)
        assertFalse(normalized.hasUnresolvedMarkers)
    }

    @Test
    fun `normalizer parses arbitrary namespaced key value marker`() {
        val normalized = TextToolCallCodec.normalize(
            json,
            """<model_x_tool_call>read<model_x_argkey>path<model_x_arg_value> C176k.java</model_x_tool_call>""",
        )

        assertEquals(1, normalized.calls.size)
        assertEquals("read", normalized.calls.single().name)
        assertEquals("""{"path":"C176k.java"}""", normalized.calls.single().argumentsJson)
        assertEquals("", normalized.displayText)
    }

    @Test
    fun `normalizer supports multiple typed arguments and unclosed final marker`() {
        val normalized = TextToolCallCodec.normalize(
            json,
            """<router_tool_call>base<router_arg_key>command<router_arg_value>pwd<router_arg_key>timeout_seconds<router_arg_value>15""",
        )

        assertEquals(1, normalized.calls.size)
        assertEquals("base", normalized.calls.single().name)
        assertEquals("""{"command":"pwd","timeout_seconds":15}""", normalized.calls.single().argumentsJson)
        assertEquals(1, normalized.markerCount)
    }

    @Test
    fun `normalizer reports malformed marker instead of treating it as an answer`() {
        val normalized = TextToolCallCodec.normalize(
            json,
            """<gateway_tool_call>read<gateway_argkey>path""",
        )

        assertTrue(normalized.calls.isEmpty())
        assertTrue(normalized.hasUnresolvedMarkers)
        assertEquals("", normalized.displayText)
    }

    @Test
    fun `normalizer leaves unrelated xml untouched`() {
        val text = "请保留 <note>普通内容</note>"
        val normalized = TextToolCallCodec.normalize(json, text)

        assertTrue(normalized.calls.isEmpty())
        assertFalse(normalized.hasMarkers)
        assertEquals(text, normalized.displayText)
    }

    @Test
    fun `structured calls take priority over textual fallback`() {
        val structured = listOf(ApiToolCallSpec("native-1", "write", "{}"))
        val textual = TextToolCallCodec.normalize(
            json,
            """<any_tool_call>{"name":"read","arguments":{"path":"a.kt"}}</any_tool_call>""",
        )

        assertEquals(structured, TextToolCallCodec.resolveCalls(structured, textual))
    }

    @Test
    fun `three concurrent responses normalize independently`() = runBlocking {
        val responses = listOf(
            """[[tool_call]]{"name":"read","arguments":{"path":"first.kt"}}[[/tool_call]]""",
            """<alpha_tool_call>{"name":"read","arguments":{"path":"second.kt"}}</alpha_tool_call>""",
            """<beta_tool_call>read<beta_argkey>path<beta_arg_value>third.kt</beta_tool_call>""",
        )

        val calls = responses.map { response ->
            async(Dispatchers.Default) { TextToolCallCodec.normalize(json, response).calls.single() }
        }.awaitAll()

        assertEquals(listOf("read", "read", "read"), calls.map { it.name })
        assertEquals(
            listOf("first.kt", "second.kt", "third.kt"),
            calls.map { Json.parseToJsonElement(it.argumentsJson).jsonObject["path"]?.jsonPrimitive?.content },
        )
    }
}
