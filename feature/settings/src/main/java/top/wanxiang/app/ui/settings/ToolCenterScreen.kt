package top.wanxiang.app.ui.settings

import top.wanxiang.app.ui.components.RuntimeAlertDialog

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.wanxiang.app.ui.components.SpotlightGuideOverlay
import top.wanxiang.app.ui.components.rememberSpotlightAnchor
import top.wanxiang.app.ui.components.spotlightAnchor
import top.wanxiang.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import top.wanxiang.app.ui.components.RuntimeFilledTonalButton as FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import top.wanxiang.app.ui.components.RuntimeIconButton as IconButton
import top.wanxiang.app.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator
import top.wanxiang.app.ui.components.RuntimeCheckbox
import androidx.compose.material3.MaterialTheme
import top.wanxiang.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import top.wanxiang.app.ui.settings.LocalizedText as Text
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import top.wanxiang.app.core.database.ToolEntity
import top.wanxiang.app.core.model.ToolState
import top.wanxiang.app.core.tools.ToolInstallProgress
import top.wanxiang.app.core.tools.ToolVerification
import top.wanxiang.app.ui.components.NoticeBanner
import top.wanxiang.app.ui.components.InfoRow
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeTopBar
import top.wanxiang.app.ui.components.StatusBadge

/**
 * 万象 · 插件与 AI 工具中心 (Tool & Plugin Center)
 */
@Composable
fun ToolCenterScreen(
    onBack: () -> Unit,
    onLaunchPty: (toolId: String) -> Unit,
    onOpenToolDetail: (toolId: String) -> Unit = {},
    onStartAiHealing: (toolId: String, toolName: String, errorLogs: List<String>) -> Unit = { _, _, _ -> },
    viewModel: ToolCenterViewModel = hiltViewModel(),
) {
    val tools by viewModel.tools.collectAsStateWithLifecycle()
    val installProgress by viewModel.installProgress.collectAsStateWithLifecycle()
    val verifications by viewModel.verifications.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val viewingLogsToolId by viewModel.viewingLogsToolId.collectAsStateWithLifecycle()
    val toolLogs by viewModel.toolLogs.collectAsStateWithLifecycle()

    val installedComponentIds by viewModel.installedComponentIds.collectAsStateWithLifecycle()
    val activeBundle by viewModel.activeBundle.collectAsStateWithLifecycle()
    val selectedComponents by viewModel.selectedComponents.collectAsStateWithLifecycle()
    val isInstallingComponents by viewModel.isInstallingComponents.collectAsStateWithLifecycle()
    val componentInstallProgress by viewModel.componentInstallProgress.collectAsStateWithLifecycle()
    val componentInstallLog by viewModel.componentInstallLog.collectAsStateWithLifecycle()
    val localPluginImport by viewModel.localPluginImport.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val operationError by viewModel.operationError.collectAsStateWithLifecycle()
    var showBundleInstallLog by remember { mutableStateOf(false) }
    var pendingUninstallId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUninstallName by rememberSaveable { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importLocalPlugin)
    }

    // 首次进入引导：高亮顶栏「导入离线插件包」文件夹按钮
    val importGuideShown by viewModel.importGuideShown.collectAsStateWithLifecycle()
    val importAnchor = rememberSpotlightAnchor()

    val categories = listOf(
        "ALL" to "全部生态",
        "BUNDLES" to "全栈开发套件",
        "SOURCE_LOCAL" to "本地插件",
        "SOURCE_REMOTE" to "在线插件",
        "CODING_AGENT" to "编程助手",
        "AI_AGENT" to "智能体生态",
    )

    val showBundles = selectedCategory == "ALL" || selectedCategory == "BUNDLES"
    val showTools = selectedCategory != "BUNDLES"

    val filteredTools = tools.filter { tool ->
        when (selectedCategory) {
            "ALL" -> true
            "SOURCE_LOCAL" -> viewModel.toolSource(tool.id) == "LOCAL"
            "SOURCE_REMOTE" -> viewModel.toolSource(tool.id) != "LOCAL"
            else -> tool.category.equals(selectedCategory, ignoreCase = true)
        }
    }

    // 遮罩与 Scaffold 放在同一 Box 下（同层兄弟节点），保证遮罩坐标与按钮 boundsInRoot 同一参照系
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = "插件与工具中心",
                statusText = "已集成 ${tools.count { it.state == ToolState.INSTALLED.name }} 个已就绪工具",
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        modifier = Modifier.spotlightAnchor(importAnchor),
                        contentDescription = "导入离线插件包",
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.FolderOpen,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            viewModel.syncRegistry()
                            viewModel.refreshInstalledStatus()
                        },
                        enabled = !isSyncing,
                        contentDescription = if (isSyncing) "正在刷新插件目录" else "刷新插件目录",
                    ) {
                        if (isSyncing) {
                            RuntimeCircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            RuntimeIcon(
                                name = RuntimeIconName.Refresh,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Category Filter Bar
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(categories) { (catId, label) ->
                    val isSelected = selectedCategory == catId
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setCategory(catId) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (operationError != null) {
                    item(key = "tool_center_operation_error") {
                        NoticeBanner(
                            text = operationError.orEmpty(),
                            isError = true,
                            onDismiss = viewModel::consumeOperationError,
                        )
                    }
                }

                if (localPluginImport !is top.wanxiang.app.core.tools.LocalPluginImportState.Idle) {
                    item {
                        val state = localPluginImport
                        val isImporting = state is top.wanxiang.app.core.tools.LocalPluginImportState.Reading ||
                            state is top.wanxiang.app.core.tools.LocalPluginImportState.Importing
                        val isError = state is top.wanxiang.app.core.tools.LocalPluginImportState.Failed
                        val isPending = state is top.wanxiang.app.core.tools.LocalPluginImportState.PendingConfirmation
                        val containerColor = when {
                            isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                            isImporting -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                        }
                        val contentColor = when {
                            isError -> MaterialTheme.colorScheme.onErrorContainer
                            isImporting -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onTertiaryContainer
                        }
                        RuntimeCard(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = containerColor,
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (isImporting) {
                                        RuntimeCircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = contentColor,
                                        )
                                    } else {
                                        RuntimeIcon(
                                            name = if (isError) RuntimeIconName.Alert else RuntimeIconName.Check,
                                            modifier = Modifier.size(20.dp),
                                            tint = contentColor,
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = when (state) {
                                                is top.wanxiang.app.core.tools.LocalPluginImportState.Reading -> "正在读取本地插件信息"
                                                is top.wanxiang.app.core.tools.LocalPluginImportState.PendingConfirmation -> "等待确认安装本地插件"
                                                is top.wanxiang.app.core.tools.LocalPluginImportState.Importing -> "正在安装本地插件"
                                                is top.wanxiang.app.core.tools.LocalPluginImportState.Succeeded -> "本地插件安装完成"
                                                is top.wanxiang.app.core.tools.LocalPluginImportState.AlreadyImported -> "插件已导入"
                                                is top.wanxiang.app.core.tools.LocalPluginImportState.Failed -> "本地插件安装失败"
                                                top.wanxiang.app.core.tools.LocalPluginImportState.Idle -> ""
                                            },
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = contentColor,
                                        )
                                        Text(
                                            text = when (state) {
                                                is top.wanxiang.app.core.tools.LocalPluginImportState.Reading -> "${state.fileName}：正在读取 manifest.json，尚未复制或解压插件资源"
                                                is top.wanxiang.app.core.tools.LocalPluginImportState.PendingConfirmation -> "${state.fileName} 已读取清单，请在弹窗中确认安装"
                                                is top.wanxiang.app.core.tools.LocalPluginImportState.Importing -> {
                                                    val percent = state.progress?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                                                    "${state.fileName}$percent · ${state.currentEntry ?: "正在解包插件资源"}，大型 ARM64 插件可能需要几分钟，请保持应用打开"
                                                }
                                                is top.wanxiang.app.core.tools.LocalPluginImportState.Succeeded -> "${state.pluginName} v${state.version} 已解压、安装并完成验证"
                                                is top.wanxiang.app.core.tools.LocalPluginImportState.AlreadyImported -> "${state.pluginName} v${state.version} 已存在，无需重复导入；可在下方插件列表中安装或重试"
                                                is top.wanxiang.app.core.tools.LocalPluginImportState.Failed -> state.message
                                                top.wanxiang.app.core.tools.LocalPluginImportState.Idle -> ""
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = contentColor,
                                            maxLines = if (isError) 5 else 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (!isImporting && !isPending) {
                                        TextButton(onClick = viewModel::clearLocalPluginImportState) {
                                            Text("关闭", color = contentColor)
                                        }
                                    }
                                }
                                if (isImporting && state is top.wanxiang.app.core.tools.LocalPluginImportState.Importing) {
                                    LinearProgressIndicator(
                                        progress = { state.progress ?: 0.03f },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = contentColor,
                                    )
                                }
                                if (state is top.wanxiang.app.core.tools.LocalPluginImportState.Reading) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = contentColor,
                                    )
                                }
                            }
                        }
                    }
                }

                // 🚀 后台装配进行中提示卡片 (Background Installing Banner)
                if (isInstallingComponents || componentInstallLog.isNotEmpty()) {
                    item {
                        RuntimeCard(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    RuntimeCircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isInstallingComponents) "后台正在装配开发套件..." else "最近一次开发套件装配",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = componentInstallProgress ?: if (isInstallingComponents) "正在执行后台批量装配流水线，你可自由切换到其他页面" else "装配任务已结束，可查看完整日志",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                        )
                                    }
                                }
                                if (isInstallingComponents) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                TextButton(
                                    onClick = { showBundleInstallLog = true },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                ) {
                                    Text("查看安装日志", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }

                // 1. 聚合大套件专区 (Plugin Bundles)
                if (showBundles) {
                    item {
                        Text(
                            text = "开发者全栈套件 (Dev Bundles)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }

                    items(viewModel.pluginBundles, key = { it.id }) { bundle ->
                        val installedCount = bundle.components.count { it.id in installedComponentIds }
                        val isCoreReady = bundle.components.filter { it.isRequired }.all { it.id in installedComponentIds }

                        RuntimeCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { viewModel.openBundleSetup(bundle) },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            borderColor = if (isCoreReady) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isCoreReady) MaterialTheme.colorScheme.primaryContainer
                                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        RuntimeIcon(
                                            name = when (bundle.iconName) {
                                                "Android" -> RuntimeIconName.Android
                                                "Flutter" -> RuntimeIconName.Flutter
                                                "Globe" -> RuntimeIconName.Globe
                                                "Search" -> RuntimeIconName.Search
                                                else -> RuntimeIconName.Code
                                            },
                                            modifier = Modifier.size(22.dp),
                                            tint = if (isCoreReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = bundle.name,
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f, fill = false),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isCoreReady) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                                                modifier = Modifier.wrapContentWidth(),
                                            ) {
                                                Text(
                                                    text = if (isCoreReady) "已就绪 ($installedCount/${bundle.components.size})" else "未装配 ($installedCount/${bundle.components.size})",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = if (isCoreReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                        Text(
                                            text = bundle.summary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                // 子组件标签胶囊列表（FlowRow 自动换行，绝不挤压）
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    bundle.components.forEach { comp ->
                                        val isInstalled = comp.id in installedComponentIds
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isInstalled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                            ) {
                                                if (comp.isRequired) {
                                                    RuntimeIcon(RuntimeIconName.Shield, Modifier.size(10.dp), tint = MaterialTheme.colorScheme.primary)
                                                }
                                                Text(
                                                    text = if (comp.isRequired) "${comp.name} (必选)" else comp.name,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = if (isInstalled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    FilledTonalButton(
                                        onClick = { viewModel.openBundleSetup(bundle) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            RuntimeIcon(RuntimeIconName.Tune, Modifier.size(14.dp))
                                            Text(
                                                text = if (isCoreReady) "组件管理与配置" else "装配套件",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. 独立 Agent 工具与服务专区
                if (showTools && filteredTools.isNotEmpty()) {
                    if (showBundles) {
                        item {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "智能体生态与服务 (Agents & Services)",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                    }

                    items(filteredTools, key = { it.id }) { tool ->
                        ToolCard(
                            tool = tool,
                            source = viewModel.toolSource(tool.id),
                            progress = installProgress[tool.id],
                            verification = verifications[tool.id],
                            onInstall = { viewModel.installTool(tool.id) },
                            onUpdate = { viewModel.updateTool(tool.id) },
                            onUninstall = {
                                pendingUninstallId = tool.id
                                pendingUninstallName = tool.name
                            },
                            onVerify = { viewModel.verifyTool(tool.id) },
                            onLaunch = { onLaunchPty(tool.id) },
                            onViewLogs = { viewModel.viewLogs(tool.id) },
                            onOpenDetail = { onOpenToolDetail(tool.id) },
                            onStartAiHealing = {
                                // 与日志弹窗「AI 自愈」入口一致：始终传入该工具的全量日志
                                viewModel.fullToolLogs(tool.id) { logs ->
                                    onStartAiHealing(tool.id, tool.name, logs)
                                }
                            },
                        )
                    }
                }

                // 分类下无任何工具时给出空态提示，避免列表区完全空白
                if (showTools && filteredTools.isEmpty()) {
                    item(key = "tool_center_empty_category") {
                        val emptyTitle = androidx.compose.ui.res.stringResource(top.wanxiang.app.feature.settings.R.string.settings_tool_center_empty_category_title)
                        val emptySubtitle = androidx.compose.ui.res.stringResource(top.wanxiang.app.feature.settings.R.string.settings_tool_center_empty_category_subtitle)
                        RuntimeCard(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            contentPadding = PaddingValues(24.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RuntimeIcon(
                                    name = RuntimeIconName.Package,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                                Text(
                                    text = emptyTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = emptySubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // A package is only committed/installed after explicit confirmation.
        (localPluginImport as? top.wanxiang.app.core.tools.LocalPluginImportState.PendingConfirmation)?.let { pending ->
            val manifest = pending.manifest
            RuntimeAlertDialog(
                onDismissRequest = viewModel::cancelLocalPluginImport,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Extension, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
                        Text("确认安装本地插件", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(manifest.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(manifest.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        InfoRow("版本", manifest.version, isCode = false)
                        InfoRow("来源", "本地插件", isCode = false)
                        InfoRow("架构", manifest.architectures.joinToString(", "), isCode = false)
                        InfoRow("包大小", pending.archiveSizeBytes?.let { formatBytes(it) } ?: "未知", isCode = false)
                        InfoRow("离线模式", if (manifest.offlineOnly) "是（不拉取在线依赖）" else "否", isCode = false)
                        Text("点击安装后才会解压资源并执行安装脚本；取消不会修改插件目录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelLocalPluginImport) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = viewModel::confirmLocalPluginImport) { Text("安装") }
                },
            )
        }

        // Install Logs Dialog
        if (viewingLogsToolId != null) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val toolId = viewingLogsToolId ?: ""
            val toolName = tools.firstOrNull { it.id == toolId }?.name ?: toolId
            val hasErrors = toolLogs.any { it.event.contains("FAIL", ignoreCase = true) || it.message.startsWith("ERR") }

            RuntimeAlertDialog(
                onDismissRequest = { viewModel.viewLogs(null) },
                title = { Text("工具执行日志 ($toolName)") },
                text = {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 360.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (toolLogs.isEmpty()) {
                                Text(
                                    text = "暂无相关事件日志",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            } else {
                                toolLogs.forEach { log ->
                                    Text(
                                        text = "[${log.event}] ${log.message}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                        ),
                                        color = if (log.event.contains("FAIL", ignoreCase = true) || log.message.startsWith("ERR:")) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hasErrors) {
                            Button(
                                onClick = {
                                    val fullLogs = toolLogs.map { "[${it.event}] ${it.message}" }
                                    viewModel.viewLogs(null)
                                    onStartAiHealing(toolId, toolName, fullLogs)
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                RuntimeIcon(
                                    name = RuntimeIconName.Brain,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("🧠 AI 自愈", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        TextButton(
                            onClick = {
                                val fullLogs = toolLogs.joinToString("\n") { "[${it.event}] ${it.message}" }
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("tool_logs", fullLogs)
                                clipboard?.setPrimaryClip(clip)
                                android.widget.Toast.makeText(context, "日志已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            enabled = toolLogs.isNotEmpty(),
                        ) {
                            Text("复制")
                        }
                        TextButton(
                            onClick = { viewModel.clearLogs(toolId) },
                            enabled = toolLogs.isNotEmpty(),
                        ) {
                            Text("清空", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { viewModel.viewLogs(null) }) {
                            Text("关闭")
                        }
                    }
                },
            )
        }

        if (showBundleInstallLog) {
            val context = androidx.compose.ui.platform.LocalContext.current
            RuntimeAlertDialog(
                onDismissRequest = { showBundleInstallLog = false },
                title = { Text("开发套件安装日志") },
                text = {
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(10.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (componentInstallLog.isEmpty()) {
                                Text("安装日志正在接收...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            } else {
                                componentInstallLog.forEach { line ->
                                    val isError = line.contains("error", ignoreCase = true) ||
                                        line.contains("failed", ignoreCase = true) ||
                                        line.contains("失败") ||
                                        line.contains("returned an error", ignoreCase = true) ||
                                        line.startsWith("E:")
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("WanXiang Bundle Install Log", componentInstallLog.joinToString("\n")))
                                // 与工具日志复制行为保持一致，复制后给出反馈
                                android.widget.Toast.makeText(context, "日志已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            enabled = componentInstallLog.isNotEmpty(),
                        ) { Text("复制") }
                        TextButton(onClick = { showBundleInstallLog = false }) { Text("关闭") }
                    }
                },
            )
        }

        // 卡片级卸载二次确认（与工具详情页同一弹窗组件）
        pendingUninstallId?.let { uninstallId ->
            UninstallToolConfirmDialog(
                toolName = pendingUninstallName.orEmpty(),
                onConfirm = {
                    viewModel.uninstallTool(uninstallId)
                    pendingUninstallId = null
                    pendingUninstallName = null
                },
                onDismiss = {
                    pendingUninstallId = null
                    pendingUninstallName = null
                },
            )
        }

        // 🛠️ 聚合大插件子组件装配弹窗 (Bundle Component Setup Dialog)
        activeBundle?.let { bundle ->
            RuntimeAlertDialog(
                onDismissRequest = viewModel::closeBundleSetup,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RuntimeIcon(
                            name = when (bundle.iconName) {
                                "Android" -> RuntimeIconName.Android
                                "Flutter" -> RuntimeIconName.Flutter
                                "Globe" -> RuntimeIconName.Globe
                                "Search" -> RuntimeIconName.Search
                                else -> RuntimeIconName.Code
                            },
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text("装配 ${bundle.name}", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Keep the dialog actions in the viewport when a bundle has many components.
                            // The component list remains fully accessible through this scroll container.
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            bundle.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (isInstallingComponents) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        RuntimeCircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Text(componentInstallProgress ?: "正在执行批量原子装配流水线...", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                    }
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
                                }
                            }
                        }

                        val uninstalledComponents = bundle.components.filter { it.id !in installedComponentIds }
                        val installedComponentsList = bundle.components.filter { it.id in installedComponentIds }

                        // 1. 待装配组件分组 (Uninstalled Components)
                        if (uninstalledComponents.isNotEmpty()) {
                            Text(
                                "待装配组件 (${uninstalledComponents.size})：",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )

                            uninstalledComponents.forEach { comp ->
                                val isUninstalledRequired = comp.isRequired
                                val isChecked = isUninstalledRequired || comp.id in selectedComponents

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(enabled = !isInstallingComponents && !isUninstalledRequired) {
                                            viewModel.toggleComponent(comp)
                                        },
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isChecked) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    ),
                                    color = if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        RuntimeCheckbox(
                                            checked = isChecked,
                                            onCheckedChange = { if (!isUninstalledRequired) viewModel.toggleComponent(comp) },
                                            enabled = !isInstallingComponents && !isUninstalledRequired,
                                        )

                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(comp.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                                if (isUninstalledRequired) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                    ) {
                                                        Text(
                                                            "必选基座",
                                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                        )
                                                    }
                                                }
                                            }
                                            Text(comp.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }

                        // 2. 已装配就绪分组 (Installed Components)
                        if (installedComponentsList.isNotEmpty()) {
                            Text(
                                "已装配就绪 (${installedComponentsList.size})：",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = successStatusColor(),
                                modifier = Modifier.padding(top = 6.dp),
                            )

                            installedComponentsList.forEach { comp ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, successStatusColor().copy(alpha = 0.25f)),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(successStatusColor().copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            RuntimeIcon(
                                                name = RuntimeIconName.Check,
                                                modifier = Modifier.size(12.dp),
                                                tint = successStatusColor(),
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(comp.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = successStatusColor().copy(alpha = 0.15f),
                                                ) {
                                                    Text(
                                                        "✓ 已就绪",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                        color = successStatusColor(),
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    )
                                                }
                                            }
                                            Text(comp.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    val uninstalledComponents = bundle.components.filter { it.id !in installedComponentIds }
                    if (uninstalledComponents.isEmpty()) {
                        Button(onClick = viewModel::closeBundleSetup) {
                            Text("全部组件已就绪")
                        }
                    } else {
                        Button(
                            onClick = viewModel::installActiveBundleComponents,
                            enabled = !isInstallingComponents && selectedComponents.isNotEmpty(),
                        ) {
                            Text(if (selectedComponents.isEmpty()) "请勾选待装配组件" else "开始装配 (${selectedComponents.size})")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::closeBundleSetup) {
                        Text("取消")
                    }
                },
            )
        }
    }

    // 首次进入引导遮罩：高亮顶栏「导入离线插件包」入口（组件内部等待按钮完成布局测量后才绘制）
    if (!importGuideShown) {
        SpotlightGuideOverlay(
            anchor = importAnchor,
            title = "导入离线插件包",
            message = "已从 QQ 群下载好全量离线插件包？点击右上角的文件夹图标，选择 zip 文件即可导入，" +
                "一次装齐 Android、Flutter、反编译环境，全程无需联网。",
            icon = RuntimeIconName.FolderOpen,
            onDismiss = viewModel::markImportGuideShown,
        )
    }
    } // Box
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}



/**
 * 工具品牌专属视觉 Avatar
 */
@Composable
private fun ToolBrandAvatar(
    toolId: String,
    category: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 46.dp,
) {
    val key = toolId.lowercase().trim()
    val (logoRes, emoji, brandColor) = when {
        key.contains("claude") || key.contains("anthropic") ->
            Triple(top.wanxiang.app.feature.components.R.drawable.components_ic_provider_anthropic, null, Color(0xFFD97757))
        key.contains("codex") || key.contains("openai") ->
            Triple(top.wanxiang.app.feature.components.R.drawable.components_ic_provider_openai, null, Color(0xFF10A37F))
        key.contains("openclaw") ->
            Triple(null, "🦞", Color(0xFFFF4757))
        key.contains("hermes") ->
            Triple(null, "🪽", Color(0xFF8B5CF6))
        key.contains("android") ->
            Triple(null, "🤖", Color(0xFF3DDC84))
        key.contains("devtools") || key.contains("base-devtools") ->
            Triple(null, "⚡", Color(0xFF0284C7))
        key.contains("hello") ->
            Triple(null, "🧪", Color(0xFF10B981))
        key.contains("deepseek") ->
            Triple(top.wanxiang.app.feature.components.R.drawable.components_ic_provider_deepseek, null, Color(0xFF4D6BFE))
        key.contains("ollama") ->
            Triple(top.wanxiang.app.feature.components.R.drawable.components_ic_provider_ollama, null, Color(0xFF334155))
        else -> when (category) {
            "CODING_AGENT" -> Triple(null, "💻", Color(0xFF6366F1))
            "AI_AGENT" -> Triple(null, "🤖", Color(0xFF0EA5E9))
            else -> Triple(null, "📦", Color(0xFF64748B))
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(13.dp))
            .background(brandColor.copy(alpha = 0.12f))
            .border(1.dp, brandColor.copy(alpha = 0.28f), RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (logoRes != null) {
            Box(
                modifier = Modifier
                    .size(size * 0.72f)
                    .clip(CircleShape)
                    .background(brandColor),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(logoRes),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(5.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        } else if (emoji != null) {
            Text(
                text = emoji,
                fontSize = (size.value * 0.44f).sp,
            )
        } else {
            RuntimeIcon(
                name = RuntimeIconName.Package,
                modifier = Modifier.size(size * 0.48f),
                tint = brandColor,
            )
        }
    }
}

/**
 * 单个工具与插件展示卡片
 */
@Composable
private fun ToolCard(
    tool: ToolEntity,
    source: String,
    progress: ToolInstallProgress?,
    verification: ToolVerification?,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onVerify: () -> Unit,
    onLaunch: () -> Unit,
    onViewLogs: () -> Unit,
    onOpenDetail: () -> Unit,
    onStartAiHealing: () -> Unit = {},
) {
    val isInstalled = tool.state == ToolState.INSTALLED.name || tool.state == ToolState.UPDATE_AVAILABLE.name
    val isInstalling = tool.state == ToolState.INSTALLING.name
    val isUpdateAvailable = tool.state == ToolState.UPDATE_AVAILABLE.name
    val isFailed = tool.state == ToolState.FAILED.name

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenDetail,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: Avatar + Title/Publisher + Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    ToolBrandAvatar(
                        toolId = tool.id,
                        category = tool.category,
                        size = 46.dp,
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val displayVersion = tool.installedVersion ?: tool.manifestVersion
                            if (displayVersion.isNotBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Text(
                                        text = "v$displayVersion",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                            Surface(
                                color = if (source == "LOCAL") {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    text = if (source == "LOCAL") "本地插件" else "在线插件",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = if (source == "LOCAL") {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }

                        Text(
                            text = "${tool.publisher} · ${when (tool.category) {
                                "CODING_AGENT" -> "编程智能体"
                                "AI_AGENT" -> "AI 智能体"
                                "DEVELOPER_TOOL" -> "开发加速包"
                                else -> tool.category
                            }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Status Badge
                StatusBadge(
                    text = when {
                        isInstalling -> "安装中"
                        isUpdateAvailable -> "可更新"
                        isInstalled -> "已就绪"
                        isFailed -> "安装失败"
                        else -> "未安装"
                    },
                    color = when {
                        isUpdateAvailable -> MaterialTheme.colorScheme.primary
                        isInstalled -> successStatusColor()
                        isInstalling -> MaterialTheme.colorScheme.tertiary
                        isFailed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    },
                )
            }

            // Description with comfortable reading line height
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Installing Progress Bar
            if (isInstalling && progress != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = progress.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        progress.progress?.let { p ->
                            Text(
                                text = "${(p * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { progress.progress ?: 0.5f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Verification info (if any)
            if (verification != null && !verification.healthy) {
                NoticeBanner(text = "自检异常: ${verification.detail}", isError = true)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))

            // Actions row: Spacious, distinct secondary buttons on left, prominent action on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Secondary actions
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = onOpenDetail,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("详情配置", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    }

                    TextButton(
                        onClick = onViewLogs,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("日志", style = MaterialTheme.typography.labelSmall)
                    }

                    if (isInstalled) {
                        TextButton(
                            onClick = onUninstall,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("卸载", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                // Primary actions (Install / Retry / Update)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isUpdateAvailable) {
                        Button(
                            onClick = onUpdate,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text("更新", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                        }
                    } else if (isFailed) {
                        Button(
                            onClick = onStartAiHealing,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Brain,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("AI 自愈", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }
                        FilledTonalButton(
                            onClick = onInstall,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Download,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("重试", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelMedium)
                        }
                    } else if (!isInstalled && !isInstalling) {
                        Button(
                            onClick = onInstall,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Download,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("安装", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}