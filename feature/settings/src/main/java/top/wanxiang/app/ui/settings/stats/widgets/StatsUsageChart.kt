package top.wanxiang.app.ui.settings.stats.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.wanxiang.app.core.model.StatsTrendDay
import java.util.Locale

@Composable
fun StatsUsageChart(
    days: List<StatsTrendDay>,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "暂无用量数据",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                ),
            )
        }
        return
    }

    var selectedDayIndex by remember { mutableStateOf<Int?>(null) }
    val scrollState = rememberScrollState()

    val maxDayTokens = remember(days) {
        days.maxOfOrNull { day ->
            day.providerTokens.values.sumOf { it.totalTokens }
        }?.coerceAtLeast(1L) ?: 1L
    }

    LaunchedEffect(days.size) {
        if (days.isNotEmpty()) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    val selectedDay = selectedDayIndex?.let { days.getOrNull(it) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 浮动详情指示卡片
        if (selectedDay != null) {
            val totalTokens = selectedDay.providerTokens.values.sumOf { it.totalTokens }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = selectedDay.date.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                        Text(
                            text = "总计: ${formatTokens(totalTokens)}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }

                    for ((provider, bucket) in selectedDay.providerTokens) {
                        if (bucket.totalTokens > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "• $provider",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = formatTokens(bucket.totalTokens),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 柱状图展示区域
        val barWidth = if (days.size > 45) 8.dp else 12.dp
        val barGap = if (days.size > 45) 3.dp else 5.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .horizontalScroll(scrollState),
        ) {
            Row(
                modifier = Modifier.fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(barGap),
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEachIndexed { index, day ->
                    val dayTotal = day.providerTokens.values.sumOf { it.totalTokens }
                    val heightFactor = if (maxDayTokens > 0) (dayTotal.toFloat() / maxDayTokens).coerceIn(0.04f, 1f) else 0.04f
                    val isSelected = selectedDayIndex == index

                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(barWidth)
                            .clickable {
                                selectedDayIndex = if (selectedDayIndex == index) null else index
                            },
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(heightFactor)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else if (dayTotal > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                ),
                        )
                    }
                }
            }
        }
    }
}

private fun formatTokens(value: Long): String {
    if (value < 1000) return "$value Tokens"
    if (value < 1_000_000) {
        return String.format(Locale.US, "%.1fk Tokens", value / 1000.0)
    }
    return String.format(Locale.US, "%.2fM Tokens", value / 1_000_000.0)
}
