package top.wanxiang.app.runtime.gui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiPrimitiveTest {
    @Test
    fun `gui key parses aliases and codes`() {
        assertEquals(GuiKey.BACK, GuiKey.parse("back"))
        assertEquals(GuiKey.PASTE, GuiKey.parse("paste"))
        assertEquals(GuiKey.RECENTS, GuiKey.parse("app_switch"))
        assertEquals(GuiKey.BACK, GuiKey.parse("4"))
        assertEquals(null, GuiKey.parse("nope"))
    }

    @Test
    fun `scroll direction maps to swipe across center`() {
        val swipe = ScrollDirection.UP.toSwipe(1080, 2400, 0.4f, 300L, null, null)
        assertTrue(swipe.y1 > swipe.y2)
        assertEquals(540, swipe.x1)
        assertNotNull(ScrollDirection.LEFT.toSwipe(1080, 2400, 0.5f, 200L, 100, 200))
    }
}
