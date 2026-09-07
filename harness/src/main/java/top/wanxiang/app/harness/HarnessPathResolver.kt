package top.wanxiang.app.harness
import javax.inject.Inject
import javax.inject.Singleton
/**
 * Single source of truth for Harness path semantics.
 */
@Singleton
class HarnessPathResolver @Inject constructor() {
    fun resolveWorkingDirectory(explicitCwd: String?, workspace: String): String {
        val candidate = explicitCwd?.takeIf { it.isNotBlank() }
        if (candidate != null) return normalize(candidate)
        if (workspace.isBlank()) return DEFAULT_CWD
        return if (workspace.startsWith("/")) normalize(workspace) else normalize("/workspace/" + workspace)
    }

    fun resolveAbsolutePath(path: String, workspace: String): String {
        if (path.isBlank()) return resolveWorkingDirectory(null, workspace)
        val normalized = normalize(path)
        if (normalized.startsWith("/")) return collapseSlashes(normalized)
        return collapseSlashes(resolveWorkingDirectory(null, workspace) + "/" + normalized)
    }

    fun isWithinWorkspace(path: String, workspace: String): Boolean {
        if (workspace.isBlank()) return false
        if (path.isBlank()) return false
        if (path.indexOf(0.toChar()) >= 0) return false
        val normalizedPath = normalize(path)
        if (normalizedPath.split("/").any { it == ".." }) return false
        val resolved = if (normalizedPath.startsWith("/")) normalizedPath else resolveAbsolutePath(path, workspace)
        val resolvedWorkspace = resolveWorkingDirectory(null, workspace)
        return resolved == resolvedWorkspace || resolved.startsWith(resolvedWorkspace + "/")
    }

    private fun normalize(path: String): String = path.replace('\\', '/').trimEnd('/')
    private fun collapseSlashes(path: String): String = path.split('/').filter { it.isNotEmpty() }.joinToString("/", prefix = if (path.startsWith("/")) "/" else "./")
    companion object { const val DEFAULT_CWD = "/root" }
}
