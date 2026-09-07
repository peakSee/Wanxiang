package top.wanxiang.app.core.model

sealed interface ToolDependency {
    data class SystemPackage(val name: String) : ToolDependency
    data class Runtime(val name: RuntimeName, val constraint: String? = null) : ToolDependency
    data class Tool(val toolId: String) : ToolDependency
}

enum class RuntimeName {
    NODE,
    PYTHON,
    GIT,
    CA_CERTIFICATES,
    CURL,
}

data class RuntimeRequirement(
    val name: RuntimeName,
    val constraint: String? = null,
)

data class InstalledRuntime(
    val id: String,
    val name: RuntimeName,
    val version: String?,
    val executablePath: String,
    val referenceCount: Int,
)
