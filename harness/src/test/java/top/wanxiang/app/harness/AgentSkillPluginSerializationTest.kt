package top.wanxiang.app.harness

import top.wanxiang.app.core.model.AgentPlugin
import top.wanxiang.app.core.model.AgentSkill
import top.wanxiang.app.core.model.BuiltinPlugins
import top.wanxiang.app.core.model.BuiltinSkills
import top.wanxiang.app.core.model.AgentSubagent
import top.wanxiang.app.core.model.AgentDepartments
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillPluginSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun testBuiltinSkillsPresets() {
        val presets = BuiltinSkills.presets
        assertTrue(presets.isNotEmpty())
        val encoded = json.encodeToString(presets)
        val decoded = json.decodeFromString<List<AgentSkill>>(encoded)
        assertEquals(presets.size, decoded.size)
        assertEquals("agent_context", decoded.first().id)
        assertNotNull(decoded.find { it.id == "linux_ops" })
    }

    @Test
    fun testBuiltinPluginsPresets() {
        val plugins = BuiltinPlugins.presets
        assertTrue(plugins.isNotEmpty())
        val encoded = json.encodeToString(plugins)
        val decoded = json.decodeFromString<List<AgentPlugin>>(encoded)
        assertEquals(plugins.size, decoded.size)
        assertNotNull(decoded.find { it.id == "proot_health_probe" })
    }

    @Test
    fun testCustomSkillCreationAndSerialization() {
        val custom = AgentSkill(
            id = "custom_1",
            name = "Flutter 移动端专家",
            description = "Flutter & Dart 代码架构",
            systemPrompt = "遵循 Flutter Clean Architecture",
            triggerCommand = "/flutter",
            isBuiltin = false,
        )
        val encoded = json.encodeToString(custom)
        val decoded = json.decodeFromString<AgentSkill>(encoded)
        assertEquals(custom.name, decoded.name)
        assertEquals(custom.triggerCommand, decoded.triggerCommand)
    }

    @Test
    fun testDepartmentAwareSubagentIsSerializable() {
        assertEquals(9, AgentDepartments.agency.size)
        assertEquals(9, AgentDepartments.agency.map { it.id }.distinct().size)
        val profile = AgentSubagent(
            id = "agency_engineering_frontend_developer",
            name = "Frontend Developer",
            description = "Frontend implementation",
            systemPrompt = "Build production-ready interfaces.",
            departmentId = "engineering",
            sortOrder = 0,
        )

        val encoded = json.encodeToString(profile)
        val decoded = json.decodeFromString<AgentSubagent>(encoded)
        assertEquals(profile, decoded)
        assertEquals("Engineering", AgentDepartments.find(decoded.departmentId).name)
    }
}
