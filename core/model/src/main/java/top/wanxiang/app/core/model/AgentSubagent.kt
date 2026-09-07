package top.wanxiang.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AgentSubagent(
    /** Stable identifier used by local dispatch and the optional exact role override. */
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    /** Saved model profile used by default; null means inherit the parent session model. */
    val defaultModelId: String? = null,
    /** Concrete model within the saved profile; null uses that profile's first configured model. */
    val defaultModelVariant: String? = null,
    val departmentId: String = AgentDepartments.CUSTOM_ID,
    val isEnabled: Boolean = true,
    val isBuiltin: Boolean = true,
    val sortOrder: Int = 0,
)

@Serializable
data class AgentSubagentIndexEntry(
    val id: String,
    val name: String,
    val description: String,
    val departmentId: String,
)

@Serializable
data class AgentDepartmentCount(
    val departmentId: String,
    val enabledCount: Int,
)

data class AgentDepartment(
    val id: String,
    val name: String,
    val localizedName: String,
    val icon: String,
    val colorArgb: Long,
    val sortOrder: Int,
)

/** Department metadata mirrored from the vendored Agency Agents divisions catalog. */
object AgentDepartments {
    const val CUSTOM_ID = "custom"

    val agency: List<AgentDepartment> = listOf(
        AgentDepartment("engineering", "Engineering", "工程研发", "Code", 0xFF3B82F6, 0),
        AgentDepartment("design", "Design", "UI/UX 设计", "PenTool", 0xFFEC4899, 1),
        AgentDepartment("product", "Product", "产品", "Box", 0xFFD946EF, 2),
        AgentDepartment("project-management", "Project Management", "项目管理", "ClipboardList", 0xFF0EA5E9, 3),
        AgentDepartment("testing", "Testing", "测试与质量", "FlaskConical", 0xFFF59E0B, 4),
        AgentDepartment("security", "Security", "软件安全", "ShieldCheck", 0xFFEF4444, 5),
        AgentDepartment("game-development", "Game Development", "游戏开发", "Gamepad2", 0xFFA855F7, 6),
        AgentDepartment("spatial-computing", "Spatial Computing", "空间计算", "Boxes", 0xFF06B6D4, 7),
        AgentDepartment("specialized", "Specialized", "研发专项", "Sparkles", 0xFF6366F1, 8),
    )

    val custom = AgentDepartment(CUSTOM_ID, "Custom", "自定义", "Bot", 0xFF64748B, Int.MAX_VALUE)
    private val byId = (agency + custom).associateBy { it.id }

    fun find(id: String): AgentDepartment = byId[id] ?: custom
}
