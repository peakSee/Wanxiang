package top.wanxiang.app.harness.subagent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import top.wanxiang.app.core.datastore.AgentPreferences
import top.wanxiang.app.harness.ApiMessage
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.CapabilityEvent
import top.wanxiang.app.harness.HarnessApiMapper
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.HarnessTool
import top.wanxiang.app.harness.ProviderClient
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.ToolExecutor
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.harness.TextToolCallCodec
import top.wanxiang.app.harness.effects.ToolReplayPolicy
import top.wanxiang.app.harness.operation.OperationCoordinator
import top.wanxiang.app.harness.session.SessionTreeStore
import top.wanxiang.app.harness.validation.ToolCallLoopDetector
import top.wanxiang.app.harness.validation.ToolSchemaValidator
import top.wanxiang.app.harness.R

data class SubagentLaneResult(
    val success: Boolean,
    val summary: String,
    val toolCallCount: Int,
)

/**
 * Headless lane interpreter used by subagents; it shares tree history but owns its operation.
 *
 * [ToolExecutor] 以 [Provider] 注入以打断 Hilt 依赖环：
 * ToolExecutor → SubagentOrchestrator → SubagentLaneRunner → ToolExecutor。
 * 工具只在 [run] 执行期才实际取用，构造期延迟解析是安全的。
 */
@Singleton
class SubagentLaneRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providerClient: ProviderClient,
    private val toolExecutor: Provider<ToolExecutor>,
    private val treeStore: SessionTreeStore,
    private val operations: OperationCoordinator,
    private val settingsDataStore: AgentPreferences,
    private val json: Json,
) {
    suspend fun run(
        sessionId: String,
        laneName: String,
        prompt: String,
        workspace: String,
        modelId: String? = null,
        modelVariant: String? = null,
        modelConfig: top.wanxiang.app.harness.ModelConfig? = null,
    ): SubagentLaneResult {
        val user = top.wanxiang.app.harness.UserMessage(UUID.randomUUID().toString(), now(), prompt)
        val operationId = operations.acceptRun(sessionId, user, laneName)
        var toolCalls = 0
        var finalText = ""
        return try {
            val configuredModel = modelConfig ?: providerClient.resolveConfigured(modelId, modelVariant)
            val loopDetector = ToolCallLoopDetector()
            val maxRounds = runCatching { settingsDataStore.maxToolRounds.first() }
                .getOrDefault(DEFAULT_MAX_ROUNDS)
                .coerceIn(MIN_MAX_ROUNDS, MAX_MAX_ROUNDS)
            repeat(maxRounds) { round ->
                val forceFinalAnswer = shouldForceSubagentFinalAnswer(round, maxRounds)
                val model = if (forceFinalAnswer) configuredModel.copy(pureChatMode = true) else configuredModel
                val responseId = UUID.randomUUID().toString()
                operations.providerIntent(operationId, responseId, round, 1, NETWORK_ATTEMPTS)
                val text = StringBuilder()
                val result = chatWithRetry(model, providerMessages(sessionId, laneName, forceFinalAnswer), text)
                val rawAssistantText = text.toString().ifBlank { result.content.orEmpty() }
                val textNormalization = TextToolCallCodec.normalize(json, rawAssistantText)
                val roundCalls = if (forceFinalAnswer) {
                    result.toolCalls
                } else {
                    TextToolCallCodec.resolveCalls(result.toolCalls, textNormalization)
                }
                val assistantText = if (textNormalization.hasMarkers) {
                    textNormalization.displayText
                } else {
                    rawAssistantText
                }
                val usageEntity = result.usage.takeIf { it.hasData }?.let {
                    operations.usageEntity(
                        sessionId = sessionId,
                        operationId = operationId,
                        entryId = responseId.takeIf { assistantText.isNotBlank() },
                        provider = model.provider,
                        modelId = model.model,
                        usage = it,
                    )
                }
                if (assistantText.isNotBlank()) {
                    val assistant = AssistantText(
                        id = responseId,
                        createdAt = now(),
                        text = assistantText,
                        reasoning = result.reasoningContent,
                        modelId = model.model,
                        providerId = model.provider,
                        promptTokens = result.usage.inputTokens.takeIf { it > 0 }?.toInt(),
                        completionTokens = result.usage.outputTokens.takeIf { it > 0 }?.toInt(),
                        cachedTokens = result.usage.cacheReadTokens.takeIf { it > 0 }?.toInt(),
                    )
                    operations.providerSettled(operationId, assistant, usage = usageEntity, round = round)
                    finalText = assistantText
                } else {
                    operations.providerSettled(operationId, null, usage = usageEntity, round = round)
                }
                // 最后一轮不向 Provider 暴露工具。只要响应仍尝试调用工具，就不能把未执行的
                // 请求误报为成功；必须真正生成不含工具协议的可见结论。
                if (forceFinalAnswer) {
                    val hasConclusion = isDirectSubagentConclusion(
                        assistantText,
                        result.toolCalls,
                        textNormalization,
                    )
                    operations.finish(
                        sessionId,
                        if (hasConclusion) "completed" else "failed",
                        responseId,
                        details = if (hasConclusion) null else "最后一轮未生成纯文本结论",
                        laneName = laneName,
                    )
                    return SubagentLaneResult(
                        hasConclusion,
                        if (hasConclusion) assistantText else "最后一轮仍尝试调用工具，子智能体未能生成结论",
                        toolCalls,
                    )
                }
                if (roundCalls.isEmpty() && textNormalization.hasUnresolvedMarkers) {
                    operations.finish(sessionId, "failed", details = "无法解析文本工具调用", laneName = laneName)
                    return SubagentLaneResult(
                        false,
                        "模型返回了无法解析的文本工具调用，未将其误判为任务完成",
                        toolCalls,
                    )
                }
                if (roundCalls.isEmpty()) {
                    operations.finish(sessionId, "completed", responseId, laneName = laneName)
                    return SubagentLaneResult(true, finalText.ifBlank { "子智能体已完成（无文本输出）" }, toolCalls)
                }

                for (spec in roundCalls) {
                    toolCalls++
                    val rawName = spec.name.trim()
                    val tool = HarnessApiMapper.toolByName(rawName)
                    val args = runCatching { json.parseToJsonElement(spec.argumentsJson) as JsonObject }.getOrElse {
                        val invalidCall = ToolCall(spec.id, now(), tool, JsonObject(emptyMap()), result.reasoningContent, rawName)
                        operations.toolIntent(
                            operationId,
                            invalidCall,
                            spec.argumentsJson,
                            ToolReplayPolicy.forTool(tool, rawName),
                            round,
                        )
                        val failed = ToolResult(UUID.randomUUID().toString(), now(), spec.id, false, "工具参数不是 JSON 对象：${it.message}")
                        operations.toolSettled(operationId, failed, round, toolName = rawName)
                        continue
                    }
                    val call = ToolCall(spec.id, now(), tool, args, result.reasoningContent, rawName)
                    val schemaProblems = ToolSchemaValidator.problemsFor(rawName, args, model.dynamicMcpTools)
                    if (schemaProblems.isNotEmpty()) {
                        operations.toolIntent(operationId, call, spec.argumentsJson, ToolReplayPolicy.forTool(tool, rawName), round)
                        val rejected = ToolResult(
                            UUID.randomUUID().toString(), now(), spec.id, false,
                            "工具参数校验未通过：${schemaProblems.joinToString("；")}。请修正参数后重新调用。",
                        )
                        operations.toolSettled(operationId, rejected, round, toolName = rawName)
                        continue
                    }
                    val loopVerdict = loopDetector.evaluate(rawName, args)
                    if (loopVerdict is ToolCallLoopDetector.LoopVerdict.Block) {
                        operations.toolIntent(operationId, call, spec.argumentsJson, ToolReplayPolicy.forTool(tool, rawName), round)
                        val blocked = ToolResult(
                            UUID.randomUUID().toString(), now(), spec.id, false,
                            "${loopVerdict.reason}\n\n${loopVerdict.guidance}",
                        )
                        operations.toolSettled(operationId, blocked, round, toolName = rawName)
                        continue
                    }
                    operations.toolIntent(operationId, call, spec.argumentsJson, ToolReplayPolicy.forTool(tool, rawName), round)
                    loopDetector.recordIntent(rawName, args)
                    val outcome = if (tool == HarnessTool.SUBAGENT) {
                        ToolResult(UUID.randomUUID().toString(), now(), call.id, false, "子智能体 Lane 禁止再次派发子智能体")
                    } else {
                        toolExecutor.get().execute(
                            call,
                            sessionId,
                            workspace,
                            allowApprovalRequest = false,
                            operationId = operationId,
                        )
                    }
                    operations.toolSettled(operationId, outcome, round, toolName = rawName)
                    loopDetector.recordSettled(rawName, args, outcome.success)
                }
            }
            operations.finish(sessionId, "failed", details = "max rounds", laneName = laneName)
            SubagentLaneResult(false, finalText.ifBlank { "达到子智能体最大工具轮数" }, toolCalls)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // 结构化取消（用户停止或编排层超时）必须向上重抛；
            // 否则 lane operation 永远停留在 RUNNING，形成僵尸行。
            // finish 自身是挂起点，需在 NonCancellable 下落盘（与主循环清理链同一模式）。
            withContext(kotlinx.coroutines.NonCancellable) {
                operations.finish(sessionId, "aborted", details = "已取消", laneName = laneName)
            }
            throw cancellation
        } catch (throwable: Throwable) {
            operations.finish(sessionId, "failed", details = throwable.message, laneName = laneName)
            SubagentLaneResult(false, throwable.message ?: "子智能体执行失败", toolCalls)
        }
    }

    private suspend fun providerMessages(
        sessionId: String,
        laneName: String,
        forceFinalAnswer: Boolean,
    ): List<ApiMessage> = isolatedProviderMessages(
        messages = treeStore.load(sessionId, laneName),
        systemPrompt = context.getString(R.string.harness_prompt_subagent_lane_system),
        forceFinalAnswer = forceFinalAnswer,
    )

    private suspend fun chatWithRetry(
        model: top.wanxiang.app.harness.ModelConfig,
        messages: List<ApiMessage>,
        text: StringBuilder,
    ): top.wanxiang.app.harness.ChatResult {
        var lastFailure: IOException? = null
        repeat(NETWORK_ATTEMPTS) { attempt ->
            try {
                return providerClient.chatStream(model, messages, onReasoning = {}) { text.append(it) }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (failure: IOException) {
                lastFailure = failure
                text.clear()
                if (attempt + 1 < NETWORK_ATTEMPTS) delay(NETWORK_RETRY_DELAY_MS)
            }
        }
        throw requireNotNull(lastFailure)
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val DEFAULT_MAX_ROUNDS = 100
        private const val MIN_MAX_ROUNDS = 10
        private const val MAX_MAX_ROUNDS = 300
        private const val NETWORK_ATTEMPTS = 2
        private const val NETWORK_RETRY_DELAY_MS = 1_000L
    }
}

internal fun isolatedProviderMessages(
    messages: List<HarnessMessage>,
    systemPrompt: String,
    forceFinalAnswer: Boolean,
): List<ApiMessage> = buildList {
    val finalInstruction = if (forceFinalAnswer) {
        "\n这是最后一轮。禁止继续调用工具，请根据已有结果直接输出结论；信息不完整时明确说明限制。"
    } else {
        ""
    }
    add(ApiMessage(role = "system", content = systemPrompt + finalInstruction))
    val taskStart = messages.indexOfLast { it is top.wanxiang.app.harness.UserMessage }
        .takeIf { it >= 0 } ?: messages.size
    messages.drop(taskStart).forEach { message ->
        if (message !is CapabilityEvent) add(HarnessApiMapper.toApiMessage(message))
    }
}

internal fun isDirectSubagentConclusion(
    assistantText: String,
    structuredCalls: List<top.wanxiang.app.harness.ApiToolCallSpec>,
    textNormalization: TextToolCallCodec.Normalization,
): Boolean = assistantText.isNotBlank() && structuredCalls.isEmpty() && !textNormalization.hasMarkers

internal fun shouldForceSubagentFinalAnswer(round: Int, maxRounds: Int): Boolean =
    round >= maxRounds.coerceAtLeast(1) - 1
