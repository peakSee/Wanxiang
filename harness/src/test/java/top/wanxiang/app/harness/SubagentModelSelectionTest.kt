package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Test

class SubagentModelSelectionTest {
    @Test
    fun omittedTaskModelUsesRoleDefault() {
        assertEquals(
            SubagentModelRoute.RoleDefault("profile-coder", "coder-v2"),
            selectSubagentModel(null, "profile-coder", "coder-v2"),
        )
        assertEquals(
            SubagentModelRoute.RoleDefault("profile-coder", null),
            selectSubagentModel("  ", "profile-coder", null),
        )
    }

    @Test
    fun explicitTaskModelOverridesRoleDefault() {
        assertEquals(
            SubagentModelRoute.Requested("profile-fast"),
            selectSubagentModel(" profile-fast ", "profile-coder", "coder-v2"),
        )
    }

    @Test
    fun inheritExplicitlyBypassesRoleDefault() {
        assertEquals(SubagentModelRoute.Inherit, selectSubagentModel("inherit", "profile-coder", "coder-v2"))
        assertEquals(SubagentModelRoute.Inherit, selectSubagentModel(" INHERIT ", "profile-coder", null))
    }

    @Test
    fun missingSelectionsInheritParent() {
        assertEquals(SubagentModelRoute.Inherit, selectSubagentModel(null, null, null))
        assertEquals(SubagentModelRoute.Inherit, selectSubagentModel(null, "  ", "unused"))
    }
}
