package top.wanxiang.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 主题感知的状态色（参考 feature/home HomeScreen 的 healthyStatusColor 模式）：
 * 深色主题使用亮色变体，浅色主题使用深色变体，避免硬编码状态色在浅色主题下刺眼或不可读。
 */
@Composable
internal fun successStatusColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF2E7D32)

@Composable
internal fun warningStatusColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFFFB74D) else Color(0xFFE65100)
