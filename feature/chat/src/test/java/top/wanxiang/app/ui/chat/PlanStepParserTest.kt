package top.wanxiang.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanStepParserTest {

    @Test
    fun `parses canonical object array with status strings`() {
        val steps = PlanStepParser.parse(
            """[{"title":"拉取仓库","status":"completed"},
                {"title":"安装依赖","status":"in_progress"},
                {"title":"运行构建","status":"pending"}]""",
        )
        assertEquals(3, steps.size)
        assertEquals("拉取仓库", steps[0].title)
        assertTrue(steps[0].isCompleted)
        assertEquals("安装依赖", steps[1].title)
        assertTrue(!steps[1].isCompleted)
        assertEquals(listOf(1, 2, 3), steps.map { it.index })
    }

    @Test
    fun `parses boolean done field and alternate key names`() {
        val steps = PlanStepParser.parse(
            """[{"step":"初始化","done":true},{"name":"编译","done":false}]""",
        )
        assertEquals(listOf(true, false), steps.map { it.isCompleted })
        assertEquals(listOf("初始化", "编译"), steps.map { it.title })
    }

    @Test
    fun `parses plain string array as pending steps`() {
        val steps = PlanStepParser.parse("""["第一步","第二步"]""")
        assertEquals(listOf("第一步", "第二步"), steps.map { it.title })
        assertTrue(steps.none { it.isCompleted })
    }

    @Test
    fun `unwraps object wrapper with steps array`() {
        val steps = PlanStepParser.parse("""{"steps":[{"title":"a","status":"completed"},{"title":"b"}]}""")
        assertEquals(2, steps.size)
        assertTrue(steps[0].isCompleted)
    }

    @Test
    fun `falls back to first non-status string when keys unknown`() {
        val steps = PlanStepParser.parse("""[{"内容":"部署到设备","state":"完成中"}]""")
        assertEquals(1, steps.size)
        assertEquals("部署到设备", steps[0].title)
    }

    @Test
    fun `invalid json or blank degrades to empty`() {
        assertTrue(PlanStepParser.parse(null).isEmpty())
        assertTrue(PlanStepParser.parse("").isEmpty())
        assertTrue(PlanStepParser.parse("not-json{").isEmpty())
        assertTrue(PlanStepParser.parse("""{"goal":"无步骤"}""").isEmpty())
    }
}
