package top.wanxiang.app.runtime.gui

/**
 * First-class GUI primitives for host automation.
 * Executed by [HostGuiToolkit] with multi-backend fallbacks.
 */
sealed class GuiPrimitive {
    data class Tap(val x: Int, val y: Int) : GuiPrimitive()
    data class DoubleTap(val x: Int, val y: Int, val gapMs: Long = 80L) : GuiPrimitive()
    data class LongPress(val x: Int, val y: Int, val durationMs: Long = 800L) : GuiPrimitive()
    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long = 300L,
    ) : GuiPrimitive()
    data class Scroll(
        val direction: ScrollDirection,
        /** 0..1 of the shorter screen edge used as travel distance. */
        val distanceRatio: Float = 0.45f,
        val durationMs: Long = 350L,
        val anchorX: Int? = null,
        val anchorY: Int? = null,
    ) : GuiPrimitive()
    data class Key(val key: GuiKey) : GuiPrimitive()
    /** Write clipboard then paste into the focused field (preferred for CJK). */
    data class PasteText(val text: String) : GuiPrimitive()
}

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

enum class GuiKey(val aliases: Set<String>, val keyCode: Int) {
    BACK(setOf("back"), 4),
    HOME(setOf("home"), 3),
    RECENTS(setOf("recents", "app_switch"), 187),
    ENTER(setOf("enter"), 66),
    DELETE(setOf("delete", "backspace"), 67),
    PASTE(setOf("paste"), 279),
    VOLUME_UP(setOf("volume_up"), 24),
    VOLUME_DOWN(setOf("volume_down"), 25),
    POWER(setOf("power"), 26),
    ;

    companion object {
        fun parse(raw: String): GuiKey? {
            val key = raw.trim().lowercase()
            if (key.isEmpty()) return null
            entries.firstOrNull { key in it.aliases }?.let { return it }
            val code = key.toIntOrNull() ?: return null
            return entries.firstOrNull { it.keyCode == code }
        }
    }
}

enum class GuiBackendId(val label: String) {
    ACCESSIBILITY("accessibility-gesture"),
    CMD_INPUT("cmd-input"),
    BIN_INPUT("bin-input"),
    CLIPBOARD("clipboard"),
}

data class GuiAttempt(
    val backend: GuiBackendId,
    val success: Boolean,
    val detail: String,
)

data class GuiExecResult(
    val success: Boolean,
    val message: String,
    val backend: GuiBackendId? = null,
    val attempts: List<GuiAttempt> = emptyList(),
) {
    fun toAgentLine(): String = buildString {
        if (success) append("✔ ") else append("✘ ")
        append(message)
        backend?.let { append(" · via ").append(it.label) }
        if (!success && attempts.isNotEmpty()) {
            append("\n降级轨迹：")
            attempts.forEach { a ->
                append("\n- ").append(a.backend.label).append(": ").append(a.detail)
            }
        }
    }
}
