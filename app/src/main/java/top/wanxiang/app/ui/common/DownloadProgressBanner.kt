package top.wanxiang.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.wanxiang.app.core.network.AppUpdateManager

/**
 * APK 下载进度横幅（P0-4）。挂在应用根布局顶部（WanXiangTheme 之后立即），跨所有页面可见。
 *
 * 数据源：[AppUpdateManager.downloadProgress] StateFlow（每次 onProgress 都写入）。
 * 断点续传时会显示「续传 12.3 MB / 10.7 MB」，用户知道是从上次中断继续（P0-3）。
 */
@Composable
fun DownloadProgressBanner(progress: AppUpdateManager.DownloadProgress) {
    val downloadedMb = progress.downloadedBytes / 1_048_576f
    val totalMb = if (progress.totalBytes > 0) progress.totalBytes / 1_048_576f else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (progress.isResumed) "续传中…" else "下载更新中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            if (progress.totalBytes > 0) {
                Text(
                    text = "${(progress.percent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        if (progress.totalBytes > 0) {
            Text(
                text = "%.1f MB / %.1f MB".format(downloadedMb, totalMb),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.15f)),
        ) {
            LinearProgressIndicator(
                progress = { progress.percent },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.05f),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}
