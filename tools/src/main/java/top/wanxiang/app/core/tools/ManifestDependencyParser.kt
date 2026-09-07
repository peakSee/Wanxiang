package top.wanxiang.app.core.tools

data class ParsedManifestDependency(
    val name: String,
    val constraint: String?,
)

/** Parses the compact, data-only dependency notation used by the local/remote registry. */
object ManifestDependencyParser {
    private val PATTERN = Regex("([a-z][a-z-]*)(?:\\s*(>=|>|=)\\s*(\\d+(?:\\.\\d+){0,3}))?")

    fun parse(value: String): ParsedManifestDependency? {
        val match = PATTERN.matchEntire(value.trim().lowercase()) ?: return null
        val name = match.groupValues[1]
        val operator = match.groupValues[2].takeIf { it.isNotBlank() }
        val version = match.groupValues[3].takeIf { it.isNotBlank() }
        return ParsedManifestDependency(name, operator?.let { it + version })
    }
}
