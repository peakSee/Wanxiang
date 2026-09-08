package top.wkbin.taixu.harness

/**
 * Prepares safe, foreground Agent commands for RTK without changing terminal,
 * MCP, process, or file-tool behaviour. RTK itself decides whether a supported
 * command has an equivalent; a missing/incompatible binary always falls back to
 * the exact original command in the same shell invocation.
 *
 * 两条硬约束：
 * 1. 改写只允许影响“给人/模型看”的输出。任何输出会被下游脚本解析的命令
 *    （`ls -1`、`grep -l`、`find -print0`、`git --porcelain` 等）必须原样执行，
 *    否则 Agent 后续的解析步骤会静默拿到压缩后的文本。
 * 2. 改写必须零收益即零成本。RTK 没有对应子命令的可执行文件不进入白名单，
 *    避免为一次注定回退的 `rtk rewrite` 白付两次进程启动。
 */
internal object RtkCommandOptimizer {
    private const val RTK_BINARY = "/opt/taixu/bin/rtk"

    private val rtkEnvironment = mapOf(
        // Raw failure output can include project secrets and is already returned to the Agent.
        "RTK_TEE" to "0",
        "XDG_CONFIG_HOME" to "/opt/taixu/data/rtk/config",
        "XDG_DATA_HOME" to "/opt/taixu/data/rtk/data",
    )

    /**
     * 只保留 RTK 真正提供等价子命令、且压缩确有收益的可执行文件。
     *
     * 已移除：`wc`（单文件调用丢弃文件名、总计行被改写成 `Σ`，输出本身就是给脚本读的数字）、
     * `du` / `yarn` / `bun` / `mvnw`（RTK 无对应子命令，改写只会多付两次进程启动）。
     */
    private val supportedCommands = setOf(
        "git", "rg", "grep", "find", "ls", "tree",
        "gradle", "gradlew", "mvn", "mvnd", "cargo", "go", "pytest",
        "npm", "pnpm", "npx",
    )

    /**
     * 只排除会改变命令结构的元字符：管道、串联、重定向、命令替换与换行。
     *
     * 通配符与花括号不在此列：`rtk rewrite` 输出会原样保留原命令的引号，随后的 `eval`
     * 只做一次展开，与直接执行原命令等价。放行它们才能覆盖 Agent 最常用、
     * 同时压缩收益最高的形态（`find . -name '*.kt'`、`rg -n pattern --glob '*.kt'`）。
     */
    private val unsupportedShellSyntax = setOf('&', '|', ';', '\n', '\r', '<', '>', '`', '$')

    /** 与具体命令无关的“机器可解析输出”标志。 */
    private val universalRawOutputFlags = setOf("--json", "-z", "--null", "--print0")

    /** grep 与 rg 共用：这些标志下输出是给脚本读的（退出码、计数、纯文件名列表）。 */
    private val grepRawOutputFlags = setOf(
        "-q", "--quiet", "--silent",
        "-c", "--count",
        "-l", "--files-with-matches",
        "-L", "--files-without-match",
        "-o", "--only-matching",
        "--vimgrep",
    )

    /**
     * 按命令区分的机器可解析标志。必须按命令区分而不是全局匹配：
     * `ls -l` 是压缩收益最高的形态之一，`grep -l` 却只输出文件名列表。
     */
    private val rawOutputFlags = mapOf(
        "ls" to setOf("-1"),
        "grep" to grepRawOutputFlags,
        "rg" to grepRawOutputFlags,
        "find" to setOf("-print0", "-printf", "-fprint", "-fprint0", "-exec", "-execdir", "-ok", "-okdir"),
        "git" to setOf("--porcelain", "--numstat", "--name-only", "--name-status", "--format", "--pretty", "--raw"),
    )

    private val whitespace = Regex("\\s+")

    data class PreparedCommand(
        val commandLine: String,
        val environment: Map<String, String> = emptyMap(),
    )

    fun prepare(command: String, enabled: Boolean): PreparedCommand {
        if (!enabled || !isEligible(command)) return PreparedCommand(command)
        return PreparedCommand(
            commandLine = wrapWithFallback(command),
            environment = rtkEnvironment,
        )
    }

    private fun isEligible(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || command.any { it in unsupportedShellSyntax }) return false
        val tokens = trimmed.split(whitespace)
        val executable = tokens.first().substringAfterLast('/').lowercase()
        if (executable !in supportedCommands) return false
        return !producesMachineReadableOutput(executable, tokens)
    }

    /**
     * 分词只按空白切分：结构性元字符已在 [isEligible] 前置排除，引号内的空格最多
     * 让某个参数被误判成标志，结果是保守地放弃改写，不会造成语义破坏。
     */
    private fun producesMachineReadableOutput(executable: String, tokens: List<String>): Boolean {
        val flags = rawOutputFlags[executable].orEmpty() + universalRawOutputFlags
        return tokens.drop(1).any { token -> isRawOutputFlag(token, flags) }
    }

    private fun isRawOutputFlag(token: String, flags: Set<String>): Boolean {
        val name = token.substringBefore('=')
        if (name in flags) return true
        val isShortCluster = name.length > 2 && name.startsWith('-') && !name.startsWith("--")
        return isShortCluster && name.drop(1).any { "-$it" in flags }
    }

    private fun wrapWithFallback(command: String): String {
        val quotedCommand = shellQuote(command)
        return """
            if [ -x "$RTK_BINARY" ]; then
                _taixu_rtk_rewritten="${'$'}("$RTK_BINARY" rewrite $quotedCommand 2>/dev/null)"
                _taixu_rtk_status=${'$'}?
                case "${'$'}_taixu_rtk_rewritten" in
                    "rtk "*) ;;
                    *) _taixu_rtk_status=1 ;;
                esac
                if [ "${'$'}_taixu_rtk_status" -eq 0 ] && [ "${'$'}(printf '%s' "${'$'}_taixu_rtk_rewritten" | wc -l)" -eq 0 ]; then
                    eval "${'$'}_taixu_rtk_rewritten"
                else
                    $command
                fi
            else
                $command
            fi
        """.trimIndent()
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
