package top.wanxiang.app.ui.home

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
import top.wanxiang.app.ui.components.RuntimeOutlinedButton
import top.wanxiang.app.ui.components.RuntimeTextButton

/**
 * 首页「消息中心」：统一入口，聚合 更新 / 公告 / 关于 三块。
 */
@Composable
fun HomeMessageCenterDialog(
    currentVersion: String,
    updateMessage: String?,
    announcements: List<CloudAnnouncement>,
    announcementLoading: Boolean,
    onCheckUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { RuntimeTextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("消息中心") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // —— 更新 ——
                SectionTitle("更新")
                Text("当前版本 v$currentVersion", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                updateMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                RuntimeOutlinedButton(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth()) {
                    Text("检查更新", maxLines = 1)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // —— 公告 ——
                SectionTitle("公告")
                when {
                    announcementLoading -> Text("加载中…", style = MaterialTheme.typography.bodySmall)
                    announcements.isEmpty() -> Text("暂无公告", style = MaterialTheme.typography.bodySmall)
                    else -> announcements.take(5).forEach { a ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                a.title.ifBlank { "(无标题)" },
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stripHtml(a.content).ifBlank { "(无内容)" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // —— 关于 ——
                SectionTitle("关于")
                Text("万象 · 掌中万象，造物随心", style = MaterialTheme.typography.bodySmall)
                Text("开源地址：github.com/peakSee/Wanxiang", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("交流群：905971993", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
