package top.wanxiang.app.harness.compaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wanxiang.app.core.database.AppDatabase
import top.wanxiang.app.core.database.HarnessEntryEntity
import top.wanxiang.app.core.database.RoomHarnessRuntimeRepository
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.UserMessage

/** latestSnapshot 快照 API：UI 折叠透明度横幅的数据源契约（真实 Room）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompactionSnapshotTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: RoomHarnessRuntimeRepository
    private lateinit var compaction: CompactionManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        compaction = CompactionManager(repository, Json)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedUserMessages(sessionId: String, count: Int) {
        repository.ensureLane(sessionId, "main")
        repeat(count) { index ->
            repository.appendToLane(
                sessionId,
                "main",
                HarnessEntryEntity(
                    id = "$sessionId-m$index",
                    sessionId = sessionId,
                    parentId = repository.findLane(sessionId, "main")!!.leafId,
                    createdAt = index.toLong(),
                    entryType = "message",
                    customType = "user",
                    payloadJson = Json.encodeToString(
                        HarnessMessage.serializer(),
                        UserMessage(id = "$sessionId-m$index", createdAt = index.toLong(), text = "消息 $index"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `no compaction yields null snapshot`() = runBlocking {
        seedUserMessages("s-fresh", 3)
        assertNull(compaction.latestSnapshot("s-fresh"))
    }

    @Test
    fun `snapshot exposes folded count and summary after compact`() = runBlocking {
        val sessionId = "s-snap"
        seedUserMessages(sessionId, 6)
        val projected = compaction.project(sessionId)
        assertEquals(6, projected.messages.size)

        compaction.compact(sessionId, projected, keepFromIndex = 4)
        val snapshot = compaction.latestSnapshot(sessionId)!!
        assertEquals(4, snapshot.foldedMessageCount)
        assertTrue(snapshot.summary.isNotBlank())
        assertTrue(snapshot.createdAt > 0)
    }

    @Test
    fun `latest snapshot across rounds reports cumulative folded count`() = runBlocking {
        val sessionId = "s-cumulative"
        seedUserMessages(sessionId, 10)
        var context = compaction.project(sessionId)
        compaction.compact(sessionId, context, keepFromIndex = 5)
        context = compaction.project(sessionId)
        compaction.compact(sessionId, context, keepFromIndex = 2)

        val snapshot = compaction.latestSnapshot(sessionId)!!
        // 多轮压缩下 foldedMessageCount 为历次累计：第一轮折叠 5 条 + 第二轮折叠 2 条
        assertEquals(7, snapshot.foldedMessageCount)
        // 横幅展示的必须是“当前生效”的压缩状态：快照摘要与第二轮投影一致
        assertEquals(compaction.project(sessionId).summary, snapshot.summary)
    }

    @Test
    fun `rolling summary keeps newly folded facts after previous summary reaches cap`() = runBlocking {
        val sessionId = "s-rolling"
        repository.ensureLane(sessionId, "main")
        val latestMarker = "LATEST_DECISION_USE_SQL_QUERY"
        val context = CompactedContext(
            summary = "old-context ".repeat(600),
            messages = listOf(
                UserMessage("latest", 1, "关键决定：$latestMarker"),
                UserMessage("retained", 2, "continue"),
            ),
        )

        val compacted = compaction.compact(sessionId, context, keepFromIndex = 1)

        assertTrue(compacted.summary.orEmpty().contains(latestMarker))
        assertTrue(compacted.summary.orEmpty().length <= 4_800)
    }
}
