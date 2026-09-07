package top.wanxiang.app.harness.checkpoint

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import top.wanxiang.app.core.database.HarnessEntryEntity
import top.wanxiang.app.core.database.HarnessRuntimeRepository
import top.wanxiang.app.core.database.HarnessSessionRepository

/**
 * [ConversationRewinder] 默认实现：在目标轮的用户消息边界处派生新会话。
 *
 * - 由 [CheckpointStore.anchorMessageIdOf] 拿到该轮用户消息 entry id（锚点）；
 * - 新会话复制主 lane 分支上锚点之前的全部消息（id 重映射、parentId 链重建），
 *   即"撤回该轮及之后对话"——原会话保持不动（会话树本就不可变，丢弃的分支仍留存）；
 * - 新会话继承原会话的模型/工作区/工程类型/审批模式，用户可直接续聊。
 */
@Singleton
class SessionForkConversationRewinder @Inject constructor(
    private val sessionDao: HarnessSessionRepository,
    private val runtimeRepo: HarnessRuntimeRepository,
    private val checkpointStore: CheckpointStore,
) : ConversationRewinder {

    override suspend fun rewindConversation(sessionId: String, turn: Int): String? {
        val source = sessionDao.findById(sessionId) ?: return null
        val anchorMessageId = checkpointStore.anchorMessageIdOf(sessionId, turn) ?: return null
        val lane = runtimeRepo.ensureLane(sessionId, MAIN_LANE)
        val entries = runtimeRepo.branch(sessionId, lane.leafId)
        val anchorIndex = entries.indexOfFirst { it.id == anchorMessageId }
        // 锚点必须是当前分支上的消息；锚点前无内容则无可回退
        if (anchorIndex <= 0) return null
        val keep = entries.subList(0, anchorIndex)

        val now = System.currentTimeMillis()
        val forkedSessionId = UUID.randomUUID().toString()
        sessionDao.upsert(
            source.copy(
                id = forkedSessionId,
                title = "${source.title} · 回退分支",
                createdAt = now,
                updatedAt = now,
            ),
        )
        runtimeRepo.ensureLane(forkedSessionId, MAIN_LANE)

        // 复制锚点前的分支（主 lane 分支为线性 parentId 链），entry id 全局唯一 → 全部换新并重映射父链
        val idRemap = HashMap<String, String>(keep.size * 2)
        for (entry in keep) {
            val newEntryId = UUID.randomUUID().toString()
            idRemap[entry.id] = newEntryId
            runtimeRepo.appendToLane(
                forkedSessionId,
                MAIN_LANE,
                entry.copy(
                    sequence = 0,
                    id = newEntryId,
                    sessionId = forkedSessionId,
                    parentId = entry.parentId?.let { idRemap[it] },
                ),
            )
        }
        return forkedSessionId
    }

    private companion object {
        const val MAIN_LANE = "main"
    }
}
