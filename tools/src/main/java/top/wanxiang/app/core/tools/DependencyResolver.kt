package top.wanxiang.app.core.tools

import top.wanxiang.app.core.model.RuntimeName
import top.wanxiang.app.core.model.RuntimeRequirement
import top.wanxiang.app.core.model.ToolDependency
import top.wanxiang.app.core.model.ToolManifest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DependencyResolver @Inject constructor() {
    fun resolve(manifest: ToolManifest): List<RuntimeRequirement> = manifest.dependencies.mapNotNull { dependency ->
        ManifestDependencyParser.parse(dependency)?.let { parsed ->
            when (parsed.name) {
                "node" -> RuntimeRequirement(RuntimeName.NODE, parsed.constraint)
                "python" -> RuntimeRequirement(RuntimeName.PYTHON, parsed.constraint)
                "git" -> RuntimeRequirement(RuntimeName.GIT, parsed.constraint)
                "ca-certificates" -> RuntimeRequirement(RuntimeName.CA_CERTIFICATES, parsed.constraint)
                "curl" -> RuntimeRequirement(RuntimeName.CURL, parsed.constraint)
                else -> null
            }
        }
        }

    fun resolveDependency(dependency: ToolDependency): RuntimeRequirement? = when (dependency) {
        is ToolDependency.Runtime -> RuntimeRequirement(dependency.name, dependency.constraint)
        else -> null
    }
}
