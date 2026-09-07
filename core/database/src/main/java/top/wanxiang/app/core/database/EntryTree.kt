package top.wanxiang.app.core.database

/** Pure immutable-tree traversal kept separate from Room storage. */
object EntryTree {
    fun branch(entries: List<HarnessEntryEntity>, leafId: String?): List<HarnessEntryEntity> {
        if (leafId == null) return emptyList()
        val byId = entries.associateBy { it.id }
        val reversed = ArrayList<HarnessEntryEntity>()
        val visited = HashSet<String>()
        var cursor: String? = leafId
        while (cursor != null) {
            check(visited.add(cursor)) { "Cycle detected in harness entry tree at $cursor" }
            val entry = byId[cursor] ?: error("Missing harness entry $cursor")
            reversed += entry
            cursor = entry.parentId
        }
        reversed.reverse()
        return reversed
    }
}
