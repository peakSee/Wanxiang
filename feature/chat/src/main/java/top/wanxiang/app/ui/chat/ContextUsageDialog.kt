package top.wanxiang.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 上下文用量可视化弹窗（借鉴 taixu v0.13 ContextUsageDialog，适配 万象 三维 ContextUsage）。
 *
 * 顶部：整条使用率水平进度条 + 百分比数字 + `已用 / 上限 tokens`；
 * 中部：**分段彩条** —— 系统提示 / 工具与 schema / 对话历史，按占比铺色；
 * 下部：4 行明细（含缓存命中、是否压缩过）。
 *
 * 用户点 Ring 打开这个能看到 token 到底花在哪，比只显示"用了 40%"透明很多。
 */
@Composable
fun ContextUsageDialog(
    usage: ContextUsage,
    onDismiss: () -> Unit,
) {
    val limit = max(usage.limitTokens, 1)
    val used = usage.usedTokens.coerceAtMost(limit).coerceAtLeast(0)
    val pct = (used * 100f / limit).roundToInt().coerceIn(0, 100)

    // 分段：确保 3 段相加 <= used，避免视觉溢出
    val system = usage.systemTokens.coerceAtLeast(0)
    val tool = usage.toolTokens.coerceAtLeast(0)
    val conversation = max(used - system - tool, 0)
    val sum = (system + tool + conversation).coerceAtLeast(1)

    val palette = usagePalette()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上下文用量", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 顶部大数字
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$pct%",
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (pct > 90) palette.warn else if (pct > 70) palette.warnSoft else palette.accent,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${format(used)} / ${format(limit)} tokens",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 总进度条
                Box(
                    Modifier.fillMaxWidth().height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier.fillMaxWidth(pct / 100f).height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (pct > 90) palette.warn else palette.accent),
                    ) {}
                }

                // 分段彩条
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("构成（按 token 数）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        Modifier.fillMaxWidth().height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(Modifier.weight(system.toFloat()).fillMaxWidth().background(palette.system)) {}
                        Box(Modifier.weight(tool.toFloat()).fillMaxWidth().background(palette.tool)) {}
                        Box(Modifier.weight(conversation.toFloat()).fillMaxWidth().background(palette.conversation)) {}
                    }
                    LegendRow("系统提示", system, sum, palette.system)
                    LegendRow("工具与 schema", tool, sum, palette.tool)
                    LegendRow("对话历史", conversation, sum, palette.conversation)
                }

                Spacer(Modifier.height(4.dp))

                // 明细行
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("已用 tokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(format(used), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                if (usage.cachedTokens > 0) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("缓存 tokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${format(usage.cachedTokens.toInt())} (${usage.cacheHitRatePercent ?: 0}% 命中)",
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = palette.accent)
                    }
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("压缩状态", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (usage.compacted) "已压缩" else "未压缩", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("模型上限", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(format(limit), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

private data class UsagePalette(
    val accent: Color,
    val warn: Color,
    val warnSoft: Color,
    val system: Color,
    val tool: Color,
    val conversation: Color,
)

@Composable
private fun usagePalette(): UsagePalette {
    val scheme = MaterialTheme.colorScheme
    return UsagePalette(
        accent = scheme.primary,
        warn = Color(0xFFDC2626),
        warnSoft = Color(0xFFD97706),
        system = Color(0xFF6366F1),
        tool = Color(0xFF0EA5E9),
        conversation = Color(0xFF10B981),
    )
}

@Composable
private fun LegendRow(label: String, tokens: Int, total: Int, color: Color) {
    val pct = if (total > 0) (tokens * 100f / total).roundToInt() else 0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(10.dp).height(10.dp).clip(CircleShape).background(color)) {}
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text("${format(tokens)} · $pct%", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun format(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
