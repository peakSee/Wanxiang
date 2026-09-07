package top.wanxiang.app.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import top.wanxiang.app.core.model.ToolImageRef
import top.wanxiang.app.ui.components.RuntimeCard

/**
 * 在 chat 中展示某次 mcp__browser__* 调用的产物（最常见：截图）。
 * 提供给 `feature/chat/.../ChatToolCards` 复用。
 */
@Composable
fun BrowserActionCard(
    title: String,
    subtitle: String,
    imageRef: ToolImageRef?,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val model = remember(imageRef) {
        imageRef?.let { ImageRequest.Builder(ctx).data(it.uri).build() }
    }
    RuntimeCard(modifier = modifier.padding(vertical = 4.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = imageRef?.caption,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
            }
        }
    }
}

