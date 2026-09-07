package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtkCommandOptimizerTest {
    @Test
    fun `eligible git command is wrapped with a same-shell fallback`() {
        val prepared = RtkCommandOptimizer.prepare("git status --short", enabled = true)

        assertTrue(prepared.commandLine.contains("/opt/wanxiang/bin/rtk\" rewrite 'git status --short'"))
        assertTrue(prepared.commandLine.contains("else\n        git status --short"))
        assertEquals("0", prepared.environment["RTK_TEE"])
    }

    @Test
    fun `shell syntax and file reads remain raw`() {
        assertEquals(
            "git status && git log --oneline",
            RtkCommandOptimizer.prepare("git status && git log --oneline", enabled = true).commandLine,
        )
        assertEquals("cat build.gradle.kts", RtkCommandOptimizer.prepare("cat build.gradle.kts", enabled = true).commandLine)
    }

    @Test
    fun `disabled setting bypasses RTK`() {
        val prepared = RtkCommandOptimizer.prepare("./gradlew test", enabled = false)

        assertEquals("./gradlew test", prepared.commandLine)
        assertFalse(prepared.environment.isNotEmpty())
    }

    @Test
    fun `quoted glob arguments are optimized and passed through verbatim`() {
        val command = "rg -n 'fun ' --glob '*.kt' ."
        val prepared = RtkCommandOptimizer.prepare(command, enabled = true)

        // 通配符不再阻止改写：rewrite 输出保留原引号，eval 只做一次展开。
        assertTrue(prepared.commandLine.contains("rewrite 'rg -n '\"'\"'fun '\"'\"' --glob '\"'\"'*.kt'\"'\"' .'"))
        assertTrue(prepared.commandLine.contains("else\n        $command"))
    }

    @Test
    fun `find with a quoted name pattern is optimized`() {
        val command = "find . -name '*.kt'"
        val prepared = RtkCommandOptimizer.prepare(command, enabled = true)

        assertTrue(prepared.commandLine.contains("rewrite 'find . -name '\"'\"'*.kt'\"'\"''"))
    }

    @Test
    fun `long listing keeps using RTK because -l is not a machine readable flag for ls`() {
        val prepared = RtkCommandOptimizer.prepare("ls -la src", enabled = true)

        assertTrue(prepared.commandLine.contains("rewrite 'ls -la src'"))
    }

    @Test
    fun `machine readable output is never rewritten`() {
        val untouched = listOf(
            "git status --porcelain",
            "git log --pretty=oneline",
            "git diff --numstat",
            "grep -l TODO src",
            "grep -rnl TODO src",
            "rg --files-with-matches TODO",
            "rg -c TODO",
            "rg -q TODO",
            "find . -name '*.kt' -print0",
            "find . -name '*.kt' -exec rm {} +",
            "ls -1",
            "ls -la1",
        )

        untouched.forEach { command ->
            assertEquals(command, RtkCommandOptimizer.prepare(command, enabled = true).commandLine)
        }
    }

    @Test
    fun `commands without an rtk equivalent stay raw`() {
        // wc 会丢掉单文件路径并把总计行改写成 Σ；du/yarn/bun/mvnw 在 RTK 里没有子命令。
        listOf("wc -l build.gradle.kts", "du -sh build", "yarn test", "bun install", "./mvnw verify").forEach { command ->
            assertEquals(command, RtkCommandOptimizer.prepare(command, enabled = true).commandLine)
        }
    }

    @Test
    fun `only a single line rtk rewrite is evaluated`() {
        val prepared = RtkCommandOptimizer.prepare("ls -la", enabled = true)

        // 只有以 "rtk " 开头的单行输出才会进入 eval，避免未来版本把诊断文本写到 stdout。
        assertTrue(prepared.commandLine.contains("\"rtk \"*) ;;"))
        assertTrue(prepared.commandLine.contains("wc -l)\" -eq 0 ]"))
    }
}
