package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.model.AgentDepartmentCount
import top.wanxiang.app.core.model.AgentDepartments
import top.wanxiang.app.core.model.AgentSubagentIndexEntry

class SubagentProfileMatcherTest {
    private val index = listOf(
        entry(
            id = "agency_engineering_frontend_developer",
            name = "Frontend Developer",
            description = "Modern web technologies, React, Vue, Angular, UI implementation and performance optimization",
            department = "engineering",
        ),
        entry(
            id = "agency_engineering_mobile_release_engineer",
            name = "Mobile Release Engineer",
            description = "iOS and Android signing, stores, phased rollouts and release health",
            department = "engineering",
        ),
        entry(
            id = "agency_engineering_mobile_app_builder",
            name = "Mobile App Builder",
            description = "Native iOS and Android application development and cross-platform frameworks",
            department = "engineering",
        ),
        entry(
            id = "agency_testing_test_automation_engineer",
            name = "Test Automation Engineer",
            description = "End-to-end Playwright and Cypress automation with resilient selectors",
            department = "testing",
        ),
    )

    @Test
    fun `routes frontend keywords inside engineering`() {
        val match = SubagentProfileMatcher.match(index, "engineering", "frontend react")

        assertEquals("agency_engineering_frontend_developer", match?.id)
    }

    @Test
    fun `uses all useful keywords to distinguish mobile build from release`() {
        val match = SubagentProfileMatcher.match(index, "Engineering", "mobile android app")

        assertEquals("agency_engineering_mobile_app_builder", match?.id)
    }

    @Test
    fun `filters by department before lexical scoring`() {
        val match = SubagentProfileMatcher.match(index, "testing", "test automation")

        assertEquals("agency_testing_test_automation_engineer", match?.id)
        assertNull(SubagentProfileMatcher.match(index, "engineering", "playwright cypress"))
    }

    @Test
    fun `accepts an exact id query and rejects unrelated keywords`() {
        val exact = SubagentProfileMatcher.match(
            index,
            "engineering",
            "agency_engineering_mobile_app_builder",
        )

        assertEquals("agency_engineering_mobile_app_builder", exact?.id)
        assertNull(SubagentProfileMatcher.match(index, "engineering", "quantum accounting"))
    }

    @Test
    fun `department prompt index has constant profile-independent size`() {
        val rendered = SubagentDepartmentIndexRenderer.render(
            AgentDepartments.agency.mapIndexed { index, department ->
                AgentDepartmentCount(department.id, index + 1)
            },
        )

        AgentDepartments.agency.forEach { department ->
            assertTrue(rendered.contains("department=\"${department.id}\""))
        }
        assertEquals(AgentDepartments.agency.size, rendered.lineSequence().count())
        assertFalse(rendered.contains("agency_"))
        assertFalse(rendered.contains("Frontend Developer"))
        assertTrue(rendered.length < 1_000)
    }

    private fun entry(
        id: String,
        name: String,
        description: String,
        department: String,
    ) = AgentSubagentIndexEntry(id, name, description, department)
}
