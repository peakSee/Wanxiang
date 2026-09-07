package top.wanxiang.app.harness

import top.wanxiang.app.core.database.AgentContextRepository
import top.wanxiang.app.core.database.AgentMemoryEntity
import top.wanxiang.app.core.database.AgentPlanEntity
import top.wanxiang.app.core.database.AgentScratchpadEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 负责执行 agent-context 核心系统能力工具：
 * - memory: 长期语义与事实记忆
 * - plan: 多步骤任务执行计划管理
 * - scratchpad: 任务局部工作草稿与排查便签
 */
@Singleton
class AgentContextExecutor @Inject constructor(
    private val agentContextDao: AgentContextRepository,
    private val json: Json,
) {
    suspend fun executeMemory(args: JsonObject, sessionId: String, workspace: String): Pair<Boolean, String> {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        return when (action) {
            "save" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "memory save 必须提供 key 参数"
                val value = args["value"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "memory save 必须提供 value 参数"
                if (key.length > MAX_MEMORY_KEY_CHARS) return false to "memory key 不能超过 $MAX_MEMORY_KEY_CHARS 字符"
                if (value.length > MAX_MEMORY_VALUE_CHARS) return false to "memory value 不能超过 $MAX_MEMORY_VALUE_CHARS 字符"
                val kind = args["kind"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "fact"
                val scope = args["scope"]?.jsonPrimitive?.contentOrNull?.lowercase()
                    ?: if (workspace.isNotBlank()) "project" else "global"
                val ownerId = memoryOwner(scope, sessionId, workspace)
                    ?: return false to "scope 仅支持 global/project/session，且 project/session 必须有对应上下文"
                val subjectKey = args["subjectKey"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotBlank() } ?: key
                val volatility = args["volatility"]?.jsonPrimitive?.contentOrNull?.lowercase()
                    ?.takeIf { it in setOf("reference", "project", "user") } ?: "reference"
                val pinned = (args["pinned"]?.jsonPrimitive?.contentOrNull?.lowercase()) == "true"
                val expiresAt = args["expiresAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val now = System.currentTimeMillis()

                // 同主题冲突去重：同 (scope, ownerId, subjectKey) 只有一个活动修订。
                val existing = agentContextDao.getMemoryBySubjectKey(subjectKey, scope, ownerId)
                    ?: agentContextDao.getMemoryByKey(key, scope, ownerId)
                if (existing == null && agentContextDao.countMemories(scope, ownerId) >= MAX_MEMORIES_PER_OWNER) {
                    return false to "[$scope] 记忆已达到上限 $MAX_MEMORIES_PER_OWNER 条，请删除旧记忆后再保存"
                }
                if (pinned) {
                    val pinnedBudget = MAX_PINNED_CHARS - agentContextDao.getPinnedMemories(projectOwner(workspace), sessionId)
                        .filter { it.id != existing?.id }.sumOf { it.value.length }
                    if (value.length > pinnedBudget) {
                        return false to "pinned 记忆总字符已接近上限（~$MAX_PINNED_CHARS），请精简内容或改为非 pinned"
                    }
                }
                val id = existing?.id ?: UUID.randomUUID().toString()
                val valueChanged = existing?.value != value
                agentContextDao.saveMemory(
                    AgentMemoryEntity(
                        id = id,
                        scope = scope,
                        ownerId = ownerId,
                        kind = kind,
                        key = key,
                        value = value,
                        subjectKey = subjectKey,
                        revision = (existing?.revision ?: 0) + if (valueChanged) 1 else 0,
                        pinned = pinned,
                        expiresAt = expiresAt,
                        lastVerifiedAt = existing?.lastVerifiedAt ?: 0,
                        volatility = volatility,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    )
                )
                val conflictNote = if (existing != null && subjectKey != key && existing.key != subjectKey) {
                    " 同主题($subjectKey)去重并升级为 revision=${existing.revision + if (valueChanged) 1 else 0}"
                } else ""
                val savedMessage = if (existing != null) {
                    val revNote = if (valueChanged) {
                        "，升级为 revision=${existing.revision + 1}"
                    } else {
                        "（内容未变化，revision=${existing.revision}）"
                    }
                    "已更新既有长期记忆[$revNote，原值被覆盖][$scope/$kind] $key = $value$conflictNote"
                } else {
                    "已成功存储长期记忆 [$scope/$kind] $key = $value"
                }
                true to savedMessage
            }
            "verify" -> {
                // 新鲜度续期：模型确认记忆仍有效时刷新 lastVerifiedAt。
                val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim()
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                val target = when {
                    id != null -> agentContextDao.getMemoryById(id)
                    key != null -> {
                        val scope = args["scope"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "global"
                        val ownerId = memoryOwner(scope, sessionId, workspace)
                            ?: return false to "scope 仅支持 global/project/session"
                        agentContextDao.getMemoryByKey(key, scope, ownerId)
                    }
                    else -> return false to "memory verify 需提供 id 或 key"
                } ?: return false to "未找到要核验的记忆"
                agentContextDao.touchMemory(target.id, System.currentTimeMillis())
                true to "已刷新记忆 ${target.key} 的新鲜度（lastVerifiedAt 续期）"
            }
            "query", "search" -> {
                val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "memory query 必须提供 query 或 key"
                val includeExpired = (args["include_expired"]?.jsonPrimitive?.contentOrNull?.lowercase()) == "true"
                val results = agentContextDao.searchMemories(
                    query = query.take(MAX_MEMORY_QUERY_CHARS),
                    projectOwnerId = projectOwner(workspace),
                    sessionId = sessionId,
                ).filter { includeExpired || isFresh(it, System.currentTimeMillis()) }
                if (results.isEmpty()) {
                    true to "未查询到与 '$query' 相关的记忆"
                } else {
                    val formatted = results.joinToString("\n") { it.render(lineBreak = true) }
                    true to "查询到以下记忆：\n$formatted"
                }
            }
            "list" -> {
                val includeExpired = (args["include_expired"]?.jsonPrimitive?.contentOrNull?.lowercase()) == "true"
                val results = agentContextDao.getFreshMemories(
                    projectOwnerId = projectOwner(workspace),
                    sessionId = sessionId,
                    pinned = false,
                    now = System.currentTimeMillis(),
                    limit = MAX_MEMORY_LIST,
                ).plus(
                    if (includeExpired) agentContextDao.getMemoriesForContext(projectOwner(workspace), sessionId)
                        .filter { !isFresh(it, System.currentTimeMillis()) }
                    else emptyList(),
                ).distinctBy { it.id }
                if (results.isEmpty()) {
                    true to "当前暂无长期记忆"
                } else {
                    val formatted = results.joinToString("\n") { it.render() }
                    true to "已保存的记忆列表：\n$formatted"
                }
            }
            "delete" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim()
                if (id != null) {
                    val memory = agentContextDao.getMemoryById(id)
                        ?: return false to "未找到记忆 id=$id"
                    val visible = when (memory.scope) {
                        "global" -> memory.ownerId.isEmpty()
                        "project" -> memory.ownerId == projectOwner(workspace) && memory.ownerId.isNotBlank()
                        "session" -> memory.ownerId == sessionId && sessionId.isNotBlank()
                        else -> false
                    }
                    if (!visible) return false to "无权删除不属于当前项目或会话的记忆"
                    agentContextDao.deleteMemoryById(id)
                    true to "已删除记忆 id=$id"
                } else if (key != null) {
                    val scope = args["scope"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "global"
                    val ownerId = memoryOwner(scope, sessionId, workspace)
                        ?: return false to "scope 仅支持 global/project/session，且 project/session 必须有对应上下文"
                    agentContextDao.deleteMemoryByKey(key, scope, ownerId)
                    true to "已删除记忆 [$scope] key=$key"
                } else {
                    false to "memory delete 需提供 id 或 key"
                }
            }
            else -> false to "未知的 memory 动作: $action"
        }
    }

    private fun memoryOwner(scope: String, sessionId: String, workspace: String): String? = when (scope) {
        "global" -> ""
        "project" -> projectOwner(workspace).takeIf { it.isNotBlank() }
        "session" -> sessionId.trim().takeIf { it.isNotBlank() }
        else -> null
    }

    private fun projectOwner(workspace: String): String = workspace.trim().trimEnd('/')

    /** 新鲜度判定：expiresAt 为 null 或晚于 now 视为新鲜（过期只降权，不删除）。 */
    private fun isFresh(memory: AgentMemoryEntity, now: Long): Boolean {
        val expires = memory.expiresAt
        return expires == null || expires > now
    }

    private fun AgentMemoryEntity.render(lineBreak: Boolean = false): String {
        val suffix = buildString {
            if (pinned) append(" pinned")
            if (expiresAt != null && !isFresh(this@render, System.currentTimeMillis())) append(" 已过期")
            append(" v${revision}")
        }
        val separator = if (lineBreak) "\n        " else " "
        return if (lineBreak) {
            "- [${scope}/${kind}] ${key}: ${value}${separator}(volatility=$volatility, freshness=$suffix)"
        } else {
            "- [${scope}/${kind}] ${key}: ${value}$separator(volatility=$volatility, freshness=$suffix)"
        }
    }

    private companion object {
        const val MAX_MEMORY_KEY_CHARS = 128
        const val MAX_MEMORY_VALUE_CHARS = 4_096
        const val MAX_MEMORY_QUERY_CHARS = 256
        const val MAX_MEMORIES_PER_OWNER = 100
        const val MAX_MEMORY_LIST = 100
        const val MAX_PINNED_CHARS = 1_500
    }

    suspend fun executePlan(args: JsonObject, sessionId: String): Pair<Boolean, String> {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "get"
        return when (action) {
            "replace_active", "set_active", "create" -> {
                val goal = args["goal"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "plan replace_active 必须提供 goal 目标描述"
                val stepsElement = args["steps"]
                val stepsJson = stepsElement?.toString() ?: "[]"
                agentContextDao.savePlan(
                    AgentPlanEntity(
                        sessionId = sessionId,
                        goal = goal,
                        stepsJson = stepsJson,
                        status = "active",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                true to "已成功创建/更新任务执行规划：\n目标：$goal\n步骤：$stepsJson\n\n提示：请按规划推进执行。每个步骤完成时调用 plan(action=\"advance\", ...) 更新步骤状态；遇到阻碍时调用 replace_active 调整方案；全部完成时调用 clear_active。"
            }
            "get_active", "get" -> {
                val plan = agentContextDao.getActivePlan(sessionId)
                if (plan == null) {
                    true to "当前会话暂无活跃任务计划"
                } else {
                    true to "当前活跃任务计划：\n目标：${plan.goal}\n步骤：${plan.stepsJson}\n状态：${plan.status}"
                }
            }
            "advance", "update_steps" -> {
                val plan = agentContextDao.getActivePlan(sessionId)
                    ?: return false to "当前会话没有可推进的活跃计划，请先用 replace_active 创建"
                val stepsElement = args["steps"]
                val newStepsJson = stepsElement?.toString() ?: plan.stepsJson
                val newStatus = args["status"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: plan.status
                agentContextDao.savePlan(
                    plan.copy(
                        stepsJson = newStepsJson,
                        status = newStatus,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                true to "【规划进度已同步更新】\n状态：$newStatus\n当前步骤：$newStepsJson\n请继续推进后续任务。"
            }
            "clear_active", "clear" -> {
                agentContextDao.deletePlanBySession(sessionId)
                true to "已清空当前会话的任务规划"
            }
            else -> false to "未知的 plan 动作: $action"
        }
    }

    suspend fun executeScratchpad(args: JsonObject, sessionId: String): Pair<Boolean, String> {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        return when (action) {
            "save", "set" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "scratchpad save 必须提供 key"
                val value = args["value"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "scratchpad save 必须提供 value"
                agentContextDao.saveScratchpad(
                    AgentScratchpadEntity(
                        sessionId = sessionId,
                        key = key,
                        value = value,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                true to "已记录工作草稿 [$key] = $value"
            }
            "get" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "scratchpad get 必须提供 key"
                val item = agentContextDao.getScratchpad(sessionId, key)
                if (item == null) {
                    true to "草稿 [$key] 不存在"
                } else {
                    true to "草稿 [$key]：${item.value}"
                }
            }
            "list" -> {
                val list = agentContextDao.listScratchpads(sessionId)
                if (list.isEmpty()) {
                    true to "当前无工作草稿记录"
                } else {
                    val formatted = list.joinToString("\n") { "- [${it.key}]: ${it.value}" }
                    true to "当前工作草稿：\n$formatted"
                }
            }
            "delete" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return false to "scratchpad delete 必须提供 key"
                agentContextDao.deleteScratchpad(sessionId, key)
                true to "已删除草稿 [$key]"
            }
            "clear" -> {
                agentContextDao.clearScratchpads(sessionId)
                true to "已清空当前任务的所有工作草稿"
            }
            else -> false to "未知的 scratchpad 动作: $action"
        }
    }
}
