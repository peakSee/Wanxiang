package top.wanxiang.app.harness.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.core.common.logging.SensitiveDataRedactor
import top.wanxiang.app.core.datastore.AgentPreferences
import top.wanxiang.app.core.datastore.SettingsDataStore
import top.wanxiang.app.core.database.AgentSkillRepository
import top.wanxiang.app.core.database.AgentSubagentRepository
import top.wanxiang.app.core.database.AgencyAgentCatalogLoader
import top.wanxiang.app.core.database.AppDatabase
import top.wanxiang.app.core.database.McpServerRepository
import top.wanxiang.app.core.database.RoomAgentContextRepository
import top.wanxiang.app.core.database.RoomHarnessRuntimeRepository
import top.wanxiang.app.core.security.SecretManager
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.CapabilityEvent
import top.wanxiang.app.harness.HarnessTool
import top.wanxiang.app.harness.ModelConfig
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.ToolCallMode
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.harness.UserMessage
import top.wanxiang.app.harness.WorkspaceFileAccess
import top.wanxiang.app.core.tools.ToolRegistry
import top.wanxiang.app.core.tools.ToolRepository
import top.wanxiang.app.harness.compaction.CompactionManager
import top.wanxiang.app.harness.prompt.PrivilegeSectionRenderer
import top.wanxiang.app.harness.prompt.PromptAssetLoader
import top.wanxiang.app.harness.prompt.PromptRouter
import top.wanxiang.app.harness.prompt.SystemPromptBuilder

/**
 * API 上下文组装器全栈集成测试：真实 Room（会话树 + 压缩树）+ 真实 DataStore 偏好 +
 * 打包内真实提示词资产。验证 NATIVE / JSON_TEXT 双协议、视觉剥离、思考回传标记、
 * 能力事件剔除、未应答调用的丢弃与压缩摘要注入。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiContextAssemblerTest {

    private lateinit var database: AppDatabase
    private lateinit var store: top.wanxiang.app.harness.session.SessionTreeStore
    private lateinit var assembler: ApiContextAssembler
    private lateinit var compactionManager: CompactionManager
    private val tempDir = File(System.getProperty("java.io.tmpdir"), "wanxiang-assembler-${System.nanoTime()}")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val runtimeRepo = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val logger = AppLogger(context, SensitiveDataRedactor { it })
        val json = Json { ignoreUnknownKeys = true }
        val agentPrefs = AgentPreferences(SettingsDataStore(context, SecretManager()))

        compactionManager = CompactionManager(runtimeRepo, json)
        store = top.wanxiang.app.harness.session.SessionTreeStore(runtimeRepo, json, logger)

        val promptAssets = PromptAssetLoader(context)
        val builder = SystemPromptBuilder(
            context = context,
            settingsDataStore = agentPrefs,
            skillRepository = AgentSkillRepository(database.agentSkillDao()),
            toolRepository = ToolRepository(database.toolDao(), ToolRegistry(context, OkHttpClient(), logger)),
            agentContextDao = RoomAgentContextRepository(database.agentContextDao()),
            subagentRepository = AgentSubagentRepository(
                database.agentSubagentDao(),
                AgencyAgentCatalogLoader(context, json),
            ),
            mcpServerRepository = McpServerRepository(database.mcpServerDao(), SecretManager()),
            promptAssets = promptAssets,
            fileAccess = WorkspaceFileAccess(tempDir),
            privilegeRenderer = PrivilegeSectionRenderer { "" },
            promptRouter = PromptRouter(promptAssets),
        )
        assembler = ApiContextAssembler(compactionManager, agentPrefs, builder)
    }

    @After
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    private fun nativeModel(vision: Boolean = false, tokens: Int = 200_000) = ModelConfig(
        name = "n", provider = "p", model = "gpt-x",
        baseUrl = "https://example.com", apiKey = null,
        contextTokens = tokens,
        visionEnabled = vision,
    )

    private suspend fun push(sessionId: String, vararg messages: top.wanxiang.app.harness.HarnessMessage) {
        messages.forEach { store.append(sessionId, it) }
    }

    private fun jsonTextModel(vision: Boolean = false) =
        nativeModel(vision).copy(toolCallMode = ToolCallMode.JSON_TEXT)

    // ---------- NATIVE 协议 ----------

    @Test
    fun `native injects system prompt and maps user and plain assistant`() = runBlocking {
        push(
            "s-native",
            UserMessage("u1", 1L, "看看文件"),
            AssistantText("a0", 2L, "好的"),
        )
        val out = assembler.assemble("s-native", nativeModel(), workspacePath = "")

        assertEquals("system", out[0].role)
        val systemPrompt = out[0].content!!
        assertTrue(systemPrompt.isNotBlank())
        assertEquals(9, Regex("department=\\\"").findAll(systemPrompt).count())
        assertFalse(systemPrompt.contains("agency_engineering_frontend_developer"))
        assertFalse(systemPrompt.contains("Frontend Developer"))
        assertEquals(2, out.size - 1)
        assertEquals("user", out[1].role)
        assertEquals("assistant", out[2].role)
        assertNull(out[2].tool_calls)
    }

    @Test
    fun `native pairs answered tool calls onto assistant and emits tool role`() = runBlocking {
        val call = ToolCall(
            id = "t1", createdAt = 3L, tool = HarnessTool.READ,
            args = buildJsonObject { }, rawToolName = "read",
        )
        push(
            "s-pair",
            UserMessage("u1", 1L, "读一下"),
            AssistantText("a0", 2L, "我来读"),
            call,
            ToolResult("r1", 4L, "t1", success = true, output = "内容"),
            AssistantText("a1", 5L, "结论"),
        )
        val out = assembler.assemble("s-pair", nativeModel(), "")

        val paired = out.first { it.role == "assistant" && !it.tool_calls.isNullOrEmpty() }
        assertEquals(listOf("t1"), paired.tool_calls!!.map { it.id })
        val toolMsg = out.last { it.role == "tool" }
        assertEquals("t1", toolMsg.tool_call_id)
        assertEquals("内容", toolMsg.content)
        assertEquals("结论", out.last { it.role == "assistant" }.content)
    }

    @Test
    fun `unanswered native tool calls are dropped silently`() = runBlocking {
        push(
            "s-orphan",
            UserMessage("u1", 1L, "x"),
            ToolCall("t9", 3L, HarnessTool.READ, buildJsonObject { }),
        )
        val out = assembler.assemble("s-orphan", nativeModel(vision = false), "")
        assertTrue(out.none { it.role == "tool" })
        assertTrue(out.none { !it.tool_calls.isNullOrEmpty() })
    }

    @Test
    fun `historical assistant messages do not leak reasoning content to prevent reasoning loop`() = runBlocking {
        push("s-think", UserMessage("u1", 1L, "hi"), AssistantText("a1", 2L, "<think>思考中</think>hello", reasoning = "历史思考"))
        val out = assembler.assemble("s-think", nativeModel(), "", thinkingMode = true)
        val assistant = out.last { it.role == "assistant" }
        assertNull(assistant.reasoning_content)
        assertEquals("hello", assistant.content)

        val off = assembler.assemble("s-think", nativeModel(), "", thinkingMode = false)
        assertNull(off.last { it.role == "assistant" }.reasoning_content)
        assertEquals("hello", off.last { it.role == "assistant" }.content)
    }

    @Test
    fun `tool-calling assistant messages must round-trip reasoning content for thinking mode`() = runBlocking {
        // DeepSeek 思考模式：两个 user 之间若有工具调用，中间 assistant 消息的
        // reasoning_content 必须原样传回，否则 400 报错。
        push(
            "s-think-tool",
            UserMessage("u1", 1L, "读文件"),
            ToolCall("t1", 2L, HarnessTool.READ, buildJsonObject { }, reasoning = "需要先看文件", rawToolName = "read"),
            ToolResult("r1", 3L, "t1", success = true, output = "内容"),
            AssistantText("a1", 4L, "文件内容是……", reasoning = "已读到"),
        )
        val out = assembler.assemble("s-think-tool", nativeModel(), "")
        val toolTurn = out.first { it.role == "assistant" && !it.tool_calls.isNullOrEmpty() }
        assertEquals("需要先看文件", toolTurn.reasoning_content)
        // 收尾纯文本 assistant 轮仍不回传
        assertNull(out.last { it.role == "assistant" && it.tool_calls == null }.reasoning_content)
    }

    // ---------- JSON_TEXT 协议 ----------

    @Test
    fun `json text converts tool results into user text messages`() = runBlocking {
        val call = ToolCall(id = "j1", createdAt = 3L, tool = HarnessTool.BASE, args = buildJsonObject { }, rawToolName = "base")
        push(
            "s-json",
            UserMessage("u1", 1L, "跑命令"),
            AssistantText("a0", 2L, "模型叙述文本"),
            call,
            ToolResult("r1", 4L, "j1", success = true, output = "输出内容"),
        )
        val out = assembler.assemble("s-json", jsonTextModel(), "")
        assertTrue(out.none { it.role == "tool" })
        assertTrue(out.none { !it.tool_calls.isNullOrEmpty() })

        val asUser = out.filter { it.role == "user" }.last()
        assertTrue(asUser.content!!.contains("【工具 base 执行结果·成功】"))
        assertTrue(asUser.content!!.contains("输出内容"))

        val narration = out.first { it.role == "assistant" }
        assertEquals("模型叙述文本", narration.content)
    }

    // ---------- 视觉与能力事件 ----------

    @Test
    fun `images are stripped when vision disabled and kept when enabled`() = runBlocking {
        val images = listOf("https://img.example/a.png")
        push("s-vision", UserMessage("u1", 1L, "看图", imageUrls = images))
        val stripped = assembler.assemble("s-vision", nativeModel(vision = false), "")
        assertTrue(stripped.first { it.role == "user" }.imageUrls.isEmpty())

        val kept = assembler.assemble("s-vision", nativeModel(vision = true), "")
        assertEquals(images, kept.first { it.role == "user" }.imageUrls)
    }

    @Test
    fun `capability events never reach the provider payload`() = runBlocking {
        push(
            "s-cap",
            UserMessage("u1", 1L, "@skill"),
            CapabilityEvent("cap1", 2L, CapabilityEvent.Kind.SKILL, "技能", "详情"),
        )
        val out = assembler.assemble("s-cap", nativeModel(), "")
        assertFalse(out.any { it.content?.contains("详情") == true })
    }

    // ---------- 压缩摘要 ----------

    @Test
    fun `existing compaction summary is injected after the main system prompt`() = runBlocking {
        push("s-compact", UserMessage("u0", 1L, "早期历史，会被折叠进摘要"))
        val context = compactionManager.project("s-compact")
        compactionManager.compact("s-compact", context, keepFromIndex = 1)

        val expectedSummary = compactionManager.project("s-compact").summary!!
        val out = assembler.assemble("s-compact", nativeModel(), "")

        assertTrue(out.size >= 2)
        assertEquals(expectedSummary, out[1].content)
        assertFalse(out.any { it.role != "system" && it.content == expectedSummary })
    }

    @Test
    fun `pure chat skips even persisted history system prompts`() = runBlocking {
        push("s-pure2", UserMessage("u1", 1L, "你好"))
        val out = assembler.assemble("s-pure2", nativeModel().copy(pureChatMode = true), "/ws")
        assertTrue(out.none { it.role == "system" })
        assertEquals("你好", out.single().content)
    }

    @Test
    fun `mcp rawToolName is preserved in native assistant tool calls`() = runBlocking {
        val mcpCall = ToolCall(
            id = "call-mcp",
            createdAt = 2L,
            tool = HarnessTool.MCP,
            args = buildJsonObject { put("query", kotlinx.serialization.json.JsonPrimitive("Kotlin coroutines")) },
            rawToolName = "mcp__mcp_websearch__search",
        )
        val mcpResult = ToolResult(
            id = "res-mcp",
            createdAt = 3L,
            toolCallId = "call-mcp",
            success = true,
            output = "搜索结果: Kotlin Coroutines 指南",
        )
        push("s-mcp", UserMessage("u1", 1L, "搜索一下"), mcpCall, mcpResult)
        val out = assembler.assemble("s-mcp", nativeModel(), "")

        val assistantMsg = out.first { it.role == "assistant" }
        val apiCall = assistantMsg.tool_calls?.single()
        assertEquals("call-mcp", apiCall?.id)
        assertEquals("mcp__mcp_websearch__search", apiCall?.function?.name)
    }
}
