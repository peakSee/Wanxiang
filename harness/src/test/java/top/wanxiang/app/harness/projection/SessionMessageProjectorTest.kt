package top.wanxiang.app.harness.projection

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.core.common.logging.SensitiveDataRedactor
import top.wanxiang.app.core.database.AppDatabase
import top.wanxiang.app.core.database.RoomHarnessRuntimeRepository
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.UserMessage
import top.wanxiang.app.harness.session.SessionTreeStore

/**
 * 会话消息投影器集成测试：真实 Room 持久化 + SessionTreeStore 全链路，
 * 验证实时流发布、历史合并、前台镜像与流式上屏语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionMessageProjectorTest {

    private lateinit var database: AppDatabase
    private lateinit var store: SessionTreeStore
    private lateinit var tracker: CurrentSessionTracker
    private lateinit var projector: SessionMessageProjector

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = SessionTreeStore(
            repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao()),
            json = Json { ignoreUnknownKeys = true },
            logger = AppLogger(context, SensitiveDataRedactor { it }),
        )
        tracker = CurrentSessionTracker()
        projector = SessionMessageProjector(store, tracker)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun user(id: String, text: String) = UserMessage(id = id, createdAt = 10L, text = text)
    private fun assistant(id: String, text: String) = AssistantText(id = id, createdAt = 11L, text = text)

    @Test
    fun `append persists and publishes into live flow`() = runBlocking {
        projector.seedEmpty("s1")
        val msg = user("m1", "你好")
        projector.append("s1", msg)

        assertEquals(listOf(msg), projector.snapshot("s1"))
        // 持久化后可从历史读回（异步合并路径之外的基础保障）
        assertEquals(1, store.load("s1").size)
    }

    @Test
    fun `preparedForLoad reuses existing flow without reload`() = runBlocking {
        projector.seedEmpty("s1")
        val same = projector.preparedForLoad("s1")
        assertTrue(same === projector.messagesFlow("s1"))
    }

    @Test
    fun `foreground mirroring only tracks current session`() = runBlocking {
        tracker.setCurrent("s1")
        projector.seedEmpty("s1")

        val msg = assistant("a1", "回复")
        projector.publishPersisted("s1", msg)
        assertEquals(listOf(msg), projector.foregroundMessages.value)

        // 切走前台后再追加，不应污染新前台镜像
        tracker.setCurrent("s2")
        projector.append("s1", user("m2", "第二条"))
        assertEquals(listOf(msg), projector.foregroundMessages.value)
    }

    @Test
    fun `replaceAll swaps live list for regen and rewind paths`() = runBlocking {
        tracker.setCurrent("s1")
        projector.seedEmpty("s1")
        projector.append("s1", user("m1", "问题"))
        projector.append("s1", assistant("a1", "旧答案"))

        projector.replaceAll("s1", listOf(user("m1", "问题")))
        assertEquals(listOf(user("m1", "问题")), projector.snapshot("s1"))
        assertEquals(listOf(user("m1", "问题")), projector.foregroundMessages.value)
    }

    @Test
    fun `streamText preserves prior reasoning of the same bubble`() = runBlocking {
        tracker.setCurrent("s1")
        projector.seedEmpty("s1")
        projector.streamReasoning("s1", "b1", 5L, "思考中…")
        projector.streamText("s1", "b1", 5L, "答案草稿")

        val bubble = projector.snapshot("s1").single() as AssistantText
        assertEquals("答案草稿", bubble.text)
        assertEquals("思考中…", bubble.reasoning)

        projector.remove("s1", "b1")
        assertTrue(projector.snapshot("s1").isEmpty())
    }

    @Test
    fun `publishPersisted updates in place when id exists`() = runBlocking {
        tracker.setCurrent("s1")
        projector.seedEmpty("s1")
        projector.append("s1", assistant("a1", "v1"))
        val updated = AssistantText(id = "a1", createdAt = 11L, text = "v2", totalMs = 8L)
        projector.publishPersisted("s1", updated)

        assertEquals(listOf(updated), projector.snapshot("s1"))
    }

    @Test
    fun `live projection stays bounded while durable history keeps growing`() = runBlocking {
        tracker.setCurrent("long")
        projector.seedEmpty("long")
        repeat(SessionTreeStore.MAX_LIVE_ENTRIES + 25) { index ->
            projector.append("long", UserMessage(id = "m$index", createdAt = index.toLong(), text = "message $index"))
        }

        val live = projector.snapshot("long")
        assertEquals(SessionTreeStore.MAX_LIVE_ENTRIES, live.size)
        assertEquals("m25", live.first().id)
        assertEquals(SessionTreeStore.MAX_LIVE_ENTRIES + 25, database.harnessRuntimeDao().listEntries("long").size)
    }
}
