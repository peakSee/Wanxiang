package top.wanxiang.app.harness.approval

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import top.wanxiang.app.core.database.AgentApprovalRequestEntity
import top.wanxiang.app.core.database.AppDatabase
import top.wanxiang.app.core.database.HarnessSessionEntity
import top.wanxiang.app.core.database.RoomHarnessRuntimeRepository
import top.wanxiang.app.core.database.RoomHarnessSessionRepository
import top.wanxiang.app.harness.ApprovalPolicyEngine
import top.wanxiang.app.harness.UserMessage
import top.wanxiang.app.harness.events.HarnessEventBus
import top.wanxiang.app.harness.operation.OperationCoordinator

/**
 * 审批恢复四重校验测试：真实 Room 会话仓储 + OperationCoordinator，
 * 覆盖过期、参数摘要、工作区变更与 operation 归属的裁决及状态映射。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApprovalResumePolicyTest {

    private lateinit var database: AppDatabase
    private lateinit var coordinator: OperationCoordinator
    private lateinit var policy: ApprovalResumePolicy

    private val nowMs = 1_700_000_000_000L
    private val argumentsJson = """{"command":"ls -la"}"""

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val runtimeRepo = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        coordinator = OperationCoordinator(runtimeRepo, Json { ignoreUnknownKeys = true }, HarnessEventBus())
        policy = ApprovalResumePolicy(
            sessionDao = RoomHarnessSessionRepository(database.harnessSessionDao()),
            operationCoordinator = coordinator,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** 与 ApprovalPolicyEngine.createRequest 字段对齐的最小请求夹具。 */
    private fun request(
        sessionId: String = "s1",
        workspace: String = "/workspace/proj",
        expiresAt: Long = nowMs + ApprovalPolicyEngine.APPROVAL_TTL_MS,
        argsHash: String = ApprovalPolicyEngine.argsHash(argumentsJson),
        operationId: String? = null,
        argumentsOverride: String = argumentsJson,
    ) = AgentApprovalRequestEntity(
        id = "req-1",
        sessionId = sessionId,
        toolCallId = "call-1",
        toolName = "base",
        argumentsJson = argumentsOverride,
        workspace = workspace,
        riskLevel = "MEDIUM",
        reason = "命令执行",
        summary = "ls",
        status = AgentApprovalRequestEntity.STATUS_PENDING,
        createdAt = nowMs - 60_000L,
        operationId = operationId,
        argsHash = argsHash,
        expiresAt = expiresAt,
    )

    private suspend fun seedSession(id: String, workspace: String) {
        database.harnessSessionDao().upsert(
            HarnessSessionEntity(
                id = id,
                title = "t",
                createdAt = 1L,
                updatedAt = 1L,
                modelId = null,
                workspace = workspace,
                projectType = "",
                approvalMode = "full_access",
            ),
        )
    }

    // ---------- evaluate ----------

    @Test
    fun `valid request with approval claims approved`() = runBlocking {
        seedSession("s1", "/workspace/proj")
        val verdict = policy.evaluate(request(), approved = true, nowMs = nowMs)

        assertNull(verdict.invalidationReason)
        assertFalse(verdict.isInvalid)
        assertEquals(AgentApprovalRequestEntity.STATUS_APPROVED, verdict.claimStatus)
    }

    @Test
    fun `valid request without approval claims rejected`() = runBlocking {
        seedSession("s1", "/workspace/proj")
        val verdict = policy.evaluate(request(), approved = false, nowMs = nowMs)

        assertNull(verdict.invalidationReason)
        assertEquals(AgentApprovalRequestEntity.STATUS_REJECTED, verdict.claimStatus)
    }

    @Test
    fun `expired request is invalidated and claimed expired regardless of approval`() = runBlocking {
        seedSession("s1", "/workspace/proj")
        val expired = request(expiresAt = nowMs - 1L)

        val asApproved = policy.evaluate(expired, approved = true, nowMs = nowMs)
        assertTrue(asApproved.invalidationReason!!.contains("过期"))
        assertEquals(AgentApprovalRequestEntity.STATUS_EXPIRED, asApproved.claimStatus)

        val asRejected = policy.evaluate(expired, approved = false, nowMs = nowMs)
        assertEquals(AgentApprovalRequestEntity.STATUS_EXPIRED, asRejected.claimStatus)
    }

    @Test
    fun `args hash mismatch fails the claim`() = runBlocking {
        seedSession("s1", "/workspace/proj")
        val tampered = request(argsHash = "deadbeef")

        val verdict = policy.evaluate(tampered, approved = true, nowMs = nowMs)
        assertTrue(verdict.invalidationReason!!.contains("摘要"))
        assertEquals(AgentApprovalRequestEntity.STATUS_FAILED, verdict.claimStatus)
    }

    @Test
    fun `blank args hash skips integrity check for legacy rows`() = runBlocking {
        seedSession("s1", "/workspace/proj")
        val legacy = request(argsHash = "")

        val verdict = policy.evaluate(legacy, approved = true, nowMs = nowMs)
        assertNull(verdict.invalidationReason)
    }

    @Test
    fun `recomputed matching hash passes`() = runBlocking {
        seedSession("s1", "/workspace/proj")
        val legit = request(argsHash = ApprovalPolicyEngine.argsHash(request().argumentsJson))
        assertNull(policy.evaluate(legit, approved = true, nowMs = nowMs).invalidationReason)
    }

    @Test
    fun `changed session workspace invalidates the grant`() = runBlocking {
        seedSession("s1", "/workspace/other")
        val verdict = policy.evaluate(request(workspace = "/workspace/proj"), approved = true, nowMs = nowMs)

        assertTrue(verdict.invalidationReason!!.contains("工作区已变更"))
        assertEquals(AgentApprovalRequestEntity.STATUS_FAILED, verdict.claimStatus)
    }

    @Test
    fun `missing bound operation invalidates as taken over`() = runBlocking {
        seedSession("s1", "/workspace/proj")
        val orphaned = request(operationId = "op-gone-${System.nanoTime()}")

        val verdict = policy.evaluate(orphaned, approved = true, nowMs = nowMs)
        assertTrue(verdict.invalidationReason!!.contains("已结束或被新运行接管"))
        assertEquals(AgentApprovalRequestEntity.STATUS_FAILED, verdict.claimStatus)
    }

    @Test
    fun `existing bound operation passes ownership check`() = runBlocking {
        seedSession("s1", "/workspace/proj")
        val opId = coordinator.acceptRun("s1", UserMessage("m1", 1L, "开始"))
        val bound = request(operationId = opId)

        assertNull(policy.evaluate(bound, approved = true, nowMs = nowMs).invalidationReason)
    }

    // ---------- 状态映射 ----------

    @Test
    fun `final status mapping covers approve execute fail paths`() {
        assertEquals(AgentApprovalRequestEntity.STATUS_EXECUTED, policy.finalStatus(approved = true, resultSuccess = true))
        assertEquals(AgentApprovalRequestEntity.STATUS_FAILED, policy.finalStatus(approved = true, resultSuccess = false))
        assertEquals(AgentApprovalRequestEntity.STATUS_REJECTED, policy.finalStatus(approved = false, resultSuccess = false))
        assertEquals(AgentApprovalRequestEntity.STATUS_REJECTED, policy.finalStatus(approved = false, resultSuccess = true))
    }

    // ---------- 写回文案 ----------

    @Test
    fun `result messages explain why nothing was executed`() {
        val msg = policy.invalidationResultMessage("该审批已过期（等待超过 10 分钟）")
        assertTrue(msg.contains("该审批已过期"))
        assertTrue(msg.contains("未执行"))

        val rejection = policy.rejectionResultMessage()
        assertTrue(rejection.contains("用户拒绝"))
    }
}
