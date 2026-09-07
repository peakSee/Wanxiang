package top.wanxiang.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.wanxiang.app.core.model.CloudAnnouncement
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import top.wanxiang.app.ui.components.RuntimeTextButton

/**
 * 公告列表弹窗：展示云端公告的标题/类型/时间与内容摘要。
 * content 可能是富文本 HTML，这里做简单标签剥离后按纯文本展示（优先简单）。
 */
@Composable
fun AnnouncementListDialog(
    announcements: List<CloudAnnouncement>,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { RuntimeTextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("公告") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    loading -> Text("加载中…", style = MaterialTheme.typography.bodySmall)
                    error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    announcements.isEmpty() -> Text("暂无公告", style = MaterialTheme.typography.bodySmall)
                    else -> announcements.forEachIndexed { index, a ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                a.title.ifBlank { "(无标题)" },
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${typeLabel(a.type)} · ${formatTime(a.createTime)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stripHtml(a.content).ifBlank { "(无内容)" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
    )
}

private fun typeLabel(type: Int): String = if (type == 2) "公告" else "通知"

private fun formatTime(millis: Long): String {
    if (millis <= 0) return ""
    return runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }.getOrNull() ?: ""
}

/** 简单剥离 HTML 标签（content 可能是富文本 HTML）。 */
private fun stripHtml(html: String): String = html
    .replace(Regex("<br\\s*/?>"), "\n")
    .replace(Regex("</p>"), "\n")
    .replace(Regex("<[^>]*>"), "")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .trim()
