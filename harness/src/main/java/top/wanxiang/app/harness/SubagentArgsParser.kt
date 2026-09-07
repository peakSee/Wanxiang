package top.wanxiang.app.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.wanxiang.app.core.model.SubagentTaskSpec

/** Tolerant parser shared by subagent execution and persisted-call rendering. */
object SubagentArgsParser {
    const val DEFAULT_MAX_TASKS = 6

    fun parse(
        args: JsonObject,
        defaultTaskName: String = "子任务",
        maxTasks: Int = DEFAULT_MAX_TASKS,
    ): List<SubagentTaskSpec> {
        val nested = args["subagents"]
        val candidates = when (nested) {
            is JsonArray -> nested.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(nested)
            else -> listOf(args)
        }

        return candidates.mapNotNull { candidate ->
            val prompt = candidate.string("prompt")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val role = candidate.string("role").orEmpty().trim()
            val department = candidate.string("department").orEmpty().trim()
            val agentQuery = candidate.string("agentQuery").orEmpty().trim()
            if (role.isBlank() && (department.isBlank() || agentQuery.isBlank())) return@mapNotNull null
            val rawModel = candidate.string("model") ?: candidate.string("modelId") ?: candidate.string("model_id")
            val model = rawModel?.trim()?.takeIf { it.isNotBlank() }
            val writePaths = (candidate.strings("writePaths") + candidate.strings("write_paths"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            SubagentTaskSpec(
                taskName = candidate.string("taskName")?.trim()?.takeIf { it.isNotBlank() } ?: defaultTaskName,
                role = role,
                prompt = prompt,
                department = department,
                agentQuery = agentQuery,
                writePaths = writePaths,
                model = model,
            )
        }.take(maxTasks.coerceAtLeast(0))

    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.strings(key: String): List<String> = when (val value = this[key]) {
        is JsonPrimitive -> listOfNotNull(value.contentOrNull?.takeIf { it.isNotBlank() })
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
        else -> emptyList()
    }
}
