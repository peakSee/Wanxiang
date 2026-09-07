package top.wanxiang.app.harness

import javax.inject.Inject
import javax.inject.Singleton

sealed interface TurnProviderOutcome {
    data class Success(val result: ChatResult, val streamText: String) : TurnProviderOutcome
    data class Failed(val message: String) : TurnProviderOutcome
}

sealed interface TurnOutcome {
    data object Complete : TurnOutcome
    data class Continue(
        val effectiveToolCallCount: Int,
        val toolsHadSuccess: Boolean,
        val followUpCount: Int = 0,
    ) : TurnOutcome
    data class Failed(val message: String) : TurnOutcome
}

/**
 * One Agent turn: provider call -> protocol normalization -> durable assistant publication ->
 * follow-up/tool branch. Validation, approval and execution are supplied as one effect port so
 * this state machine stays deterministic while the production port retains its security policy.
 */
@Singleton
class TurnRunner @Inject constructor(
    private val normalizer: ProviderResponseNormalizer,
) {
    suspend fun run(
        toolsEnabled: Boolean,
        callProvider: suspend () -> TurnProviderOutcome,
        observeResponse: suspend (NormalizedProviderResponse) -> Unit = {},
        persistAssistant: suspend (NormalizedProviderResponse) -> Unit,
        consumeFollowUps: suspend () -> Int,
        enforceToolLimit: suspend (List<ApiToolCallSpec>, ChatResult) -> List<ApiToolCallSpec>,
        executeTools: suspend (List<ApiToolCallSpec>, ChatResult) -> Boolean,
    ): TurnOutcome {
        val provider = callProvider()
        if (provider is TurnProviderOutcome.Failed) return TurnOutcome.Failed(provider.message)
        provider as TurnProviderOutcome.Success

        val normalized = normalizer.normalize(provider.result, provider.streamText, toolsEnabled)
        observeResponse(normalized)
        persistAssistant(normalized)

        if (normalized.toolCalls.isEmpty() && normalized.hasUnresolvedMarkers) {
            return TurnOutcome.Failed(
                "模型返回了无法解析的文本工具调用；已停止，避免把未执行的工具请求误判为完成",
            )
        }
        if (normalized.toolCalls.isEmpty()) {
            val followUpCount = consumeFollowUps()
            return if (followUpCount == 0) {
                TurnOutcome.Complete
            } else {
                TurnOutcome.Continue(
                    effectiveToolCallCount = 0,
                    toolsHadSuccess = true,
                    followUpCount = followUpCount,
                )
            }
        }

        val effectiveCalls = enforceToolLimit(normalized.toolCalls, normalized.result)
        val toolsHadSuccess = executeTools(effectiveCalls, normalized.result)
        return TurnOutcome.Continue(
            effectiveToolCallCount = effectiveCalls.size,
            toolsHadSuccess = toolsHadSuccess,
        )
    }
}
