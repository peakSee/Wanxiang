package top.wanxiang.app.core.model.workflow

/** Shared edge routing semantics used by both validation and the runtime scheduler. */
object WorkflowEdgeCondition {
    val supportedPorts = setOf("output", "success", "failure")

    fun validationError(edge: WorkflowEdge): String? {
        if (edge.fromPort.lowercase() !in supportedPorts) {
            return "输出端口只能是 output、success 或 failure"
        }
        if (edge.toPort.lowercase() != "input") return "输入端口只能是 input"

        val expression = edge.conditionExpression?.trim().orEmpty()
        if (expression.isEmpty()) return null
        EXIT_CODE_EQ.matchEntire(expression)?.let { return null }
        EXIT_CODE_NEQ.matchEntire(expression)?.let { return null }
        OUTPUT_CONTAINS.matchEntire(expression)?.let { match ->
            return if (match.groupValues[1].isBlank()) "output contains 后必须填写匹配文本" else null
        }
        return runCatching { Regex(expression) }.exceptionOrNull()?.let { "条件正则无效：${it.message}" }
    }

    fun matches(edge: WorkflowEdge, output: NodeExecutionOutput): Boolean {
        val portMatches = when (edge.fromPort.lowercase()) {
            "success" -> output.status == NodeRunStatus.SUCCESS
            "failure" -> output.status == NodeRunStatus.FAILED
            "output" -> output.status !in setOf(NodeRunStatus.SKIPPED, NodeRunStatus.CANCELLED)
            else -> false
        }
        if (!portMatches) return false

        val expression = edge.conditionExpression?.trim().orEmpty()
        if (expression.isEmpty()) return true
        EXIT_CODE_EQ.matchEntire(expression)?.let { return output.exitCode == it.groupValues[1].toInt() }
        EXIT_CODE_NEQ.matchEntire(expression)?.let { return output.exitCode != it.groupValues[1].toInt() }
        OUTPUT_CONTAINS.matchEntire(expression)?.let {
            return output.textOutput.contains(it.groupValues[1], ignoreCase = true)
        }
        return runCatching { Regex(expression).containsMatchIn(output.textOutput) }.getOrDefault(false)
    }

    private val EXIT_CODE_EQ = Regex("^exitCode\\s*==\\s*(-?\\d+)$", RegexOption.IGNORE_CASE)
    private val EXIT_CODE_NEQ = Regex("^exitCode\\s*!=\\s*(-?\\d+)$", RegexOption.IGNORE_CASE)
    private val OUTPUT_CONTAINS = Regex("^output\\s+contains\\s+(.+)$", RegexOption.IGNORE_CASE)
}
