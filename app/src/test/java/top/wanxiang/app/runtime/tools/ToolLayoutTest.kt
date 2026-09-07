package top.wanxiang.app.runtime.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolLayoutTest {
    @Test
    fun keepsProgramDataAndCommandEntrypointsSeparated() {
        assertEquals("/opt/wanxiang/tools/openclaw", ToolLayout.toolDirectory("openclaw"))
        assertEquals("/opt/wanxiang/data/openclaw", ToolLayout.toolDataDirectory("openclaw"))
        assertEquals("/opt/wanxiang/bin/openclaw", ToolLayout.commandPath("openclaw"))
    }
}
