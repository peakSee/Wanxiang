package top.wanxiang.app.harness.checkpoint

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.wanxiang.app.core.database.HarnessEntryEntity
import top.wanxiang.app.core.database.HarnessLaneEntity
import top.wanxiang.app.core.database.HarnessOperationEntity
import top.wanxiang.app.core.database.HarnessQueueItemEntity
import top.wanxiang.app.core.database.HarnessRuntimeRepository
import top.wanxiang.app.core.database.HarnessSessionEntity
import top.wanxiang.app.core.database.HarnessSessionRepository
import top.wanxiang.app.core.database.HarnessUsageEntity
import top.wanxiang.app.harness.WorkspaceFileAccess

class SessionForkConversationRewinderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // ---- 测试基建：内存版会话树 ----

    private class FakeSessionRepository : HarnessSessionRepository {
        val sessions = LinkedHashMap<String, HarnessSessionEntity>()
        val upserts = mutableListOf<String>()

        override fun observeAll(): Flow<List<HarnessSessionEntity>> = flowOf(sessions.values.toList())
        override suspend fun findById(id: String) = sessions[id]
        override suspend fun upsert(session: HarnessSessionEntity) {
            sessions[session.id] = session
            upserts += session.id
        }

        override suspend fun touch(id: String, updatedAt: Long) = Unit
        override suspend fun rename(id: String, title: String, updatedAt: Long) = Unit
        override suspend fun setApprovalMode(id: String, approvalMode: String, updatedAt: Long) = Unit
        override suspend fun setApprovalModeForAll(approvalMode: String, updatedAt: Long) = Unit
        override suspend fun setModelSelection(id: String, modelId: String?, modelVariant: String?, updatedAt: Long) = Unit
        override suspend fun deleteSession(id: String) { sessions.remove(id) }
        override suspend fun countInRange(start: Long?, end: Long?): Int = 0
        override suspend fun listAll() = sessions.values.toList()
    }

    private class FakeRuntimeRepository : HarnessRuntimeRepository {
        val lanes = LinkedHashMap<Pair<String, String>, HarnessLaneEntity>()
        val entries = mutableListOf<HarnessEntryEntity>()

        override suspend fun ensureLane(sessionId: String, laneName: String, atEntryId: String?): HarnessLaneEntity {
            lanes[sessionId to laneName]?.let { return it }
            val lane = HarnessLaneEntity(sessionId = sessionId, name = laneName, leafId = atEntryId, updatedAt = 0L)
            lanes[sessionId to laneName] = lane
            return lane
        }

        override suspend fun findLane(sessionId: String, laneName: String) = lanes[sessionId to laneName]
        override fun observeLanes(sessionId: String): Flow<List<HarnessLaneEntity>> = flowOf(emptyList())
        override suspend fun listEntries(sessionId: String) = entries.filter { it.sessionId == sessionId }
        override suspend fun listEntriesInRange(start: Long?, end: Long?) =
            entries.filter { (start == null || it.createdAt >= start) && (end == null || it.createdAt < end) }

        override suspend fun countEntriesInRange(start: Long?, end: Long?) = listEntriesInRange(start, end).size
        override suspend fun branch(sessionId: String, leafId: String?): List<HarnessEntryEntity> {
            if (leafId == null) return emptyList()
            val byId = entries.associateBy { it.id }
            val chain = ArrayList<HarnessEntryEntity>()
            var cursor: String? = leafId
            while (cursor != null) {
                val entry = byId[cursor] ?: error("Missing entry $cursor")
                chain += entry
                cursor = entry.parentId
            }
            return chain.asReversed()
        }

        override suspend fun appendToLane(sessionId: String, laneName: String, entry: HarnessEntryEntity) {
            check(entry.parentId == lanes.getValue(sessionId to laneName).leafId) { "parent mismatch" }
            entries += entry
            lanes[sessionId to laneName] = lanes.getValue(sessionId to laneName).copy(leafId = entry.id)
        }

        override suspend fun moveLane(sessionId: String, laneName: String, leafId: String?) = Unit
        override suspend fun clearLaneOperation(sessionId: String, laneName: String) = Unit
        override suspend fun findOperation(operationId: String): HarnessOperationEntity? = null
        override suspend fun listActiveOperations(sessionId: String) = emptyList<HarnessOperationEntity>()
        override suspend fun acceptOperation(entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity) = Unit
        override suspend fun acceptQueuedOperation(queueItemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity) = Unit
        override suspend fun beginOperation(lane: HarnessLaneEntity, operation: HarnessOperationEntity) = Unit
        override suspend fun saveOperation(operation: HarnessOperationEntity) = Unit
        override suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?, operation: HarnessOperationEntity, lane: HarnessLaneEntity) = Unit
        override suspend fun finishOperation(result: top.wanxiang.app.core.database.HarnessLaneResultEntity, lane: HarnessLaneEntity) = Unit
        override suspend fun enqueue(item: HarnessQueueItemEntity) = Unit
        override suspend fun listQueue(sessionId: String, laneName: String, queueType: String) = emptyList<HarnessQueueItemEntity>()
        override suspend fun listAllQueues(sessionId: String, laneName: String) = emptyList<HarnessQueueItemEntity>()
        override suspend fun cancelQueued(itemId: String) = Unit
        override suspend fun clearQueue(sessionId: String, laneName: String, queueType: String) = Unit
        override suspend fun consumeQueued(itemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity) = Unit
        override suspend fun recordUsage(usage: HarnessUsageEntity) = Unit
        override suspend fun listUsage(sessionId: String) = emptyList<HarnessUsageEntity>()
        override suspend fun deleteSessionData(sessionId: String) = Unit
    }

    /** 顺着主 lane 追加一条消息 entry。 */
    private suspend fun appendMessage(
        repo: FakeRuntimeRepository,
        sessionId: String,
        id: String,
        createdAt: Long,
        customType: String = "user",
    ) {
        val entry = HarnessEntryEntity(
            sequence = 0,
            id = id,
            sessionId = sessionId,
            parentId = repo.lanes.getValue(sessionId to "main").leafId,
            createdAt = createdAt,
            entryType = "message",
            customType = customType,
            payloadJson = """{"type":"$customType","id":"$id","createdAt":$createdAt,"text":"m"}""",
        )
        repo.appendToLane(sessionId, "main", entry)
    }

    private fun sourceSession() = HarnessSessionEntity(
        id = "src",
        title = "源会话",
        createdAt = 1L,
        updatedAt = 2L,
        modelId = "m1",
        modelVariant = "v1",
        workspace = "/ws/demo",
    )

    @Test
    fun `fork copies branch prefix before anchor with remapped ids and rebuilt parent chain`() = runBlocking {
        val sessions = FakeSessionRepository()
        val repo = FakeRuntimeRepository()
        val store = CheckpointStore()
        sessions.upsert(sourceSession())

        // 会话树：u1 → a1 → u2(turn1 锚点) → a2
        repo.ensureLane("src", "main")
        appendMessage(repo, "src", "u1", 10)
        appendMessage(repo, "src", "a1", 11, customType = "assistant")
        appendMessage(repo, "src", "u2", 12)
        appendMessage(repo, "src", "a2", 13, customType = "assistant")

        // turn0 无写触碰不入 checkpoint；只有带锚点的轮才有对话回退
        store.beginTurn("src", "第二轮", "u2")
        store.capture("src", "demo.txt", "old")
        store.beginTurn("src", "第三轮", "a2") // 关闭上一轮（turn0 = 第二轮）

        val rewinder = SessionForkConversationRewinder(sessions, repo, store)
        val forkedId = checkNotNull(rewinder.rewindConversation("src", 0))
        // 新会话：复制 u1、a1（锚点 u2 之前的前缀），entry id 全新、父链重建
        val forked = repo.entries.filter { it.sessionId == forkedId }
        assertEquals(listOf("user", "assistant"), forked.map { it.customType })
        assertTrue(forked.all { it.id !in setOf("u1", "a1", "u2", "a2") })
        assertNull(forked.first().parentId)
        assertEquals(forked[0].id, forked[1].parentId)
        assertEquals(forked.last().id, repo.lanes.getValue(forkedId to "main").leafId)
        // 元数据继承
        val forkedSession = sessions.sessions.getValue(forkedId)
        assertEquals(sourceSession().modelId, forkedSession.modelId)
        assertEquals(sourceSession().workspace, forkedSession.workspace)
        assertTrue(forkedSession.title.contains("回退"))
        // 原会话树不受影响
        assertEquals(4, repo.entries.filter { it.sessionId == "src" }.size)
    }

    @Test
    fun `fork returns null when session anchor or branch prefix is missing`() = runBlocking {
        val sessions = FakeSessionRepository()
        val repo = FakeRuntimeRepository()
        val store = CheckpointStore()
        sessions.upsert(sourceSession())

        repo.ensureLane("src", "main")
        appendMessage(repo, "src", "u1", 10)
        store.beginTurn("src", "p", "u1")
        store.capture("src", "demo.txt", "old")
        store.beginTurn("src", "p2") // 关闭 turn0

        val rewinder = SessionForkConversationRewinder(sessions, repo, store)
        // 会话不存在
        assertNull(rewinder.rewindConversation("nope", 0))
        // 轮次无锚点（无写触碰的轮不入 checkpoint）
        assertNull(rewinder.rewindConversation("src", 99))
        // 锚点是分支首条消息（前缀为空 → 无可回退）
        assertNull(rewinder.rewindConversation("src", 0))
    }

    @Test
    fun `rewind controller BOTH scope now reports forked session id`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val sessions = FakeSessionRepository()
        val repo = FakeRuntimeRepository()
        val store = CheckpointStore()
        sessions.upsert(sourceSession())

        repo.ensureLane("src", "main")
        appendMessage(repo, "src", "u1", 10)
        appendMessage(repo, "src", "a1", 11)
        appendMessage(repo, "src", "u2", 12)

        store.beginTurn("src", "第二轮", "u2")
        store.capture("src", "demo.txt", "old")
        WorkspaceFileAccess(root).write("demo.txt", "new")
        store.beginTurn("src", "第三轮") // 关闭 turn0

        val rewinder = SessionForkConversationRewinder(sessions, repo, store)
        val rc = RewindController(store, WorkspaceFileAccess(root), rewinder)

        val result = rc.commit(rc.prepare("src", 0, RewindScope.BOTH))

        assertFalse(result.partial)
        assertEquals(1, result.filesRestored)
        assertEquals("old", File(root, "demo.txt").readText())
        assertNotNull(result.forkedSessionId)
        assertTrue(sessions.upserts.contains(result.forkedSessionId))
    }
}
