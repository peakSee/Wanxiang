package top.wanxiang.app.ui.settings.stats.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.wanxiang.app.core.model.StatsSummary
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import java.util.Locale

@Composable
fun StatsMetricGrid(
    summary: StatsSummary,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        MetricItem(
            icon = RuntimeIconName.Chat,
            label = "对话总数",
            value = formatCompact(summary.totalConversations.toLong()),
        ),
        MetricItem(
            icon = RuntimeIconName.NavMessage,
            label = "消息总数",
            value = formatCompact(summary.totalMessages.toLong()),
        ),
        MetricItem(
            icon = RuntimeIconName.Sparkles,
            label = "输入 Token",
            value = formatCompact(summary.inputTokens),
        ),
        MetricItem(
            icon = RuntimeIconName.Sparkles,
            label = "输出 Token",
            value = formatCompact(summary.outputTokens),
        ),
        MetricItem(
            icon = RuntimeIconName.Brain,
            label = "缓存 Token",
            value = formatCompact(summary.cachedTokens),
        ),
        MetricItem(
            icon = RuntimeIconName.Speed,
            label = "启动次数",
            value = "${summary.launchCount} 次",
        ),
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (i in items.indices step 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile(item = items[i], modifier = Modifier.weight(1f))
                if (i + 1 < items.size) {
                    MetricTile(item = items[i + 1], modifier = Modifier.weight(1f))
                } else {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private data class MetricItem(
    val icon: RuntimeIconName,
    val label: String,
    val value: String,
)

@Composable
private fun MetricTile(
    item: MetricItem,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RuntimeIcon(
                    name = item.icon,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                )
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    ),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Text(
                text = item.value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatCompact(value: Long): String {
    if (value < 1000) return value.toString()
    if (value < 1_000_000) {
        val k = value / 1000.0
        return String.format(Locale.US, if (k < 10) "%.1fk" else "%.0fk", k)
    }
    val m = value / 1_000_000.0
    return String.format(Locale.US, if (m < 10) "%.1fM" else "%.0fM", m)
}
