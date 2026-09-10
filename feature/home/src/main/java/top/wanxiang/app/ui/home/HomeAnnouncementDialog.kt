package top.wanxiang.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import top.wanxiang.app.core.model.CloudAnnouncement
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import top.wanxiang.app.ui.components.RuntimeTextButton

/**
 * 首页公告弹窗：卡片化展示云端公告（标题 + 彩色类型徽标 + 相对时间 + 内容摘要）。
 * 用于「公告(type=2)自动弹出」与「顶栏公告图标」两个入口。
 */
@Composable
fun HomeAnnouncementDialog(
    announcements: List<CloudAnnouncement>,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { RuntimeTextButton(onClick = onDismiss) { Text("知道了") } },
        title = { Text("万象公告") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    loading -> Text("加载中…", style = MaterialTheme.typography.bodySmall)
                    announcements.isEmpty() -> Text("暂无公告", style = MaterialTheme.typography.bodySmall)
                    else -> announcements.forEach { a ->
                        AnnouncementCard(a)
                    }
                }
            }
        },
    )
}

@Composable
private fun AnnouncementCard(a: CloudAnnouncement) {
    val isNotice = a.type != 2
    val typeColor = if (isNotice) Color(0xFF1976D2) else Color(0xFFB45309)
    var expanded by androidx.compose.runtime.remember(a.id) { mutableStateOf(false) }
    val content = stripHtml(a.content)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = typeColor.copy(alpha = 0.14f), shape = RoundedCornerShape(5.dp)) {
                    Text(
                        if (isNotice) "通知" else "公告",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = typeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(
                    a.title.ifBlank { "(无标题)" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            // 逐行渲染（富文本换行保真）+ 行距；超过 4 行给「展开/收起」而不是硬截断
            val lines = content.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) {
                Text("(无内容)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val shown = if (expanded) lines else lines.take(4)
                    shown.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (lines.size > 4) {
                        RuntimeTextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "收起" else "展开全文（共 ${lines.size} 行）", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Text(
                formatRelativeTime(a.createTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatRelativeTime(millis: Long): String {
    if (millis <= 0) return ""
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 7 * 86_400_000 -> "${diff / 86_400_000} 天前"
        else -> runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(millis))
        }.getOrNull() ?: ""
    }
}

private fun stripHtml(html: String): String = html
    .replace(Regex("<br\\s*/?>"), "\n")
    .replace(Regex("</p>|</div>|</li>|</h[1-6]>"), "\n")
    .replace(Regex("<[^>]*>"), "")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace(Regex("\n{3,}"), "\n\n")
    .lines().map { it.trim() }.joinToString("\n")
    .trim()
