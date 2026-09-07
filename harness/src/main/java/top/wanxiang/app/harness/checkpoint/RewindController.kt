package top.wanxiang.app.harness.checkpoint

import javax.inject.Inject
import javax.inject.Singleton
import top.wanxiang.app.harness.WorkspaceFileAccess

/**
 * 恢复编排（prepare/commit 两段式）。前端/未来 MCP 只驱动这一套 API，
 * 具体路径校验与落盘复用在 [WorkspaceFileAccess] 的安全层。
 *
 * - CODE：用 [CheckpointStore.planCodeRewind] 拿到方案，逐文件恢复（content==null 即删除）。
 * - CONVERSATION/BOTH：需要可选的对话 fork 处理器（[conversationRewinder]）在目标轮处派生新会话；
 *   未配置时为 partial 结果，不阻塞代码恢复。
 */
@Singleton
class RewindController @Inject constructor(
    private val store: CheckpointStore,
    private val fileAccess: WorkspaceFileAccess,
    private val conversationRewinder: ConversationRewinder? = null,
) {
    fun checkpoints(sessionId: String): List<CheckpointMeta> = store.checkpoints(sessionId)

    /** 用户轮次开始处调用：开启该轮 checkpoint。 */
    fun beginTurn(sessionId: String, prompt: String, anchorMessageId: String? = null) =
        store.beginTurn(sessionId, prompt, anchorMessageId)

    /** 会话结束/删除清理。 */
    fun dropSession(sessionId: String) = store.dropSession(sessionId)

    /** 只读规划，不落盘。 */
    fun prepare(sessionId: String, turn: Int, scope: RewindScope): RewindPlan = RewindPlan(
        sessionId = sessionId,
        turn = turn,
        scope = scope,
        fileSnaps = if (scope == RewindScope.CONVERSATION) emptyList() else store.planCodeRewind(sessionId, turn),
    )

    /** 执行已规划方案。workspace 用于 `withBase` 子工作区定位。 */
    suspend fun commit(plan: RewindPlan, workspace: String = ""): RewindResult {
        val activeFileAccess = if (workspace.isNotBlank()) fileAccess.withBase(workspace) else fileAccess
        var restored = 0
        var deleted = 0
        val problems = mutableListOf<String>()
        for (snap in plan.fileSnaps) {
            val ok = if (snap.content == null) {
                activeFileAccess.delete(snap.path)
            } else {
                activeFileAccess.write(snap.path, snap.content).isSuccess
            }
            if (ok) {
                if (snap.content == null) deleted++ else restored++
            } else {
                problems += snap.path
            }
        }

        var partial = problems.isNotEmpty()
        var note: String? = null
        var forkedSessionId: String? = null
        if (plan.scope != RewindScope.CODE) {
            forkedSessionId = conversationRewinder?.rewindConversation(plan.sessionId, plan.turn)
            if (forkedSessionId == null) {
                partial = true
                note = "对话尚未在目标轮派生新会话（未配置对话 fork 处理器或该轮无锚点）；代码恢复已完成。"
            }
        }
        if (problems.isNotEmpty()) {
            note = (note?.let { "$it\n" } ?: "") + "以下文件恢复失败：${problems.joinToString("；")}"
        }
        return RewindResult(
            filesRestored = restored,
            filesDeleted = deleted,
            partial = partial,
            note = note,
            forkedSessionId = forkedSessionId,
        )
    }
}

/** 在指定轮处派生新会话的可选回调（多态注入点，避免 RewindController 反向依赖会话存储）。 */
fun interface ConversationRewinder {
    /** @return 新会话 id；返回 null 表示未能派生。 */
    suspend fun rewindConversation(sessionId: String, turn: Int): String?
}