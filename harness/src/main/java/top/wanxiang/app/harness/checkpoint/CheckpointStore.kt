package top.wanxiang.app.harness.checkpoint

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 文件快照的按会话存储与重放规划。不触碰 git：
 * - 每个用户轮次 [beginTurn] 开启一个 checkpoint，仅记录该轮被 write/edit 触碰文件在触碰前的轮初内容；
 * - 同一轮同一路径去重（只保留第一次触碰的轮初内容）；
 * - [beginTurn] 切换时关闭上一轮 checkpoint，并保留最近 [MAX_KEPT] 轮；
 * - [planCodeRewind] 生成从目标轮起逐 path 取最早快照的恢复方案，供 RewindController 落盘。
 *
 * 落盘布局（[Persistence] 配置了根目录时启用）：
 * `<root>/<sessionId>/<turn>.index.json` + `<turn>/<seq>-<safeName>`（内容文件，null 快照无内容文件）。
 * 关轮时异步写入（失败仅放弃持久化，不影响内存态）；启动后首次访问该会话时从磁盘恢复。
 * 未配置根目录时退化为纯内存（进程被杀后 rewind 丢失），行为与旧版一致。
 */
@Singleton
class CheckpointStore @Inject constructor() {

    /** 持久化配置；null = 纯内存模式。由宿主在初始化期一次性注入。 */
    @Volatile
    var persistence: Persistence? = null

    /** 磁盘恢复标记：会话首次访问时懒恢复，避免启动期全量 IO。 */
    private val restoredSessions = ConcurrentHashMap.newKeySet<String>()

    private val sessions = ConcurrentHashMap<String, SessionState>()

    private val json = Json { ignoreUnknownKeys = true }

    /** 磁盘持久化接缝：把轮 checkpoint 以索引 + 内容文件形式落盘/读回。 */
    interface Persistence {
        /** 落盘一个已关闭的轮 checkpoint（content 为 null 的快照不产生内容文件）。 */
        fun write(sessionId: String, checkpoint: Checkpoint)

        /** 读取该会话已落盘的全部轮 checkpoint（升序）。 */
        fun readAll(sessionId: String): List<Checkpoint>

        /** 删除该会话的全部持久化数据（会话删除时）。 */
        fun delete(sessionId: String)
    }

    private class SessionState {
        val checkpoints = mutableListOf<Checkpoint>()
        var active: MutableMap<String, FileSnap>? = null
        var activeTurn = 0
        var activePrompt = ""
        var activeAnchorMessageId: String? = null
        var open = false
        /** 已分配的最大轮号；内存裁剪（MAX_KEPT）后 size 会回绕，轮号必须独立单调递增。 */
        var lastTurn = -1
    }

    /** 开启一个新的用户轮次 checkpoint，并关闭上一轮（若有）。 */
    @Synchronized
    fun beginTurn(sessionId: String, prompt: String, anchorMessageId: String? = null) {
        val state = stateOf(sessionId)
        closeTurn(sessionId, state)
        state.activeTurn = state.lastTurn + 1
        state.activePrompt = prompt.ifBlank { "（空白输入）" }
        state.activeAnchorMessageId = anchorMessageId
        state.active = LinkedHashMap()
        state.open = true
    }

    /** 记录某路径在该轮"触碰前"的内容；无活动轮或路径已记录时忽略。 */
    @Synchronized
    fun capture(sessionId: String, path: String, before: String?): Boolean {
        val state = sessions[sessionId] ?: return false
        val active = state.active ?: return false
        if (path in active) return false
        active[path] = FileSnap(path, before)
        return true
    }

    /** 强制关闭当前活动轮（无触碰则丢弃空轮）。 */
    @Synchronized
    fun endTurn(sessionId: String) {
        sessions[sessionId]?.let { closeTurn(sessionId, it) }
    }

    /** 会话删除/重建时清理。 */
    @Synchronized
    fun dropSession(sessionId: String) {
        sessions.remove(sessionId)
        restoredSessions.remove(sessionId)
        persistence?.delete(sessionId)
    }

    @Synchronized
    fun checkpoints(sessionId: String): List<CheckpointMeta> =
        stateOf(sessionId).checkpoints.map {
            CheckpointMeta(it.turn, it.time, it.prompt, it.files.map { snap -> snap.path }, it.anchorMessageId)
        }

    /** 查询某轮 checkpoint 的用户消息锚点（供对话 fork 定位）。 */
    @Synchronized
    fun anchorMessageIdOf(sessionId: String, turn: Int): String? =
        stateOf(sessionId).checkpoints.firstOrNull { it.turn == turn }?.anchorMessageId

    /**
     * 规划"代码回滚"：撤回到 [turn]，即撤销该轮及之后的所有写改动。
     * 对每路径取 [turn] 起最早的轮初内容；路径本身是 [turn] 前创建的、
     * 之后仅被改动，则该最早快照即 [turn] 的轮初内容。
     */
    @Synchronized
    fun planCodeRewind(sessionId: String, turn: Int): List<FileSnap> {
        val state = stateOf(sessionId)
        val open = state.active
        val totalTurns = state.checkpoints.size + if (open != null) 1 else 0
        if (turn < 0 || turn >= totalTurns) return emptyList()
        val merged = LinkedHashMap<String, FileSnap>()
        for (index in turn until state.checkpoints.size) {
            for (snap in state.checkpoints[index].files) {
                merged.putIfAbsent(snap.path, snap)
            }
        }
        // 当前（尚未关闭）的轮次也纳入回滚范围
        if (open != null && turn <= state.checkpoints.size) {
            for (snap in open.values) merged.putIfAbsent(snap.path, snap)
        }
        return merged.values.toList()
    }

    /** 首次访问时从磁盘恢复该会话的已关闭轮；后续访问直接用内存态。 */
    private fun stateOf(sessionId: String): SessionState {
        val state = sessions.getOrPut(sessionId) { SessionState() }
        val disk = persistence
        if (disk != null && restoredSessions.add(sessionId)) {
            runCatching {
                val restored = disk.readAll(sessionId)
                // 内存态非空说明本进程已有新轮（恢复发生在运行中），只补齐磁盘里更早的轮
                val existingTurns = state.checkpoints.mapTo(hashSetOf()) { it.turn }
                val missing = restored.filter { it.turn !in existingTurns }
                if (missing.isNotEmpty()) {
                    state.checkpoints.addAll(0, missing.sortedBy { it.turn })
                }
                restored.maxOfOrNull { it.turn }?.let { if (it > state.lastTurn) state.lastTurn = it }
            }
        }
        return state
    }

    private fun closeTurn(sessionId: String, state: SessionState) {
        val active = state.active ?: return
        if (active.isNotEmpty()) {
            val checkpoint = Checkpoint(
                turn = state.activeTurn,
                time = System.currentTimeMillis(),
                prompt = state.activePrompt,
                files = active.values.toList(),
                anchorMessageId = state.activeAnchorMessageId,
            )
            state.checkpoints.add(checkpoint)
            while (state.checkpoints.size > MAX_KEPT) state.checkpoints.removeAt(0)
            if (checkpoint.turn > state.lastTurn) state.lastTurn = checkpoint.turn
            // 同步落盘：每轮一次、单文件 ≤1MiB（快照捕获上限），失败只放弃持久化不影响内存态
            persistence?.let { disk -> runCatching { disk.write(sessionId, checkpoint) } }
        }
        state.active = null
        state.activeAnchorMessageId = null
        state.open = false
    }

    companion object {
        const val MAX_KEPT = 100
    }
}

/**
 * 默认文件系统持久化：`<root>/<sessionId>/<turn>.index.json` + `<root>/<sessionId>/<turn>/<seq>.snap`。
 * 根目录由宿主指定（应用私有目录，不经 SAF；不会出现在 PRoot 工作区中，模型不可见）。
 */
class FileCheckpointPersistence(private val root: File) : CheckpointStore.Persistence {

    @Serializable
    private data class IndexEntry(
        val turn: Int,
        val time: Long,
        val prompt: String,
        val anchorMessageId: String? = null,
        /** seq -> 快照内容文件名；content 为 null（文件不存在）的快照无条目。 */
        val files: Map<Int, String> = emptyMap(),
        /** seq -> 快照原始路径。 */
        val paths: Map<Int, String> = emptyMap(),
        /** 记录 content==null 的 seq（恢复时区分"不存在"与"空内容文件"）。 */
        val absent: List<Int> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun write(sessionId: String, checkpoint: Checkpoint) {
        val sessionDir = File(root, sessionId)
        val turnDir = File(sessionDir, checkpoint.turn.toString())
        turnDir.mkdirs()
        val entries = mutableMapOf<Int, String>()
        val paths = mutableMapOf<Int, String>()
        val absent = mutableListOf<Int>()
        checkpoint.files.forEachIndexed { seq, snap ->
            paths[seq] = snap.path
            val content = snap.content ?: run { absent += seq; return@forEachIndexed }
            val file = File(turnDir, "$seq.snap")
            file.writeText(content, Charsets.UTF_8)
            entries[seq] = file.name
        }
        File(sessionDir, "${checkpoint.turn}.index.json").writeText(
            json.encodeToString(
                IndexEntry(
                    turn = checkpoint.turn,
                    time = checkpoint.time,
                    prompt = checkpoint.prompt,
                    anchorMessageId = checkpoint.anchorMessageId,
                    files = entries,
                    paths = paths,
                    absent = absent,
                ),
            ),
        )
        // 与内存保留窗口对齐：超龄轮的索引与内容目录一并清掉
        val keptFloor = (checkpoint.turn - CheckpointStore.MAX_KEPT + 1).coerceAtLeast(0)
        sessionDir.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { dir ->
                dir.name.toIntOrNull()?.let { turn -> if (turn < keptFloor) dir.deleteRecursively() }
            }
        sessionDir.listFiles { file -> file.name.endsWith(".index.json") }
            ?.forEach { index ->
                index.name.removeSuffix(".index.json").toIntOrNull()?.let { turn ->
                    if (turn < keptFloor) index.delete()
                }
            }
    }

    override fun readAll(sessionId: String): List<Checkpoint> {
        val sessionDir = File(root, sessionId)
        val indexFiles = sessionDir.listFiles { file -> file.name.endsWith(".index.json") } ?: return emptyList()
        return indexFiles.mapNotNull { indexFile ->
            runCatching {
                val entry = json.decodeFromString<IndexEntry>(indexFile.readText(Charsets.UTF_8))
                val snaps = entry.paths.keys.sorted().mapNotNull { seq ->
                    val path = entry.paths.getValue(seq)
                    val content: String? = if (seq in entry.absent) {
                        null
                    } else {
                        val snapFile = File(sessionDir, "${entry.turn}/${entry.files[seq] ?: return@mapNotNull null}")
                        if (snapFile.isFile) snapFile.readText(Charsets.UTF_8) else null
                    }
                    FileSnap(path, content)
                }
                Checkpoint(
                    turn = entry.turn,
                    time = entry.time,
                    prompt = entry.prompt,
                    files = snaps,
                    anchorMessageId = entry.anchorMessageId,
                )
            }.getOrNull()
        }.sortedBy { it.turn }
    }

    override fun delete(sessionId: String) {
        File(root, sessionId).deleteRecursively()
    }
}
