package top.wanxiang.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 万象高拟真毛玻璃面板 (WanXiang Glass Surface)
 *
 * 1. 消除暗色模式下死板的粗描边，将整体边框透明度弱化至 0.08f（避免 PPT 描边感）；
 * 2. 顶部独占 1px 镜面受光渐变高光 (Specular Highlight)，模拟真实光打在玻璃顶部的折射；
 * 3. 支持胶囊拼接 (omitTopBorder)，当面板紧贴上方组件时省略顶边与高光，杜绝接缝处双重亮线。
 */
@Composable
fun WanXiangGlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    forceDark: Boolean = false,
    omitTopBorder: Boolean = false,
    showTopHighlight: Boolean = true,
    surfaceColor: Color? = null,
    borderWidth: Dp = 1.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val isDark = forceDark || isSystemInDarkTheme()

    val topTint = if (isDark) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
    } else {
        Color.White.copy(alpha = 0.75f)
    }
    val bottomTint = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.25f)
    } else {
        Color.White.copy(alpha = 0.50f)
    }

    val borderColor = if (isDark) {
        Color.White.copy(alpha = 0.08f)
    } else {
        Color.White.copy(alpha = 0.85f)
    }

    val backgroundBrush = if (surfaceColor != null) {
        Brush.verticalGradient(listOf(surfaceColor, surfaceColor))
    } else {
        Brush.verticalGradient(listOf(topTint, bottomTint))
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundBrush, shape)
            .then(
                if (!omitTopBorder) {
                    Modifier.border(borderWidth, borderColor, shape)
                } else {
                    Modifier
                }
            ),
    ) {
        content()

        // 顶部 1px 镜面高光反射线
        if (showTopHighlight && !omitTopBorder) {
            val highlightBrush = Brush.horizontalGradient(
                listOf(
                    Color.White.copy(alpha = 0.05f),
                    Color.White.copy(alpha = if (isDark) 0.35f else 0.80f),
                    Color.White.copy(alpha = 0.05f),
                )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.TopCenter)
                    .background(highlightBrush),
            )
        }
    }
}
