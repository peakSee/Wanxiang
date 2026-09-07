package top.wanxiang.app.harness

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the protocol-normalization boundary shared by ProviderClient and TurnRunner.
 * Locks down the regressions most likely to slip through review:
 *  1. structured native tool calls taking precedence over a textual fallback (no duplicate execution),
 *  2. unresolved textual markers failing closed instead of being silently reported as success.
 */
class ProviderResponseNormalizerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val normalizer = ProviderResponseNormalizer(json)

    @Test
    fun `native tool call wins over identical textual fallback`() {
        val structured = listOf(ApiToolCallSpec("native-1", "read", "{\"path\":\"native.kt\"}"))
        val textualRaw = "<any_tool_call>{\"name\":\"read\",\"arguments\":{\"path\":\"textual.kt\"}}</any_tool_call>"
        val result = ChatResult(content = textualRaw, toolCalls = structured, reasoningContent = null)
        val normalized = normalizer.normalize(result, textualRaw, toolsEnabled = true)
        assertEquals(structured, normalized.toolCalls)
        assertFalse("Native call must suppress textual markers", normalized.hasUnresolvedMarkers)
        assertEquals(0, normalized.textToolCallCount)
    }

    @Test
    fun `pure chat disables textual tool decoding but preserves display text`() {
        val raw = "[[tool_call]]{\"name\":\"read\",\"arguments\":{}}[[/tool_call]]"
        val normalized = normalizer.normalize(ChatResult(content = raw, toolCalls = emptyList()), raw, toolsEnabled = false)
        assertEquals(raw, normalized.displayText)
        assertTrue(normalized.toolCalls.isEmpty())
        assertFalse(normalized.hasUnresolvedMarkers)
    }

    @Test
    fun `textual protocol yields calls when no native calls present`() {
        val raw = "<provider_tool_call>{\"name\":\"read\",\"arguments\":{\"path\":\"a.kt\"}}</provider_tool_call>"
        val normalized = normalizer.normalize(ChatResult(content = raw, toolCalls = emptyList()), raw, toolsEnabled = true)
        assertEquals(1, normalized.toolCalls.size)
        assertEquals("read", normalized.toolCalls.single().name)
        assertEquals("{\"path\":\"a.kt\"}", normalized.toolCalls.single().argumentsJson)
    }

    @Test
    fun `malformed marker is reported so the runner can fail closed`() {
        val raw = "<gateway_tool_call>read<gateway_argkey>path"
        val normalized = normalizer.normalize(ChatResult(content = raw, toolCalls = emptyList()), raw, toolsEnabled = true)
        assertTrue(normalized.toolCalls.isEmpty())
        assertTrue(normalized.hasUnresolvedMarkers)
    }

    @Test
    fun `never executes tool call mentioned only in reasoning_content`() {
        val reasoning = "I should read the file first: [[tool_call]]{\"name\":\"read\",\"arguments\":{\"path\":\"foo.txt\"}}[[/tool_call]]"
        val finalText = "I considered reading the file, but decided no tool should run."
        val result = ChatResult(content = finalText, toolCalls = emptyList(), reasoningContent = reasoning)
        val normalized = normalizer.normalize(result, rawText = finalText, toolsEnabled = true)
        assertTrue(normalized.toolCalls.isEmpty())
        assertEquals(finalText, normalized.displayText)
        assertFalse(normalized.hasUnresolvedMarkers)
    }

    @Test
    fun `structured tool calls remain authoritative when reasoning contains markers`() {
        val structured = listOf(ApiToolCallSpec("call-1", "edit", "{\"path\":\"a.kt\"}"))
        val reasoning = "Deliberating: [[tool_call]]{\"name\":\"read\",\"arguments\":{\"path\":\"b.kt\"}}[[/tool_call]]"
        val result = ChatResult(content = "", toolCalls = structured, reasoningContent = reasoning)
        val normalized = normalizer.normalize(result, rawText = "", toolsEnabled = true)
        assertEquals(structured, normalized.toolCalls)
    }
}
