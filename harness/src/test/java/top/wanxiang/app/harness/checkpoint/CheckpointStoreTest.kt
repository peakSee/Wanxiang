package top.wanxiang.app.harness.checkpoint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckpointStoreTest {

    private val store = CheckpointStore()

    @Test
    fun `capture without an active turn is ignored`() {
        assertFalse(store.capture("s", "a.txt", "x"))
    }

    @Test
    fun `capture dedups per path per turn and keeps first-touch content`() {
        store.beginTurn("s", "p0")
        assertTrue(store.capture("s", "a.txt", "turn0-start"))
        // 同轮再次触碰不覆盖：仍保留轮初内容
        assertFalse(store.capture("s", "a.txt", "turn0-after"))
        store.beginTurn("s", "p1")

        val checkpoints = store.checkpoints("s")
        assertEquals(1, checkpoints.size)
        assertEquals(listOf("a.txt"), checkpoints[0].changedFiles)
    }

    @Test
    fun `empty turn closes without creating a checkpoint`() {
        store.beginTurn("s", "p0")
        store.beginTurn("s", "p1")
        assertTrue(store.checkpoints("s").isEmpty())
    }

    @Test
    fun `code rewind takes earliest snapshot per path starting from the target turn`() {
        // turn0: a.txt 不存在 → b.txt = "b0"
        store.beginTurn("s", "p0")
        store.capture("s", "a.txt", null)
        store.capture("s", "b.txt", "b0")
        // turn1: a.txt = "a1", c.txt = "c1"
        store.beginTurn("s", "p1")
        store.capture("s", "a.txt", "a1")
        store.capture("s", "c.txt", "c1")
        // turn2: a.txt = "a2"
        store.beginTurn("s", "p2")
        store.capture("s", "a.txt", "a2")

        // 撤回到 turn0：a.txt 恢复为"当时不存在 → null"、b.txt="b0"、c.txt="c1"
        val to0 = store.planCodeRewind("s", 0).associate { it.path to it.content }
        assertEquals(null, to0["a.txt"])
        assertEquals("b0", to0["b.txt"])
        assertEquals("c1", to0["c.txt"])

        // 撤回到 turn1：a.txt 恢复为 "a1"
        val to1 = store.planCodeRewind("s", 1).associate { it.path to it.content }
        assertEquals("a1", to1["a.txt"])
    }

    @Test
    fun `rewind plan is empty for out-of-range turns`() {
        store.beginTurn("s", "p0")
        store.capture("s", "a.txt", null)
        store.beginTurn("s", "p1")
        assertTrue(store.planCodeRewind("s", 5).isEmpty())
        assertTrue(store.planCodeRewind("s", -1).isEmpty())
    }

    @Test
    fun `retention keeps only the newest MAX_KEPT turns`() {
        repeat(CheckpointStore.MAX_KEPT + 10) { index ->
            store.beginTurn("s", "p$index")
            store.capture("s", "f$index.txt", "x")
        }
        assertEquals(CheckpointStore.MAX_KEPT, store.checkpoints("s").size)
    }
}