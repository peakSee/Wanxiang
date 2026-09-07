package top.wanxiang.app.harness.events

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import top.wanxiang.app.core.database.AgentSkillRepository
import top.wanxiang.app.core.database.McpServerRepository
import top.wanxiang.app.harness.CapabilityEvent
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.ModelConfig
import top.wanxiang.app.harness.projection.LiveMessagePort

import top.wanxiang.app.harness.mcp.McpManager

/**
 * @提及 能力事件写入器：当用户消息提及技能或 MCP 服务时，
 * 在会话内插入一条幂等的 [CapabilityEvent] 展示卡片（同一用户消息下不重复）。
 */
@Singleton
class CapabilityEventWriter @Inject constructor(
    private val port: LiveMessagePort,
    private val skillRepository: AgentSkillRepository,
    private val mcpServerRepository: McpServerRepository,
    private val mcpManager: McpManager? = null,
) {
    suspend fun writeIfMentioned(
        sessionId: String,
        userMessageId: String,
        mentionedNames: Set<String>,
        model: ModelConfig,
    ) {
        if (mentionedNames.isEmpty()) return
        if (userMessageId.isBlank()) return
        val existing = port.snapshot(sessionId)
        writeSkillEvents(existing, sessionId, userMessageId, mentionedNames)
        writeMcpEvents(existing, sessionId, userMessageId, mentionedNames, model)
    }

    private suspend fun writeSkillEvents(
        existing: List<HarnessMessage>,
        sessionId: String,
        userMessageId: String,
        mentionedNames: Set<String>,
    ) {
        val skills = runCatching { skillRepository.allSkills.first() }.getOrDefault(emptyList())
            .filter { skill ->
                val names = setOf(
                    skill.name.lowercase(),
                    skill.id.lowercase(),
                    skill.triggerCommand?.removePrefix("/")?.lowercase().orEmpty(),
                )
                names.any { it.isNotBlank() && it in mentionedNames }
            }
        skills.forEach { skill ->
            appendEventOnce(
                existing,
                sessionId,
                id = "skill:$userMessageId:${skill.id}",
                kind = CapabilityEvent.Kind.SKILL,
                name = skill.name,
                description = skill.description,
            )
        }
    }

    private suspend fun writeMcpEvents(
        existing: List<HarnessMessage>,
        sessionId: String,
        userMessageId: String,
        mentionedNames: Set<String>,
        model: ModelConfig,
    ) {
        val configuredMcp = runCatching { mcpServerRepository.servers.first() }
            .getOrDefault(emptyList())
            .map { it.id to it.name }
        val discoveredMcp = model.dynamicMcpTools.map { it.serverId to it.serverName }
        (configuredMcp + discoveredMcp)
            .distinctBy { it.first }
            .filter { (serverId, serverName) -> serverId.lowercase() in mentionedNames || serverName.lowercase() in mentionedNames }
            .forEach { (serverId, serverName) ->
                val lastError = mcpManager?.getLastError(serverId)
                val isMounted = model.dynamicMcpTools.any { it.serverId == serverId }
                val toolCount = model.dynamicMcpTools.count { it.serverId == serverId }
                val desc = when {
                    isMounted -> "MCP 工具已挂载（$toolCount 个工具，模型可按需调用）"
                    lastError != null -> "⚠️ MCP 服务异常：$lastError"
                    else -> "已选择 MCP 服务，正在尝试发现工具..."
                }
                appendEventOnce(
                    existing,
                    sessionId,
                    id = "mcp:$userMessageId:$serverId",
                    kind = CapabilityEvent.Kind.MCP,
                    name = serverName,
                    description = desc,
                )
            }
    }

    private suspend fun appendEventOnce(
        existing: List<HarnessMessage>,
        sessionId: String,
        id: String,
        kind: CapabilityEvent.Kind,
        name: String,
        description: String,
    ) {
        if (existing.none { message -> message is CapabilityEvent && message.id == id }) {
            port.append(sessionId, CapabilityEvent(id, System.currentTimeMillis(), kind, name, description))
        }
    }
}
