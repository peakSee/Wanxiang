package top.wkbin.taixu.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp

/**
 * 🌟 上下文用量仪表环 (Context Usage Ring)
 * 以精致的圆形微仪表盘展示当前会话已消耗 Token 比例，点击展开 8 维可视化面板。
 */
@Composable
fun ContextUsageRing(
    usage: ContextUsage,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    val rawRatio = (usage.usedTokens.toFloat() / usage.limitTokens.coerceAtLeast(1)).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = rawRatio,
        animationSpec = tween(durationMillis = 600),
        label = "contextUsageSweep",
    )

    val ringColor = when {
        rawRatio >= 0.9f -> MaterialTheme.colorScheme.error
        rawRatio >= 0.7f -> Color(0xFFE65100)
        rawRatio >= 0.45f -> Color(0xFFF59E0B)
        else -> MaterialTheme.colorScheme.primary
    }

    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)

    Box(modifier = modifier) {
        Surface(
            onClick = { showDialog = true },
            shape = CircleShape,
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

        if (showDialog) {
            ContextUsageDialog(
                usage = usage,
                onDismiss = { showDialog = false },
            )
        }
    }
}
