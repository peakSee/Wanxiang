package top.wanxiang.app.core.model.workflow

/** Longest-path layering followed by barycentric crossing reduction. Stable across runs. */
object WorkflowLayout {
    fun arrange(definition: WorkflowDefinition): WorkflowDefinition {
        require(WorkflowValidator.validate(definition).isEmpty()) { "请先修正工作流结构" }
        val incoming = definition.edges.groupBy { it.toNodeId }
        val outgoing = definition.edges.groupBy { it.fromNodeId }
        val ranks = mutableMapOf<String, Int>()
        val pending = definition.nodes.map { it.id }.toMutableList()
        while (pending.isNotEmpty()) {
            val ready = pending.filter { id -> incoming[id].orEmpty().all { it.fromNodeId in ranks } }
            check(ready.isNotEmpty()) { "工作流存在循环" }
            ready.forEach { id -> ranks[id] = incoming[id].orEmpty().maxOfOrNull { ranks.getValue(it.fromNodeId) + 1 } ?: 0 }
            pending.removeAll(ready.toSet())
        }
        val layers = definition.nodes.groupBy { ranks.getValue(it.id) }.toSortedMap()
            .mapValues { (_, nodes) -> nodes.map { it.id }.toMutableList() }
        val order = mutableMapOf<String, Int>()
        fun refresh() = layers.values.forEach { ids -> ids.forEachIndexed { index, id -> order[id] = index } }
        refresh()
        repeat(4) {
            for (rank in layers.keys.drop(1)) {
                layers.getValue(rank).sortBy { id -> incoming[id].orEmpty().mapNotNull { order[it.fromNodeId] }.average().takeUnless { it.isNaN() } ?: order.getValue(id).toDouble() }
                refresh()
            }
            for (rank in layers.keys.reversed().drop(1)) {
                layers.getValue(rank).sortBy { id -> outgoing[id].orEmpty().mapNotNull { order[it.toNodeId] }.average().takeUnless { it.isNaN() } ?: order.getValue(id).toDouble() }
                refresh()
            }
        }
        val height = layers.values.maxOfOrNull { it.size } ?: 0
        return definition.copy(nodes = definition.nodes.map { node ->
            val rank = ranks.getValue(node.id)
            node.copy(canvasX = 64f + rank * 304f, canvasY = 64f + (order.getValue(node.id) + (height - layers.getValue(rank).size) / 2f) * 160f)
        })
    }
}
