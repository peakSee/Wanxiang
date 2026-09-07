package top.wanxiang.app.harness.session

import javax.inject.Inject
import kotlinx.coroutines.flow.first
import top.wanxiang.app.core.datastore.AgentPreferences
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.CapabilityEvent
import top.wanxiang.app.harness.ContextWindowPolicy
import top.wanxiang.app.harness.HarnessApiMapper
import top.wanxiang.app.harness.ModelConfig
import top.wanxiang.app.harness.ProviderClient
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.ToolCallMode
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.harness.UserMessage
import top.wanxiang.app.harness.ApiFunctionCall
import top.wanxiang.app.harness.ApiMessage
import top.wanxiang.app.harness.ApiToolCall
import top.wanxiang.app.harness.MentionExtractor
import top.wanxiang.app.harness.compaction.CompactionManager
import top.wanxiang.app.harness.prompt.SystemPromptBuilder

/**
 * API 请求上下文组装器：把会话实时消息投影成提供商协议消息列表。
 *
 * 从原 HarnessLoop.apiMessages 迁移而来，负责：
 * - 系统提示词注入（非纯净聊天模式）
 * - 上下文压缩摘要的头部注入（预算驱动的滑动窗口折叠）
 * - NATIVE / JSON_TEXT 两种工具调用协议的消息形态转换
 * - 视觉能力关闭时剥离图片输入
 */
class ApiContextAssembler @Inject constructor(
    private val compactionManager: CompactionManager,
    private val settingsDataStore: AgentPreferences,
    private val systemPromptBuilder: SystemPromptBuilder,
) {
    suspend fun assemble(
        sessId: String,
        model: ModelConfig,
        workspacePath: String,
        projectTypeOverride: String = "",
        thinkingMode: Boolean = false,
    ): List<ApiMessage> {
        val compactionEnabled = runCatching { settingsDataStore.contextCompactionEnabled.first() }.getOrDefault(true)
        val budgetTokens = model.contextTokens
            ?: runCatching { settingsDataStore.contextBudgetTokens.first() }.getOrDefault(128_000)
        val toolCallMode = if (model.pureChatMode) ToolCallMode.DISABLED else model.toolCallMode

        var compactedContext = compactionManager.project(sessId)
        var msgs = compactedContext.messages
        val latestUserText = msgs.filterIsInstance<UserMessage>().lastOrNull()?.text.orEmpty()
        val mentionedNames = MentionExtractor.parse(latestUserText)

        val rawSystemPrompt = if (!model.pureChatMode) {
            systemPromptBuilder.build(
                workspacePath,
                toolCallMode,
                mentionedNames,
                sessId,
                projectTypeOverride,
                latestUserText,
                mcpTools = model.dynamicMcpTools,
            )
        } else {
            ""
        }
        val systemPrompt = ContextWindowPolicy.fitSystemPrompt(rawSystemPrompt, budgetTokens)
        return buildList {
            if (systemPrompt.isNotEmpty()) {
                add(ApiMessage(role = "system", content = systemPrompt))
            }
            val answeredIds = msgs.filterIsInstance<ToolResult>().mapTo(mutableSetOf()) { it.toolCallId }
            val toolCallDetails = msgs.filterIsInstance<ToolCall>().associate {
                it.id to ((it.rawToolName ?: HarnessApiMapper.apiName(it.tool)) to it.args)
            }

            // 预算驱动的滑动窗口：从最近一轮往回累加 token，超出预算则更早的历史进入压缩态。
            // 是否裁剪原文只由真实 token 预算决定，不再按用户轮次阈值强制折叠。
            val computedKeepFromIndex = if (compactionEnabled) {
                ContextWindowPolicy.computeKeepFromIndex(
                    msgs,
                    budgetTokens,
                    ContextWindowPolicy.estimateTokens(systemPrompt),
                )
            } else {
                0
            }
            if (computedKeepFromIndex > 0) {
                compactedContext = compactionManager.compact(sessId, compactedContext, computedKeepFromIndex)
                msgs = compactedContext.messages
            }
            val shouldCompact = !compactedContext.summary.isNullOrBlank()
            // Compute the message index at which "recent" history begins. Messages before this
            // boundary are either in the global compaction summary zone (shouldCompact) or subject
            // to turn-end capping for large tool results. Either way they get compacted.
            //
            // recentTurnCutoffIndex is the index of the oldest user message within the last
            // RECENT_TURNS_FULL_DETAIL user turns — messages before it are "historical" for the
            // purpose of capping. The value is now a real boundary, not zero.
            val recentTurnCutoffIndex = computeRecentTurnCutoffIndex(msgs, RECENT_TURNS_FULL_DETAIL)

            if (shouldCompact) {
                add(
                    ApiMessage(
                        role = "system",
                        content = compactedContext.summary,
                    ),
                )
            }

            // JSON 文本模式：工具调用以文本表达，tool 消息需转成 user 文本（API 不认识 tool 角色）
            val toolNames = toolCallDetails.mapValuesTo(mutableMapOf()) { it.value.first }

            var i = 0
            fun apiToolCall(tc: ToolCall) = ApiToolCall(
                id = tc.id,
                function = ApiFunctionCall(
                    name = tc.rawToolName ?: HarnessApiMapper.apiName(tc.tool),
                    arguments = tc.args.toString(),
                ),
            )
            val isCollapsed = { index: Int -> shouldCompact && index < recentTurnCutoffIndex }
            while (i < msgs.size) {
                val message = msgs[i]
                if (isCollapsed(i)) {
                    i++
                    continue
                }
                if (message is CapabilityEvent) {
                    i++
                    continue
                }
                if (toolCallMode == ToolCallMode.JSON_TEXT) {
                    when (message) {
                        is ToolCall -> {
                            toolNames[message.id] = message.rawToolName ?: HarnessApiMapper.apiName(message.tool)
                            i++
                        }
                        is ToolResult -> {
                            val name = toolNames[message.toolCallId] ?: "工具"
                            val status = if (message.success) "成功" else "失败"
                            val args = toolCallDetails[message.toolCallId]?.second
                            val content = when {
                                isCollapsed(i) && message.output.length > ContextWindowPolicy.compactThresholdFor(name) ->
                                    ContextWindowPolicy.compactToolOutput(name, args, message.output, message.success)
                                // Turn-end capping for JSON_TEXT mode: same policy as NATIVE.
                                i < recentTurnCutoffIndex && message.output.length > TURN_END_RESULT_CAP_CHARS ->
                                    "【工具 $name 执行结果·$status】\n${capToolResultForHistory(message.output)}"
                                else ->
                                    "【工具 $name 执行结果·$status】\n${message.output}"
                            }
                            add(ApiMessage(role = "user", content = content))
                            i++
                        }
                        else -> {
                            val mapped = when {
                                isCollapsed(i) && message is AssistantText && message.text.length > 120 ->
                                    ApiMessage(
                                        role = "assistant",
                                        content = ContextWindowPolicy.foldMessageText(
                                            "助手",
                                            ContextWindowPolicy.assistantTextForContext(
                                                ProviderClient.stripThinkTags(message.text) ?: message.text,
                                            ),
                                        ),
                                        reasoning_content = null,
                                    )
                                isCollapsed(i) && message is UserMessage && message.text.length > 120 ->
                                    ApiMessage(
                                        role = "user",
                                        content = ContextWindowPolicy.foldMessageText("用户", message.text),
                                        imageUrls = message.imageUrls,
                                    )
                                isCollapsed(i) && message is AssistantText ->
                                    HarnessApiMapper.toApiMessage(message).copy(
                                        content = ContextWindowPolicy.assistantTextForContext(
                                            ProviderClient.stripThinkTags(message.text) ?: message.text,
                                        ),
                                        reasoning_content = null,
                                    )
                                message is AssistantText ->
                                    HarnessApiMapper.toApiMessage(message).copy(
                                        content = ContextWindowPolicy.assistantTextForContext(
                                            ProviderClient.stripThinkTags(message.text) ?: message.text,
                                        ),
                                        reasoning_content = null,
                                    )
                                else -> HarnessApiMapper.toApiMessage(message)
                            }
                            add(if (message is UserMessage && !model.visionEnabled) mapped.copy(imageUrls = emptyList()) else mapped)
                            i++
                        }
                    }
                    continue
                }
                if (message is AssistantText || message is ToolCall) {
                    if (message is ToolCall && message.id !in answeredIds) {
                        i++
                        continue
                    }
                    // 预算折叠态：早期 assistant 文本压缩为一行占位，避免撑爆上下文。
                    val text = if (isCollapsed(i) && message is AssistantText && message.text.length > 120) {
                        ContextWindowPolicy.foldMessageText(
                            "助手",
                            ContextWindowPolicy.assistantTextForContext(
                                ProviderClient.stripThinkTags(message.text) ?: message.text,
                            ),
                        )
                    } else {
                        (message as? AssistantText)?.text?.let {
                            ContextWindowPolicy.assistantTextForContext(ProviderClient.stripThinkTags(it) ?: it)
                        }
                    }
                    val toolCalls = mutableListOf<ApiToolCall>()
                    if (message is ToolCall) toolCalls.add(apiToolCall(message))
                    var j = i + 1
                    while (j < msgs.size && msgs[j] is ToolCall) {
                        val tc = msgs[j] as ToolCall
                        if (tc.id in answeredIds) toolCalls.add(apiToolCall(tc))
                        j++
                    }
                    // DeepSeek 思考模式（V3.2+/V4）：两个 user 消息之间若有工具调用，
                    // 中间 assistant 消息的 reasoning_content 必须原样传回，否则
                    // 400 "The reasoning_content in the thinking mode must be passed back to the API"。
                    // 纯文本 assistant 轮仍不回传（DeepSeek-R1 规则，防推理循环）。
                    val reasoning = if (toolCalls.isNotEmpty()) {
                        (message as? AssistantText)?.reasoning
                            ?: (message as? ToolCall)?.reasoning
                            ?: msgs.subList(i, j).filterIsInstance<ToolCall>().firstNotNullOfOrNull { it.reasoning }
                    } else {
                        null
                    }
                    add(
                        ApiMessage(
                            role = "assistant",
                            content = text,
                            reasoning_content = reasoning,
                            tool_calls = toolCalls.takeIf { it.isNotEmpty() },
                        ),
                    )
                    i = j
                } else if (message is ToolResult) {
                    val detail = toolCallDetails[message.toolCallId]
                    val content = when {
                        isCollapsed(i) && message.output.length > ContextWindowPolicy.compactThresholdFor(detail?.first) ->
                            ContextWindowPolicy.compactToolOutput(detail?.first, detail?.second, message.output, message.success)
                        // Turn-end capping: historic results (before the recent-turns boundary)
                        // that exceed the cap are trimmed to head + ellipsis + tail.
                        // This runs independently from global compaction (isCollapsed) and fires
                        // even when shouldCompact is false — preventing a single large read from
                        // occupying full tokens across dozens of subsequent rounds.
                        i < recentTurnCutoffIndex && message.output.length > TURN_END_RESULT_CAP_CHARS ->
                            capToolResultForHistory(message.output)
                        else -> message.output
                    }
                    add(
                        ApiMessage(
                            role = "tool",
                            content = content,
                            tool_call_id = message.toolCallId,
                        ),
                    )
                    i++
                } else {
                    // 用户消息在早期历史中同样折叠，仅保留极简占位。
                    val folded = if (isCollapsed(i) && message is UserMessage && message.text.length > 120) {
                        ContextWindowPolicy.foldMessageText("用户", message.text)
                    } else {
                        null
                    }
                    if (folded != null) {
                        add(ApiMessage(role = "user", content = folded))
                    } else {
                        val mapped = HarnessApiMapper.toApiMessage(message)
                        add(if (message is UserMessage && !model.visionEnabled) mapped.copy(imageUrls = emptyList()) else mapped)
                    }
                    i++
                }
            }
        }
    }

    companion object {
        /**
         * Maximum character length for a ToolResult that occurred before the recent-turns
         * boundary. Outputs exceeding this length are capped to head + ellipsis + tail.
         * ~4096 chars ≈ ~1024 tokens — generous enough for typical tool outputs; small
         * enough to prevent a single 15 KB `read` from consuming context across 40 rounds.
         */
        const val TURN_END_RESULT_CAP_CHARS = 4_096

        /** Number of user-turn boundaries that define "recent" history. */
        const val RECENT_TURNS_FULL_DETAIL = 3

        /** Head size preserved during turn-end capping. */
        private const val CAP_HEAD_CHARS = 2_048

        /** Tail size preserved during turn-end capping. */
        private const val CAP_TAIL_CHARS = 512

        /**
         * Return the message index of the oldest UserMessage within the last [n] user turns.
         * If there are fewer than [n] user turns the whole history is "recent" and 0 is returned.
         */
        fun computeRecentTurnCutoffIndex(messages: List<top.wanxiang.app.harness.HarnessMessage>, n: Int): Int {
            var remaining = n
            for (index in messages.indices.reversed()) {
                if (messages[index] is top.wanxiang.app.harness.UserMessage) {
                    remaining--
                    if (remaining == 0) return index
                }
            }
            return 0
        }

        /**
         * Trim a historic tool output to [CAP_HEAD_CHARS] head + ellipsis + [CAP_TAIL_CHARS]
         * tail. Called only when the output exceeds [TURN_END_RESULT_CAP_CHARS] and the message
         * is outside the recent-turns boundary — never applied to recent turns.
         */
        fun capToolResultForHistory(output: String): String {
            if (output.length <= TURN_END_RESULT_CAP_CHARS) return output
            val head = output.take(CAP_HEAD_CHARS)
            val tail = output.takeLast(CAP_TAIL_CHARS)
            val omitted = output.length - CAP_HEAD_CHARS - CAP_TAIL_CHARS
            return "$head\n…[历史工具结果中段已裁剪 $omitted 字节，仅保留首尾]…\n$tail"
        }
    }
}
