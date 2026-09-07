package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionExtractorTest {

    @Test
    fun `parses lowercased mention names`() {
        val names = MentionExtractor.parse("@Builder 帮我看看 @CodeR 的实现")
        assertEquals(setOf("builder", "coder"), names)
    }

    @Test
    fun `stops at chinese punctuation and colon boundaries`() {
        val names = MentionExtractor.parse("联系 @Skill，或 @x:y 再问 @trigger_cmd")
        assertEquals(setOf("skill", "x", "trigger_cmd"), names)
    }

    @Test
    fun `email local part matches as mention per protocol`() {
        // 协议即如此：@ 后的连续非空白片段都会成为候选名，由上层匹配具体技能/MCP ID。
        assertEquals(setOf("host.com"), MentionExtractor.parse("邮箱 user@host.com"))
    }

    @Test
    fun `text without at returns empty`() {
        assertTrue(MentionExtractor.parse("没有提及的普通消息").isEmpty())
    }
}
