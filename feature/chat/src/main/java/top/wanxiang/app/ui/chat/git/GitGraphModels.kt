package top.wanxiang.app.ui.chat.git

/**
 * Git 提交拓扑图领域模型（移植自 AiCode feature/git/domain/model）。
 * 由 ChatViewModel 解析 `git log`（带 %P 父哈希）与 `git for-each-ref` 输出，
 * 纯 Kotlin 算出泳道布局，供 UI 用 Canvas 绘制 IDE 风格分支彩色连线。
 */

/** 一条提交记录（拓扑图用）。parents：一个=普通提交，两个=合并，空=根提交。 */
data class GraphCommit(
    val hash: String,
    val shortHash: String,
    val author: String,
    val date: String,
    val message: String,
    /** 提交正文（message 之后的段落，无正文时为空串）。 */
    val body: String = "",
    /** 父提交完整哈希列表（按 git 输出顺序，第一父为主线）。 */
    val parents: List<String>,
) {
    /** 是否为合并提交（≥2 个父提交）。用于绘制合并节点。 */
    val isMerge: Boolean get() = parents.size >= 2
}

/** 指向某提交的一个引用（分支或标签）。用于在拓扑图节点旁标注分支名并着色。 */
data class GitGraphRef(
    val name: String,
    /** true=本地/远程分支，false=标签。 */
    val isBranch: Boolean,
    /** 是否为当前 HEAD 所在分支（仅本地分支可能为 true）。 */
    val isCurrent: Boolean,
    /** 是否为远程分支（refs/remotes 下）。 */
    val isRemote: Boolean,
)

/**
 * 一条连线：从 fromLane 泳道连到 toLane 泳道，对应相邻两个提交之间的父子关系。
 * 同列即竖线，跨列即分叉/合并的折线。颜色由泳道号映射调色板。
 */
data class GraphEdge(
    val fromLane: Int,
    val toLane: Int,
    /** 该边所属泳道号（决定颜色）。 */
    val lane: Int,
    /** true=合并入边（父支线从下方汇入本提交）；false=出边（本提交向父分叉）。 */
    val isMergeIn: Boolean,
)

/**
 * 拓扑图整体视图。
 *
 * @param commits 按时间从新到旧排列的提交列表。
 * @param refs 提交哈希 → 指向它的引用列表（分支/标签）。
 * @param lanes 提交哈希 → 其所在泳道列号（0 起）。
 * @param edges 相邻提交之间的所有连线（含分叉/合并跨泳道边），扁平有序按提交顺序生成。
 * @param activeTopLanes 每个提交 → 该行上半段需画竖线的泳道列号。
 * @param activeBottomLanes 每个提交 → 该行下半段需画竖线的泳道列号。
 * @param activeLanes 每个提交 → 贯穿泳道快照（兼容字段）。
 * @param maxLane 最大泳道列号，决定 Canvas 宽度。
 * @param hasMore 是否还有更旧的提交可分页加载。
 */
data class GitGraph(
    val commits: List<GraphCommit>,
    val refs: Map<String, List<GitGraphRef>>,
    val lanes: Map<String, Int>,
    val edges: List<GraphEdge>,
    val activeTopLanes: Map<String, List<Int>>,
    val activeBottomLanes: Map<String, List<Int>>,
    val activeLanes: Map<String, List<Int>>,
    val maxLane: Int,
    val hasMore: Boolean = false,
) {
    companion object {
        val EMPTY = GitGraph(emptyList(), emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyMap(), emptyMap(), 0, false)
    }
}
