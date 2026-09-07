package top.wanxiang.app.runtime.webchat

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Runtime-facing boundary for exposing WanXiang's native Harness to the LAN Web console. */
interface WebChatAgentGateway {
    suspend fun createSession(title: String, workspace: String): String
    suspend fun deleteSession(sessionId: String)
    suspend fun messages(sessionId: String): List<WebChatMessage>
    suspend fun pendingApprovals(sessionId: String): List<WebChatApproval>
    fun observeSession(sessionId: String): Flow<WebChatSessionSnapshot>
    suspend fun send(sessionId: String, text: String, imageUrls: List<String>)
    suspend fun resolveApproval(sessionId: String, requestId: String, approved: Boolean): Boolean
    fun cancel(sessionId: String)
}

@Serializable
data class WebChatApproval(
    val id: String,
    val toolName: String,
    val argumentsJson: String,
    val workspace: String,
    val riskLevel: String,
    val reason: String,
    val summary: String,
    val createdAt: Long,
    val expiresAt: Long,
)

@Serializable
data class WebChatMessage(
    val id: String,
    val user: Int,
    val type: Int,
    val content: JsonObject,
    val createAt: Long,
    val reasoningContent: String? = null,
    val isError: Boolean = false,
    val isLoading: Boolean = false,
)

data class WebChatSessionSnapshot(
    val messages: List<WebChatMessage>,
    val running: Boolean,
    val waitingApproval: Boolean,
    val approvals: List<WebChatApproval> = emptyList(),
    val error: String? = null,
)
