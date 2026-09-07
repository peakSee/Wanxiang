package top.wanxiang.app.harness.validation

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallLoopDetectorTest {

    @Test
    fun `single execution passes without warnings`() {
        val detector = ToolCallLoopDetector()
        val args = buildJsonObject { put("path", "file.txt") }
        val verdict = detector.evaluate("read", args)
        assertEquals(ToolCallLoopDetector.LoopVerdict.Pass, verdict)
    }

    @Test
    fun `consecutive failures of identical tool call triggers block and reflection guidance`() {
        val detector = ToolCallLoopDetector(maxSameCallFailures = 2)
        val args = buildJsonObject { put("command", "invalid_command_xyz") }

        // 第 1 次调用
        assertEquals(ToolCallLoopDetector.LoopVerdict.Pass, detector.evaluate("base", args))
        detector.recordIntent("base", args)
        detector.recordSettled("base", args, success = false)

        // 第 2 次调用相同参数
        assertEquals(ToolCallLoopDetector.LoopVerdict.Pass, detector.evaluate("base", args))
        detector.recordIntent("base", args)
        detector.recordSettled("base", args, success = false)

        // 第 3 次调用应被强制拦截并要求反思
        val verdict = detector.evaluate("base", args)
        assertTrue(verdict is ToolCallLoopDetector.LoopVerdict.Block)
        val block = verdict as ToolCallLoopDetector.LoopVerdict.Block
        assertTrue(block.reason.contains("连续执行失败 2 次"))
        assertTrue(block.guidance.contains("严禁重试"))
    }

    @Test
    fun `identical calls without progress triggers stagnation block`() {
        val detector = ToolCallLoopDetector(maxIdenticalCallsStreak = 3)
        val args = buildJsonObject { put("path", "src/Main.kt") }

        repeat(3) {
            assertEquals(ToolCallLoopDetector.LoopVerdict.Pass, detector.evaluate("read", args))
            detector.recordIntent("read", args)
            detector.recordSettled("read", args, success = true)
        }

        // 第 4 次完全相同的调用被判定为无进展死循环
        val verdict = detector.evaluate("read", args)
        assertTrue(verdict is ToolCallLoopDetector.LoopVerdict.Block)
        val block = verdict as ToolCallLoopDetector.LoopVerdict.Block
        assertTrue(block.reason.contains("重复空转"))
    }

    @Test
    fun `reset clears all historical call streaks`() {
        val detector = ToolCallLoopDetector(maxSameCallFailures = 2)
        val args = buildJsonObject { put("command", "foo") }

        repeat(2) {
            detector.recordIntent("base", args)
            detector.recordSettled("base", args, success = false)
        }

        assertTrue(detector.evaluate("base", args) is ToolCallLoopDetector.LoopVerdict.Block)

        detector.reset()
        assertEquals(ToolCallLoopDetector.LoopVerdict.Pass, detector.evaluate("base", args))
    }

    @Test
    fun `oscillating tool calls pattern triggers block`() {
        val detector = ToolCallLoopDetector()
        val readArgs = buildJsonObject { put("path", "file.txt") }
        val editArgs = buildJsonObject { put("path", "file.txt"); put("old_text", "a"); put("new_text", "b") }

        // 模拟 A -> B -> A -> B -> A
        detector.recordIntent("read", readArgs)
        detector.recordSettled("read", readArgs, success = true)
        detector.recordIntent("edit", editArgs)
        detector.recordSettled("edit", editArgs, success = false)

        detector.recordIntent("read", readArgs)
        detector.recordSettled("read", readArgs, success = true)
        detector.recordIntent("edit", editArgs)
        detector.recordSettled("edit", editArgs, success = false)

        detector.recordIntent("read", readArgs)
        detector.recordSettled("read", readArgs, success = true)

        // 下一次若是 edit，则形成完整的 3 轮震荡死循环 [read, edit, read, edit, read, edit]
        val verdict = detector.evaluate("edit", editArgs)
        assertTrue(verdict is ToolCallLoopDetector.LoopVerdict.Block)
        val block = verdict as ToolCallLoopDetector.LoopVerdict.Block
        assertTrue(block.reason.contains("交替震荡死循环"))
    }
}
