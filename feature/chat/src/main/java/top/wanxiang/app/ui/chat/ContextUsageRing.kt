package top.wanxiang.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.wanxiang.app.feature.chat.R
import top.wanxiang.app.ui.components.RuntimeLinearProgressIndicator
import top.wanxiang.app.ui.components.StatusBadge
import kotlin.math.roundToInt

/**
 * 🌟 上下文用量仪表环 (Context Usage Ring)
 * 以精致的圆形仪表盘展示当前会话已消耗 Token 比例与详细分解。
 */
@Composable
fun ContextUsageRing(
    usage: ContextUsage,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val rawRatio = (usage.usedTokens.toFloat() / usage.limitTokens.coerceAtLeast(1)).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = rawRatio,
        animationSpec = tween(durationMillis = 600),
        label = "contextUsageSweep",
    )
    val displayPercent = (rawRatio * 100).roundToInt().coerceIn(0, 100)

    val ringColor = when {
        rawRatio >= 0.9f -> MaterialTheme.colorScheme.error
        rawRatio >= 0.7f -> Color(0xFFE65100)
        rawRatio >= 0.45f -> Color(0xFFF59E0B)
        else -> MaterialTheme.colorScheme.primary
    }

    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)

    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            shape = androidx.compose.foundation.shape.CircleShape,
            color = ringColor.copy(alpha = 0.08f),
            modifier = Modifier.size(30.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                // 环形进度仪表盘
                Canvas(modifier = Modifier.size(16.dp)) {
                    val strokeWidth = 2.4.dp.toPx()
                    val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                    val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)

                    // 背景轨道
                    drawArc(
                        color = trackColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth),
                    )

                    // 前景进度
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = animatedProgress * 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 260.dp, max = 300.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.chat_context_usage),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "$displayPercent% (${formatContextTokens(usage.usedTokens)} / ${formatContextTokens(usage.limitTokens)})",
                        style = MaterialTheme.typography.labelMedium,
                        color = ringColor,
                        fontWeight = FontWeight.Bold,
                    )
                }

                RuntimeLinearProgressIndicator(
                    progress = { rawRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp),
                    color = ringColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    UsageBreakdownRow(
                        label = stringResource(R.string.chat_context_system_tokens),
                        tokens = usage.systemTokens,
                    )
                    UsageBreakdownRow(
                        label = stringResource(R.string.chat_context_dialogue_tokens),
                        tokens = usage.conversationTokens,
                    )
                    UsageBreakdownRow(
                        label = stringResource(R.string.chat_context_tool_tokens),
                        tokens = usage.toolTokens,
                    )
                    if (usage.cachedTokens > 0L) {
                        UsageBreakdownRow(
                            label = "KV 前缀缓存命中",
                            tokens = usage.cachedTokens.toInt(),
                            valueOverride = usage.cacheHitRatePercent?.let {
                                "⚡${formatContextTokens(usage.cachedTokens.toInt())} ($it%)"
                            },
                        )
                    }
                }

                if (usage.compacted) {
                    StatusBadge(
                        text = stringResource(R.string.chat_context_compacted_active),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageBreakdownRow(
    label: String,
    tokens: Int,
    valueOverride: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Text(
            text = valueOverride ?: formatContextTokens(tokens),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = if (valueOverride != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
        )
    }
}


private fun formatContextTokens(tokens: Int): String {
    return if (tokens >= 1000) "${tokens / 1000}k" else "$tokens"
}
