package top.wanxiang.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Git 长操作（clone/pull/push）流式进度横幅。挂在 ChatTopBar 下方，
 * 只要 [gitProgress] StateFlow 非 null 就展开显示，操作结束自动收缩。
 *
 * 数据源：ChatViewModel 里 `git` 命令的 `onOutput` 回调解析出的 `Receiving objects: 45% (123/270), 3.5 MiB | 1.2 MiB/s`
 * 抽出的百分比 / 对象数 / 传输速率。右侧「取消」→ [onCancel] 触发 `cancelRequested` 标志。
 */
@Composable
fun GitProgressBanner(progress: GitProgress, onCancel: () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = progress.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                progress.percent?.let { pct ->
                    Text(
                        text = "$pct%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(
                    onClick = onCancel,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) { Text("取消", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                progress.objects?.let { Text("对象 $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                progress.transfer?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
            }
            val p = progress.percent?.let { it / 100f }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f)),
            ) {
                if (p != null) {
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.05f),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.05f),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }
        }
    }
}
