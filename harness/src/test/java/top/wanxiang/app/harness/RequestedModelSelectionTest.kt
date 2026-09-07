package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.wanxiang.app.core.database.AiModelEntity

class RequestedModelSelectionTest {
    private val profiles = listOf(
        AiModelEntity(
            id = "profile-fast",
            name = "Fast Executor",
            provider = "openai",
            model = "gpt-4o-mini,gpt-4.1-mini",
            createdAt = 1L,
        ),
        AiModelEntity(
            id = "profile-planner",
            name = "Deep Planner",
            provider = "deepseek",
            model = "deepseek-reasoner",
            createdAt = 2L,
        ),
    )

    @Test
    fun `profile id and name resolve without a variant override`() {
        assertEquals(RequestedModelTarget("profile-fast"), selectRequestedModelTarget(profiles, "profile-fast"))
        assertEquals(RequestedModelTarget("profile-planner"), selectRequestedModelTarget(profiles, "deep planner"))
    }

    @Test
    fun `configured concrete model resolves to its owning profile`() {
        assertEquals(
            RequestedModelTarget("profile-fast", "gpt-4.1-mini"),
            selectRequestedModelTarget(profiles, "GPT-4.1-MINI"),
        )
    }

    @Test
    fun `unknown model does not silently select the active profile`() {
        assertNull(selectRequestedModelTarget(profiles, "not-configured"))
    }
}
