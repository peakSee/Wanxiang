package top.wanxiang.app.harness.checkpoint

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CheckpointPersistenceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun storeWithDisk(): Pair<CheckpointStore, File> {
        val root = temporaryFolder.newFolder("ckpt")
        val store = CheckpointStore()
        store.persistence = FileCheckpointPersistence(root)
        return store to root
    }

    @Test
    fun `closed turns survive process restart via disk persistence`() {
        val (store, root) = storeWithDisk()
        store.beginTurn("s", "第一轮", "anchor-1")
        store.capture("s", "a.txt", "old-a")
        store.capture("s", "b.txt", null)
        store.beginTurn("s", "第二轮", "anchor-2") // 关闭 turn0 并落盘

        // 模拟进程重启：全新 store 挂同一磁盘目录
        val revived = CheckpointStore()
        revived.persistence = FileCheckpointPersistence(root)

        val metas = revived.checkpoints("s")
        assertEquals(1, metas.size)
        assertEquals("anchor-1", metas.single().anchorMessageId)
        assertEquals(listOf("a.txt", "b.txt"), metas.single().changedFiles)

        // 恢复后的 rewind 计划与内存时代一致：含 null 快照（该轮创建的文件 → 回滚即删除）
        val plan = revived.planCodeRewind("s", 0)
        assertEquals(2, plan.size)
        assertEquals("old-a", plan.first { it.path == "a.txt" }.content)
        assertNull(plan.first { it.path == "b.txt" }.content)
    }

    @Test
    fun `absent snapshots are distinguished from empty content files on restore`() {
        val (store, root) = storeWithDisk()
        store.beginTurn("s", "t", null)
        store.capture("s", "created.txt", null) // 该轮新建
        store.capture("s", "empty.txt", "") // 已存在但内容为空
        store.beginTurn("s", "t2") // 关闭 turn0

        val revived = CheckpointStore()
        revived.persistence = FileCheckpointPersistence(root)
        val plan = revived.planCodeRewind("s", 0)

        assertNull(plan.first { it.path == "created.txt" }.content)
        assertEquals("", plan.first { it.path == "empty.txt" }.content)
    }

    @Test
    fun `dropSession removes both memory state and disk directory`() {
        val (store, root) = storeWithDisk()
        store.beginTurn("s", "t", null)
        store.capture("s", "a.txt", "x")
        store.beginTurn("s", "t2") // 关闭并落盘
        assertTrue(File(root, "s").isDirectory)

        store.dropSession("s")

        assertTrue(store.checkpoints("s").isEmpty())
        assertTrue(File(root, "s").isDirectory.not() || File(root, "s").listFiles().isEmpty())
        // 重启后也无残留
        val revived = CheckpointStore()
        revived.persistence = FileCheckpointPersistence(root)
        assertTrue(revived.checkpoints("s").isEmpty())
    }

    @Test
    fun `disk turns older than retention window are pruned on write`() {
        val (store, root) = storeWithDisk()
        repeat(CheckpointStore.MAX_KEPT + 5) { index ->
            store.beginTurn("s", "turn-$index", null)
            store.capture("s", "f-$index.txt", "v-$index")
        }
        store.beginTurn("s", "final") // 关闭最后一轮触发落盘+清理

        val indexFiles = File(root, "s").listFiles { f -> f.name.endsWith(".index.json") }!!
        assertEquals(CheckpointStore.MAX_KEPT, indexFiles.size)
        // 最老的超龄轮目录已删
        assertTrue(!File(root, "s/0").exists())
        assertTrue(indexFiles.all { it.name.removeSuffix(".index.json").toInt() >= 5 })
    }

    @Test
    fun `restored turns merge with newer in-memory turns without duplicates`() {
        val (store, root) = storeWithDisk()
        // 首轮落盘
        store.beginTurn("s", "第一轮", "a1")
        store.capture("s", "a.txt", "v1")
        store.beginTurn("s", "第二轮", "a2") // 关闭 turn0

        // 同一进程里继续（此时触发磁盘恢复 + 已有内存态）
        store.beginTurn("s", "第三轮", "a3")
        store.capture("s", "b.txt", "v2")
        store.beginTurn("s", "第四轮") // 关闭 turn（新轮）

        val metas = store.checkpoints("s")
        // turn0（磁盘恢复）+ 新内存轮，去重无重复
        assertEquals(metas.map { it.turn }.distinct().size, metas.size)
        assertTrue(metas.any { it.prompt == "第一轮" && it.changedFiles == listOf("a.txt") })
        assertTrue(metas.any { it.changedFiles == listOf("b.txt") })
        // 顺序保持
        assertEquals(metas.sortedBy { it.turn }, metas)
        assertNotNull(metas.firstOrNull { it.prompt == "第一轮" }?.anchorMessageId)
    }
}
