package top.wanxiang.app.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EntryTreeTest {
    @Test
    fun `projects only ancestors of selected leaf`() {
        val root = entry("root", null)
        val left = entry("left", "root")
        val right = entry("right", "root")
        val leaf = entry("leaf", "left")

        assertEquals(listOf("root", "left", "leaf"), EntryTree.branch(listOf(root, left, right, leaf), "leaf").map { it.id })
        assertEquals(listOf("root", "right"), EntryTree.branch(listOf(root, left, right, leaf), "right").map { it.id })
    }

    @Test
    fun `rejects cycles and missing parents`() {
        assertThrows(IllegalStateException::class.java) { EntryTree.branch(listOf(entry("a", "b"), entry("b", "a")), "a") }
        assertThrows(IllegalStateException::class.java) { EntryTree.branch(listOf(entry("a", "missing")), "a") }
    }

    private fun entry(id: String, parentId: String?) = HarnessEntryEntity(
        id = id,
        sessionId = "session",
        parentId = parentId,
        createdAt = 1,
        entryType = "message",
        payloadJson = "{}",
    )
}
