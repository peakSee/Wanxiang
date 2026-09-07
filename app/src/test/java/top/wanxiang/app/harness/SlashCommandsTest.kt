package top.wanxiang.app.harness

import top.wanxiang.app.ui.chat.SlashCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {

    @Test
    fun filtersCommandsCorrectly() {
        val all = SlashCommands.filterCommands("")
        assertEquals(SlashCommands.presetCommands.size, all.size)

        val runResults = SlashCommands.filterCommands("/run")
        assertTrue(runResults.any { it.command == "/run" })

        val installResults = SlashCommands.filterCommands("install")
        assertTrue(installResults.any { it.command == "/install" })

        val gitResults = SlashCommands.filterCommands("git")
        assertTrue(gitResults.any { it.command == "/git" })
    }

    @Test
    fun filtersActiveSkillsCommands() {
        val skill = top.wanxiang.app.core.model.AgentSkill(
            id = "custom_test",
            name = "Rust测试专家",
            description = "测试描述",
            systemPrompt = "prompt",
            triggerCommand = "/rust",
        )
        val results = SlashCommands.filterCommands("rust", listOf(skill))
        assertTrue(results.any { it.command == "/rust" && it.label == "Rust测试专家" })
    }
}

