package top.wkbin.taixu.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallIdNormalizerTest {

    @Test
    fun `null or blank rawId generates valid call_ prefix with unique suffix`() {
        val id1 = ToolCallIdNormalizer.normalize(null)
        val id2 = ToolCallIdNormalizer.normalize("")
        val id3 = ToolCallIdNormalizer.normalize("   ")

        assertTrue("id1 must start with call_: $id1", id1.startsWith("call_"))
        assertTrue("id2 must start with call_: $id2", id2.startsWith("call_"))
        assertTrue("id3 must start with call_: $id3", id3.startsWith("call_"))

        assertNotEquals("id1 and id2 must be unique", id1, id2)
        assertNotEquals("id2 and id3 must be unique", id2, id3)
    }

    @Test
    fun `fixed rawId preserves base prefix and appends unique suffix`() {
        val fixedId = "call_0"
        val norm1 = ToolCallIdNormalizer.normalize(fixedId)
        val norm2 = ToolCallIdNormalizer.normalize(fixedId)

        assertTrue("norm1 should retain base prefix: $norm1", norm1.startsWith("call_0_"))
        assertTrue("norm2 should retain base prefix: $norm2", norm2.startsWith("call_0_"))
        assertNotEquals("Successive normalizations of the same fixed ID must be globally unique", norm1, norm2)
    }

    @Test
    fun `sanitizes illegal protocol characters while preserving valid ones`() {
        val hostile = "call:test/special@name#1!"
        val normalized = ToolCallIdNormalizer.normalize(hostile)

        assertTrue("Should clean illegal chars: $normalized", normalized.startsWith("calltestspecialname1_"))
        // Valid OpenAI/Anthropic ID pattern: only alphanumeric, underscore, and dash
        assertTrue("Must only contain protocol-safe characters", normalized.matches(Regex("^[a-zA-Z0-9_-]+$")))
    }

    @Test
    fun `long rawId is capped to reasonable length before appending suffix`() {
        val veryLong = "a".repeat(100)
        val normalized = ToolCallIdNormalizer.normalize(veryLong)

        assertTrue("Base should be capped so total length remains reasonable", normalized.length <= 45)
    }
}
