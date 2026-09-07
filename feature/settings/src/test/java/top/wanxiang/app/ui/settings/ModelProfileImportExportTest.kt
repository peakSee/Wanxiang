package top.wanxiang.app.ui.settings

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.model.AiModelProfileBundle
import top.wanxiang.app.core.model.AiModelProfileExport

class ModelProfileImportExportTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun testSerializeAndDeserializeSingleProfile() {
        val original = AiModelProfileExport(
            id = "test-model-1",
            name = "DeepSeek-V3",
            provider = "DeepSeek",
            model = "deepseek-chat, deepseek-reasoner",
            baseUrl = "https://api.deepseek.com/v1",
            apiKeys = listOf("sk-key-1", "sk-key-2"),
            apiKey = "sk-key-1",
            requestsPerMinutePerKey = 10,
            temperature = 0.7f,
            maxTokens = 8000,
            topP = 0.95f,
            reasoningMode = "enabled",
            toolCallMode = "native",
            contextTokens = 64000,
            customHeaders = "X-Test: value",
            pureChatMode = false,
            visionEnabled = true,
            responseApiEnabled = false,
        )

        val jsonString = json.encodeToString(original)
        assertTrue(jsonString.contains("DeepSeek-V3"))
        assertTrue(jsonString.contains("sk-key-1"))

        val decoded = json.decodeFromString<AiModelProfileExport>(jsonString)
        assertEquals("DeepSeek-V3", decoded.name)
        assertEquals("DeepSeek", decoded.provider)
        assertEquals("deepseek-chat, deepseek-reasoner", decoded.model)
        assertEquals(2, decoded.apiKeys.size)
        assertEquals(0.7f, decoded.temperature)
        assertEquals(8000, decoded.maxTokens)
        assertEquals("enabled", decoded.reasoningMode)
    }

    @Test
    fun testBundleSerialization() {
        val bundle = AiModelProfileBundle(
            schemaVersion = 1,
            exportedAt = 1700000000000L,
            source = "WanXiang",
            profiles = listOf(
                AiModelProfileExport(
                    name = "Claude 3.7",
                    provider = "Anthropic",
                    model = "claude-3-7-sonnet-20250219",
                    baseUrl = "https://api.anthropic.com/v1",
                ),
                AiModelProfileExport(
                    name = "GPT-4o",
                    provider = "OpenAI",
                    model = "gpt-4o",
                    baseUrl = "https://api.openai.com/v1",
                )
            ),
        )

        val jsonString = json.encodeToString(bundle)
        assertTrue(jsonString.contains("\"schemaVersion\": 1"))
        assertTrue(jsonString.contains("Claude 3.7"))
        assertTrue(jsonString.contains("GPT-4o"))

        val decoded = json.decodeFromString<AiModelProfileBundle>(jsonString)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(2, decoded.profiles.size)
        assertEquals("Claude 3.7", decoded.profiles[0].name)
        assertEquals("GPT-4o", decoded.profiles[1].name)
    }

    @Test
    fun testLenientParsingArrayOrSingle() {
        val arrayJson = """
            [
              {
                "name": "Custom Model",
                "provider": "Custom",
                "model": "my-custom-model",
                "baseUrl": "https://custom.api/v1"
              }
            ]
        """.trimIndent()

        val decodedList = json.decodeFromString<List<AiModelProfileExport>>(arrayJson)
        assertEquals(1, decodedList.size)
        assertEquals("Custom Model", decodedList[0].name)
        assertEquals("https://custom.api/v1", decodedList[0].baseUrl)
    }
}
