package top.wanxiang.app.ui.chat

import top.wanxiang.app.ui.components.RuntimeIconName
import android.content.Context
import top.wanxiang.app.feature.chat.R

data class SlashCommandItem(
    val command: String,
    val label: String,
    val description: String,
    val template: String,
    val icon: RuntimeIconName,
)

object SlashCommands {
    private data class Preset(
        val command: String,
        val labelRes: Int,
        val descriptionRes: Int,
        val template: String,
        val icon: RuntimeIconName,
    )

    private val presets = listOf(
        Preset(
            command = "/wf",
            labelRes = R.string.chat_command_workflow,
            descriptionRes = R.string.chat_command_workflow_description,
            template = "/wf ",
            icon = RuntimeIconName.Hub,
        ),
        Preset(
            command = "/run",
            labelRes = R.string.chat_command_run,
            descriptionRes = R.string.chat_command_run_description,
            template = "/run ",
            icon = RuntimeIconName.Play,
        ),
        Preset(
            command = "/install",
            labelRes = R.string.chat_command_install,
            descriptionRes = R.string.chat_command_install_description,
            template = "/install ",
            icon = RuntimeIconName.Package,
        ),
        Preset(
            command = "/init",
            labelRes = R.string.chat_command_init,
            descriptionRes = R.string.chat_command_init_description,
            template = "/init ",
            icon = RuntimeIconName.Plus,
        ),
        Preset(
            command = "/git",
            labelRes = R.string.chat_command_git,
            descriptionRes = R.string.chat_command_git_description,
            template = "/git status",
            icon = RuntimeIconName.Code,
        ),
        Preset(
            command = "/test",
            labelRes = R.string.chat_command_test,
            descriptionRes = R.string.chat_command_test_description,
            template = "/test ",
            icon = RuntimeIconName.Check,
        ),
        Preset(
            command = "/clear",
            labelRes = R.string.chat_command_clear,
            descriptionRes = R.string.chat_command_clear_description,
            template = "/clear",
            icon = RuntimeIconName.Trash,
        ),
        Preset(
            command = "/help",
            labelRes = R.string.chat_command_help,
            descriptionRes = R.string.chat_command_help_description,
            template = "/help",
            icon = RuntimeIconName.Alert,
        ),
    )

    /** Resource-independent command metadata for filtering and JVM tests. */
    val presetCommands: List<SlashCommandItem> = presets.map { preset ->
        SlashCommandItem(preset.command, "", "", preset.template, preset.icon)
    }

    fun presetCommands(context: Context): List<SlashCommandItem> = presets.map { preset ->
        SlashCommandItem(
            command = preset.command,
            label = context.getString(preset.labelRes),
            description = context.getString(preset.descriptionRes),
            template = preset.template,
            icon = preset.icon,
        )
    }

    fun filterCommands(
        query: String,
        activeSkills: List<top.wanxiang.app.core.model.AgentSkill> = emptyList(),
        workflows: List<top.wanxiang.app.core.model.workflow.WorkflowDefinition> = emptyList(),
    ): List<SlashCommandItem> = filterCommands(presetCommands, query, activeSkills, workflows)

    fun filterCommands(
        context: Context,
        query: String,
        activeSkills: List<top.wanxiang.app.core.model.AgentSkill> = emptyList(),
        workflows: List<top.wanxiang.app.core.model.workflow.WorkflowDefinition> = emptyList(),
    ): List<SlashCommandItem> {
        return filterCommands(presetCommands(context), query, activeSkills, workflows)
    }

    private fun filterCommands(
        presetItems: List<SlashCommandItem>,
        query: String,
        activeSkills: List<top.wanxiang.app.core.model.AgentSkill>,
        workflows: List<top.wanxiang.app.core.model.workflow.WorkflowDefinition>,
    ): List<SlashCommandItem> {
        val skillItems = activeSkills.mapNotNull { skill ->
            val cmd = skill.triggerCommand ?: return@mapNotNull null
            SlashCommandItem(
                command = if (cmd.startsWith("/")) cmd else "/$cmd",
                label = skill.name,
                description = skill.description,
                template = if (cmd.startsWith("/")) "$cmd " else "/$cmd ",
                icon = RuntimeIconName.Code,
            )
        }
        val workflowItems = workflows.map { wf ->
            SlashCommandItem(
                command = "/wf ${wf.id}",
                label = wf.name,
                description = "${wf.category} · ${wf.description}",
                template = "/wf ${wf.id}",
                icon = RuntimeIconName.Hub,
            )
        }
        val all = (skillItems + presetItems + workflowItems).distinctBy { it.command }
        val q = query.trim().removePrefix("/").lowercase()
        if (q.isEmpty()) return all

        if (q.startsWith("wf")) {
            val subQuery = q.removePrefix("wf").trim()
            if (subQuery.isEmpty()) {
                val wfPreset = presetItems.firstOrNull { it.command == "/wf" }
                return listOfNotNull(wfPreset) + workflowItems
            }
            return workflowItems.filter {
                it.command.lowercase().contains(subQuery) ||
                    it.label.lowercase().contains(subQuery) ||
                    it.description.lowercase().contains(subQuery)
            }
        }

        return all.filter {
            it.command.removePrefix("/").contains(q) ||
                it.label.lowercase().contains(q) ||
                it.description.lowercase().contains(q)
        }
    }
}
