package top.wanxiang.app.ui.settings.stats.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import top.wanxiang.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.wanxiang.app.core.model.StatsRankItem
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName

@Composable
fun StatsRankSection(
    title: String,
    leftHeader: String,
    rightHeader: String,
    items: List<StatsRankItem>,
    icon: RuntimeIconName? = null,
    modifier: Modifier = Modifier,
) {
    var showAllDialog by remember { mutableStateOf(false) }

    StatsSectionCard(
        title = title,
        modifier = modifier,
        trailing = if (items.size > 5) {
            {
                IconButton(
                    onClick = { showAllDialog = true },
                    modifier = Modifier.size(24.dp),
                ) {
                    RuntimeIcon(
                        name = RuntimeIconName.OpenInNew,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
            }
        } else null,
    ) {
        RankBody(
            leftHeader = leftHeader,
            rightHeader = rightHeader,
            items = items.take(5),
            icon = icon,
        )
    }

    if (showAllDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showAllDialog = false },
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    RankBody(
                        leftHeader = leftHeader,
                        rightHeader = rightHeader,
                        items = items,
                        icon = icon,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAllDialog = false }) {
                    Text("关闭")
                }
            },
        )
    }
}

@Composable
private fun RankBody(
    leftHeader: String,
    rightHeader: String,
    items: List<StatsRankItem>,
    icon: RuntimeIconName?,
) {
    if (items.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "暂无统计数据",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                ),
            )
        }
        return
    }

    val maxValue = remember(items) {
        items.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = leftHeader,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                ),
            )
            Text(
                text = rightHeader,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                ),
            )
        }

        for (item in items) {
            RankRow(item = item, maxValue = maxValue, icon = icon)
        }
    }
}

@Composable
private fun RankRow(
    item: StatsRankItem,
    maxValue: Int,
    icon: RuntimeIconName?,
) {
    val ratio = if (maxValue > 0) (item.value.toFloat() / maxValue) else 0f
    val widthFactor = (0.36f + ratio * 0.64f).coerceIn(0.36f, 1f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(34.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            // 背景半透明圆角比例胶囊条
            Box(
                modifier = Modifier
                    .fillMaxWidth(widthFactor)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(17.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            )

            // 内容展示
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (icon != null) {
                    RuntimeIcon(
                        name = icon,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    )
                }
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = item.value.toString(),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
    }
}
