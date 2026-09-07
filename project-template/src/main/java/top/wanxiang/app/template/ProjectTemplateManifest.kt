package top.wanxiang.app.template

import kotlinx.serialization.Serializable

@Serializable
data class ProjectTemplateManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val projectType: TemplateProjectType = TemplateProjectType.GENERAL,
    val category: ProjectTemplateCategory = ProjectTemplateCategory(),
    /** Optional 270 x 270 preview image stored inside the template package. */
    val previewImage: String = "",
    val variables: List<ProjectTemplateVariable> = emptyList(),
    val hooks: ProjectTemplateHooks = ProjectTemplateHooks(),
    val validation: ProjectTemplateValidation = ProjectTemplateValidation(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MANIFEST_PATH = "template.json"
    }
}

@Serializable enum class TemplateProjectType { ANDROID, FLUTTER, GENERAL }

@Serializable
data class ProjectTemplateCategory(val id: String = "general", val name: String = "General", val sortOrder: Int = 0)

@Serializable
data class ProjectTemplateVariable(
    val name: String,
    val label: String = name,
    val description: String = "",
    val prompt: Boolean = false,
    val inputType: ProjectTemplateInputType = ProjectTemplateInputType.TEXT,
    val placeholder: String = "",
    val options: List<ProjectTemplateVariableOption> = emptyList(),
    val required: Boolean = true,
    val defaultValue: String = "",
    val validationRegex: String = "",
)

@Serializable enum class ProjectTemplateInputType { TEXT, MULTILINE, NUMBER, BOOLEAN, SELECT, SECRET }
@Serializable data class ProjectTemplateVariableOption(val value: String, val label: String = value)
@Serializable data class ProjectTemplateHooks(val beforeCreate: String = "", val afterCreate: String = "")

@Serializable
data class ProjectTemplateValidation(
    val requiredFiles: List<String> = emptyList(),
    val forbiddenFiles: List<String> = emptyList(),
    val contentRules: List<ProjectTemplateContentRule> = emptyList(),
)

@Serializable
data class ProjectTemplateContentRule(
    val path: String,
    val equals: String = "",
    val contains: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
)
