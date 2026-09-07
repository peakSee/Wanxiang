package top.wanxiang.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ToolManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val description: String,
    val dependencies: List<String> = emptyList(),
    val launchType: String = "one_shot",
    val servicePort: Int? = null,
    val servicePath: String = "/",
    val version: String = "0.1.0",
    val latestVersion: String? = null,
    val enabled: Boolean = true,
    val icon: String? = null,
    val homepage: String? = null,
    val publisher: String = "",
    val category: String = "AI_AGENT",
    val architectures: List<String> = listOf("ARM64"),
    val permissions: List<String> = emptyList(),
    val updateStrategy: String = "REINSTALL",
    val installMethod: String = "SCRIPT",
    val installSteps: List<String> = emptyList(),
    val uninstallSteps: List<String> = emptyList(),
    val launchCommand: String? = null,
    val verifyCommand: String? = null,
    val commandLinks: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    /** REMOTE is supplied by the signed/online registry; LOCAL is imported from a .txplugin package. */
    val source: String = "REMOTE",
    /** Local packages must be self-contained and may use WANXIANG_PLUGIN_PAYLOAD. */
    val offlineOnly: Boolean = false,
) {
    val installScript: String?
        get() = installSteps.takeIf { it.isNotEmpty() }?.joinToString("\n")

    val uninstallScript: String?
        get() = uninstallSteps.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
