package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 [ToolCallIdNormalizer] 的 4 个关键场景：
 * - 空白 → 生成合法非重复 call_xxx
 * - 已存在的合法 id → 原样保留（保 provider 兼容）
 * - 混排非法字符 → sanitize 或 fresh
 * - 超长 → 保留（保守版不截断，DB 无长度约束）
 */
class ToolCallIdNormalizerTest {
    @Test
    fun `blank or whitespace input gets a valid fresh call id`() {
        val a = ToolCallIdNormalizer.normalize(null)
        val b = ToolCallIdNormalizer.normalize("")
        val c = ToolCallIdNormalizer.normalize("   ")
        for (id in listOf(a, b, c)) {
            assertTrue("应以 call_ 开头: $id", id.startsWith("call_"))
            assertTrue("长度合理", id.length >= 12)
        }
        // 三个不同空白 → 三次都要 unique
        assertNotEquals(a, b)
        assertNotEquals(b, c)
    }

    @Test
    fun `valid provider id is preserved exactly to keep provider compat`() {
        // Anthropic / Gemini / OpenAI 都可能对 tool_use_id 严格匹配，不能篡改
        val valid = "toolu_01A09q90qw90lq917835lq9"
        val normalized = ToolCallIdNormalizer.normalize(valid)
        assertEquals(valid, normalized)
    }

    @Test
    fun `hostile chars are sanitized, all-illegal falls back to fresh`() {
        val withBad = "abc/../def"
        val cleaned = ToolCallIdNormalizer.normalize(withBad)
        assertTrue("只保留字母数字_-", cleaned.all { it.isLetterOrDigit() || it == '_' || it == '-' })
        assertTrue("保留合法字母部分", cleaned.contains("abc"))

        val allBad = "///***"
        val fallback = ToolCallIdNormalizer.normalize(allBad)
        assertTrue("全非法字符 走 生成 路径", fallback.startsWith("call_"))
    }

    @Test
    fun `long id is kept as-is`() {
        val long = "a".repeat(200)
        assertEquals(long, ToolCallIdNormalizer.normalize(long))
    }

    @Test
    fun `needsNormalize only true for blank or all-illegal`() {
        assertTrue(ToolCallIdNormalizer.needsNormalize(null))
        assertTrue(ToolCallIdNormalizer.needsNormalize(""))
        assertTrue(ToolCallIdNormalizer.needsNormalize("  \t\n "))
        assertTrue(ToolCallIdNormalizer.needsNormalize("///***"))
        org.junit.Assert.assertFalse(ToolCallIdNormalizer.needsNormalize("call_abc"))
        org.junit.Assert.assertFalse(ToolCallIdNormalizer.needsNormalize("toolu_01XYZ"))
    }
}
