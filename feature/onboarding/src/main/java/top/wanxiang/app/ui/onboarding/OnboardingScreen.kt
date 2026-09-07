package top.wanxiang.app.ui.onboarding

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wanxiang.app.feature.onboarding.R
import top.wanxiang.app.core.model.RuntimeState
import top.wanxiang.app.core.tools.AgentProviderDefinition
import top.wanxiang.app.runtime.DistributionCatalog
import top.wanxiang.app.ui.components.IconTile
import top.wanxiang.app.ui.components.ProviderBadge
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import top.wanxiang.app.ui.components.RuntimeButton
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator
import top.wanxiang.app.ui.components.RuntimeFilledTonalButton
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconButton
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeLinearProgressIndicator
import top.wanxiang.app.ui.components.RuntimeOutlinedButton
import top.wanxiang.app.ui.components.RuntimeTextButton
import top.wanxiang.app.ui.components.WanXiangBrandBadge

private data class SetupOption(val id: String, val label: String)

private val distributionOptions = DistributionCatalog.supported.map { SetupOption(it.id, it.displayWithVersion) }

/**
 * 万象 · 启程配置向导 (Onboarding Wizard)
 */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel()) {
    val page by viewModel.page.collectAsStateWithLifecycle()
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        when (page) {
            0 -> SystemSetupPage(viewModel, Modifier.fillMaxSize().padding(padding))
            else -> ModelSetupPage(viewModel, Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun SystemSetupPage(viewModel: OnboardingViewModel, modifier: Modifier) {
    val distribution by viewModel.distribution.collectAsStateWithLifecycle()
    val mirror by viewModel.mirror.collectAsStateWithLifecycle()
    val state by viewModel.runtimeState.collectAsStateWithLifecycle()
    val importing by viewModel.isImporting.collectAsStateWithLifecycle()
    val importError by viewModel.importError.collectAsStateWithLifecycle()
    val installing = state is RuntimeState.Initializing || importing
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importArchive) }
    val mirrorOptions = listOf(
        SetupOption("auto", stringResource(R.string.onboarding_mirror_auto)),
        SetupOption("official", stringResource(R.string.onboarding_mirror_official)),
        SetupOption("china", stringResource(R.string.onboarding_mirror_china)),
    )

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Intro(
                title = stringResource(R.string.onboarding_title),
                description = stringResource(R.string.onboarding_description),
            )
        }
        item {
            SetupDropdown(
                label = stringResource(R.string.onboarding_distribution),
                selectedId = distribution,
                options = distributionOptions,
                enabled = !installing,
                onSelected = viewModel::selectDistribution,
            )
        }
        item {
            SetupDropdown(
                label = stringResource(R.string.onboarding_mirror),
                selectedId = mirror,
                options = mirrorOptions,
                enabled = !installing,
                onSelected = viewModel::selectMirror,
            )
        }
        if (state is RuntimeState.Initializing) {
            val progress = state as RuntimeState.Initializing
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = progress.step,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${(progress.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        RuntimeLinearProgressIndicator(
                            progress = { progress.progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        progress.detail?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        (state as? RuntimeState.Error)?.let { error ->
            item {
                Text(
                    error.throwable.message ?: stringResource(R.string.onboarding_initialization_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            RuntimeButton(
                onClick = viewModel::install,
                enabled = !installing,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    stringResource(
                        if (state is RuntimeState.Error) R.string.onboarding_retry
                        else if (installing) R.string.onboarding_preparing
                        else R.string.onboarding_initialize,
                    ),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        importError?.let { error ->
            item {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            RuntimeTextButton(
                onClick = { importLauncher.launch(arrayOf("application/gzip", "application/x-gzip", "application/x-tar", "application/x-xz", "application/octet-stream")) },
                enabled = !installing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_import))
            }
        }
        item {
            Text(
                stringResource(R.string.onboarding_import_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state is RuntimeState.Error) {
            item {
                RuntimeTextButton(
                    onClick = viewModel::retryReady,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.onboarding_recheck), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelSetupPage(viewModel: OnboardingViewModel, modifier: Modifier) {
    val provider by viewModel.modelProvider.collectAsStateWithLifecycle()
    val model by viewModel.modelId.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val discovered by viewModel.discoveredModels.collectAsStateWithLifecycle()
    val discovering by viewModel.discoveringModels.collectAsStateWithLifecycle()
    val discoveryError by viewModel.modelDiscoveryError.collectAsStateWithLifecycle()
    val providers = viewModel.providerCatalog
    val selectedProvider = providers.firstOrNull { it.name == provider } ?: providers.first()

    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var keyRevealed by remember { mutableStateOf(false) }
    var providerMenuExpanded by remember { mutableStateOf(false) }

    val candidateModels = remember(discovered, selectedProvider) {
        (discovered + selectedProvider.recommendedModels).distinct().filter { it.isNotBlank() }
    }

    val compactFieldShape = RoundedCornerShape(10.dp)
    val compactFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 顶部 Hero 与快速导入入口
        item {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.size(36.dp))
                    ProviderBadge(providerIdOrName = selectedProvider.id, size = 48.dp)
                    RuntimeIconButton(
                        onClick = { showImportDialog = true },
                        modifier = Modifier.size(36.dp),
                        contentDescription = "从 JSON 导入配置",
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Download,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.onboarding_connect_model),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = stringResource(R.string.onboarding_connect_model_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 1. 服务商 Hero 预设卡片
        item {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProviderBadge(providerIdOrName = selectedProvider.id, size = 32.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "服务商预设",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = selectedProvider.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Box {
                        RuntimeFilledTonalButton(
                            onClick = { providerMenuExpanded = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("切换", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                                RuntimeIcon(RuntimeIconName.ChevronDown, Modifier.size(14.dp))
                            }
                        }

                        DropdownMenu(
                            expanded = providerMenuExpanded,
                            onDismissRequest = { providerMenuExpanded = false },
                        ) {
                            providers.forEach { option ->
                                DropdownMenuItem(
                                    leadingIcon = {
                                        ProviderBadge(providerIdOrName = option.id, size = 20.dp)
                                    },
                                    text = {
                                        Text(
                                            option.name,
                                            fontWeight = if (option.id == selectedProvider.id) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        viewModel.selectProvider(option.id)
                                        providerMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. 接口 Base URL
        item {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "接口地址 (Base URL)",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = viewModel::setBaseUrl,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://api.openai.com/v1", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        shape = compactFieldShape,
                        colors = compactFieldColors,
                        singleLine = true,
                        trailingIcon = {
                            RuntimeIconButton(
                                onClick = viewModel::discoverModels,
                                enabled = !discovering && baseUrl.isNotBlank(),
                                modifier = Modifier.size(32.dp),
                            ) {
                                if (discovering) {
                                    RuntimeCircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        },
                    )
                }
            }
        }

        // 3. API Key
        item {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "API Key 凭据",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (apiKey.isNotBlank()) {
                            Text(
                                text = "已填写",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = viewModel::setApiKey,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("sk-...", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        shape = compactFieldShape,
                        colors = compactFieldColors,
                        visualTransformation = if (keyRevealed) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        trailingIcon = {
                            if (apiKey.isNotEmpty()) {
                                RuntimeIconButton(
                                    onClick = { keyRevealed = !keyRevealed },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    RuntimeIcon(
                                        name = if (keyRevealed) RuntimeIconName.Brain else RuntimeIconName.Key,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }

        // 4. 模型选择与候选 FlowRow Chip
        item {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "选择模型 (Model ID)",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    OutlinedTextField(
                        value = model,
                        onValueChange = viewModel::setModelId,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如：gpt-4o / deepseek-chat", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        shape = compactFieldShape,
                        colors = compactFieldColors,
                        singleLine = true,
                    )

                    // 拉取模型列表：从 Base URL 的 /models 端点获取可用模型，供候选标签点选
                    RuntimeOutlinedButton(
                        onClick = viewModel::discoverModels,
                        enabled = !discovering && baseUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(38.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (discovering) {
                                RuntimeCircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text("正在拉取模型列表…", style = MaterialTheme.typography.labelMedium)
                            } else {
                                RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(14.dp), MaterialTheme.colorScheme.primary)
                                Text("拉取模型列表", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    discoveryError?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (candidateModels.isNotEmpty()) {
                        Text(
                            text = "候选模型标签（点击一键填入）：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            candidateModels.forEach { candidate ->
                                val isSelected = model == candidate
                                Surface(
                                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    ),
                                    modifier = Modifier.clickable { viewModel.setModelId(candidate) },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (isSelected) {
                                            RuntimeIcon(
                                                name = RuntimeIconName.Check,
                                                modifier = Modifier.size(13.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        Text(
                                            text = candidate,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            ),
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 错误提示
        discoveryError?.let { err ->
            item {
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 底部操作栏
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeButton(
                    onClick = viewModel::saveModelAndFinish,
                    enabled = model.isNotBlank() && baseUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        stringResource(R.string.onboarding_enter),
                        fontWeight = FontWeight.Bold,
                    )
                }

                RuntimeTextButton(
                    onClick = viewModel::skipModel,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                ) {
                    Text(
                        stringResource(R.string.onboarding_later),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // JSON 导入对话框
    if (showImportDialog) {
        OnboardingModelImportDialog(
            onDismiss = { showImportDialog = false },
            onImportJson = { jsonStr ->
                val result = viewModel.importProfileFromJson(jsonStr)
                result.fold(
                    onSuccess = { profile ->
                        Toast.makeText(context, "成功载入模型配置「${profile.name.ifBlank { profile.model }}」", Toast.LENGTH_SHORT).show()
                        showImportDialog = false
                    },
                    onFailure = { err ->
                        Toast.makeText(context, "解析失败: ${err.message}", Toast.LENGTH_LONG).show()
                    },
                )
            },
        )
    }
}

@Composable
private fun Intro(title: String, description: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WanXiangBrandBadge(52.dp)
        Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupDropdown(
    label: String,
    selectedId: String,
    options: List<SetupOption>,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId } ?: options.first()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            shape = RoundedCornerShape(10.dp),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(
                ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                enabled,
            ),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelected(option.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * 引导页专属 JSON 模型导入弹窗
 */
@Composable
private fun OnboardingModelImportDialog(
    onDismiss: () -> Unit,
    onImportJson: (String) -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var jsonText by remember { mutableStateOf("") }
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                }
            }.onSuccess { fileContent ->
                if (!fileContent.isNullOrBlank()) {
                    jsonText = fileContent
                    selectedTab = 0
                    Toast.makeText(context, "已载入文件内容", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(context, "读取文件失败: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Download, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                Text("导入模型配置", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("剪贴板粘贴") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            filePickerLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        text = { Text("选择本地文件") },
                    )
                }

                if (selectedTab == 0) {
                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = { jsonText = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        placeholder = { Text("粘贴导出的 JSON 模型配置（单个或 bundle 均支持）") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        shape = RoundedCornerShape(10.dp),
                    )

                    RuntimeOutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                            if (clipText.isNotBlank()) {
                                jsonText = clipText
                            } else {
                                Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("一键粘贴剪贴板内容")
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RuntimeOutlinedButton(
                            onClick = {
                                filePickerLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                RuntimeIcon(RuntimeIconName.File, Modifier.size(16.dp))
                                Text("选择本地 JSON 文件")
                            }
                        }
                        if (jsonText.isNotBlank()) {
                            Text(
                                text = "已载入 ${jsonText.length} 字符",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            RuntimeButton(
                onClick = { onImportJson(jsonText) },
                enabled = jsonText.isNotBlank(),
            ) {
                Text("载入配置")
            }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
