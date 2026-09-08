package top.wkbin.taixu.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.wkbin.taixu.feature.chat.R
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.StatusBadge
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 🌟 上下文用量可视化弹窗 (Context Usage Dialog)
 * 高保真复刻 8 维彩色分段进度条与用量细分清单。
 */
@Composable
fun ContextUsageDialog(
    usage: ContextUsage,
    onDismiss: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF18181B) else MaterialTheme.colorScheme.surfaceContainerHigh
    val cardBorder = if (isDark) Color(0xFF27272A) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    val limitTokens = usage.limitTokens.coerceAtLeast(1)
    val rawRatio = (usage.usedTokens.toFloat() / limitTokens).coerceIn(0f, 1f)
    val displayPercent = (rawRatio * 100).roundToInt().coerceIn(0, 100)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            RuntimeCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp),
                containerColor = cardBg,
                borderColor = cardBorder,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // 1. Header: Title + Close Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_context_title),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = if (isDark) Color(0xFFF3F4F6) else MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                        Surface(
                            onClick = onDismiss,
                            shape = CircleShape,
                            color = Color.Transparent,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                RuntimeIcon(
                                    name = RuntimeIconName.Close,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isDark) Color(0xFF9CA3AF) else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // 2. Summary stats row: "73% Full" / "~185.9K / 256K Tokens"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_context_full, displayPercent),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = if (isDark) Color(0xFFE5E7EB) else MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                        Text(
                            text = stringResource(
                                R.string.chat_context_tokens_summary,
                                formatDialogTokenCount(usage.usedTokens),
                                formatLimitTokens(usage.limitTokens),
                            ),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                                color = if (isDark) Color(0xFF9CA3AF) else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }

                    // 3. Segmented multi-color progress bar
                    ContextUsageSegmentedBar(
                        usage = usage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                    )

                    // 4. Breakdown legend rows
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val categories = remember(usage.breakdown) {
                            listOf(
                                ContextCategorySpec(Color(0xFF8E8E93), R.string.chat_context_system_prompt, usage.breakdown.systemPromptTokens),
                                ContextCategorySpec(Color(0xFFA855F7), R.string.chat_context_tool_definitions, usage.breakdown.toolDefinitionTokens),
                                ContextCategorySpec(Color(0xFF22C55E), R.string.chat_context_rules, usage.breakdown.rulesTokens),
                                ContextCategorySpec(Color(0xFFF59E0B), R.string.chat_context_skills, usage.breakdown.skillsTokens),
                                ContextCategorySpec(Color(0xFFC084FC), R.string.chat_context_mcp_dynamic, usage.breakdown.mcpTokens),
                                ContextCategorySpec(Color(0xFF38BDF8), R.string.chat_context_subagent_definitions, usage.breakdown.subagentTokens),
                                ContextCategorySpec(Color(0xFFF43F5E), R.string.chat_context_summarized_conversation, usage.breakdown.summarizedTokens),
                                ContextCategorySpec(Color(0xFF885472), R.string.chat_context_conversation, usage.breakdown.conversationTokens),
                            )
                        }

                        categories.forEach { spec ->
                            ContextUsageRow(spec = spec, isDark = isDark)
                        }
                    }

                    // 5. Optional TaiXu Enhanced Footer (KV Cache & Compaction)
                    if (usage.cachedTokens > 0L || usage.compacted) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            if (usage.cachedTokens > 0L) {
                                Text(
                                    text = stringResource(
                                        R.string.chat_context_kv_cache,
                                        formatDialogTokenCount(usage.cachedTokens.toInt()),
                                        usage.cacheHitRatePercent ?: 0,
                                    ),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 11.sp,
                                    ),
                                )
                            }
                            if (usage.compacted) {
                                StatusBadge(
                                    text = stringResource(R.string.chat_context_compacted_active),
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextUsageRow(
    spec: ContextCategorySpec,
    isDark: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Color swatch
        Box(
            modifier = Modifier
                .size(11.dp)
                .background(spec.color, RoundedCornerShape(2.5.dp)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        // Category Label
        Text(
            text = stringResource(spec.titleRes),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = if (isDark) Color(0xFFD1D5DB) else MaterialTheme.colorScheme.onSurface,
            ),
        )
        Spacer(modifier = Modifier.weight(1f))
        // Formatted Token Count
        Text(
            text = formatDialogTokenCount(spec.tokens),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = if (isDark) Color(0xFF9CA3AF) else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
fun ContextUsageSegmentedBar(
    usage: ContextUsage,
    modifier: Modifier = Modifier,
) {
    val items = remember(usage.breakdown) {
        listOf(
            SegmentItem(Color(0xFF8E8E93), usage.breakdown.systemPromptTokens),
            SegmentItem(Color(0xFFA855F7), usage.breakdown.toolDefinitionTokens),
            SegmentItem(Color(0xFF22C55E), usage.breakdown.rulesTokens),
            SegmentItem(Color(0xFFF59E0B), usage.breakdown.skillsTokens),
            SegmentItem(Color(0xFFC084FC), usage.breakdown.mcpTokens),
            SegmentItem(Color(0xFF38BDF8), usage.breakdown.subagentTokens),
            SegmentItem(Color(0xFFF43F5E), usage.breakdown.summarizedTokens),
            SegmentItem(Color(0xFF885472), usage.breakdown.conversationTokens),
        ).filter { it.tokens > 0 }
    }

    val limit = usage.limitTokens.coerceAtLeast(1)
    val totalUsed = usage.usedTokens.coerceAtLeast(0)

    val animatedRatio by animateFloatAsState(
        targetValue = (totalUsed.toFloat() / limit).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "contextBarRatio",
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp)),
    ) {
        val w = size.width
        val h = size.height
        val cornerRadius = CornerRadius(h / 2f, h / 2f)

        // 1. Draw track (empty budget background)
        drawRoundRect(
            color = Color(0xFF27272A),
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = cornerRadius,
        )

        if (items.isEmpty() || animatedRatio <= 0f) return@Canvas

        val gapPx = 2.dp.toPx()
        val minPillPx = 4.dp.toPx()

        // Total pixel width available for used tokens
        val totalUsedW = w * animatedRatio
        val sumTokens = items.sumOf { it.tokens }.coerceAtLeast(1)
        val gapsCount = (items.size - 1).coerceAtLeast(0)
        val netWidth = (totalUsedW - gapsCount * gapPx).coerceAtLeast(0f)

        var cursorX = 0f
        for (item in items) {
            val proportionalW = (item.tokens.toFloat() / sumTokens) * netWidth
            val segmentW = minOf(maxOf(minPillPx, proportionalW), (w - cursorX).coerceAtLeast(0f))
            if (segmentW > 0f) {
                drawRoundRect(
                    color = item.color,
                    topLeft = Offset(cursorX, 0f),
                    size = Size(segmentW, h),
                    cornerRadius = CornerRadius(h / 2f, h / 2f),
                )
                cursorX += segmentW + gapPx
            }
            if (cursorX >= w) break
        }
    }
}

private data class ContextCategorySpec(
    val color: Color,
    val titleRes: Int,
    val tokens: Int,
)

private data class SegmentItem(
    val color: Color,
    val tokens: Int,
)

private fun formatDialogTokenCount(tokens: Int): String {
    return when {
        tokens <= 0 -> "0"
        tokens < 1000 -> tokens.toString()
        tokens < 1_000_000 -> {
            val k = tokens / 1000.0
            String.format(Locale.US, "%.1fK", k)
        }
        else -> {
            val m = tokens / 1_000_000.0
            String.format(Locale.US, "%.1fM", m)
        }
    }
}

private fun formatLimitTokens(tokens: Int): String {
    return when {
        tokens <= 0 -> "0"
        tokens < 1000 -> tokens.toString()
        tokens % 1000 == 0 && tokens < 1_000_000 -> "${tokens / 1000}K"
        tokens < 1_000_000 -> String.format(Locale.US, "%.1fK", tokens / 1000.0)
        tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
        else -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    }
}
