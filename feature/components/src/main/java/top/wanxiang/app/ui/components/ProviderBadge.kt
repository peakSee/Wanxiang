package top.wanxiang.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.wanxiang.app.feature.components.R

/**
 * 模型厂商徽章：优先显示真实 logo（单色矢量，白色绘制在品牌色圆底上，
 * 视觉统一且深浅色主题均可读）；无公开 logo 素材的厂商回退品牌色 + 标识字。
 */
data class ProviderVisual(
    /** 有真实 logo 时的 drawable 资源 id。 */
    val logoRes: Int?,
    /** 无 logo 时的徽章标识字符。 */
    val label: String,
    /** 品牌主色（圆底）。 */
    val color: Color,
)

/** provider id / 厂商名 → 视觉标识；未知厂商回退中性色 + 首字符，空值回退通用模型图标。 */
fun providerVisual(providerIdOrName: String): ProviderVisual {
    val trimmed = providerIdOrName.trim()
    if (trimmed.isEmpty()) return ProviderVisual(null, "", Color(0xFF64748B))
    val key = trimmed.lowercase()
    val known = PROVIDER_VISUALS.firstOrNull { (id, _) -> key == id || key.contains(id) }?.second
    if (known != null) return known
    val fallbackChar = trimmed.firstOrNull()?.uppercaseChar()?.toString() ?: ""
    return ProviderVisual(null, fallbackChar, Color(0xFF64748B))
}

private val PROVIDER_VISUALS: List<Pair<String, ProviderVisual>> = listOf(
    "openai" to ProviderVisual(R.drawable.components_ic_provider_openai, "AI", Color(0xFF10A37F)),
    "anthropic" to ProviderVisual(R.drawable.components_ic_provider_anthropic, "CL", Color(0xFFD97757)),
    "claude" to ProviderVisual(R.drawable.components_ic_provider_anthropic, "CL", Color(0xFFD97757)),
    "gemini" to ProviderVisual(R.drawable.components_ic_provider_gemini, "G", Color(0xFF4285F4)),
    "google" to ProviderVisual(R.drawable.components_ic_provider_gemini, "G", Color(0xFF4285F4)),
    "deepseek" to ProviderVisual(R.drawable.components_ic_provider_deepseek, "DS", Color(0xFF4D6BFE)),
    "qwen" to ProviderVisual(R.drawable.components_ic_provider_qwen, "Q", Color(0xFF615CED)),
    "阿里云" to ProviderVisual(R.drawable.components_ic_provider_qwen, "Q", Color(0xFF615CED)),
    "dashscope" to ProviderVisual(R.drawable.components_ic_provider_qwen, "Q", Color(0xFF615CED)),
    "moonshot" to ProviderVisual(R.drawable.components_ic_provider_kimi, "K", Color(0xFF16191E)),
    "kimi" to ProviderVisual(R.drawable.components_ic_provider_kimi, "K", Color(0xFF16191E)),
    "minimax" to ProviderVisual(R.drawable.components_ic_provider_minimax, "MM", Color(0xFFE9383C)),
    "openrouter" to ProviderVisual(R.drawable.components_ic_provider_openrouter, "OR", Color(0xFF444B5A)),
    "groq" to ProviderVisual(null, "GQ", Color(0xFFF55036)),
    "mistral" to ProviderVisual(R.drawable.components_ic_provider_mistral, "M", Color(0xFFFF7000)),
    "together" to ProviderVisual(null, "T", Color(0xFF0F6FFF)),
    "nvidia" to ProviderVisual(R.drawable.components_ic_provider_nvidia, "NV", Color(0xFF76B900)),
    "xai" to ProviderVisual(null, "X", Color(0xFF1A1A1A)),
    "grok" to ProviderVisual(null, "X", Color(0xFF1A1A1A)),
    "zhipu" to ProviderVisual(null, "GLM", Color(0xFF3859FF)),
    "智谱" to ProviderVisual(null, "GLM", Color(0xFF3859FF)),
    "doubao" to ProviderVisual(null, "豆", Color(0xFF00C8C8)),
    "火山" to ProviderVisual(null, "豆", Color(0xFF00C8C8)),
    "siliconflow" to ProviderVisual(null, "硅", Color(0xFF7C3AED)),
    "硅基" to ProviderVisual(null, "硅", Color(0xFF7C3AED)),
    "modelscope" to ProviderVisual(null, "魔", Color(0xFFE62E2E)),
    "魔搭" to ProviderVisual(null, "魔", Color(0xFFE62E2E)),
    "llamacpp" to ProviderVisual(null, "ll", Color(0xFF7B2CBF)),
    "llama" to ProviderVisual(null, "ll", Color(0xFF7B2CBF)),
    "ollama" to ProviderVisual(R.drawable.components_ic_provider_ollama, "ll", Color(0xFF334155)),
    "lmstudio" to ProviderVisual(R.drawable.components_ic_provider_lmstudio, "LM", Color(0xFF475569)),
    "vllm" to ProviderVisual(null, "v", Color(0xFF0891B2)),
    "custom" to ProviderVisual(null, "⌘", Color(0xFF64748B)),
    "自定义" to ProviderVisual(null, "⌘", Color(0xFF64748B)),
)

@Composable
fun ProviderBadge(
    providerIdOrName: String,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    val visual = providerVisual(providerIdOrName)
    Box(
        modifier = modifier
            .size(size)
            .background(visual.color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (visual.logoRes != null) {
            Image(
                painter = painterResource(visual.logoRes),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(size / 5),
                contentScale = ContentScale.Fit,
            )
        } else if (visual.label.isNotEmpty()) {
            val fontSize = when {
                visual.label.length >= 3 -> 9.sp
                visual.label.length == 2 -> 11.sp
                else -> 14.sp
            }
            Text(
                text = visual.label,
                color = Color.White,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
            )
        } else {
            // 空 provider：渲染通用模型图标，避免无意义的 "?" 占位
            RuntimeIcon(
                name = RuntimeIconName.Model,
                modifier = Modifier.size(size / 2),
                tint = Color.White,
            )
        }
    }
}
