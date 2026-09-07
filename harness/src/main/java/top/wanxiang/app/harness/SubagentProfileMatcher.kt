package top.wanxiang.app.harness

import java.util.Locale
import top.wanxiang.app.core.model.AgentDepartmentCount
import top.wanxiang.app.core.model.AgentDepartments
import top.wanxiang.app.core.model.AgentSubagentIndexEntry

/** Deterministic, local-only routing over the lightweight enabled-agent catalog index. */
internal object SubagentProfileMatcher {
    fun match(
        entries: List<AgentSubagentIndexEntry>,
        department: String,
        query: String,
    ): AgentSubagentIndexEntry? {
        val departmentId = resolveDepartmentId(department)
        val candidates = entries.filter { it.departmentId.equals(departmentId, ignoreCase = true) }
        if (candidates.isEmpty()) return null

        val rawQuery = query.trim()
        if (rawQuery.isEmpty()) return null
        candidates.firstOrNull { it.id.equals(rawQuery, ignoreCase = true) }?.let { return it }
        candidates.firstOrNull { it.name.equals(rawQuery, ignoreCase = true) }?.let { return it }

        val queryTokens = tokenize(rawQuery).filterNot { it in QUERY_STOP_WORDS }
        if (queryTokens.isEmpty()) return null

        return candidates.mapIndexedNotNull { position, entry ->
            val score = score(entry, queryTokens) ?: return@mapIndexedNotNull null
            RankedEntry(entry, score, position)
        }.sortedWith(
            compareByDescending<RankedEntry> { it.score }
                .thenBy { it.position }
                .thenBy { it.entry.id },
        ).firstOrNull()?.entry
    }

    private fun score(entry: AgentSubagentIndexEntry, queryTokens: List<String>): Int? {
        val idTokens = tokenize(entry.id)
        val nameTokens = tokenize(entry.name)
        val descriptionTokens = tokenize(entry.description)
        var matched = 0
        var score = 0

        if (nameTokens == queryTokens) score += 50_000
        if (idTokens == queryTokens || idTokens.endsWith(queryTokens)) score += 40_000
        if (nameTokens.containsSequence(queryTokens)) score += 8_000
        if (idTokens.containsSequence(queryTokens)) score += 6_000
        if (descriptionTokens.containsSequence(queryTokens)) score += 1_200
        if (nameTokens.containsPrefixSequence(queryTokens)) score += 4_000
        if (idTokens.containsPrefixSequence(queryTokens)) score += 3_000

        queryTokens.forEach { token ->
            val tokenScore = when {
                nameTokens.any { it == token } -> 500
                idTokens.any { it == token } -> 400
                nameTokens.any { it.prefixMatches(token) } -> 350
                idTokens.any { it.prefixMatches(token) } -> 300
                descriptionTokens.any { it == token } -> 100
                descriptionTokens.any { it.prefixMatches(token) } -> 60
                else -> 0
            }
            if (tokenScore > 0) matched++
            score += tokenScore
        }

        if (matched == 0) return null
        score += matched * 200
        if (matched == queryTokens.size) score += 2_000
        return score
    }

    private fun resolveDepartmentId(value: String): String {
        val trimmed = value.trim()
        val known = (AgentDepartments.agency + AgentDepartments.custom).firstOrNull { department ->
            department.id.equals(trimmed, ignoreCase = true) ||
                department.name.equals(trimmed, ignoreCase = true) ||
                department.localizedName.equals(trimmed, ignoreCase = true)
        }
        return known?.id ?: trimmed.lowercase(Locale.ROOT).replace('_', '-').replace(' ', '-')
    }

    private fun tokenize(value: String): List<String> = TOKEN.findAll(value.lowercase(Locale.ROOT))
        .map { it.value }
        .toList()

    private fun List<String>.endsWith(suffix: List<String>): Boolean =
        suffix.size <= size && takeLast(suffix.size) == suffix

    private fun List<String>.containsSequence(needle: List<String>): Boolean =
        needle.size <= size && windowed(needle.size).any { it == needle }

    private fun List<String>.containsPrefixSequence(needle: List<String>): Boolean =
        needle.size <= size && windowed(needle.size).any { window ->
            window.zip(needle).all { (candidate, query) -> candidate.prefixMatches(query) }
        }

    private fun String.prefixMatches(other: String): Boolean =
        this == other || (length >= MIN_PREFIX_LENGTH && other.length >= MIN_PREFIX_LENGTH &&
            (startsWith(other) || other.startsWith(this)))

    private data class RankedEntry(
        val entry: AgentSubagentIndexEntry,
        val score: Int,
        val position: Int,
    )

    private val TOKEN = Regex("[a-z0-9]+")
    private val QUERY_STOP_WORDS = setOf(
        "a", "an", "and", "or", "the", "for", "of", "in", "on", "with", "to", "from", "by",
        "agent", "agency", "expert", "specialist", "specialized", "professional",
    )
    private const val MIN_PREFIX_LENGTH = 3
}

/** Renders a constant-size department routing index; no profile metadata enters the main prompt. */
internal object SubagentDepartmentIndexRenderer {
    fun render(counts: List<AgentDepartmentCount>): String {
        val countByDepartment = counts.associate { it.departmentId to it.enabledCount }
        return buildString {
            AgentDepartments.agency.forEach { department ->
                append("- department=\"")
                append(department.id)
                append("\"：")
                append(department.localizedName)
                append(" / ")
                append(department.name)
                append("（启用 ")
                append(countByDepartment[department.id] ?: 0)
                append("）\n")
            }
            val customCount = countByDepartment[AgentDepartments.CUSTOM_ID] ?: 0
            if (customCount > 0) {
                append("- 用户自定义精确覆盖：启用 ")
                append(customCount)
                append("；仅在已知精确 role 时使用，不参与目录展开。\n")
            }
        }.trimEnd()
    }
}
