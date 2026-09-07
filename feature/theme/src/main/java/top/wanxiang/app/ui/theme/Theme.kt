package top.wanxiang.app.ui.theme

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.wanxiang.app.feature.theme.R

/**
 * 万象内置主题风格。
 *
 * - [XUANTONG]（玄同）—— 默认主题。源自《老子》「万物同归于玄」，曜石夜空与温润素白的 M3 Expressive 设计系统。
 * - [CHENGMING]（澄明）—— 液态玻璃主题。源自「澄明」通透清澈之意，以毛玻璃折射 + 流光 Aurora 渲染虚实交织的界面。
 */
enum class ThemeStyle(
    val id: String,
    val displayName: String,
    val displayNameEn: String,
    val description: String,
) {
    XUANTONG(
        id = "xuantong",
        displayName = "玄同",
        displayNameEn = "Xuantong",
        description = "默认主题 · 源自《老子》「万物同归于玄」。曜石夜空与温润素白，M3 Expressive 设计系统。",
    ),
    CHENGMING(
        id = "chengming",
        displayName = "澄明",
        displayNameEn = "Chengming",
        description = "液态玻璃主题 · 毛玻璃折射 + 流光 Aurora，源自「澄明」通透清澈之意。",
    ),
    ;

    companion object {
        fun fromId(id: String?): ThemeStyle = entries.firstOrNull { it.id == id } ?: XUANTONG
    }
}

// ======================= 「玄同 · Xuantong」= 默认 M3 Expressive 配色 =======================

private val XuantongLightColors = lightColorScheme(
    primary = M3ExpLightPrimary,
    onPrimary = M3ExpLightOnPrimary,
    primaryContainer = M3ExpLightPrimaryContainer,
    onPrimaryContainer = M3ExpLightOnPrimaryContainer,
    secondary = M3ExpLightSecondary,
    onSecondary = M3ExpLightOnSecondary,
    secondaryContainer = M3ExpLightSecondaryContainer,
    onSecondaryContainer = M3ExpLightOnSecondaryContainer,
    tertiary = M3ExpLightTertiary,
    onTertiary = M3ExpLightOnTertiary,
    tertiaryContainer = M3ExpLightTertiaryContainer,
    onTertiaryContainer = M3ExpLightOnTertiaryContainer,
    error = M3ExpLightError,
    onError = Color.White,
    errorContainer = M3ExpLightErrorContainer,
    onErrorContainer = Color(0xFF410002),
    background = M3ExpLightBackground,
    onBackground = M3ExpLightOnBackground,
    surface = M3ExpLightSurface,
    onSurface = M3ExpLightOnSurface,
    surfaceVariant = M3ExpLightSurfaceVariant,
    onSurfaceVariant = M3ExpLightOnSurfaceVariant,
    surfaceContainerLowest = M3ExpLightSurfaceContainerLowest,
    surfaceContainerLow = M3ExpLightSurfaceContainerLow,
    surfaceContainer = M3ExpLightSurfaceContainer,
    surfaceContainerHigh = M3ExpLightSurfaceContainerHigh,
    surfaceContainerHighest = M3ExpLightSurfaceContainerHighest,
    outline = M3ExpLightOutline,
    outlineVariant = M3ExpLightOutlineVariant,
)

private val XuantongDarkColors = darkColorScheme(
    primary = M3ExpDarkPrimary,
    onPrimary = M3ExpDarkOnPrimary,
    primaryContainer = M3ExpDarkPrimaryContainer,
    onPrimaryContainer = M3ExpDarkOnPrimaryContainer,
    secondary = M3ExpDarkSecondary,
    onSecondary = M3ExpDarkOnSecondary,
    secondaryContainer = M3ExpDarkSecondaryContainer,
    onSecondaryContainer = M3ExpDarkOnSecondaryContainer,
    tertiary = M3ExpDarkTertiary,
    onTertiary = M3ExpDarkOnTertiary,
    tertiaryContainer = M3ExpDarkTertiaryContainer,
    onTertiaryContainer = M3ExpDarkOnTertiaryContainer,
    error = M3ExpDarkError,
    onError = Color(0xFF690005),
    errorContainer = M3ExpDarkErrorContainer,
    onErrorContainer = Color(0xFFFFDAD6),
    background = M3ExpDarkBackground,
    onBackground = M3ExpDarkOnBackground,
    surface = M3ExpDarkSurface,
    onSurface = M3ExpDarkOnSurface,
    surfaceVariant = M3ExpDarkSurfaceVariant,
    onSurfaceVariant = M3ExpDarkOnSurfaceVariant,
    surfaceContainerLowest = M3ExpDarkSurfaceContainerLowest,
    surfaceContainerLow = M3ExpDarkSurfaceContainerLow,
    surfaceContainer = M3ExpDarkSurfaceContainer,
    surfaceContainerHigh = M3ExpDarkSurfaceContainerHigh,
    surfaceContainerHighest = M3ExpDarkSurfaceContainerHighest,
    outline = M3ExpDarkOutline,
    outlineVariant = M3ExpDarkOutlineVariant,
)

// ======================= 「澄明 · Chengming」液态玻璃配色 =======================

private val ChengmingLightColors = lightColorScheme(
    primary = ChengmingLightPrimary,
    onPrimary = ChengmingLightOnPrimary,
    primaryContainer = ChengmingLightPrimaryContainer,
    onPrimaryContainer = ChengmingLightOnPrimaryContainer,
    secondary = ChengmingLightSecondary,
    onSecondary = ChengmingLightOnSecondary,
    secondaryContainer = ChengmingLightSecondaryContainer,
    onSecondaryContainer = ChengmingLightOnSecondaryContainer,
    tertiary = ChengmingLightTertiary,
    onTertiary = ChengmingLightOnTertiary,
    tertiaryContainer = ChengmingLightTertiaryContainer,
    onTertiaryContainer = ChengmingLightOnTertiaryContainer,
    error = ChengmingLightError,
    onError = ChengmingLightOnError,
    errorContainer = ChengmingLightErrorContainer,
    onErrorContainer = ChengmingLightOnErrorContainer,
    background = ChengmingLightBackground,
    onBackground = ChengmingLightOnBackground,
    surface = ChengmingLightSurface,
    onSurface = ChengmingLightOnSurface,
    surfaceVariant = ChengmingLightSurfaceVariant,
    onSurfaceVariant = ChengmingLightOnSurfaceVariant,
    surfaceContainerLowest = ChengmingLightSurfaceContainerLowest,
    surfaceContainerLow = ChengmingLightSurfaceContainerLow,
    surfaceContainer = ChengmingLightSurfaceContainer,
    surfaceContainerHigh = ChengmingLightSurfaceContainerHigh,
    surfaceContainerHighest = ChengmingLightSurfaceContainerHighest,
    outline = ChengmingLightOutline,
    outlineVariant = ChengmingLightOutlineVariant,
)

private val ChengmingDarkColors = darkColorScheme(
    primary = ChengmingDarkPrimary,
    onPrimary = ChengmingDarkOnPrimary,
    primaryContainer = ChengmingDarkPrimaryContainer,
    onPrimaryContainer = ChengmingDarkOnPrimaryContainer,
    secondary = ChengmingDarkSecondary,
    onSecondary = ChengmingDarkOnSecondary,
    secondaryContainer = ChengmingDarkSecondaryContainer,
    onSecondaryContainer = ChengmingDarkOnSecondaryContainer,
    tertiary = ChengmingDarkTertiary,
    onTertiary = ChengmingDarkOnTertiary,
    tertiaryContainer = ChengmingDarkTertiaryContainer,
    onTertiaryContainer = ChengmingDarkOnTertiaryContainer,
    error = ChengmingDarkError,
    onError = ChengmingDarkOnError,
    errorContainer = ChengmingDarkErrorContainer,
    onErrorContainer = ChengmingDarkOnErrorContainer,
    background = ChengmingDarkBackground,
    onBackground = ChengmingDarkOnBackground,
    surface = ChengmingDarkSurface,
    onSurface = ChengmingDarkOnSurface,
    surfaceVariant = ChengmingDarkSurfaceVariant,
    onSurfaceVariant = ChengmingDarkOnSurfaceVariant,
    surfaceContainerLowest = ChengmingDarkSurfaceContainerLowest,
    surfaceContainerLow = ChengmingDarkSurfaceContainerLow,
    surfaceContainer = ChengmingDarkSurfaceContainer,
    surfaceContainerHigh = ChengmingDarkSurfaceContainerHigh,
    surfaceContainerHighest = ChengmingDarkSurfaceContainerHighest,
    outline = ChengmingDarkOutline,
    outlineVariant = ChengmingDarkOutlineVariant,
)

/**
 * Material 3 Expressive 形状体系 (Generous, Organic, Bolder)
 */
private val WanXiangShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun WanXiangTheme(
    style: ThemeStyle = ThemeStyle.XUANTONG,
    darkTheme: Boolean = isSystemInDarkTheme(),
    backgroundUri: String? = null,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.isNavigationBarContrastEnforced = false
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    val colorScheme = when (style) {
        ThemeStyle.XUANTONG -> if (darkTheme) XuantongDarkColors else XuantongLightColors
        ThemeStyle.CHENGMING -> if (darkTheme) ChengmingDarkColors else ChengmingLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = WanXiangShapes,
    ) {
        if (style == ThemeStyle.CHENGMING) {
            LiquidGlassRoot(content, darkTheme, backgroundUri)
        } else {
            content()
        }
    }
}

// ======================= 澄明 · 液态玻璃根容器 =======================

/**
 * 当前液态玻璃折射源（[LayerBackdrop]），仅在澄明主题存在（否则为 null）。
 * 底部导航等"液态玻璃"组件可通过 [LocalLiquidGlassBackdrop] 读取并叠加毛玻璃折射。
 */
val LocalLiquidGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 澄明组件使用的稳定背景折射源。
 *
 * 页面内容会记录到 [LocalLiquidGlassBackdrop] 供底部导航折射；卡片、按钮等页面内部组件
 * 不能反过来读取同一记录层，否则部分设备的 RenderThread 会形成递归合成。这里单独记录
 * Aurora 根背景，让任意层级的玻璃组件都能安全取样。
 */
val LocalLiquidGlassSurfaceBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 液态玻璃根容器（核心面液态玻璃）：
 * 底层绘制缓慢漂移的流光 Aurora，并通过 [layerBackdrop] 捕获为折射源；
 * 应用内容绘制在其上，半透明配色透出流光。底部导航等玻璃组件读取
 * [LocalLiquidGlassBackdrop]，用 [com.kyant.backdrop.drawBackdrop] 叠加毛玻璃折射，
 * 呈现悬浮磨砂玻璃效果（见 RuntimeBottomBar）。
 *
 * 在 RenderEffect 不可用的设备上，折射会自动退化为"半透明冰霜 + 流光底色"，不崩溃。
 */
@Composable
private fun LiquidGlassRoot(content: @Composable () -> Unit, darkTheme: Boolean, backgroundUri: String?) {
    val pageBackdrop = rememberLayerBackdrop()
    val surfaceBackdrop = rememberLayerBackdrop()
    CompositionLocalProvider(
        LocalLiquidGlassBackdrop provides pageBackdrop,
        LocalLiquidGlassSurfaceBackdrop provides surfaceBackdrop,
    ) {
        // Keep the source layer separate from consumers of the backdrop. Capturing a
        // parent that also contains drawBackdrop surfaces can recurse inside RenderThread.
        Box(modifier = Modifier.fillMaxSize()) {
            ChengmingBackdrop(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(surfaceBackdrop),
                darkTheme = darkTheme,
                backgroundUri = backgroundUri,
            )
            content()
        }
    }
}

/**
 * 澄明的真实取样背景。与参考 Glass 工程一样使用高信息量壁纸作为折射源，
 * 而不是低对比度纯渐变；玻璃边缘因此能呈现清晰的位移、亮度与色彩变化。
 */
@Composable
private fun ChengmingBackdrop(modifier: Modifier, darkTheme: Boolean, backgroundUri: String?) {
    val context = LocalContext.current
    val painter by produceState<BitmapPainter?>(
        initialValue = null,
        key1 = context,
        key2 = backgroundUri,
    ) {
        value = withContext(Dispatchers.IO) {
            backgroundUri?.takeIf { it.isNotBlank() }?.let { savedUri ->
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(savedUri))?.use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)?.asImageBitmap()?.let(::BitmapPainter)
                    }
                }.getOrNull()
            }
        }
    }
    Box(modifier) {
        if (painter != null) {
            Image(
                painter = painter!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (darkTheme) Color(0xFF111318) else Color(0xFFF4F5F7)),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (painter == null) {
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                    } else if (darkTheme) {
                        Brush.verticalGradient(
                            listOf(Color(0xB80A1020), Color(0x8F07142A), Color(0xC9040914)),
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.12f), Color.Transparent, Color(0xFFECF6FF).copy(alpha = 0.18f)),
                        )
                    },
                ),
        )
    }
}
