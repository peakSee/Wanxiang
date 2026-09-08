package top.wkbin.taixu.harness.session

import javax.inject.Inject
import kotlinx.coroutines.flow.first
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.CapabilityEvent
import top.wkbin.taixu.harness.ContextWindowPolicy
import top.wkbin.taixu.harness.HarnessApiMapper
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.ProviderClient
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolCallMode
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.ApiFunctionCall
import top.wkbin.taixu.harness.ApiMessage
import top.wkbin.taixu.harness.ApiToolCall
import top.wkbin.taixu.harness.MentionExtractor
import top.wkbin.taixu.harness.compaction.CompactionManager
import top.wkbin.taixu.harness.prompt.SystemPromptBuilder

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
                    ContextWindowPolicy.estimateTokens(systemPrompt) +
                        ContextWindowPolicy.estimateTokens(compactedContext.summary.orEmpty()),
                )
            } else {
                0
            }
            if (computedKeepFromIndex > 0) {
                compactedContext = compactionManager.compact(sessId, compactedContext, computedKeepFromIndex)
                msgs = compactedContext.messages
            }
            if (!compactedContext.summary.isNullOrBlank()) {
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
            while (i < msgs.size) {
                val message = msgs[i]
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
                            val content = "【工具 $name 执行结果·$status】\n${message.output}"
                            add(ApiMessage(role = "user", content = content))
                            i++
                        }
                        else -> {
                            val mapped = when (message) {
                                is AssistantText ->
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
                    val text = (message as? AssistantText)?.text?.let {
                        ContextWindowPolicy.assistantTextForContext(ProviderClient.stripThinkTags(it) ?: it)
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
                    val content = message.output
                    add(
                        ApiMessage(
                            role = "tool",
                            content = content,
                            tool_call_id = message.toolCallId,
                        ),
                    )
                    i++
                } else {
                    val mapped = HarnessApiMapper.toApiMessage(message)
                    add(if (message is UserMessage && !model.visionEnabled) mapped.copy(imageUrls = emptyList()) else mapped)
                    i++
                }
            }
        }
    }

}
