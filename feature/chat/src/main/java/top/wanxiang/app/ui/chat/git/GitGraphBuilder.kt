package top.wanxiang.app.ui.chat.git

/**
 * 提交拓扑图的纯 Kotlin 解析与泳道布局（IDE 风格，移植自 AiCode GitGraphBuilder）。
 * 无状态、无 Android 依赖：输入 `git log`（带 %P 父哈希）与 refs 映射，
 * 输出 [GitGraph] 供 UI 用 Canvas 绘制。
 */
internal object GitGraphBuilder {

    /**
     * 解析 `git log --pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%P%x1f%b%x1f%s%x1e`。
     * 字段用 0x1f 分隔、**记录用 0x1e (RS) 结尾**——多行 %b 正文不再把记录拆散；
     * 且 runGitRead 的 trim() 只会吃掉最末尾一个 \x1e，字段完整性不受影响
     * （原 AiCode 版按 \n 拆行 + %s 在前，浅克隆空 %P/%b 行尾控制符被 trim 吃掉致 parts<6 全丢）。
     * 字段序：0=hash 1=short 2=author 3=date 4=parents 5=body 6=subject。
     */
    fun parseGraphCommits(raw: String): List<GraphCommit> =
        raw.split('\u001e').mapNotNull { record ->
            // git log --pretty=format: 会在每条提交间自动插 \n（只有最后一条没有尾换行）——
            // 按 \x1e 拆记录后每条开头会残留这个换行，必须 trim 掉：否则 hash 带 \n，
            // 下游 `git diff $hash^ $hash` 会把换行后的半截 hash 当命令执行（真机实锤：
            // /bin/sh: not found 乱码 diff）。
            val parts = record.trim('\r', '\n').removeSuffix("\r").split('\u001f')
            if (parts.size < 7) null
            else {
                val parents = parts[4].split(' ').filter { it.isNotBlank() }
                GraphCommit(parts[0], parts[1], parts[2], parts[3], parts[6], parents = parents, body = parts[5])
            }
        }

    /**
     * 对完整提交列表重算泳道布局并组装 [GitGraph]。[refs] 为外部传入的全量 refs-by-commit 映射，
     * 过滤为只含已加载提交 hash 的条目，减少传给 UI 的数据量。
     */
    fun buildGraph(
        commits: List<GraphCommit>,
        refs: Map<String, List<GitGraphRef>>,
        hasMore: Boolean,
    ): GitGraph {
        if (commits.isEmpty()) return GitGraph.EMPTY
        val commitHashes = commits.mapTo(HashSet()) { it.hash }
        val filteredRefs = refs.filterKeys { it in commitHashes }
        val layout = computeLanes(commits)
        return GitGraph(
            commits,
            filteredRefs,
            layout.lanes,
            layout.edges,
            layout.activeTopLanes,
            layout.activeBottomLanes,
            layout.activeLanes,
            layout.maxLane,
            hasMore,
        )
    }

    /**
     * 纯 Kotlin 泳道分配算法（IDE 风格拓扑布局）。
     *
     * 维护「活跃泳道」数组 active，每个槽位记录当前占据该列的提交哈希。
     * 按提交从新到旧顺序处理：复用已占位泳道或取最左空闲槽；第一父延续主线，
     * 其余父分配/复用泳道并生成合并入边；根提交释放泳道。
     */
    private fun computeLanes(commits: List<GraphCommit>): GraphLayout {
        val commitMap = commits.associateBy { it.hash }
        val lanes = mutableMapOf<String, Int>()
        val edges = mutableListOf<GraphEdge>()
        val activeTopLanes = mutableMapOf<String, List<Int>>()
        val activeBottomLanes = mutableMapOf<String, List<Int>>()
        val activeLanes = mutableMapOf<String, List<Int>>()
        // 活跃泳道：槽位索引即列号，值为占据该列的提交哈希（或 null=空闲）。
        val active = mutableListOf<String?>()
        var maxLane = 0

        for (commit in commits) {
            // 当前提交是否已被某子提交的父引用占位。
            var lane = active.indexOf(commit.hash)
            if (lane < 0) {
                // 未占位：取最左空闲槽位。
                lane = active.indexOf(null)
                if (lane < 0) {
                    lane = active.size
                    active.add(commit.hash)
                } else {
                    active[lane] = commit.hash
                }
            }
            if (lane > maxLane) maxLane = lane
            lanes[commit.hash] = lane

            // 快照上半段（0 -> centerY）活跃泳道。
            val topSnapshot = active.mapIndexedNotNull { idx, h -> if (h != null) idx else null }
            activeTopLanes[commit.hash] = topSnapshot

            if (commit.parents.isEmpty()) {
                // 根提交：释放当前泳道后快照下半段。
                active[lane] = null
                val botSnapshot = active.mapIndexedNotNull { idx, h -> if (h != null) idx else null }
                activeBottomLanes[commit.hash] = botSnapshot
                activeLanes[commit.hash] = botSnapshot
                continue
            }

            // 第一父复用当前泳道（主线延续）。
            val parents = commit.parents
            val firstParent = parents[0]
            val firstParentLane = active.indexOf(firstParent)
            if (firstParentLane >= 0 && firstParentLane != lane) {
                edges.add(GraphEdge(lane, firstParentLane, lane, isMergeIn = false))
                active[lane] = null
            } else {
                edges.add(GraphEdge(lane, lane, lane, isMergeIn = false))
                active[lane] = firstParent
            }

            // 其余父（合并的第二个及以后）：优先复用活跃列表可继承的泳道，否则分配新泳道。
            for (i in 1 until parents.size) {
                val p = parents[i]
                val existing = active.indexOf(p)
                val pLane = if (existing >= 0) {
                    existing
                } else {
                    // 查找 active 中占位提交 X（非当前列），X 的父引用直接包含 p → 不抢列。
                    val reuse = active.indexOfFirst { aHash ->
                        aHash != null && active.indexOf(aHash) != lane && commitMap[aHash]?.parents?.contains(p) == true
                    }
                    if (reuse >= 0) {
                        reuse
                    } else {
                        val free = active.indexOf(null)
                        if (free < 0) {
                            active.add(p)
                            active.size - 1
                        } else {
                            active[free] = p
                            free
                        }
                    }
                }
                if (pLane > maxLane) maxLane = pLane
                edges.add(GraphEdge(lane, pLane, pLane, isMergeIn = true))
            }

            // 快照下半段（centerY -> height）活跃泳道。
            val botSnapshot = active.mapIndexedNotNull { idx, h -> if (h != null) idx else null }
            activeBottomLanes[commit.hash] = botSnapshot
            activeLanes[commit.hash] = botSnapshot
        }
        return GraphLayout(lanes, edges, activeTopLanes, activeBottomLanes, activeLanes, maxLane)
    }

    /** [computeLanes] 的输出：泳道映射 + 边列表 + 每行活跃泳道快照 + 最大列号。 */
    private class GraphLayout(
        val lanes: Map<String, Int>,
        val edges: List<GraphEdge>,
        val activeTopLanes: Map<String, List<Int>>,
        val activeBottomLanes: Map<String, List<Int>>,
        val activeLanes: Map<String, List<Int>>,
        val maxLane: Int,
    )
}
