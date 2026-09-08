package top.wanxiang.app.core.model.workflow

/**
 * Evaluates workflow condition expressions against runtime context + upstream output.
 *
 * Supported forms (case-insensitive keywords where noted):
 * - `true` / `false`
 * - `exitCode == N` / `exitCode != N`
 * - `output contains TEXT` / `output matches REGEX`
 * - `${VAR} == value` / `!=` / `contains` / `matches`
 * - `${VAR} > N` / `>=` / `<` / `<=` (numeric)
 * - `empty ${VAR}` / `notEmpty ${VAR}`
 * - Combine with `&&` and `||` (|| has lower precedence)
 */
object WorkflowConditionEvaluator {
    data class Result(val matched: Boolean, val detail: String)

    fun evaluate(
        expression: String?,
        context: WorkflowRuntimeContext,
        upstream: NodeExecutionOutput? = null,
    ): Result {
        val raw = expression?.trim().orEmpty()
        if (raw.isEmpty()) {
            val code = upstream?.exitCode ?: 0
            return Result(code == 0, "默认规则：exitCode == 0（实际 $code）")
        }
        return runCatching {
            val matched = evalOr(raw, context, upstream)
            Result(matched, "表达式「$raw」→ $matched")
        }.getOrElse { error ->
            Result(false, "条件求值失败：${error.message ?: error::class.java.simpleName}")
        }
    }

    fun validationError(expression: String?): String? {
        val raw = expression?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching {
            evalOr(raw, WorkflowRuntimeContext("v", "v", "/"), null)
            null
        }.exceptionOrNull()?.message?.let { "条件表达式无效：$it" }
    }

    private fun evalOr(expression: String, context: WorkflowRuntimeContext, upstream: NodeExecutionOutput?): Boolean {
        val parts = splitKeep(expression, "||")
        return parts.any { evalAnd(it.trim(), context, upstream) }
    }

    private fun evalAnd(expression: String, context: WorkflowRuntimeContext, upstream: NodeExecutionOutput?): Boolean {
        val parts = splitKeep(expression, "&&")
        return parts.all { evalAtom(it.trim(), context, upstream) }
    }

    private fun evalAtom(expression: String, context: WorkflowRuntimeContext, upstream: NodeExecutionOutput?): Boolean {
        val expr = expression.trim()
        require(expr.isNotEmpty()) { "空条件片段" }

        when (expr.lowercase()) {
            "true", "yes", "1" -> return true
            "false", "no", "0" -> return false
        }

        EXIT_CODE_EQ.matchEntire(expr)?.let { return (upstream?.exitCode ?: 0) == it.groupValues[1].toInt() }
        EXIT_CODE_NEQ.matchEntire(expr)?.let { return (upstream?.exitCode ?: 0) != it.groupValues[1].toInt() }

        OUTPUT_CONTAINS.matchEntire(expr)?.let {
            val needle = interpolate(it.groupValues[1].trim().trimQuotes(), context)
            return (upstream?.textOutput ?: context.previousOutput()).contains(needle, ignoreCase = true)
        }
        OUTPUT_MATCHES.matchEntire(expr)?.let {
            val pattern = interpolate(it.groupValues[1].trim().trimQuotes(), context)
            return Regex(pattern).containsMatchIn(upstream?.textOutput ?: context.previousOutput())
        }

        EMPTY_VAR.matchEntire(expr)?.let {
            return resolveValue(it.groupValues[1], context, upstream).isBlank()
        }
        NOT_EMPTY_VAR.matchEntire(expr)?.let {
            return resolveValue(it.groupValues[1], context, upstream).isNotBlank()
        }

        COMPARE.matchEntire(expr)?.let { match ->
            val left = resolveValue(match.groupValues[1], context, upstream)
            val op = match.groupValues[2]
            val right = interpolate(match.groupValues[3].trim().trimQuotes(), context)
            return when (op) {
                "==", "=" -> left.equals(right, ignoreCase = true)
                "!=", "<>" -> !left.equals(right, ignoreCase = true)
                "contains" -> left.contains(right, ignoreCase = true)
                "matches" -> Regex(right).containsMatchIn(left)
                ">", ">=", "<", "<=" -> compareNumbers(left, right, op)
                else -> error("不支持的运算符：$op")
            }
        }

        // Bare regex against upstream/previous output (legacy edge style)
        return runCatching { Regex(expr).containsMatchIn(upstream?.textOutput ?: context.previousOutput()) }
            .getOrElse { error("无法解析条件：$expr") }
    }

    private fun compareNumbers(left: String, right: String, op: String): Boolean {
        val a = left.trim().toDoubleOrNull() ?: error("左侧不是数字：$left")
        val b = right.trim().toDoubleOrNull() ?: error("右侧不是数字：$right")
        return when (op) {
            ">" -> a > b
            ">=" -> a >= b
            "<" -> a < b
            "<=" -> a <= b
            else -> false
        }
    }

    private fun resolveValue(token: String, context: WorkflowRuntimeContext, upstream: NodeExecutionOutput?): String {
        val key = token.trim().removePrefix("\${").removeSuffix("}").trim()
        return when {
            key.equals("exitCode", ignoreCase = true) -> (upstream?.exitCode ?: 0).toString()
            key.equals("output", ignoreCase = true) || key == "previous.output" ->
                upstream?.textOutput ?: context.previousOutput()
            key.endsWith(".output") -> context.nodeOutputs[key.removeSuffix(".output")]?.textOutput.orEmpty()
            key == "WORKSPACE_PATH" -> context.workspacePath
            else -> context.globalVariables[key]
                ?: upstream?.variables?.get(key).orEmpty()
        }
    }

    private fun interpolate(template: String, context: WorkflowRuntimeContext): String =
        VARIABLE.replace(template) { match ->
            val key = match.groupValues[1]
            when {
                key == "WORKSPACE_PATH" -> context.workspacePath
                key == "previous.output" -> context.previousOutput()
                key.endsWith(".output") -> context.nodeOutputs[key.removeSuffix(".output")]?.textOutput.orEmpty()
                else -> context.globalVariables[key].orEmpty()
            }
        }

    private fun String.trimQuotes(): String = when {
        length >= 2 && first() == '"' && last() == '"' -> substring(1, length - 1)
        length >= 2 && first() == '\'' && last() == '\'' -> substring(1, length - 1)
        else -> this
    }

    /** Split by operator outside of quotes / ${}. */
    private fun splitKeep(input: String, delimiter: String): List<String> {
        if (!input.contains(delimiter)) return listOf(input)
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var i = 0
        var inSingle = false
        var inDouble = false
        var brace = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '{' && !inSingle && !inDouble && i > 0 && input[i - 1] == '$' -> brace++
                c == '}' && !inSingle && !inDouble && brace > 0 -> brace--
            }
            if (!inSingle && !inDouble && brace == 0 && input.startsWith(delimiter, i)) {
                out += buf.toString()
                buf.clear()
                i += delimiter.length
                continue
            }
            buf.append(c)
            i++
        }
        out += buf.toString()
        return out
    }

    private val VARIABLE = Regex("\\$\\{([A-Za-z0-9_.\\-]+)\\}")
    private val EXIT_CODE_EQ = Regex("^exitCode\\s*==\\s*(-?\\d+)$", RegexOption.IGNORE_CASE)
    private val EXIT_CODE_NEQ = Regex("^exitCode\\s*!=\\s*(-?\\d+)$", RegexOption.IGNORE_CASE)
    private val OUTPUT_CONTAINS = Regex("^output\\s+contains\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val OUTPUT_MATCHES = Regex("^output\\s+matches\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val EMPTY_VAR = Regex("^empty\\s+(\\$\\{[^}]+\\}|[A-Za-z0-9_.\\-]+)$", RegexOption.IGNORE_CASE)
    private val NOT_EMPTY_VAR = Regex("^notEmpty\\s+(\\$\\{[^}]+\\}|[A-Za-z0-9_.\\-]+)$", RegexOption.IGNORE_CASE)
    private val COMPARE = Regex(
        "^(\\$\\{[^}]+\\}|[A-Za-z0-9_.\\-]+)\\s*(==|=|!=|<>|>=|<=|>|<|contains|matches)\\s*(.+)$",
        RegexOption.IGNORE_CASE,
    )
}
