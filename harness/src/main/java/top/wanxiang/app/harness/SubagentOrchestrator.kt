package top.wanxiang.app.harness

import top.wanxiang.app.core.database.AgentContextRepository
import top.wanxiang.app.core.database.HarnessSessionRepository
import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.core.model.AgentSubagent
import top.wanxiang.app.core.model.AgentSubagentIndexEntry
import top.wanxiang.app.core.model.SubagentTaskSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import top.wanxiang.app.harness.session.LaneManager
import top.wanxiang.app.harness.subagent.SubagentLaneRunner
import top.wanxiang.app.harness.prompt.PromptAssetLoader
import top.wanxiang.app.harness.WorkspaceFileAccess

internal class SubagentConcurrencyGate(
    maxParallelism: Int = DEFAULT_MAX_CONCURRENT_SUBAGENTS,
) {
    private val permits = Semaphore(maxParallelism.coerceAtLeast(1))

    suspend fun <T> withPermit(block: suspend () -> T): T = permits.withPermit { block() }
}

internal const val DEFAULT_MAX_CONCURRENT_SUBAGENTS = 3

/**
 * Subagent 子智能体任务编排器：
 * 负责解析主智能体的 invoke_subagent 请求，动态创建隔离的子会话，
 * 并发调度子智能体执行研究、编写或测试任务，最终汇聚输出结构化 Markdown。
 */
@Singleton
class SubagentOrchestrator @Inject constructor(
    private val sessionDao: HarnessSessionRepository,
    private val laneManager: LaneManager,
    private val laneRunner: SubagentLaneRunner,
    private val subagentRepository: top.wanxiang.app.core.database.AgentSubagentRepository,
    private val promptAssets: PromptAssetLoader,
    private val agentContextRepo: AgentContextRepository,
    private val fileAccess: WorkspaceFileAccess,
    private val providerClient: ProviderClient,
    private val logger: AppLogger,
) {
    /**
     * Application-wide budget: a three-agent fan-out should actually run three lanes at once,
     * while larger batches still queue to protect the shared API/PRoot/Room resources.
     */
    private val globalParallelism = SubagentConcurrencyGate()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun executeSubagents(
        args: JsonObject,
        parentSessionId: String,
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val parentSession = sessionDao.findById(parentSessionId)
        val workspace = parentSession?.workspace.orEmpty()
        val modelId = parentSession?.modelId
        val modelVariant = parentSession?.modelVariant
        val projectType = parentSession?.projectType.orEmpty()
        val specs = SubagentArgsParser.parse(args)
        if (specs.isEmpty()) {
            return@withContext false to "未解析到有效的 subagents 任务列表，请检查参数"
        }

        val profileIndex = subagentRepository.enabledIndex()
        if (profileIndex.isEmpty()) {
            return@withContext false to "当前没有启用的子智能体角色，请先在 Agent 设置中添加或启用角色"
        }
        val parentLeaf = laneManager.get(parentSessionId, "main")?.leafId

        // 写租约协调：把任务按 write_paths 冲突切成若干"写不冲突"的波。
        // 同一波内的写路径互不相交 → 可并行（受 globalParallelism 约束）；跨波顺序执行。
        // 未声明 writePaths 的任务为只读任务，可彼此并行；["*"] 才表示整工作区独占写租约。
        val results = mutableListOf<SubagentExecutionOutcome>()
        val waves = buildWriteCleanWaves(specs)
        logger.logAgent(
            parentSessionId,
            "SubagentSchedule",
            "tasks=${specs.size}, waves=${waves.size}, maxParallelism=$DEFAULT_MAX_CONCURRENT_SUBAGENTS, " +
                "waveSizes=${waves.joinToString(prefix = "[", postfix = "]") { it.size.toString() }}",
        )
        for ((waveIndex, wave) in waves.withIndex()) {
            logger.logAgent(
                parentSessionId,
                "SubagentWave",
                "start=${waveIndex + 1}/${waves.size}, tasks=${wave.joinToString { it.taskName }}",
            )
            results += wave.map { spec ->
                async {
                    globalParallelism.withPermit {
                        runSubagent(spec, parentSessionId, parentLeaf, workspace, modelId, modelVariant, profileIndex)
                    }
                }
            }.awaitAll()
        }

        // Completion order and lease waves must not change the user-requested presentation order.
        val orderedResults = results.sortedBy { outcome -> specs.indexOf(outcome.spec) }
        val summaryMarkdown = paginateSummary(orderedResults, workspace)
        val anySuccess = orderedResults.any { it.isSuccess }
        anySuccess to summaryMarkdown
    }

    private suspend fun runSubagent(
        spec: SubagentTaskSpec,
        parentSessionId: String,
        parentLeaf: String?,
        workspace: String,
        modelId: String?,
        modelVariant: String?,
        profileIndex: List<AgentSubagentIndexEntry>,
    ): SubagentExecutionOutcome {
        val profile = resolveProfile(spec, profileIndex)
        if (profile == null) {
            return SubagentExecutionOutcome(
                spec = spec,
                subSessionId = "",
                isSuccess = false,
                summary = routingFailure(spec),
                toolCallCount = 0,
            )
        }
        val targetModel = runCatching {
            when (val route = selectSubagentModel(spec.model, profile.defaultModelId, profile.defaultModelVariant)) {
                SubagentModelRoute.Inherit -> providerClient.resolveRequestedModel(null, modelId, modelVariant)
                is SubagentModelRoute.Requested -> providerClient.resolveRequestedModel(route.selection, modelId, modelVariant)
                is SubagentModelRoute.RoleDefault -> providerClient.resolveSavedModelProfile(route.profileId, route.variant)
            }
        }.getOrElse { failure ->
            return SubagentExecutionOutcome(
                spec = spec,
                subSessionId = "",
                isSuccess = false,
                summary = failure.message ?: "无法解析子智能体模型",
                toolCallCount = 0,
                resolvedProfileId = profile.id,
                resolvedProfileName = profile.name,
            )
        }
        val laneName = "subagent:${profile.id}:${java.util.UUID.randomUUID()}"
        laneManager.create(parentSessionId, laneName, parentLeaf)
        val prompt = buildSubagentPrompt(spec, profile, workspace, parentSessionId)
        val laneResult = withTimeoutOrNull(SUBAGENT_TIMEOUT_MS) {
            laneRunner.run(parentSessionId, laneName, prompt, workspace, modelConfig = targetModel)
        }

        // 超时取消时 withTimeoutOrNull 返回 null，若直接 ?: 0 会把子智能体在超时窗口内
        // 真实执行的工具调用全部归零，汇总里出现"明明在跑却显示 0 次工具调用"。
        // lane 的 tool_call entry 在执行期已逐条落库，从 transcript 恢复真实计数。
        val toolCallCount = resolveSubagentToolCallCount(
            laneResult?.toolCallCount,
            runCatching { laneManager.transcript(parentSessionId, laneName) }.getOrDefault(emptyList()),
        )

        return SubagentExecutionOutcome(
            spec = spec,
            subSessionId = laneName,
            isSuccess = laneResult?.success == true,
            summary = laneResult?.summary ?: "执行超时 (${SUBAGENT_TIMEOUT_MS / 60_000} 分钟，已执行 $toolCallCount 次工具调用)",
            toolCallCount = toolCallCount,
            resolvedProfileId = profile.id,
            resolvedProfileName = profile.name,
            resolvedModel = "${targetModel.provider}/${targetModel.model}",
        )
    }


    private suspend fun resolveProfile(
        spec: SubagentTaskSpec,
        profileIndex: List<AgentSubagentIndexEntry>,
    ): AgentSubagent? {
        val selected = if (spec.role.isNotBlank()) {
            profileIndex.firstOrNull { entry ->
                entry.id.equals(spec.role, ignoreCase = true) ||
                    entry.name.equals(spec.role, ignoreCase = true)
            }
        } else {
            SubagentProfileMatcher.match(profileIndex, spec.department, spec.agentQuery)
        } ?: return null
        return subagentRepository.findEnabledProfile(selected.id)
    }

    private fun routingFailure(spec: SubagentTaskSpec): String = if (spec.role.isNotBlank()) {
        "精确角色 ${spec.role} 未配置或未启用。可改用 department + agentQuery 让本地索引派发。"
    } else {
        "部门 ${spec.department} 中没有匹配 agentQuery=\"${spec.agentQuery}\" 的已启用角色。" +
            "请保留部门并改用 2–5 个更具体的英文专业关键词。"
    }

    private suspend fun buildSubagentPrompt(
        spec: SubagentTaskSpec,
        profile: AgentSubagent,
        workspace: String,
        parentSessionId: String,
    ): String {
        val factsPack = buildParentFactsPack(parentSessionId, workspace)
        val writeLine = when {
            spec.writePaths.isEmpty() ->
                "本任务为只读任务：禁止调用 write/edit，禁止执行会修改工作区的命令；只返回分析或数据。"
            spec.writePaths.any(::isWholeWorkspacePath) ->
                "本任务持有整工作区独占写租约；仅修改任务确实需要的文件，避免无关改动。"
            else ->
                "限定写入范围（请只在这些文件/目录下写，勿越界）：${spec.writePaths.joinToString("、")}"
        }
        return promptAssets.render(
            "prompts/subagent_task.md",
            mapOf(
                "ROLE_NAME" to profile.name,
                "ROLE_ID" to profile.id,
                "TASK_NAME" to spec.taskName,
                "WORKSPACE_LINE" to workspace.takeIf { it.isNotBlank() }?.let { "工作区：$it" }.orEmpty(),
                "ROLE_PROMPT" to profile.systemPrompt.trim(),
                "TASK_PROMPT" to spec.prompt,
                "WRITE_LINE" to writeLine,
                "FACTS_PACK" to factsPack,
            ),
        )
    }

    /** 父级 facts pack：把父会话已 pin 的长期指令/事实浓缩为一段低体积背景，而非整段父 transcript。 */
    private suspend fun buildParentFactsPack(parentSessionId: String, workspace: String): String {
        val pinned = runCatching {
            agentContextRepo.getPinnedMemories(
                projectOwnerId = workspace.trim().trimEnd('/'),
                sessionId = parentSessionId,
            )
        }.getOrDefault(emptyList())
        if (pinned.isEmpty()) return ""
        return "## 父级上下文事实包（父会话已 pin 的指令/事实，作为本子任务的背景参考）\n" +
            pinned.joinToString("\n") { "- [${it.scope}/${it.kind}] ${it.key}: ${it.value}" }
    }

    private companion object {
        // 15 分钟：移动端复杂分析任务（大文件读取 + 多轮工具调用 + 长文本产出）
        // 实测 6 分钟不够用，超时前往往仍在正常执行中途。
        const val SUBAGENT_TIMEOUT_MS = 15 * 60 * 1000L
    }

    private fun buildSummaryMarkdown(outcomes: List<SubagentExecutionOutcome>): String =
        renderSummaryMarkdown(outcomes)

    /**
     * 结果分页读取：汇总注入父上下文前先做预算控制。
     * 总量 ≤ [SUMMARY_INLINE_BUDGET] 字符 → 原样注入（保持现状，不破坏小批次体验）；
     * 超限 → 每个子任务输出截断为 [PER_TASK_INLINE_BUDGET] 字符，
     * 完整结果落盘 `.wanxiang-subagent/<laneName-safe>.md`（工作区相对路径），
     * 模型可用 read 工具按 offset/limit 分页读取。
     */
    private suspend fun paginateSummary(outcomes: List<SubagentExecutionOutcome>, workspace: String): String =
        paginateSubagentSummary(outcomes, workspace, fileAccess)

    internal data class SubagentExecutionOutcome(
        val spec: SubagentTaskSpec,
        val subSessionId: String,
        val isSuccess: Boolean,
        val summary: String,
        val toolCallCount: Int,
        val resolvedProfileId: String? = null,
        val resolvedProfileName: String? = null,
        val resolvedModel: String? = null,
    )
}

internal sealed interface SubagentModelRoute {
    data object Inherit : SubagentModelRoute
    data class Requested(val selection: String) : SubagentModelRoute
    data class RoleDefault(val profileId: String, val variant: String?) : SubagentModelRoute
}

/** Explicit `inherit` bypasses a role default; an omitted task selection uses that default. */
internal fun selectSubagentModel(
    taskSelection: String?,
    roleDefaultModelId: String?,
    roleDefaultModelVariant: String?,
): SubagentModelRoute {
    val requested = taskSelection?.trim().orEmpty()
    if (requested.equals("inherit", ignoreCase = true)) return SubagentModelRoute.Inherit
    if (requested.isNotBlank()) return SubagentModelRoute.Requested(requested)
    val profileId = roleDefaultModelId?.trim().orEmpty()
    return if (profileId.isNotBlank()) {
        SubagentModelRoute.RoleDefault(profileId, roleDefaultModelVariant?.trim()?.takeIf { it.isNotBlank() })
    } else {
        SubagentModelRoute.Inherit
    }
}


/**
 * 将任务按 write_paths 冲突切成若干互不相交的"波"。
 * 同一波内所有任务的写路径集合互不重叠 → 可安全并行执行。
 * 空 writePaths 是只读任务，可与其他只读任务并行；["*"] 是整工作区独占写租约。
 * 为避免读到写入中间态，只读波与任何写入波分离；局部写路径互不冲突时可并行。
 */
internal fun buildWriteCleanWaves(specs: List<SubagentTaskSpec>): List<List<SubagentTaskSpec>> {
    val waves = mutableListOf<MutableList<SubagentTaskSpec>>()
    val waveKinds = mutableListOf<SubagentWaveKind>()
    val wavePaths = mutableListOf<MutableSet<String>>() // 该波已占用的写路径
    for (spec in specs) {
        val paths = spec.writePaths.mapTo(hashSetOf()) { normalizeWritePath(it) }
        val kind = when {
            paths.isEmpty() -> SubagentWaveKind.READ_ONLY
            paths.any(::isWholeWorkspacePath) -> SubagentWaveKind.WHOLE_WORKSPACE
            else -> SubagentWaveKind.SCOPED_WRITE
        }
        val joinIndex = waves.indices.firstOrNull { i ->
            when (kind) {
                SubagentWaveKind.READ_ONLY -> waveKinds[i] == SubagentWaveKind.READ_ONLY
                SubagentWaveKind.WHOLE_WORKSPACE -> false
                SubagentWaveKind.SCOPED_WRITE ->
                    waveKinds[i] == SubagentWaveKind.SCOPED_WRITE &&
                        wavePaths[i].none { existing -> paths.any { candidate -> writePathsConflict(existing, candidate) } }
            }
        }
        if (joinIndex != null) {
            waves[joinIndex].add(spec)
            wavePaths[joinIndex].addAll(paths)
        } else {
            waves.add(mutableListOf(spec))
            waveKinds.add(kind)
            wavePaths.add(paths)
        }
    }
    return waves
}

/** 规范化写路径，统一去掉首尾斜杠，保证跨子任务的路径比较稳定。 */
internal fun normalizeWritePath(path: String): String = path.trim().replace('\\', '/').trim('/').removeSuffix("/.")

private enum class SubagentWaveKind { READ_ONLY, SCOPED_WRITE, WHOLE_WORKSPACE }

private fun isWholeWorkspacePath(path: String): Boolean = normalizeWritePath(path) in setOf("*", ".")

internal fun writePathsConflict(left: String, right: String): Boolean {
    val a = normalizeWritePath(left)
    val b = normalizeWritePath(right)
    if (isWholeWorkspacePath(a) || isWholeWorkspacePath(b)) return true
    return a == b || a.startsWith("$b/") || b.startsWith("$a/")
}

/**
 * 超时取消时 laneRunner 协程被中止，其内存中的工具调用统计随返回值一起丢失；
 * 此时从 lane 已落库的 transcript（tool_call entry）恢复真实计数，
 * 避免汇总里出现"子智能体明明在干活却显示 0 次工具调用"。
 */
internal fun resolveSubagentToolCallCount(
    laneResultToolCalls: Int?,
    persistedTranscript: List<HarnessMessage>,
): Int = laneResultToolCalls ?: persistedTranscript.count { it is ToolCall }

/** 汇总注入父上下文的字符预算；超出即走截断+落盘分页。 */
internal const val SUMMARY_INLINE_BUDGET = 12_000

/** 超限时分任务的截断保留长度。 */
internal const val PER_TASK_INLINE_BUDGET = 3_000

/** 子智能体汇总 Markdown：状态行 + 每任务的输出。 */
internal fun renderSummaryMarkdown(outcomes: List<SubagentOrchestrator.SubagentExecutionOutcome>): String =
    buildString {
        val succeeded = outcomes.count { it.isSuccess }
        val batchStatus = when (succeeded) {
            outcomes.size -> "全部成功"
            0 -> "全部失败"
            else -> "部分成功"
        }
        append("### 🤖 子智能体协同执行完成 · $batchStatus ($succeeded/${outcomes.size})\n\n")
        outcomes.forEachIndexed { index, outcome ->
            val statusIcon = if (outcome.isSuccess) "✅" else "⚠️"
            val resolvedRole = if (outcome.resolvedProfileId != null) {
                "${outcome.resolvedProfileName} · ${outcome.resolvedProfileId}"
            } else {
                outcome.spec.role.ifBlank { "${outcome.spec.department} / ${outcome.spec.agentQuery}" }
            }
            val modelBadge = outcome.resolvedModel?.let { " · 专用模型: $it" } ?: ""
            append("#### ${index + 1}. $statusIcon 【${outcome.spec.taskName}】(角色: $resolvedRole$modelBadge)\n")
            append("- **工具调用次数**：${outcome.toolCallCount} 次\n")

            append("- **子任务输出**：\n")
            append(outcome.summary.trim())
            append("\n\n")
        }
    }

/**
 * 结果分页读取：汇总注入父上下文前的预算控制。
 * 总量 ≤ [SUMMARY_INLINE_BUDGET] 字符 → 原样注入（保持现状，小批次体验不变）；
 * 超限 → 每个超长子任务输出截断为 [PER_TASK_INLINE_BUDGET] 字符，完整结果落盘
 * `.wanxiang-subagent/<laneName-safe>.md`（工作区相对路径），模型可用 read 工具按 offset/limit 分页读取。
 */
internal suspend fun paginateSubagentSummary(
    outcomes: List<SubagentOrchestrator.SubagentExecutionOutcome>,
    workspace: String,
    fileAccess: WorkspaceFileAccess,
): String {
    val full = renderSummaryMarkdown(outcomes)
    if (full.length <= SUMMARY_INLINE_BUDGET || workspace.isBlank()) return full

    val overflowTasks = outcomes.filter { it.summary.length > PER_TASK_INLINE_BUDGET }
    val spillDir = ".wanxiang-subagent"
    val spilled = mutableMapOf<String, String>() // laneName -> 相对路径
    overflowTasks.forEach { outcome ->
        val fileName = outcome.subSessionId
            .filter { it.isLetterOrDigit() || it == '-' || it == ':' }
            .replace(':', '-')
            .takeLast(80) + ".md"
        val relativePath = "$spillDir/$fileName"
        // 落盘失败不阻塞：该任务按普通截断处理
        if (fileAccess.write(relativePath, outcome.summary) is top.wanxiang.app.core.common.result.AppResult.Success) {
            spilled[outcome.subSessionId] = relativePath
        }
    }

    return buildString {
        val succeeded = outcomes.count { it.isSuccess }
        val batchStatus = when (succeeded) {
            outcomes.size -> "全部成功"
            0 -> "全部失败"
            else -> "部分成功"
        }
        append("### 🤖 子智能体协同执行完成 · $batchStatus ($succeeded/${outcomes.size})\n\n")
        append("（本批输出总量超出注入预算，超长子任务已截断；完整结果可用 read 工具按 offset/limit 分页读取）\n\n")
        outcomes.forEachIndexed { index, outcome ->
            val statusIcon = if (outcome.isSuccess) "✅" else "⚠️"
            val resolvedRole = if (outcome.resolvedProfileId != null) {
                "${outcome.resolvedProfileName} · ${outcome.resolvedProfileId}"
            } else {
                outcome.spec.role.ifBlank { "${outcome.spec.department} / ${outcome.spec.agentQuery}" }
            }
            append("#### ${index + 1}. $statusIcon 【${outcome.spec.taskName}】(角色: $resolvedRole)\n")
            append("- **工具调用次数**：${outcome.toolCallCount} 次\n")
            append("- **子任务输出**：\n")
            val spillPath = spilled[outcome.subSessionId]
            if (spillPath != null) {
                append(outcome.summary.take(PER_TASK_INLINE_BUDGET))
                append("\n\n…（截断，共 ${outcome.summary.length} 字符。完整结果：read 路径 `$spillPath`）\n\n")
            } else {
                append(outcome.summary.trim())
                append("\n\n")
            }
        }
    }
}
