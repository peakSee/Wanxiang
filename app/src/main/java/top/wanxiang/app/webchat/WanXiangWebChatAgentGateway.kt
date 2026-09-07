package top.wanxiang.app.webchat

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import top.wanxiang.app.core.database.HarnessSessionEntity
import top.wanxiang.app.core.database.HarnessSessionRepository
import top.wanxiang.app.core.database.AiModelRepository
import top.wanxiang.app.core.database.AgentApprovalRepository
import top.wanxiang.app.core.database.AgentApprovalRequestEntity
import top.wanxiang.app.core.model.ApprovalMode
import top.wanxiang.app.core.model.SessionRunState
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.CapabilityEvent
import top.wanxiang.app.harness.HarnessLoop
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.harness.UserMessage
import top.wanxiang.app.runtime.webchat.WebChatAgentGateway
import top.wanxiang.app.runtime.webchat.WebChatApproval
import top.wanxiang.app.runtime.webchat.WebChatMessage
import top.wanxiang.app.runtime.webchat.WebChatSessionSnapshot

@Singleton
class WanXiangWebChatAgentGateway @Inject constructor(
    private val harnessLoop: HarnessLoop,
    private val sessions: HarnessSessionRepository,
    private val models: AiModelRepository,
    private val approvals: AgentApprovalRepository,
) : WebChatAgentGateway {

    override suspend fun createSession(title: String, workspace: String): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val defaultModel = models.activeModel()
        sessions.upsert(
            HarnessSessionEntity(
                id = id,
                title = title.trim().ifBlank { "新会话" },
                createdAt = now,
                updatedAt = now,
                modelId = defaultModel?.id,
                modelVariant = defaultModel?.model?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() },
                workspace = workspace,
                approvalMode = ApprovalMode.ASSISTED.id,
            ),
        )
        return id
    }

    override suspend fun deleteSession(sessionId: String) = harnessLoop.deleteSession(sessionId)

    override suspend fun messages(sessionId: String): List<WebChatMessage> =
        harnessLoop.prepareRemoteSession(sessionId).map(::toWebMessage)

    override suspend fun pendingApprovals(sessionId: String): List<WebChatApproval> =
        approvals.pendingNow(sessionId).map(::toWebApproval)

    override fun observeSession(sessionId: String): Flow<WebChatSessionSnapshot> =
        combine(
            harnessLoop.messagesForSession(sessionId),
            harnessLoop.sessionRunStates,
            harnessLoop.sessionStatuses,
            approvals.pendingForSession(sessionId),
        ) { messages, states, statuses, pending ->
            val state = states[sessionId]
            WebChatSessionSnapshot(
                messages = messages.map(::toWebMessage),
                running = state == SessionRunState.RUNNING,
                waitingApproval = state == SessionRunState.WAITING_APPROVAL,
                approvals = pending.map(::toWebApproval),
                error = if (state == SessionRunState.FAILED) statuses[sessionId] else null,
            )
        }

    override suspend fun send(sessionId: String, text: String, imageUrls: List<String>) {
        harnessLoop.prepareRemoteSession(sessionId)
        harnessLoop.send(text, sessionId, imageUrls)
    }

    override suspend fun resolveApproval(sessionId: String, requestId: String, approved: Boolean): Boolean {
        val request = approvals.find(requestId) ?: return false
        if (request.sessionId != sessionId || request.status != AgentApprovalRequestEntity.STATUS_PENDING) return false
        harnessLoop.resolveApproval(requestId, approved)
        return true
    }

    override fun cancel(sessionId: String) = harnessLoop.cancel(sessionId)

    private fun toWebApproval(request: AgentApprovalRequestEntity) = WebChatApproval(
        id = request.id,
        toolName = request.toolName,
        argumentsJson = request.argumentsJson,
        workspace = request.workspace,
        riskLevel = request.riskLevel,
        reason = request.reason,
        summary = request.summary,
        createdAt = request.createdAt,
        expiresAt = request.expiresAt,
    )

    private fun toWebMessage(message: HarnessMessage): WebChatMessage = when (message) {
        is UserMessage -> WebChatMessage(
            id = message.id,
            user = 1,
            type = 1,
            content = buildJsonObject {
                put("text", message.text)
                if (message.imageUrls.isNotEmpty()) {
                    putJsonArray("attachments") {
                        message.imageUrls.forEachIndexed { index, url ->
                            add(buildJsonObject {
                                put("name", "图片 ${index + 1}")
                                put("dataUrl", url)
                                put("isImage", true)
                            })
                        }
                    }
                }
            },
            createAt = message.createdAt,
        )
        is AssistantText -> WebChatMessage(
            id = message.id,
            user = 0,
            type = 1,
            content = buildJsonObject { put("text", message.text) },
            createAt = message.createdAt,
            reasoningContent = message.reasoning,
        )
        is ToolCall -> WebChatMessage(
            id = message.id,
            user = 0,
            type = 2,
            content = buildJsonObject {
                put("type", "agent_tool_summary")
                put("toolTitle", message.rawToolName ?: message.tool.name.lowercase())
                put("toolType", message.tool.name.lowercase())
                put("status", "running")
                put("arguments", message.args)
            },
            createAt = message.createdAt,
            reasoningContent = message.reasoning,
            isLoading = true,
        )
        is ToolResult -> WebChatMessage(
            id = message.id,
            user = 0,
            type = 2,
            content = buildJsonObject {
                put("type", "agent_tool_summary")
                put("toolTitle", "工具结果")
                put("toolType", "tool")
                put("status", if (message.success) "success" else "error")
                put("output", message.output)
                message.durationMs?.let { put("durationMs", it) }
            },
            createAt = message.createdAt,
            isError = !message.success,
        )
        is CapabilityEvent -> WebChatMessage(
            id = message.id,
            user = 0,
            type = 2,
            content = buildJsonObject {
                put("type", "agent_tool_summary")
                put("toolTitle", message.name)
                put("toolType", message.kind.name.lowercase())
                put("status", "success")
                put("details", message.details)
            },
            createAt = message.createdAt,
        )
    }
}
