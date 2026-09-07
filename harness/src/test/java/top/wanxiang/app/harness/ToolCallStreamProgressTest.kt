package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolCallStreamProgressTest {

    @Test
    fun `counts newlines in an incomplete content string`() {
        val partial = """{"content":"first\nsecond\nthird"""

        assertEquals(3, countPartialJsonStringLines(partial, "content"))
    }

    @Test
    fun `does not count an escaped backslash followed by n`() {
        val partial = """{"content":"literal\\ntext"}"""

        assertEquals(1, countPartialJsonStringLines(partial, "content"))
    }

    @Test
    fun `counts edit fields independently while json is streaming`() {
        val partial = """{"path":"a.kt","oldText":"a\nb","newText":"a\nb\nc"""

        assertEquals(2, countPartialJsonStringLines(partial, "oldText"))
        assertEquals(3, countPartialJsonStringLines(partial, "newText"))
    }

    @Test
    fun `returns null before field value starts`() {
        assertNull(countPartialJsonStringLines("""{"path":"a.kt","content""", "content"))
    }
}
