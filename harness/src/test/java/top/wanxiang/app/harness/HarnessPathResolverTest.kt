package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the path-boundary semantics shared by ApprovalPolicyEngine and ToolExecutor.
 */
class HarnessPathResolverTest {
    private val resolver = HarnessPathResolver()
    private val workspace = "/workspace/project"

    @Test
    fun `explicit absolute cwd wins over workspace`() {
        assertEquals("/tmp/alt", resolver.resolveWorkingDirectory("/tmp/alt", workspace))
    }

    @Test
    fun `workspace without leading slash is mounted under slash workspace`() {
        assertEquals("/workspace/repo", resolver.resolveWorkingDirectory(null, "repo"))
    }

    @Test
    fun `blank workspace falls back to default cwd`() {
        assertEquals(HarnessPathResolver.DEFAULT_CWD, resolver.resolveWorkingDirectory(null, ""))
    }

    @Test
    fun `relative path resolves against the workspace cwd not the callers pwd`() {
        assertEquals("/workspace/project/etc/hosts", resolver.resolveAbsolutePath("etc/hosts", workspace))
    }

    @Test
    fun `parent traversal in absolute path is rejected by isWithinWorkspace`() {
        assertFalse(resolver.isWithinWorkspace("/workspace/project/../secrets", workspace))
    }

    @Test
    fun `relative path with parent segment is rejected by isWithinWorkspace`() {
        assertFalse(resolver.isWithinWorkspace("../secrets", workspace))
    }

    @Test
    fun `bare relative path under workspace stays inside after resolution`() {
        val resolved = resolver.resolveAbsolutePath("etc/passwd", workspace)
        assertEquals("/workspace/project/etc/passwd", resolved)
        assertTrue(resolver.isWithinWorkspace("etc/passwd", workspace))
    }

    @Test
    fun `sibling workspace prefix is not misclassified as inside current workspace`() {
        assertFalse(resolver.isWithinWorkspace("/workspace/projectother/.env", workspace))
    }

    @Test
    fun `path equal to workspace root is allowed`() {
        assertTrue(resolver.isWithinWorkspace("/workspace/project", workspace))
    }

    @Test
    fun `path nested under workspace is allowed`() {
        assertTrue(resolver.isWithinWorkspace("/workspace/project/src/Main.kt", workspace))
    }

    @Test
    fun `blank path or blank workspace is rejected`() {
        assertFalse(resolver.isWithinWorkspace("", workspace))
        assertFalse(resolver.isWithinWorkspace("/workspace/project", ""))
    }

    @Test
    fun `null byte in path is rejected as hostile input`() {
        val hostile = StringBuilder("/workspace/project/").append(0.toChar()).append("evil").toString()
        assertFalse(resolver.isWithinWorkspace(hostile, workspace))
    }
}
