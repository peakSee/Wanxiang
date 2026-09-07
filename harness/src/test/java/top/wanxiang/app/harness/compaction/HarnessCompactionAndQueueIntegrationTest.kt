package top.wanxiang.app.harness.compaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wanxiang.app.core.database.AppDatabase
import top.wanxiang.app.core.database.RoomHarnessRuntimeRepository
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.PendingMessage
import top.wanxiang.app.harness.UserMessage
import top.wanxiang.app.harness.queue.PromptQueue
import top.wanxiang.app.harness.queue.PromptQueueManager

/**
 * Compaction 与持久化队列的集成测试（真实 Room）：
 * 验证压缩后上下文投影正确、旧消息可从 entry tree 找回、
 * 队列跨"进程重启"存活、并发入队顺序稳定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HarnessCompactionAndQueueIntegrationTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: top.wanxiang.app.core.database.HarnessRuntimeRepository
    private lateinit var compaction: CompactionManager
    private lateinit var queues: PromptQueueManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        compaction = CompactionManager(repository, Json)
        queues = PromptQueueManager(repository, Json)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun message(index: Int) =
        if (index % 2 == 0) UserMessage(id = "m-$index", createdAt = index.toLong(), text = "消息内容 $index")
        else AssistantText(id = "m-$index", createdAt = index.toLong(), text = "回复内容 $index")

    @Test
    fun `compaction projects summary plus retained tail`() = runBlocking {
        val sessionId = "s-compaction"
        repository.ensureLane(sessionId, "main")
        for (index in 0 until 10) {
            repository.appendToLane(
                sessionId, "main",
                top.wanxiang.app.core.database.HarnessEntryEntity(
                    id = "m-$index",
                    sessionId = sessionId,
                    parentId = repository.findLane(sessionId, "main")!!.leafId,
                    createdAt = index.toLong(),
                    entryType = "message",
                    customType = if (index % 2 == 0) "user" else "assistant",
                    payloadJson = Json.encodeToString(
                        top.wanxiang.app.harness.HarnessMessage.serializer(),
                        message(index),
                    ),
                ),
            )
        }

        val context = compaction.project(sessionId)
        assertEquals(10, context.messages.size)
        assertEquals(null, context.summary)

        // 压缩：保留最近 4 条
        val compacted = compaction.compact(sessionId, context, keepFromIndex = 6)

        assertTrue("压缩后应生成摘要", !compacted.summary.isNullOrBlank())
        assertEquals(4, compacted.messages.size)
        assertEquals("m-6", compacted.messages.first().id)
        assertEquals("m-9", compacted.messages.last().id)

        // 重新投影：摘要 + 保留尾部（旧消息不在投影中）
        val projected = compaction.project(sessionId)
        assertEquals(compacted.summary, projected.summary)
        assertEquals(4, projected.messages.size)

        // 旧消息仍在 entry tree 中（审计与分支恢复能力保留）
        val allEntries = repository.listEntries(sessionId)
        assertEquals("压缩不删除历史 entry", 11, allEntries.size) // 10 条消息 + 1 条 compaction entry
        val compactionEntry = allEntries.firstOrNull { it.entryType == "compaction" }
        assertNotNull(compactionEntry)
    }

    @Test
    fun `compaction is incremental across multiple rounds`() = runBlocking {
        val sessionId = "s-multi"
        repository.ensureLane(sessionId, "main")
        for (index in 0 until 8) {
            repository.appendToLane(
                sessionId, "main",
                top.wanxiang.app.core.database.HarnessEntryEntity(
                    id = "m-$index",
                    sessionId = sessionId,
                    parentId = repository.findLane(sessionId, "main")!!.leafId,
                    createdAt = index.toLong(),
                    entryType = "message",
                    customType = "user",
                    payloadJson = Json.encodeToString(
                        top.wanxiang.app.harness.HarnessMessage.serializer(),
                        message(index),
                    ),
                ),
            )
        }

        val first = compaction.compact(sessionId, compaction.project(sessionId), keepFromIndex = 5)
        val secondContext = compaction.project(sessionId)
        // 再追加 2 条新消息后二次压缩
        for (index in 8 until 10) {
            repository.appendToLane(
                sessionId, "main",
                top.wanxiang.app.core.database.HarnessEntryEntity(
                    id = "m-$index",
                    sessionId = sessionId,
                    parentId = repository.findLane(sessionId, "main")!!.leafId,
                    createdAt = index.toLong(),
                    entryType = "message",
                    customType = "assistant",
                    payloadJson = Json.encodeToString(
                        top.wanxiang.app.harness.HarnessMessage.serializer(),
                        message(index),
                    ),
                ),
            )
        }
        val second = compaction.compact(sessionId, compaction.project(sessionId), keepFromIndex = 3)

        assertTrue("二次压缩应累积摘要", second.summary!!.length >= first.summary!!.length)
        // project 投影 = 一次压缩保留 3 条 + 新追加 2 条 = 5 条；keepFromIndex=3 折叠前 3 条
        assertEquals(2, second.messages.size)
        assertEquals("m-8", second.messages.first().id)
        assertEquals("m-9", second.messages.last().id)
    }

    @Test
    fun `queued prompts survive runtime recreation in order`() = runBlocking {
        val sessionId = "s-queue"
        queues.enqueue(sessionId, PromptQueue.NEXT_RUN, PendingMessage("第一条", createdAt = 1L))
        queues.enqueue(sessionId, PromptQueue.NEXT_RUN, PendingMessage("第二条", createdAt = 2L))
        queues.enqueue(sessionId, PromptQueue.STEER, PendingMessage("插队", createdAt = 3L))

        // 模拟进程重启：仅从数据库重建队列管理器
        val revived = PromptQueueManager(repository, Json)

        val nextRun = revived.list(sessionId, PromptQueue.NEXT_RUN)
        assertEquals(listOf("第一条", "第二条"), nextRun.map { it.second.text })
        assertEquals(listOf("插队"), revived.list(sessionId, PromptQueue.STEER).map { it.second.text })

        // 消费顺序与入队一致，且原子转成 entry
        val consumed = revived.consume(sessionId, PromptQueue.NEXT_RUN)
        assertEquals(listOf("第一条", "第二条"), consumed.map { it.text })
        assertTrue(revived.list(sessionId, PromptQueue.NEXT_RUN).isEmpty())
        assertEquals(2, repository.listEntries(sessionId).size)
        assertEquals("第二条", repository.findLane(sessionId, "main")!!.leafId?.let { leafId ->
            repository.listEntries(sessionId).first { it.id == leafId }
                .let { entry -> Json.decodeFromString(top.wanxiang.app.harness.HarnessMessage.serializer(), entry.payloadJson) }
                .let { (it as UserMessage).text }
        })
    }

    @Test
    fun `concurrent enqueues keep deterministic order`() = runBlocking {
        val sessionId = "s-race"
        (1..20).map { index ->
            async(Dispatchers.IO) {
                queues.enqueue(sessionId, PromptQueue.NEXT_RUN, PendingMessage("消息-$index", createdAt = index.toLong()))
            }
        }.awaitAll()

        val items = queues.list(sessionId, PromptQueue.NEXT_RUN)
        assertEquals(20, items.size)
        // createdAt 单调（created_at, id 排序），无丢失
        val timestamps = items.map { it.second.createdAt }
        assertEquals(timestamps.sorted(), timestamps)

        // 单条取消不影响其余
        queues.cancel(sessionId, PromptQueue.NEXT_RUN, index = 5)
        assertEquals(19, queues.list(sessionId, PromptQueue.NEXT_RUN).size)
    }

    @Test
    fun `queues are isolated per lane`() = runBlocking {
        val sessionId = "s-lanes"
        queues.enqueue(sessionId, PromptQueue.NEXT_RUN, PendingMessage("主 lane 消息", createdAt = 1L))
        queues.enqueue(sessionId, PromptQueue.NEXT_RUN, PendingMessage("子 lane 消息", createdAt = 2L), laneName = "subagent:test:1")

        assertEquals(listOf("主 lane 消息"), queues.list(sessionId, PromptQueue.NEXT_RUN).map { it.second.text })
        assertEquals(listOf("子 lane 消息"), queues.list(sessionId, PromptQueue.NEXT_RUN, laneName = "subagent:test:1").map { it.second.text })

        // 消费子 lane 队列只影响该 lane 的 entry tree
        val consumed = queues.consume(sessionId, PromptQueue.NEXT_RUN, laneName = "subagent:test:1")
        assertEquals(listOf("子 lane 消息"), consumed.map { it.text })
        assertEquals("子 lane 消息", repository.findLane(sessionId, "subagent:test:1")!!.leafId?.let { leafId ->
            repository.listEntries(sessionId).first { it.id == leafId }
                .let { entry -> Json.decodeFromString(top.wanxiang.app.harness.HarnessMessage.serializer(), entry.payloadJson) }
                .let { (it as UserMessage).text }
        })
        // 主 lane 队列不受影响
        assertEquals(1, queues.list(sessionId, PromptQueue.NEXT_RUN).size)
        assertNull(repository.findLane(sessionId, "main")!!.leafId)
    }
}
