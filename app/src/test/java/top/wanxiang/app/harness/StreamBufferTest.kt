package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamBufferTest {

    @Test
    fun `first append publishes immediately`() {
        val buffer = StreamBuffer()
        buffer.append("hello")
        assertEquals("hello", buffer.publishIfDue(nowMs = 1_000L, intervalMs = 100L))
    }

    @Test
    fun `publishes within interval is suppressed`() {
        val buffer = StreamBuffer()
        buffer.append("a")
        assertEquals("a", buffer.publishIfDue(1_000L, 100L))
        buffer.append("b")
        assertNull(buffer.publishIfDue(1_050L, 100L))
        assertEquals("ab", buffer.publishIfDue(1_101L, 100L))
    }

    @Test
    fun `no repeat publish when content unchanged`() {
        val buffer = StreamBuffer()
        buffer.append("x")
        assertEquals("x", buffer.publishIfDue(1_000L, 100L))
        assertNull(buffer.publishIfDue(2_000L, 100L))
    }

    @Test
    fun `clear resets published length so next append publishes`() {
        val buffer = StreamBuffer()
        buffer.append("old")
        buffer.publishIfDue(1_000L, 100L)
        buffer.clear()
        buffer.append("new")
        assertEquals("new", buffer.publishIfDue(1_050L, 100L))
    }

    @Test
    fun `append is capped at maxChars`() {
        val buffer = StreamBuffer(maxChars = 10)
        buffer.append("12345")
        buffer.append("67890")
        buffer.append("EXTRA")
        assertEquals(10, buffer.length)
        assertEquals("1234567890", buffer.toString())
        // 已达上限后内容不再变化，不应重复发布
        assertEquals("1234567890", buffer.publishIfDue(1_000L, 100L))
        assertNull(buffer.publishIfDue(2_000L, 100L))
    }

    @Test
    fun `oversized single chunk is truncated to fit`() {
        val buffer = StreamBuffer(maxChars = 4)
        buffer.append("abcdef")
        assertEquals("abcd", buffer.toString())
    }
}
