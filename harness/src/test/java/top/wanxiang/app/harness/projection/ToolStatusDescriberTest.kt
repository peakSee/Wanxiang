package top.wanxiang.app.harness.projection

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import top.wanxiang.app.harness.HarnessTool

private fun args(vararg pairs: Pair<String, String>): JsonObject =
    buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

class ToolStatusDescriberTest {

    @Test
    fun `base without command reads generic`() {
        assertEquals("执行命令", ToolStatusDescriber.describe(HarnessTool.BASE, buildJsonObject { }))
    }

    @Test
    fun `base shows first command line truncated to limit`() {
        val long = "a".repeat(80)
        val status = ToolStatusDescriber.describe(HarnessTool.BASE, args("command" to "$long\necho second"))
        assertEquals("执行命令：" + "a".repeat(ToolStatusDescriber.MAX_STATUS_ARG_LENGTH), status)
    }

    @Test
    fun `file tools show tail of path argument`() {
        val long = "p".repeat(70)
        val read = ToolStatusDescriber.describe(HarnessTool.READ, args("path" to long))
        val write = ToolStatusDescriber.describe(HarnessTool.WRITE, args("path" to long))
        val tail = "p".repeat(ToolStatusDescriber.MAX_STATUS_ARG_LENGTH)
        assertEquals("读取文件：$tail", read)
        assertEquals("写入文件：$tail", write)
    }

    @Test
    fun `process shows action and id`() {
        val status = ToolStatusDescriber.describe(
            HarnessTool.PROCESS,
            args("action" to "start", "id" to "i".repeat(70)),
        )
        assertEquals(true, status.startsWith("管理后台进程：start · "))
        assertEquals(ToolStatusDescriber.MAX_STATUS_ARG_LENGTH, status.substringAfter("· ").length + 0)
    }

    @Test
    fun `mcp uses raw name with fallback`() {
        assertEquals("正在调用 MCP 插件工具：mcp__fs__cat…", ToolStatusDescriber.describe(HarnessTool.MCP, buildJsonObject { }, "mcp__fs__cat"))
        assertEquals("正在调用 MCP 插件工具：mcp…", ToolStatusDescriber.describe(HarnessTool.MCP, buildJsonObject { }))
    }

    @Test
    fun `memory plan scratchpad fall back to defaults`() {
        assertEquals("正在存取长期记忆：memory", ToolStatusDescriber.describe(HarnessTool.MEMORY, buildJsonObject { }))
        assertEquals("正在更新任务执行规划：plan", ToolStatusDescriber.describe(HarnessTool.PLAN, buildJsonObject { }))
        assertEquals("正在记录工作草稿便签：scratchpad", ToolStatusDescriber.describe(HarnessTool.SCRATCHPAD, buildJsonObject { }))
    }
}
