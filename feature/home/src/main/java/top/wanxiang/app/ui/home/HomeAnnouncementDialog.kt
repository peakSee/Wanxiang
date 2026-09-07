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
            Text(
                stripHtml(a.content).ifBlank { "(无内容)" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
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
    .replace(Regex("</p>"), "\n")
    .replace(Regex("<[^>]*>"), "")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .trim()
