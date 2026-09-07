package top.wanxiang.app.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentArgsParserTest {
    @Test
    fun `parses an array and skips malformed elements`() {
        val args = jsonObject(
            """{
                "subagents": [
                    {"taskName":"实现","role":"coder","prompt":"写代码"},
                    "partial",
                    {"role":"tester","prompt":"跑测试"},
                    {"taskName":"缺少提示"}
                ]
            }""",
        )

        val result = SubagentArgsParser.parse(args, defaultTaskName = "子任务")

        assertEquals(2, result.size)
        assertEquals("实现", result[0].taskName)
        assertEquals("coder", result[0].role)
        assertEquals("子任务", result[1].taskName)
        assertEquals("tester", result[1].role)
    }

    @Test
    fun `accepts a single object in subagents`() {
        val args = jsonObject(
            """{"subagents":{"taskName":"调查","role":"researcher","prompt":"定位根因"}}""",
        )

        val result = SubagentArgsParser.parse(args)

        assertEquals(1, result.size)
        assertEquals("调查", result.single().taskName)
        assertEquals("researcher", result.single().role)
    }

    @Test
    fun `parses department index routing without a role`() {
        val args = jsonObject(
            """{"subagents":{"taskName":"移动端实现","department":"engineering","agentQuery":"mobile android","prompt":"实现 Android 功能"}}""",
        )

        val result = SubagentArgsParser.parse(args)

        assertEquals(1, result.size)
        assertEquals("", result.single().role)
        assertEquals("engineering", result.single().department)
        assertEquals("mobile android", result.single().agentQuery)
    }

    @Test
    fun `keeps exact role as a backwards compatible override`() {
        val args = jsonObject(
            """{"subagents":{"taskName":"审查","role":"agency_engineering_code_reviewer","department":"testing","agentQuery":"test automation","prompt":"审查代码"}}""",
        )

        val result = SubagentArgsParser.parse(args)

        assertEquals("agency_engineering_code_reviewer", result.single().role)
        assertEquals("testing", result.single().department)
    }

    @Test
    fun `accepts legacy top-level arguments when nested value is incomplete`() {
        val args = jsonObject(
            """{"subagents":"partial","taskName":"兼容","role":"coder","prompt":"继续执行"}""",
        )

        val result = SubagentArgsParser.parse(args)

        assertEquals(1, result.size)
        assertEquals("继续执行", result.single().prompt)
    }

    @Test
    fun `returns empty for an interrupted malformed call`() {
        val args = jsonObject("""{"subagents":"partial"}""")

        assertTrue(SubagentArgsParser.parse(args).isEmpty())
    }

    @Test
    fun `rejects a task without either exact role or complete index routing`() {
        val args = jsonObject(
            """{"subagents":{"taskName":"不完整","department":"engineering","prompt":"继续执行"}}""",
        )

        assertTrue(SubagentArgsParser.parse(args).isEmpty())
    }

    @Test
    fun `parses writePaths as an array or a single string`() {
        val args = jsonObject(
            """{"subagents":
                [
                    {"taskName":"前端","role":"coder","prompt":"写前端","writePaths":["app/src/ui/","/app/src/model/core.kt"]},
                    {"taskName":"后端","role":"coder","prompt":"写后端","writePaths":"server/src"}
                ]}""",
        )

        val result = SubagentArgsParser.parse(args)

        assertEquals(listOf("app/src/ui/", "/app/src/model/core.kt"), result[0].writePaths)
        assertEquals(listOf("server/src"), result[1].writePaths)
    }

    @Test
    fun `tasks without writePaths default to read-only`() {
        val args = jsonObject(
            """{"subagents":{"taskName":"审查","role":"reviewer","prompt":"审查"}}""",
        )

        val result = SubagentArgsParser.parse(args)

        assertTrue(result.single().writePaths.isEmpty())
    }

    @Test
    fun `parses dedicated model when specified`() {
        val args = jsonObject(
            """{"subagents":[
                {"taskName":"重构","role":"coder","prompt":"重构代码","model":"qwen-2.5-coder-32b"},
                {"taskName":"测试","role":"tester","prompt":"运行单测","modelId":"gpt-4o-mini"},
                {"taskName":"常规","role":"reviewer","prompt":"常规审查","model":"inherit"}
            ]}""",
        )

        val result = SubagentArgsParser.parse(args)

        assertEquals(3, result.size)
        assertEquals("qwen-2.5-coder-32b", result[0].model)
        assertEquals("gpt-4o-mini", result[1].model)
        assertEquals("inherit", result[2].model) // Preserve explicit parent override over a role default.
    }

    @Test
    fun `accepts snake case write paths for compatibility`() {
        val args = jsonObject(
            """{"subagents":{"taskName":"实现","role":"coder","prompt":"修改","write_paths":["app/src"]}}""",
        )

        assertEquals(listOf("app/src"), SubagentArgsParser.parse(args).single().writePaths)
    }

    private fun jsonObject(raw: String) = Json.parseToJsonElement(raw).jsonObject
}

