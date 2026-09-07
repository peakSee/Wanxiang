package top.wanxiang.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.wanxiang.app.core.database.AiModelEntity
import top.wanxiang.app.core.model.AiModelProfileExport
import top.wanxiang.app.ui.components.IconTile
import top.wanxiang.app.ui.components.ProviderBadge
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import top.wanxiang.app.ui.components.RuntimeButton
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator
import top.wanxiang.app.ui.components.RuntimeCheckbox
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconButton
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeOutlinedButton
import top.wanxiang.app.ui.components.RuntimeTextButton
import top.wanxiang.app.ui.components.RuntimeTopBar

/**
 * 模型档案管理全屏独立页面
 */
@Composable
fun ModelProfilesScreen(
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showImportDialog by remember { mutableStateOf(false) }
    var showExportAllDialog by remember { mutableStateOf(false) }
    var singleExportModel by remember { mutableStateOf<AiModelEntity?>(null) }
    var modelPendingDelete by remember { mutableStateOf<AiModelEntity?>(null) }
    var importing by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = "模型档案",
                onBack = onBack,
                actions = {
                    // 📥 导入按钮
                    RuntimeIconButton(
                        onClick = { showImportDialog = true },
                        contentDescription = "导入模型配置",
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Download,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // 📤 导出全部按钮
                    RuntimeIconButton(
                        onClick = {
                            if (models.isEmpty()) {
                                Toast.makeText(context, "暂无模型档案可导出", Toast.LENGTH_SHORT).show()
                            } else {
                                showExportAllDialog = true
                            }
                        },
                        contentDescription = "导出全部模型配置",
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.OpenInNew,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        ModelProfilesContent(
            modifier = Modifier.padding(padding),
            models = models,
            onCreate = onCreate,
            onEdit = { model -> onEdit(model.id) },
            onActivate = viewModel::setActiveModel,
            onDelete = { model -> modelPendingDelete = model },
            onExportSingle = { model -> singleExportModel = model },
        )
    }

    // 导入弹窗
    if (showImportDialog) {
        ModelImportDialog(
            onDismiss = { showImportDialog = false },
            importing = importing,
            onImportJson = { jsonStr ->
                if (importing) return@ModelImportDialog
                importing = true
                coroutineScope.launch {
                    val result = viewModel.importProfilesFromJson(jsonStr)
                    importing = false
                    result.fold(
                        onSuccess = { count ->
                            Toast.makeText(context, "成功导入 $count 个模型档案", Toast.LENGTH_SHORT).show()
                            showImportDialog = false
                        },
                        onFailure = { err ->
                            android.util.Log.e("ModelProfiles", "Profile import failed: ${err.message}", err)
                            Toast.makeText(context, "导入失败：JSON 无法解析或配置无效，请检查后重试", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            },
        )
    }

    // 批量导出弹窗
    if (showExportAllDialog) {
        ModelExportDialog(
            title = "批量导出模型档案",
            subtitle = "准备导出 ${models.size} 个模型档案配置",
            onDismiss = { showExportAllDialog = false },
            onGenerateJson = { includeKeys ->
                viewModel.exportAllProfilesJson(includeKeys)
            },
        )
    }

    // 单项导出弹窗
    singleExportModel?.let { targetModel ->
        ModelExportDialog(
            title = "导出模型档案配置",
            subtitle = "模型: ${targetModel.name} (${targetModel.provider})",
            defaultFileName = "wanxiang_model_${targetModel.name.replace(" ", "_")}.json",
            onDismiss = { singleExportModel = null },
            onGenerateJson = { includeKeys ->
                viewModel.exportSingleProfileJson(targetModel.id, includeKeys) ?: ""
            },
        )
    }

    // 删除确认弹窗
    modelPendingDelete?.let { target ->
        RuntimeAlertDialog(
            onDismissRequest = { modelPendingDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeIcon(RuntimeIconName.Alert, Modifier.size(20.dp), MaterialTheme.colorScheme.error)
                    Text("删除模型档案", fontWeight = FontWeight.Bold)
                }
            },
            text = { Text("确定要删除模型档案「${target.name}」吗？此操作将同时清理该模型保存的 API Key。") },
            confirmButton = {
                RuntimeButton(
                    onClick = {
                        viewModel.deleteModel(target.id)
                        modelPendingDelete = null
                    },
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { modelPendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelProfilesContent(
    modifier: Modifier,
    models: List<AiModelEntity>,
    onCreate: () -> Unit,
    onEdit: (AiModelEntity) -> Unit,
    onActivate: (String) -> Unit,
    onDelete: (AiModelEntity) -> Unit,
    onExportSingle: (AiModelEntity) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            RuntimeButton(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuntimeIcon(RuntimeIconName.Plus, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimary)
                    Text("新增模型档案", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (models.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconTile(RuntimeIconName.Model, color = MaterialTheme.colorScheme.primary, size = 42.dp)
                    Text("暂无模型档案", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        "点击上方新增或从右上角导入 OpenAI / DeepSeek / Claude / 本地模型配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(models, key = { it.id }) { model ->
            ModelProfileCard(
                model = model,
                onEdit = { onEdit(model) },
                onActivate = { onActivate(model.id) },
                onDelete = { onDelete(model) },
                onExport = { onExportSingle(model) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelProfileCard(
    model: AiModelEntity,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = if (model.isActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        },
        onClick = onEdit,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        // 头部：Provider Badge + 标题 + 激活状态 + 更多菜单
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderBadge(providerIdOrName = model.provider, size = 24.dp)

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = model.provider,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (model.isActive) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = "当前激活",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            } else {
                RuntimeTextButton(
                    onClick = onActivate,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("设为激活", style = MaterialTheme.typography.labelMedium)
                }
            }

            // 更多操作下拉菜单
            Box {
                RuntimeIconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(32.dp),
                    contentDescription = "更多操作",
                ) {
                    RuntimeIcon(
                        name = RuntimeIconName.More,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RuntimeIcon(RuntimeIconName.Edit, Modifier.size(16.dp))
                                Text("编辑档案")
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RuntimeIcon(RuntimeIconName.OpenInNew, Modifier.size(16.dp))
                                Text("导出为 JSON")
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onExport()
                        },
                    )
                    if (!model.isActive) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RuntimeIcon(RuntimeIconName.Check, Modifier.size(16.dp))
                                    Text("设为激活")
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                onActivate()
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RuntimeIcon(RuntimeIconName.Trash, Modifier.size(16.dp), MaterialTheme.colorScheme.error)
                                Text("删除档案", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }

        // 模型列表标签
        val modelList = remember(model.model) {
            model.model.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (modelList.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                modelList.take(3).forEach { modelId ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            text = modelId,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (modelList.size > 3) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                    ) {
                        Text(
                            text = "+${modelList.size - 3} 更多",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        // 元数据摘要
        val ctxTokens = model.contextTokens
        val metadataSummary = buildList {
            if (model.baseUrl.isNotBlank()) add(model.baseUrl)
            if (model.apiKeyCount > 0) add("${model.apiKeyCount} Key")
            if (model.requestsPerMinutePerKey > 0) add("${model.requestsPerMinutePerKey} RPM/Key")
            if (ctxTokens != null) add("${ctxTokens / 1000}k 上下文")
        }.joinToString(" • ")

        if (metadataSummary.isNotBlank()) {
            Text(
                text = metadataSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}
}

/**
 * 模型档案导入对话框
 */
@Composable
fun ModelImportDialog(
    importing: Boolean = false,
    onDismiss: () -> Unit,
    onImportJson: (String) -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var jsonText by remember { mutableStateOf("") }
    val context = LocalContext.current

    // 文件选择器
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
                Text("导入模型档案", fontWeight = FontWeight.Bold)
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
                        text = { Text("选择文件 (.json)") },
                    )
                }

                if (selectedTab == 0) {
                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = { jsonText = it },
                        modifier = Modifier.fillMaxWidth().height(170.dp),
                        placeholder = { Text("粘贴导出的 JSON 模型配置（支持单个或批量 bundle）") },
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
                enabled = jsonText.isNotBlank() && !importing,
            ) {
                if (importing) {
                    RuntimeCircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (importing) "正在导入…" else "开始导入")
            }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/**
 * 模型档案导出对话框
 */
@Composable
fun ModelExportDialog(
    title: String,
    subtitle: String,
    defaultFileName: String = "wanxiang_models_export.json",
    onDismiss: () -> Unit,
    onGenerateJson: suspend (includeKeys: Boolean) -> String,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var includeKeys by remember { mutableStateOf(false) }

    // 保存文件选择器
    val createFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                runCatching {
                    val jsonContent = onGenerateJson(includeKeys)
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.bufferedWriter().use { it.write(jsonContent) }
                    }
                }.onSuccess {
                    Toast.makeText(context, "导出文件保存成功", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }.onFailure {
                    Toast.makeText(context, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.OpenInNew, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                Text(title, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // 是否包含 API Key 复选框
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RuntimeCheckbox(
                            checked = includeKeys,
                            onCheckedChange = { includeKeys = it },
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "包含 API Key",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Text(
                                text = if (includeKeys) "⚠️ 包含敏感凭据，请妥善保管，切勿公开发送" else "推荐：脱敏导出（不含 Key），适合安全分享",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = if (includeKeys) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 导出操作选项
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 1. 复制到剪贴板
                    RuntimeOutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                val jsonStr = onGenerateJson(includeKeys)
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = ClipData.newPlainText("WanXiang Model Config", jsonStr)
                                clipboard?.setPrimaryClip(clip)
                                Toast.makeText(context, "已复制 JSON 配置到剪贴板", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RuntimeIcon(RuntimeIconName.Copy, Modifier.size(16.dp))
                            Text("复制 JSON 到剪贴板")
                        }
                    }

                    // 2. 保存为文件
                    RuntimeOutlinedButton(
                        onClick = {
                            createFileLauncher.launch(defaultFileName)
                        },
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RuntimeIcon(RuntimeIconName.Save, Modifier.size(16.dp))
                            Text("保存为 .json 文件")
                        }
                    }

                    // 3. 系统分享
                    RuntimeOutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                val jsonStr = onGenerateJson(includeKeys)
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "WanXiang Model Profiles")
                                    putExtra(Intent.EXTRA_TEXT, jsonStr)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "分享模型配置"))
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RuntimeIcon(RuntimeIconName.OpenInNew, Modifier.size(16.dp))
                            Text("系统分享 (Share Sheet)")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}
