package top.wanxiang.app.ui.chat.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提交拓扑图的解析与泳道布局（[GitGraphBuilder]，移植自 AiCode 原版测试）。纯 JVM 逻辑：
 * - parseGraphCommits：`git log --pretty=format:%H%x1f...` 输出 → [GraphCommit] 列表
 * - buildGraph：提交列表 + refs 映射 + hasMore → [GitGraph]（refs 过滤 + 泳道布局）
 */
class GitGraphBuilderTest {

    // ── parseGraphCommits：字段解析（%x1e 记录分隔 + 字段序 H/h/an/ar/P/b/s）──

    @Test
    fun parsesFullRecord_allFieldsIncludingBody() {
        val raw = "a1b2c3d4e5f6\u001fa1b2c3d\u001fAlice\u001f2 days ago\u001fp1p2p3 q1q2q3\u001f正文第一行\u001ffix: 问题\u001e"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(1, commits.size)
        assertEquals(
            GraphCommit(
                hash = "a1b2c3d4e5f6",
                shortHash = "a1b2c3d",
                author = "Alice",
                date = "2 days ago",
                message = "fix: 问题",
                parents = listOf("p1p2p3", "q1q2q3"),
                body = "正文第一行",
            ),
            commits[0],
        )
    }

    @Test
    fun parsesRecord_emptyParentsAndBody_stillSevenFields() {
        // 浅克隆根提交：空 %P 与空 %b 的行尾控制符不再被 trim 吃掉（记录以 \x1e 结尾、%s 在其前）
        val raw = "a1b2c3d4e5f6\u001fa1b2c3d\u001fAlice\u001f2 days ago\u001f\u001f\u001froot msg\u001e"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(1, commits.size)
        assertTrue(commits[0].parents.isEmpty())
        assertEquals("root msg", commits[0].message)
        assertEquals("", commits[0].body)
    }

    @Test
    fun multilineBody_staysInOneRecord() {
        // 旧版按 \n 拆行会把多行 body 拆散；%x1e 记录分隔彻底解决
        val raw = "h1h1h1\u001fh1h1h1\u001fAlice\u001fd\u001fp1p1p1\u001f第一行\n第二行\n第三行\u001fsubject here\u001e"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(1, commits.size)
        assertEquals("subject here", commits[0].message)
        assertEquals("第一行\n第二行\n第三行", commits[0].body)
        assertEquals(listOf("p1p1p1"), commits[0].parents)
    }

    @Test
    fun multipleRecords_splitByRecordSeparator() {
        val raw = "aaa1\u001faaa1\u001fAlice\u001fd1\u001fbbb1\u001f\u001fc2\u001e" +
            "bbb1\u001fbbb1\u001fBob\u001fd2\u001fccc1\u001f\u001fc1\u001e"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(2, commits.size)
        assertEquals("aaa1", commits[0].hash)
        assertEquals("c2", commits[0].message)
        assertEquals("bbb1", commits[1].hash)
    }

    @Test
    fun shortRecordBeforeGoodRecord_droppedWithoutPoisoning() {
        // 第一条字段不足（如输出被截断）→ 丢弃；第二条完整记录不受影响
        val raw = "broken\u001e" +
            "d4e5f6\u001fd4e5f6\u001fBob\u001f3 days ago\u001fr0r0r0\u001f\u001ffeat: 2\u001e"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(1, commits.size)
        assertEquals("d4e5f6", commits[0].hash)
    }

    @Test
    fun parentsSeparatedByMultipleSpaces_filteredToHashList() {
        val raw = "a1b2c3\u001fa1b2c3\u001fAlice\u001f2 days ago\u001fp1  p2  \u001f\u001fmerge\u001e"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(listOf("p1", "p2"), commits[0].parents)
        assertTrue(commits[0].isMerge)
    }

    // ── parseGraphCommits：容错 ─────────────────────────────────

    @Test
    fun shortRecord_lessThan7Fields_returnsNull() {
        val raw = "a1b2c3\u001fa1b2c3\u001fAlice\u001f2 days ago\u001ffix"
        assertEquals(0, GitGraphBuilder.parseGraphCommits(raw).size)
    }

    @Test
    fun emptyRaw_returnsEmpty() {
        assertTrue(GitGraphBuilder.parseGraphCommits("").isEmpty())
    }

    // ── parseGraphCommits：历史 bug 回归 ────────────────────────

    @Test
    fun messageContainingPipe_doesNotSplitParents() {
        val raw = "m1m2m3\u001fm1m2m3\u001fAlice\u001f2 days ago\u001fp1p2p3 p4p5p6\u001f\u001ffix: merge | conflict\u001e"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(1, commits.size)
        assertEquals(listOf("p1p2p3", "p4p5p6"), commits[0].parents)
        assertTrue(commits[0].isMerge)
    }

    @Test
    fun messageContainingPipe_simpleCommit_staysSingleParent() {
        val raw = "a1b2c3\u001fa1b2c3\u001fAlice\u001f2 days ago\u001fr0r0r0\u001f\u001fupdate a | b\u001e"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(listOf("r0r0r0"), commits[0].parents)
        assertFalse(commits[0].isMerge)
    }

    // ── buildGraph：空输入 ──────────────────────────────────────

    @Test
    fun emptyCommits_returnsEmptyGraph() {
        assertEquals(GitGraph.EMPTY, GitGraphBuilder.buildGraph(emptyList(), emptyMap(), hasMore = false))
    }

    // ── buildGraph：refs 过滤 ───────────────────────────────────

    @Test
    fun refs_filteredToLoadedCommitHashes() {
        val loaded = GraphCommit("a1b2c3", "a1b2c3", "Alice", "d", "fix", parents = emptyList())
        val refs = mapOf(
            "a1b2c3" to listOf(GitGraphRef("main", isBranch = true, isCurrent = true, isRemote = false)),
            "unloaded1" to listOf(GitGraphRef("other", isBranch = true, isCurrent = false, isRemote = false)),
        )
        val graph = GitGraphBuilder.buildGraph(listOf(loaded), refs, hasMore = true)
        assertEquals(setOf("a1b2c3"), graph.refs.keys)
        assertEquals("main", graph.refs.getValue("a1b2c3")[0].name)
    }

    @Test
    fun refs_allUnloaded_returnsEmptyRefs() {
        val loaded = GraphCommit("a1b2c3", "a1b2c3", "Alice", "d", "fix", parents = emptyList())
        val refs = mapOf(
            "unloaded1" to listOf(GitGraphRef("x", isBranch = true, isCurrent = false, isRemote = false)),
            "unloaded2" to listOf(GitGraphRef("y", isBranch = true, isCurrent = false, isRemote = false)),
        )
        val graph = GitGraphBuilder.buildGraph(listOf(loaded), refs, hasMore = false)
        assertTrue(graph.refs.isEmpty())
    }

    @Test
    fun hasMore_passedThrough() {
        val loaded = GraphCommit("a1b2c3", "a1b2c3", "Alice", "d", "fix", parents = emptyList())
        assertTrue(GitGraphBuilder.buildGraph(listOf(loaded), emptyMap(), hasMore = true).hasMore)
        assertFalse(GitGraphBuilder.buildGraph(listOf(loaded), emptyMap(), hasMore = false).hasMore)
    }

    // ── buildGraph：泳道布局 ────────────────────────────────────

    @Test
    fun simpleChain_computesSingleLane() {
        val head = GraphCommit("h1h2h3", "h1h2h3", "Alice", "d", "c2", parents = listOf("r0r0r0"))
        val root = GraphCommit("r0r0r0", "r0r0r0", "Bob", "d", "c1", parents = emptyList())
        val graph = GitGraphBuilder.buildGraph(listOf(head, root), emptyMap(), hasMore = false)
        assertEquals(mapOf("h1h2h3" to 0, "r0r0r0" to 0), graph.lanes)
        assertEquals(listOf(GraphEdge(0, 0, 0, isMergeIn = false)), graph.edges)
        assertEquals(0, graph.maxLane)
    }

    @Test
    fun rootCommit_noEdges() {
        val root = GraphCommit("r0r0r0", "r0r0r0", "Alice", "d", "root", parents = emptyList())
        val graph = GitGraphBuilder.buildGraph(listOf(root), emptyMap(), hasMore = false)
        assertTrue(graph.edges.isEmpty())
        assertEquals(mapOf("r0r0r0" to 0), graph.lanes)
        assertEquals(0, graph.maxLane)
    }

    @Test
    fun fork_branchComputesCrossEdge() {
        val c1 = GraphCommit("c1c1c1", "c1c1c1", "Alice", "d", "branch A", parents = listOf("r0r0r0"))
        val c2 = GraphCommit("c2c2c2", "c2c2c2", "Bob", "d", "branch B", parents = listOf("r0r0r0"))
        val root = GraphCommit("r0r0r0", "r0r0r0", "Dan", "d", "root", parents = emptyList())
        val graph = GitGraphBuilder.buildGraph(listOf(c1, c2, root), emptyMap(), hasMore = false)
        assertEquals(mapOf("c1c1c1" to 0, "c2c2c2" to 1, "r0r0r0" to 0), graph.lanes)
        assertEquals(
            listOf(
                GraphEdge(0, 0, 0, isMergeIn = false),
                GraphEdge(1, 0, 1, isMergeIn = false),
            ),
            graph.edges,
        )
        assertEquals(1, graph.maxLane)
    }

    @Test
    fun mergeCommit_computesMergeInEdge() {
        val merge = GraphCommit("m1m1m1", "m1m1m1", "Alice", "d", "Merge", parents = listOf("p1p1p1", "p2p2p2"))
        val p1 = GraphCommit("p1p1p1", "p1p1p1", "Bob", "d", "feat x", parents = listOf("r0r0r0"))
        val p2 = GraphCommit("p2p2p2", "p2p2p2", "Carol", "d", "feat y", parents = listOf("r0r0r0"))
        val root = GraphCommit("r0r0r0", "r0r0r0", "Dan", "d", "root", parents = emptyList())
        val graph = GitGraphBuilder.buildGraph(listOf(merge, p1, p2, root), emptyMap(), hasMore = false)
        assertTrue(graph.commits[0].isMerge)
        assertEquals(mapOf("m1m1m1" to 0, "p1p1p1" to 0, "p2p2p2" to 1, "r0r0r0" to 0), graph.lanes)
        assertEquals(4, graph.edges.size)
        assertTrue(graph.edges.any { it.isMergeIn && it.fromLane == 0 && it.toLane == 1 && it.lane == 1 })
        assertEquals(1, graph.maxLane)
    }
}
