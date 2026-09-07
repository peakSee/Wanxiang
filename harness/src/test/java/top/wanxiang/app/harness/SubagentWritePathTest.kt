package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.model.SubagentTaskSpec

/**
 * 阶段三写租约：验证同一波内写路径互不相交（可并行）、撞同文件被拆到不同波（串行）、
 * 缺省只读任务并行、整工作区显式租约独占一波（串行）。
 */
class SubagentWritePathTest {

    private fun spec(taskName: String, vararg paths: String) = SubagentTaskSpec(
        taskName = taskName,
        role = "coder",
        prompt = "$taskName 任务",
        writePaths = paths.toList(),
    )

    @Test
    fun `disjoint write paths are packed into the same wave for parallel execution`() {
        val waves = buildWriteCleanWaves(
            listOf(
                spec("前端", "app/src/ui"),
                spec("后端", "server/src"),
                spec("测试", "tests/"),
            ),
        )

        assertEquals(1, waves.size)
        assertEquals(3, waves.single().size)
    }

    @Test
    fun `two writers of the same file are split into different waves (serial)`() {
        val waves = buildWriteCleanWaves(
            listOf(
                spec("改造A", "app/src/model/core.kt"),
                spec("改造B", "app/src/model/core.kt"),
            ),
        )

        assertEquals(2, waves.size)
        assertEquals(1, waves[0].size)
        assertEquals(1, waves[1].size)
    }

    @Test
    fun `read-only tasks are packed into one parallel wave`() {
        val waves = buildWriteCleanWaves(
            listOf(
                spec("S1"),
                spec("S2"),
                spec("S3"),
            ),
        )

        assertEquals(1, waves.size)
        assertEquals(listOf("S1", "S2", "S3"), waves.single().map { it.taskName })
    }

    @Test
    fun `whole-workspace marker gets its own exclusive serial wave`() {
        val waves = buildWriteCleanWaves(
            listOf(
                spec("全量整理", "*"),
                spec("局部", "docs/readme.md"),
            ),
        )

        assertEquals(2, waves.size)
        assertTrue(waves.all { it.size == 1 })
    }

    @Test
    fun `read-only tasks do not share a wave with writers`() {
        val waves = buildWriteCleanWaves(listOf(spec("调研"), spec("实现", "app/src"), spec("审查")))

        assertEquals(2, waves.size)
        assertEquals(setOf("调研", "审查"), waves.first().map { it.taskName }.toSet())
        assertEquals(listOf("实现"), waves.last().map { it.taskName })
    }

    @Test
    fun `parent and child write paths conflict`() {
        val waves = buildWriteCleanWaves(
            listOf(spec("父目录", "app/src"), spec("子文件", "app/src/main/Main.kt")),
        )

        assertEquals(2, waves.size)
    }

    @Test
    fun `path normalization treats leading slashes and trailing slashes as equivalent`() {
        val a = spec("A", "app/src/ui")
        val b = spec("B", "/app/src/ui/")
        val c = spec("C", "app/src/other")

        // a 与 b 归一化后同路径 → 冲突 → 不同波；c 与 a 不冲突 → 可与 a 同波。
        val waves = buildWriteCleanWaves(listOf(a, b, c))

        assertEquals(2, waves.size)
        val waveOfA = waves.first { "A" in it.map { s -> s.taskName } }
        assertTrue("C" in waveOfA.map { s -> s.taskName })
        assertFalse("B" in waveOfA.map { s -> s.taskName })
    }
}
