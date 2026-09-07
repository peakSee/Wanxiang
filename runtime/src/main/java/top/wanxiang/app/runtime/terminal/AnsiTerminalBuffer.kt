package top.wanxiang.app.runtime.terminal

data class TerminalCell(
    /** A complete Unicode code point (including supplementary-plane emoji). */
    val character: String,
    val foreground: Long? = null,
    val background: Long? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val strikeThrough: Boolean = false,
    /** Number of terminal columns occupied by this grapheme. */
    val width: Int = 1,
)

data class TerminalLine(val cells: List<TerminalCell>)

data class TerminalCursor(
    val row: Int,
    val column: Int,
    val visible: Boolean,
)

/**
 * Small stateful ANSI/VT100 renderer for CLI output.
 *
 * It intentionally covers the control sequences emitted by common AI CLIs:
 * SGR colors, cursor movement, erase line/screen, carriage return, wrapping,
 * scrollback and OSC title sequences. The parser keeps escape state between
 * chunks so UTF-8/ANSI sequences split across pipe reads remain valid.
 */
class AnsiTerminalBuffer(
    columns: Int = DEFAULT_COLUMNS,
    private val maxRows: Int = DEFAULT_MAX_ROWS,
) {
    private var columns: Int = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    private var cursorRow = 0
    private var cursorColumn = 0
    private var savedRow = 0
    private var savedColumn = 0
    private var foreground: Long? = null
    private var background: Long? = null
    private var bold = false
    private var dim = false
    private var italic = false
    private var underline = false
    private var inverse = false
    private var strikeThrough = false
    private var bracketedPaste = false
    private var state = ParserState.NORMAL
    private val control = StringBuilder()

    // 替代屏幕缓冲（alternate screen）：vim/less 等 TUI 通过 ESC[?1049h/l
    // 切换，退出时恢复原屏幕内容与光标。
    private val mainRows = ArrayList<MutableList<TerminalCell>>()
    private val altRows = ArrayList<MutableList<TerminalCell>>()
    private var activeRows: MutableList<MutableList<TerminalCell>> = mainRows
    private var mainCursorRow = 0
    private var mainCursorColumn = 0
    private var cursorVisible = true
    private var pendingHighSurrogate: Char? = null

    init {
        mainRows.add(mutableListOf())
        altRows.add(mutableListOf())
    }

    fun resize(newColumns: Int) = synchronized(this) {
        columns = newColumns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        cursorColumn = cursorColumn.coerceAtMost(columns)
    }

    fun append(text: String): List<TerminalLine> = synchronized(this) {
        text.forEach { character ->
            val high = pendingHighSurrogate
            if (high != null) {
                pendingHighSurrogate = null
                if (character.isLowSurrogate()) {
                    consumeText(String(charArrayOf(high, character)))
                    return@forEach
                }
                consume(high)
            }
            if (character.isHighSurrogate()) {
                pendingHighSurrogate = character
            } else {
                consume(character)
            }
        }
        snapshotLocked()
    }

    fun cursor(): TerminalCursor = synchronized(this) {
        TerminalCursor(
            row = cursorRow,
            column = cursorColumn,
            visible = cursorVisible,
        )
    }

    fun isBracketedPasteEnabled(): Boolean = synchronized(this) { bracketedPaste }

    fun snapshot(): List<TerminalLine> = synchronized(this) {
        snapshotLocked()
    }

    /** 内部快照，调用方必须已持有 this 锁。 */
    private fun snapshotLocked(): List<TerminalLine> = activeRows.mapIndexed { rowIndex, row ->
        val copy = ArrayList(row)          // 先拷贝一份，防止 row 在 map 过程中被写入
        val minimumEnd = if (rowIndex == cursorRow) cursorColumn.coerceAtMost(copy.size) else 0
        var end = copy.size
        while (end > minimumEnd && copy[end - 1].character == " ") end -= 1
        TerminalLine(copy.subList(0, end).toList())
    }

    private fun consumeText(text: String) {
        if (state == ParserState.NORMAL) {
            write(text)
        } else {
            text.forEach(::consume)
        }
    }

    private fun consume(character: Char) {
        when (state) {
            ParserState.NORMAL -> consumeNormal(character)
            ParserState.ESCAPE -> consumeEscape(character)
            ParserState.CSI -> consumeCsi(character)
            ParserState.OSC -> {
                when (character) {
                    '\u0007' -> state = ParserState.NORMAL
                    '\u001B' -> state = ParserState.OSC_ESCAPE
                }
            }
            ParserState.OSC_ESCAPE -> if (character == '\\') state = ParserState.NORMAL else state = ParserState.OSC
        }
    }

    private fun consumeNormal(character: Char) {
        when (character) {
            '\u001B' -> state = ParserState.ESCAPE
            '\n' -> lineFeed()
            '\r' -> cursorColumn = 0
            '\b' -> cursorColumn = (cursorColumn - 1).coerceAtLeast(0)
            '\t' -> cursorColumn = ((cursorColumn / TAB_SIZE) + 1) * TAB_SIZE
            '\u0007' -> Unit
            else -> if (!character.isISOControl()) write(character.toString())
        }
    }

    private fun consumeEscape(character: Char) {
        when (character) {
            '[' -> {
                control.clear()
                state = ParserState.CSI
            }
            ']' -> {
                control.clear()
                state = ParserState.OSC
            }
            '7' -> {
                savedRow = cursorRow
                savedColumn = cursorColumn
                state = ParserState.NORMAL
            }
            '8' -> {
                cursorRow = savedRow.coerceIn(0, activeRows.lastIndex)
                cursorColumn = savedColumn.coerceIn(0, columns)
                state = ParserState.NORMAL
            }
            'c' -> reset()
            else -> state = ParserState.NORMAL
        }
    }

    private fun consumeCsi(character: Char) {
        if (character in '@'..'~') {
            applyCsi(character, control.toString())
            control.clear()
            state = ParserState.NORMAL
        } else if (control.length < MAX_CONTROL_LENGTH) {
            control.append(character)
        }
    }

    private fun applyCsi(final: Char, raw: String) {
        val privateMode = raw.startsWith("?")
        val params = raw.removePrefix("?").split(';').map { it.toIntOrNull() ?: 0 }
        fun param(index: Int, default: Int = 1) = params.getOrNull(index)?.takeIf { it > 0 } ?: default
        when (final) {
            'A' -> cursorRow = (cursorRow - param(0)).coerceAtLeast(0)
            'B', 'e' -> cursorRow = (cursorRow + param(0)).coerceAtMost(activeRows.lastIndex)
            'C', 'a' -> cursorColumn = (cursorColumn + param(0)).coerceAtMost(columns)
            'D' -> cursorColumn = (cursorColumn - param(0)).coerceAtLeast(0)
            'G', '`' -> cursorColumn = (param(0) - 1).coerceIn(0, columns)
            'd' -> cursorRow = (param(0) - 1).coerceIn(0, activeRows.lastIndex)
            'H', 'f' -> {
                cursorRow = (param(0) - 1).coerceIn(0, activeRows.lastIndex)
                cursorColumn = (param(1) - 1).coerceIn(0, columns)
            }
            'J' -> eraseScreen(params.firstOrNull()?.coerceAtLeast(0) ?: 0)
            'K' -> eraseLine(params.firstOrNull()?.coerceAtLeast(0) ?: 0)
            'm' -> applySgr(params)
            's' -> {
                savedRow = cursorRow
                savedColumn = cursorColumn
            }
            'u' -> {
                cursorRow = savedRow.coerceIn(0, activeRows.lastIndex)
                cursorColumn = savedColumn.coerceIn(0, columns)
            }
            'h', 'l' -> if (privateMode) applyPrivateMode(final, params)
        }
    }

    private fun applyPrivateMode(final: Char, params: List<Int>) {
        val mode = params.firstOrNull() ?: 0
        when (mode) {
            1049, 1047, 47 -> if (final == 'h') enterAltScreen() else exitAltScreen()
            25 -> cursorVisible = final == 'h'
            2004 -> bracketedPaste = final == 'h'
            else -> Unit
        }
    }

    private fun enterAltScreen() {
        mainCursorRow = cursorRow
        mainCursorColumn = cursorColumn
        activeRows = altRows
        altRows.clear()
        altRows.add(mutableListOf())
        cursorRow = 0
        cursorColumn = 0
    }

    private fun exitAltScreen() {
        activeRows = mainRows
        cursorRow = mainCursorRow.coerceIn(0, mainRows.lastIndex)
        cursorColumn = mainCursorColumn.coerceIn(0, columns)
    }

    private fun applySgr(params: List<Int>) {
        val values = if (params.isEmpty()) listOf(0) else params
        var index = 0
        while (index < values.size) {
            when (val code = values[index]) {
                0 -> {
                    foreground = null
                    background = null
                    bold = false
                    dim = false
                    italic = false
                    underline = false
                    inverse = false
                    strikeThrough = false
                }
                1 -> bold = true
                2 -> dim = true
                3 -> italic = true
                4 -> underline = true
                7 -> inverse = true
                9 -> strikeThrough = true
                21, 24 -> underline = false
                22 -> { bold = false; dim = false }
                23 -> italic = false
                27 -> inverse = false
                29 -> strikeThrough = false
                in 30..37 -> foreground = ANSI_COLORS[code - 30]
                39 -> foreground = null
                in 90..97 -> foreground = ANSI_BRIGHT_COLORS[code - 90]
                in 40..47 -> background = ANSI_COLORS[code - 40]
                49 -> background = null
                in 100..107 -> background = ANSI_BRIGHT_COLORS[code - 100]
                38, 48 -> {
                    // xterm 256 色 / RGB。
                    if (index + 1 < values.size && values[index + 1] == 5) {
                        val paletteIndex = values.getOrNull(index + 2)
                        if (paletteIndex != null) {
                            if (code == 38) foreground = xtermColor(paletteIndex)
                            else background = xtermColor(paletteIndex)
                        }
                        index += 2
                    } else if (index + 4 < values.size && values[index + 1] == 2) {
                        val red = values.getOrNull(index + 2)
                        val green = values.getOrNull(index + 3)
                        val blue = values.getOrNull(index + 4)
                        if (red != null && green != null && blue != null) {
                            val color = 0xFF000000L or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
                            if (code == 38) foreground = color else background = color
                        }
                        index += 4
                    }
                }
            }
            index += 1
        }
    }

    private fun write(character: String) {
        val cellWidth = unicodeWidth(character)
        if (cursorColumn + cellWidth > columns) {
            lineFeed()
            cursorColumn = 0
        }
        val row = activeRows[cursorRow]
        while (row.size <= cursorColumn) row += TerminalCell(" ")
        if (cellWidth == 0 && cursorColumn > 0 && cursorColumn - 1 < row.size) {
            val previous = row[cursorColumn - 1]
            row[cursorColumn - 1] = previous.copy(character = previous.character + character)
            return
        }
        row[cursorColumn] = TerminalCell(
            character = character,
            foreground = foreground,
            background = background,
            bold = bold,
            dim = dim,
            italic = italic,
            underline = underline,
            inverse = inverse,
            strikeThrough = strikeThrough,
            width = cellWidth,
        )
        if (cellWidth == 2) {
            if (cursorColumn + 1 < row.size) {
                row[cursorColumn + 1] = TerminalCell("", width = 0)
            } else {
                while (row.size <= cursorColumn + 1) row += TerminalCell("", width = 0)
            }
        }
        cursorColumn += cellWidth
    }

    private fun lineFeed() {
        cursorColumn = 0
        cursorRow += 1
        if (cursorRow >= activeRows.size) activeRows.add(mutableListOf())
        if (activeRows.size > maxRows) {
            activeRows.removeAt(0)
            cursorRow -= 1
        }
    }

    private fun eraseLine(mode: Int) {
        val row = activeRows[cursorRow]
        when (mode) {
            2 -> for (index in row.indices) row[index] = TerminalCell(" ")
            1 -> for (index in 0..cursorColumn.coerceAtMost(row.lastIndex)) row[index] = TerminalCell(" ")
            else -> for (index in cursorColumn until row.size) row[index] = TerminalCell(" ")
        }
    }

    private fun eraseScreen(mode: Int) {
        when (mode) {
            2, 3 -> {
                activeRows.clear()
                activeRows.add(mutableListOf())
                cursorRow = 0
                cursorColumn = 0
            }
            1 -> {
                for (rowIndex in 0..cursorRow.coerceAtMost(activeRows.lastIndex)) {
                    val row = activeRows[rowIndex]
                    val end = if (rowIndex == cursorRow) cursorColumn else row.size
                    for (index in 0 until end.coerceAtMost(row.size)) row[index] = TerminalCell(" ")
                }
            }
            else -> {
                eraseLine(0)
                for (rowIndex in cursorRow + 1 until activeRows.size) activeRows[rowIndex].clear()
            }
        }
    }

    private fun reset() {
        mainRows.clear()
        mainRows.add(mutableListOf())
        altRows.clear()
        altRows.add(mutableListOf())
        activeRows = mainRows
        cursorRow = 0
        cursorColumn = 0
        savedRow = 0
        savedColumn = 0
        mainCursorRow = 0
        mainCursorColumn = 0
        cursorVisible = true
        pendingHighSurrogate = null
        foreground = null
        background = null
        bold = false
        dim = false
        italic = false
        underline = false
        inverse = false
        strikeThrough = false
        bracketedPaste = false
        state = ParserState.NORMAL
    }

    /** xterm 256 色板：16 基础色 + 6×6×6 立方体 + 24 级灰度。 */
    private fun xtermColor(index: Int): Long {
        val clamped = index.coerceIn(0, 255)
        return when {
            clamped < 16 -> ANSI_PALETTE[clamped]
            clamped < 232 -> {
                val value = clamped - 16
                val red = (value / 36) * 51
                val green = ((value / 6) % 6) * 51
                val blue = (value % 6) * 51
                0xFF000000L or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
            }
            else -> {
                val gray = 8 + (clamped - 232) * 10
                0xFF000000L or (gray.toLong() shl 16) or (gray.toLong() shl 8) or gray.toLong()
            }
        }
    }

    private fun unicodeWidth(value: String): Int {
        val codePoint = value.codePointAt(0)
        return when {
            codePoint == 0 -> 0
            codePoint in 0x0300..0x036F || codePoint in 0xFE00..0xFE0F -> 0
            codePoint in 0x1100..0x115F || codePoint in 0x2329..0x232A ||
                codePoint in 0x2E80..0xA4CF || codePoint in 0xAC00..0xD7A3 ||
                codePoint in 0xF900..0xFAFF || codePoint in 0xFE10..0xFE6F ||
                codePoint in 0xFF01..0xFF60 || codePoint in 0xFFE0..0xFFE6 ||
                codePoint >= 0x1F300 -> 2
            else -> 1
        }
    }

    private enum class ParserState { NORMAL, ESCAPE, CSI, OSC, OSC_ESCAPE }

    private companion object {
        const val DEFAULT_COLUMNS = 120
        const val DEFAULT_MAX_ROWS = 2000
        const val MIN_COLUMNS = 20
        const val MAX_COLUMNS = 400
        const val TAB_SIZE = 8
        const val MAX_CONTROL_LENGTH = 64
        val ANSI_COLORS = longArrayOf(
            0xFF000000,
            0xFFCC0000,
            0xFF4E9A06,
            0xFFC4A000,
            0xFF3465A4,
            0xFF75507B,
            0xFF06989A,
            0xFFD3D7CF,
        )
        val ANSI_BRIGHT_COLORS = longArrayOf(
            0xFF555753,
            0xFFEF2929,
            0xFF8AE234,
            0xFFFCE94F,
            0xFF729FCF,
            0xFFAD7FA8,
            0xFF34E2E2,
            0xFFEEEEEC,
        )
        val ANSI_PALETTE = longArrayOf(
            0xFF000000, 0xFFCC0000, 0xFF4E9A06, 0xFFC4A000, 0xFF3465A4, 0xFF75507B, 0xFF06989A, 0xFFD3D7CF,
            0xFF555753, 0xFFEF2929, 0xFF8AE234, 0xFFFCE94F, 0xFF729FCF, 0xFFAD7FA8, 0xFF34E2E2, 0xFFEEEEEC,
        )
    }
}
