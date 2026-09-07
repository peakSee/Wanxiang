package top.wanxiang.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 长期语义与事实记忆实体
 * 用于记录用户的长期偏好、项目架构规范、全局事实。
 *
 * 基于 Reasonix Context Engine v2 语义扩展：
 * - subjectKey: 主题冲突去重键（如 "project.package_manager"）
 * - revision: 不可变修订版本号（每次修改+1）
 * - pinned: 是否钉选到 system prompt 稳定前缀（正交于检索召回）
 * - expiresAt: 过期时间戳（null=永不过期）
 * - lastVerifiedAt: 最后验证新鲜度时间戳
 * - volatility: 新鲜度分级（reference/project/user）
 */
@Entity(
    tableName = "agent_memories",
    indices = [
        Index(value = ["scope", "ownerId", "key"]),
        Index(value = ["scope", "ownerId", "subjectKey"]),
        Index(value = ["pinned"]),
        Index(value = ["expiresAt"]),
    ],
)
data class AgentMemoryEntity(
    @PrimaryKey
    val id: String,
    val scope: String, // global, project, session
    /** global 为空；project 为稳定 workspace；session 为 sessionId。 */
    @ColumnInfo(defaultValue = "''")
    val ownerId: String = "",
    val kind: String,  // preference, rule, fact, project_info
    val key: String,
    val value: String,
    /** 主题冲突去重键（dotted format，如 project.package_manager），空字符串表示无主题 */
    @ColumnInfo(defaultValue = "''")
    val subjectKey: String = "",
    /** 修订版本号（从 1 开始，每次内容变更递增） */
    @ColumnInfo(defaultValue = "1")
    val revision: Int = 1,
    /** 是否钉选到 system prompt 稳定前缀（pinned 总是注入，非 pinned 仅检索召回） */
    @ColumnInfo(defaultValue = "0")
    val pinned: Boolean = false,
    /** 过期时间戳（毫秒，null=永不过期） */
    @ColumnInfo(defaultValue = "null")
    val expiresAt: Long? = null,
    /** 最后验证新鲜度时间戳（毫秒） */
    @ColumnInfo(defaultValue = "0")
    val lastVerifiedAt: Long = 0,
    /** 新鲜度波动率：reference=稳定参考，project=项目上下文，user=用户偏好易变 */
    @ColumnInfo(defaultValue = "reference")
    val volatility: String = "reference",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 结构化多步骤任务执行计划实体
 */
@Entity(tableName = "agent_plans")
data class AgentPlanEntity(
    @PrimaryKey
    val sessionId: String,
    val goal: String,
    val stepsJson: String, // List<PlanStep> JSON
    val status: String,    // active, completed, cancelled
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 会话/任务局部工作草稿便签实体
 */
@Entity(tableName = "agent_scratchpads", primaryKeys = ["sessionId", "key"])
data class AgentScratchpadEntity(
    val sessionId: String,
    val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
