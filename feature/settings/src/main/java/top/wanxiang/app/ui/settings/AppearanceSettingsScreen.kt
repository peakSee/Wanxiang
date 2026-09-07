package top.wanxiang.app.ui.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import top.wanxiang.app.ui.components.RuntimeRadioButton as RadioButton
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import top.wanxiang.app.ui.components.RuntimeSlider as Slider
import top.wanxiang.app.ui.components.RuntimeSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import top.wanxiang.app.feature.settings.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeTopBar

/**
 * 万象 · 外观、字号与终端深度定制页面 (Appearance & Terminal Settings)
 */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themeStyle by viewModel.themeStyle.collectAsStateWithLifecycle()
    val terminalFontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()
    val terminalColorScheme by viewModel.terminalColorScheme.collectAsStateWithLifecycle()
    val terminalHapticsEnabled by viewModel.terminalHapticsEnabled.collectAsStateWithLifecycle()
    val appFontScale by viewModel.appFontScale.collectAsStateWithLifecycle()
    val backgroundUri by viewModel.chengmingBackgroundUri.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LocalConfiguration.current // Recompose after AppCompat applies a locale change.
    val requestedLocales = AppCompatDelegate.getApplicationLocales()
    val appLanguage = if (requestedLocales.isEmpty) "system" else requestedLocales[0]?.language.orEmpty()
    var terminalFontSizeSlider by androidx.compose.runtime.saveable.rememberSaveable { mutableFloatStateOf(terminalFontSize.toFloat()) }
    var pageScaleSlider by androidx.compose.runtime.saveable.rememberSaveable { mutableFloatStateOf(appFontScale) }
    LaunchedEffect(terminalFontSize) {
        terminalFontSizeSlider = terminalFontSize.toFloat()
    }
    LaunchedEffect(appFontScale) {
        pageScaleSlider = appFontScale
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        viewModel.setChengmingBackgroundUri(uri.toString())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar(stringResource(R.string.settings_appearance_title), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. 终端实时效果预览卡片
            item {
                Text(
                    text = stringResource(R.string.settings_terminal_preview),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                TerminalPreviewCard(
                    colorScheme = terminalColorScheme,
                    fontSizeSp = terminalFontSizeSlider.roundToInt(),
                )
            }

            // 2. 终端外观与控制台偏好
            item {
                Text(
                    text = stringResource(R.string.settings_terminal_appearance),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    // 终端配色方案
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.settings_terminal_color_scheme),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TerminalThemeChip(
                                title = stringResource(R.string.settings_terminal_theme_obsidian),
                                bg = Color(0xFF0F1117),
                                text = Color(0xFFE2E2E9),
                                selected = terminalColorScheme == "obsidian",
                                onClick = { viewModel.setTerminalColorScheme("obsidian") },
                                modifier = Modifier.weight(1f),
                            )
                            TerminalThemeChip(
                                title = stringResource(R.string.settings_terminal_theme_matrix),
                                bg = Color(0xFF0A0F0D),
                                text = Color(0xFF10B981),
                                selected = terminalColorScheme == "matrix",
                                onClick = { viewModel.setTerminalColorScheme("matrix") },
                                modifier = Modifier.weight(1f),
                            )
                            TerminalThemeChip(
                                title = stringResource(R.string.settings_terminal_theme_amber),
                                bg = Color(0xFF140F0A),
                                text = Color(0xFFF59E0B),
                                selected = terminalColorScheme == "amber",
                                onClick = { viewModel.setTerminalColorScheme("amber") },
                                modifier = Modifier.weight(1f),
                            )
                            TerminalThemeChip(
                                title = stringResource(R.string.settings_terminal_theme_aurora),
                                bg = Color(0xFF0D1424),
                                text = Color(0xFF38BDF8),
                                selected = terminalColorScheme == "aurora",
                                onClick = { viewModel.setTerminalColorScheme("aurora") },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 终端字体大小
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_terminal_font_size),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${terminalFontSizeSlider.roundToInt()} sp",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = terminalFontSizeSlider,
                            onValueChange = { terminalFontSizeSlider = it.roundToInt().toFloat() },
                            onValueChangeFinished = {
                                viewModel.setTerminalFontSize(terminalFontSizeSlider.roundToInt())
                            },
                            valueRange = 10f..24f,
                            steps = 13,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "10 sp",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "24 sp",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 终端触觉震动反馈
                    ToggleRow(
                        icon = RuntimeIconName.Vibrate,
                        title = stringResource(R.string.settings_terminal_haptics),
                        subtitle = stringResource(R.string.settings_terminal_haptics_description),
                        checked = terminalHapticsEnabled,
                        change = viewModel::setTerminalHapticsEnabled,
                    )
                }
            }

            // 3. 应用视觉与主题
            item {
                Text(
                    text = stringResource(R.string.settings_theme_style),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    ThemeOptionRow(
                        title = stringResource(R.string.settings_theme_xuantong),
                        subtitle = stringResource(R.string.settings_theme_xuantong_description),
                        accentColor = androidx.compose.ui.graphics.Color(0xFF4259C3),
                        selected = themeStyle == "xuantong",
                        onClick = { viewModel.setThemeStyle("xuantong") },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ThemeOptionRow(
                        title = stringResource(R.string.settings_theme_chengming),
                        subtitle = stringResource(R.string.settings_theme_chengming_description),
                        accentColor = androidx.compose.ui.graphics.Color(0xFF6E7CE0),
                        selected = themeStyle == "chengming",
                        onClick = { viewModel.setThemeStyle("chengming") },
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.settings_chengming_background),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(if (backgroundUri == null) R.string.settings_background_none else R.string.settings_background_custom),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        )
                        backgroundUri?.let { uri -> ChengmingBackgroundPreview(uri) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { backgroundPicker.launch(arrayOf("image/*")) },
                            ) { Text(stringResource(if (backgroundUri == null) R.string.settings_background_upload else R.string.settings_background_change)) }
                            if (backgroundUri != null) {
                                TextButton(
                                    onClick = { viewModel.setChengmingBackgroundUri(null) },
                                ) { Text(stringResource(R.string.settings_background_remove)) }
                            }
                        }
                    }
                }
            }

            // 3.5 深浅色模式
            item {
                Text(
                    text = stringResource(R.string.settings_color_mode),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    ThemeOptionRow(
                        title = stringResource(R.string.settings_color_system),
                        subtitle = stringResource(R.string.settings_color_system_description),
                        selected = themeMode == "system",
                        onClick = { viewModel.setThemeMode("system") },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ThemeOptionRow(
                        title = stringResource(R.string.settings_color_light),
                        subtitle = stringResource(R.string.settings_color_light_description),
                        selected = themeMode == "light",
                        onClick = { viewModel.setThemeMode("light") },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ThemeOptionRow(
                        title = stringResource(R.string.settings_color_dark),
                        subtitle = stringResource(R.string.settings_color_dark_description),
                        selected = themeMode == "dark",
                        onClick = { viewModel.setThemeMode("dark") },
                    )
                }
            }

            // 4. 页面缩放
            item {
                Text(
                    text = stringResource(R.string.settings_language_title),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    ThemeOptionRow(
                        title = stringResource(R.string.settings_language_system),
                        subtitle = stringResource(R.string.settings_language_system_description),
                        selected = appLanguage == "system",
                        onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ThemeOptionRow(
                        title = stringResource(R.string.settings_language_chinese),
                        subtitle = stringResource(R.string.settings_language_chinese_description),
                        selected = appLanguage == "zh",
                        onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN")) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ThemeOptionRow(
                        title = stringResource(R.string.settings_language_english),
                        subtitle = stringResource(R.string.settings_language_english_description),
                        selected = appLanguage == "en",
                        onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) },
                    )
                }
            }

            // 5. 页面缩放
            item {
                Text(
                    text = stringResource(R.string.settings_interface_scale),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_scale),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${(pageScaleSlider * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = pageScaleSlider,
                            onValueChange = {
                                pageScaleSlider = ((it * 20f).roundToInt() / 20f).coerceIn(0.8f, 1.3f)
                            },
                            onValueChangeFinished = { viewModel.setAppFontScale(pageScaleSlider) },
                            valueRange = 0.8f..1.3f,
                            steps = 9,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "80%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "130%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalPreviewCard(
    colorScheme: String,
    fontSizeSp: Int,
) {
    val (bg, textColor, accentColor) = when (colorScheme) {
        "matrix" -> Triple(Color(0xFF0A0F0D), Color(0xFF10B981), Color(0xFF34D399))
        "amber" -> Triple(Color(0xFF140F0A), Color(0xFFF59E0B), Color(0xFFFBBF24))
        "aurora" -> Triple(Color(0xFF0D1424), Color(0xFF38BDF8), Color(0xFF818CF8))
        else -> Triple(Color(0xFF0F1117), Color(0xFFE2E2E9), Color(0xFF60A5FA))
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(1.dp, Color(0xFF282A36)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF5F56)))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFFBD2E)))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF27C93F)))
                }
                Text(
                    text = "Linux PRoot (pty0)",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                    color = textColor.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "wanxiang@debian:~$ uname -a",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSizeSp.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = accentColor,
            )
            Text(
                text = "Linux wanxiang 6.6.0-aarch64 #1 SMP PREEMPT GNU/Linux",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSizeSp.sp,
                ),
                color = textColor.copy(alpha = 0.9f),
            )
            Text(
                text = stringResource(R.string.settings_terminal_preview_command),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSizeSp.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = accentColor,
            )
            Text(
                text = stringResource(R.string.settings_terminal_preview_output),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSizeSp.sp,
                ),
                color = textColor,
            )
        }
    }
}

@Composable
private fun ChengmingBackgroundPreview(uri: String) {
    val context = LocalContext.current
    val bitmap = remember(uri) {
        runCatching {
            context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use(BitmapFactory::decodeStream)
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.settings_background_preview),
            modifier = Modifier
                .fillMaxWidth()
                .height(124.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun TerminalThemeChip(
    title: String,
    bg: Color,
    text: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = BorderStroke(
            1.5.dp,
            if (selected) MaterialTheme.colorScheme.primary else Color(0xFF282A36),
        ),
    ) {
        Column(
            Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(text),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    RuntimeIcon(RuntimeIconName.Check, Modifier.size(10.dp), bg)
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                color = text,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ThemeOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    accentColor: androidx.compose.ui.graphics.Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (accentColor != null) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(accentColor),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
        )
    }
}
