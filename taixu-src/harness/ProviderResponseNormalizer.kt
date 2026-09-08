package top.wkbin.taixu.harness

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** Protocol normalization boundary shared by the main loop and independently testable. */
@Singleton
class ProviderResponseNormalizer @Inject constructor(
    private val json: Json,
) {
    fun normalize(result: ChatResult, rawText: String, toolsEnabled: Boolean): NormalizedProviderResponse {
        // Structured native calls are authoritative: skip the textual codec so the display text
        // stays byte-identical with rawText and stray markers in the content do not get stripped
        // as if they were executed. The textual branch only runs as a fallback.
        if (!toolsEnabled || result.toolCalls.isNotEmpty()) {
            return NormalizedProviderResponse(
                result = result,
                rawText = rawText,
                displayText = rawText,
                toolCalls = result.toolCalls,
                textToolCallCount = 0,
                invalidMarkerCount = 0,
                hasUnresolvedMarkers = false,
            )
        }
        val textNormalization = TextToolCallCodec.normalize(json, rawText)
        if (textNormalization.calls.isNotEmpty() || textNormalization.hasUnresolvedMarkers) {
            return NormalizedProviderResponse(
                result = result,
                rawText = rawText,
                displayText = textNormalization.displayText,
                toolCalls = textNormalization.calls,
                textToolCallCount = textNormalization.calls.size,
                invalidMarkerCount = textNormalization.invalidMarkerCount,
                hasUnresolvedMarkers = textNormalization.hasUnresolvedMarkers,
            )
        }

        return NormalizedProviderResponse(
            result = result,
            rawText = rawText,
            displayText = rawText,
            toolCalls = emptyList(),
            textToolCallCount = 0,
            invalidMarkerCount = 0,
            hasUnresolvedMarkers = false,
        )
    }

}

data class NormalizedProviderResponse(
    val result: ChatResult,
    val rawText: String,
    val displayText: String,
    val toolCalls: List<ApiToolCallSpec>,
    val textToolCallCount: Int,
    val invalidMarkerCount: Int,
    val hasUnresolvedMarkers: Boolean,
)
