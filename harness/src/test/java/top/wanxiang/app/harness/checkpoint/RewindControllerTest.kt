package top.wanxiang.app.harness.checkpoint

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.wanxiang.app.harness.WorkspaceFileAccess

class RewindControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun textOf(root: File, path: String): String? {
        val file = root.resolve(path)
        return if (file.exists()) file.readText() else null
    }

    private suspend fun write(root: File, path: String, content: String) {
        check(WorkspaceFileAccess(root).write(path, content).isSuccess) { "测试 setup 写文件失败: $path" }
    }

    @Test
    fun `code rewind restores files and deletes those created after the target turn`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val store = CheckpointStore()
        val rc = RewindController(store, WorkspaceFileAccess(root))

        // turn0：a.txt = "v1"，b.txt 不存在
        rc.beginTurn("s", "p0")
        store.capture("s", "a.txt", null)
        store.capture("s", "b.txt", null)
        write(root, "a.txt", "v1")
        // turn1：a.txt = "v2"，创建 b.txt
        rc.beginTurn("s", "p1")
        store.capture("s", "a.txt", "v1")
        store.capture("s", "b.txt", null)
        write(root, "a.txt", "v2")
        write(root, "b.txt", "b")

        // 撤回到 turn1：a.txt 应回到 "v1"，b.txt（turn1 时不存在）应被删除
        val plan = rc.prepare("s", 1, RewindScope.CODE)
        val result = rc.commit(plan)

        assertEquals(1, result.filesRestored)
        assertEquals(1, result.filesDeleted)
        assertFalse(result.partial)
        assertEquals("v1", textOf(root, "a.txt"))
        assertNull(textOf(root, "b.txt"))
    }

    @Test
    fun `code rewind to turn0 restores to initial absent state`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val store = CheckpointStore()
        val rc = RewindController(store, WorkspaceFileAccess(root))

        rc.beginTurn("s", "p0")
        store.capture("s", "a.txt", null)
        write(root, "a.txt", "v1")
        rc.beginTurn("s", "p1")
        store.capture("s", "a.txt", "v1")
        write(root, "a.txt", "v2")

        rc.commit(rc.prepare("s", 0, RewindScope.CODE))
        assertNull(textOf(root, "a.txt"))
    }

    @Test
    fun `conversation scope is partial when no conversation rewirer is configured`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val store = CheckpointStore()
        val rc = RewindController(store, WorkspaceFileAccess(root))

        rc.beginTurn("s", "p0")
        store.capture("s", "a.txt", null)
        write(root, "a.txt", "v1")

        val result = rc.commit(rc.prepare("s", 0, RewindScope.BOTH))
        assertTrue(result.partial)
        assertTrue(result.note.orEmpty().contains("对话"))
    }

    @Test
    fun `path traversal in restore target is rejected by the workspace boundary`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val store = CheckpointStore()
        val rc = RewindController(store, WorkspaceFileAccess(root))

        // 伪装一个可逃逸路径：delete 解析到工作区外应返回 false，不会产生副作用
        store.beginTurn("s", "p0")
        store.capture("s", "../../outside.txt", null)
        val result = rc.commit(rc.prepare("s", 0, RewindScope.CODE))
        assertTrue(result.filesDeleted == 0)
        assertEquals(0, result.filesRestored)
    }
}