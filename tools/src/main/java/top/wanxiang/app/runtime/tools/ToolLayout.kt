package top.wanxiang.app.runtime.tools

/** Stable Linux-side layout for installed tool code and command shims. */
object ToolLayout {
    const val ROOT = "/opt/wanxiang"
    const val TOOLS = "$ROOT/tools"
    const val DATA = "$ROOT/data"
    const val BIN = "$ROOT/bin"

    fun toolDirectory(toolId: String): String = "$TOOLS/$toolId"

    fun toolDataDirectory(toolId: String): String = "$DATA/$toolId"

    fun toolBinary(toolId: String, command: String): String =
        "${toolDirectory(toolId)}/bin/$command"

    fun commandPath(command: String): String = "$BIN/$command"
}
