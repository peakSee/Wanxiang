package top.wanxiang.app.harness.events

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
import top.wanxiang.app.core.database.AgentSkillEntity
import top.wanxiang.app.core.database.AppDatabase
import top.wanxiang.app.core.model.McpToolInfo
import top.wanxiang.app.core.security.SecretManager
import top.wanxiang.app.harness.CapabilityEvent
import top.wanxiang.app.harness.ModelConfig
import top.wanxiang.app.harness.projection.LiveMessagePort

/**
 * @提及 能力事件写入器测试：真实 Room 的技能仓储 + 内存端口，
 * 验证技能与 MCP 两类事件的命中、幂等（同消息不重复）与挂载文案分支。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapabilityEventWriterTest {

    private class RecordingPort : LiveMessagePort {
        val appended = mutableListOf<Pair<String, top.wanxiang.app.harness.HarnessMessage>>()
        val snapshots = mutableMapOf<String, MutableList<top.wanxiang.app.harness.HarnessMessage>>()

        override suspend fun append(sessionId: String, message: top.wanxiang.app.harness.HarnessMessage) {
            appended += sessionId to message
            snapshots.getOrPut(sessionId) { mutableListOf() }.add(message)
        }

        override suspend fun publishPersisted(sessionId: String, message: top.wanxiang.app.harness.HarnessMessage) {
            append(sessionId, message)
        }

        override fun snapshot(sessionId: String): List<top.wanxiang.app.harness.HarnessMessage> =
            snapshots[sessionId]?.toList().orEmpty()
    }

    private lateinit var database: AppDatabase
    private lateinit var port: RecordingPort
    private lateinit var writer: CapabilityEventWriter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        port = RecordingPort()
        writer = CapabilityEventWriter(
            port = port,
            skillRepository = top.wanxiang.app.core.database.AgentSkillRepository(database.agentSkillDao()),
            mcpServerRepository = top.wanxiang.app.core.database.McpServerRepository(
                database.mcpServerDao(),
                SecretManager(),
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedSkill(id: String, name: String = id, trigger: String? = "/$id", enabled: Boolean = true) {
        database.agentSkillDao().upsert(
            AgentSkillEntity(
                id = id,
                name = name,
                description = "$name 描述",
                systemPrompt = "提示",
                triggerCommand = trigger,
                iconName = "Code",
                isEnabled = enabled,
                isBuiltin = true,
                isImmutable = false,
                category = "通用",
                resourcePath = null,
            ),
        )
    }

    private val model = ModelConfig(
        name = "m",
        provider = "p",
        model = "gpt-x",
        baseUrl = "https://example.com",
        apiKey = null,
        dynamicMcpTools = listOf(
            McpToolInfo(serverId = "fs", serverName = "文件服务", name = "cat", description = "读文件"),
        ),
    )

    @Test
    fun `writes skill events for mentioned skills only`() = runBlocking {
        seedSkill("coder")
        seedSkill("reviewer", enabled = false)

        writer.writeIfMentioned("s1", "um1", setOf("@coder".removePrefix("@")), model)

        val events = port.appended.map { it.second }.filterIsInstance<CapabilityEvent>()
        assertEquals(1, events.size)
        assertEquals(CapabilityEvent.Kind.SKILL, events[0].kind)
        assertEquals("skill:um1:coder", events[0].id)
    }

    @Test
    fun `is idempotent for the same user message`() = runBlocking {
        seedSkill("coder")
        val names = setOf("coder")
        writer.writeIfMentioned("s1", "um1", names, model)
        writer.writeIfMentioned("s1", "um1", names, model)

        assertEquals(1, port.appended.size)
    }

    @Test
    fun `discovered dynamic mcp tools produce mount or discovery text`() = runBlocking {
        val mountedModel = model
        writer.writeIfMentioned("s1", "um1", setOf("fs"), mountedModel)
        val first = port.appended.single().second as CapabilityEvent
        assertEquals(CapabilityEvent.Kind.MCP, first.kind)
        assertTrue(first.details.contains("已挂载"))

        // 重新写入时视为已知事件，不重复追加
        writer.writeIfMentioned("s1", "um1", setOf("fs"), mountedModel)
        assertEquals(1, port.appended.size)
    }

    @Test
    fun `no mention produces nothing`() = runBlocking {
        seedSkill("coder")
        writer.writeIfMentioned("s1", "um1", emptySet(), model)
        assertTrue(port.appended.isEmpty())
    }
}
