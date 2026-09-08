package top.wanxiang.app.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.runtime.terminal.AnsiTerminalBuffer

class AnsiTerminalBufferTest {
    @Test
    fun preservesSgrStyleAcrossChunkBoundaries() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        buffer.append("hello\u001B[3")
        val screen = buffer.append("1mred\u001B[0m!")

        val line = screen.first()
        assertEquals("hellored!", line.cells.joinToString("") { it.character })
        assertEquals(0xFFCC0000L, line.cells[5].foreground)
        assertEquals(null, line.cells.last().foreground)
    }

    @Test
    fun handlesCursorMovementAndEraseLine() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        buffer.append("hello")
        val screen = buffer.append("\u001B[1G\u001B[Kx")

        assertEquals("x", screen.first().cells.joinToString("") { it.character })
    }

    @Test
    fun scrollbackIsBounded() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 3)
        val screen = buffer.append("one\ntwo\nthree\nfour")

        assertTrue(screen.size <= 3)
        assertEquals("four", screen.last().cells.joinToString("") { it.character })
    }

    @Test
    fun preservesTrailingSpacesBeforeCursor() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        val screen = buffer.append("echo  ")

        assertEquals("echo  ", screen.first().cells.joinToString("") { it.character })
        assertEquals(6, buffer.cursor().column)
    }

    @Test
    fun keepsEmojiSurrogatePairAsOneRenderableCell() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        val emoji = "🙂"

        val first = emoji.substring(0, 1)
        val second = emoji.substring(1)
        buffer.append("ok $first")
        val screen = buffer.append(second)

        assertEquals("ok $emoji", screen.first().cells.joinToString("") { it.character })
        assertEquals(4, screen.first().cells.count { it.character.isNotEmpty() })
        assertEquals(5, buffer.cursor().column)
    }

    @Test
    fun supportsBackground256ColorAndStyles() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        val line = buffer.append("\u001B[1;3;4;48;5;21;38;2;1;2;3mX").first()
        val cell = line.cells.first()
        assertEquals(0xFF010203L, cell.foreground)
        assertEquals(true, cell.bold)
        assertEquals(true, cell.italic)
        assertEquals(true, cell.underline)
        assertEquals(0xFF0000FFL, cell.background)
    }

    @Test
    fun wideCharactersOccupyTwoColumnsAndCombiningMarksAttach() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        val line = buffer.append("中e\u0301").first()
        assertEquals("中", line.cells.first().character)
        assertEquals(2, line.cells.first().width)
        assertEquals("e\u0301", line.cells[2].character)
        assertEquals(3, buffer.cursor().column)
    }

    @Test
    fun bracketedPasteModeIsTracked() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        buffer.append("\u001B[?2004h")
        assertEquals(true, buffer.isBracketedPasteEnabled())
        buffer.append("\u001B[?2004l")
        assertEquals(false, buffer.isBracketedPasteEnabled())
    }

    @Test
    fun wideCharactersOverwritingExistingSpacesDoNotLeaveSpaces() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        // 模拟 readline/bash：先清行，再输入中文字符
        buffer.append("abcdefgh")
        val screen = buffer.append("\u001B[1G\u001B[K你好万象")

        val line = screen.first()
        assertEquals("你好万象", line.cells.joinToString("") { it.character })
        assertEquals(8, buffer.cursor().column)
        assertEquals("你", line.cells[0].character)
        assertEquals("", line.cells[1].character)
        assertEquals("好", line.cells[2].character)
        assertEquals("", line.cells[3].character)
        assertEquals("万", line.cells[4].character)
        assertEquals("", line.cells[5].character)
        assertEquals("象", line.cells[6].character)
        assertEquals("", line.cells[7].character)
    }
}
