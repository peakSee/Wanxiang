package top.wanxiang.app.harness.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchPreviewTextTest {
    @Test
    fun `generated image data url becomes bounded image label`() {
        val payload = "A".repeat(500_000)
        val preview = branchPreviewText("生成完成：![星空](data:image/png;base64,$payload) 希望你喜欢")

        assertEquals("生成完成： [图片] 希望你喜欢", preview)
        assertFalse(preview.contains(payload.take(32)))
        assertTrue(preview.length <= 240)
    }

    @Test
    fun `ordinary branch preview is normalized and bounded`() {
        val preview = branchPreviewText("  第一行\n\n第二行  " + "x".repeat(500))

        assertTrue(preview.startsWith("第一行 第二行"))
        // 实现契约：先截断到 240 再做空白归一化/trim，因此结果 <= 240 而非精确等于
        assertTrue(preview.length <= 240)
        assertFalse(preview.contains("  "))
    }
}
