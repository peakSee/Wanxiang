package top.wanxiang.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import top.wanxiang.app.ui.settings.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wanxiang.app.runtime.LocalGgufModel
import top.wanxiang.app.runtime.LocalLlmServiceState
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import top.wanxiang.app.ui.components.RuntimeButton
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconButton
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeLinearProgressIndicator
import top.wanxiang.app.ui.components.RuntimeOutlinedButton
import top.wanxiang.app.ui.components.RuntimeTextButton
import top.wanxiang.app.ui.components.RuntimeTopBar

@Composable
fun LocalLlmScreen(
    onBack: () -> Unit,
    onOpenEngine: () -> Unit,
    viewModel: LocalLlmViewModel = hiltViewModel(),
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val messageIsError by viewModel.messageIsError.collectAsStateWithLifecycle()
    val engineInstalled by viewModel.engineInstalled.collectAsStateWithLifecycle()
    val deviceRamBytes = viewModel.deviceRamBytes
    val mobileModelPresets = viewModel.mobileModelPresets
    val topBarStatus = when (val state = serviceState) {
        LocalLlmServiceState.Stopped -> "服务未启动"
        is LocalLlmServiceState.Starting -> "正在启动 · ${state.fileName}"
        is LocalLlmServiceState.Running -> "运行中 · ${state.fileName}"
        is LocalLlmServiceState.Failed -> "启动失败"
    }

    var showDownloadDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showPathDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // 待确认删除的模型文件名（破坏性操作二次确认）
    var pendingDeleteModel by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importUri)
    }

    message?.let { currentMessage ->
        RuntimeAlertDialog(
            onDismissRequest = viewModel::consumeMessage,
            title = { Text(if (messageIsError) "操作未完成" else "本地 LLM") },
            text = { Text(currentMessage) },
            confirmButton = {
                RuntimeTextButton(onClick = viewModel::consumeMessage) { Text("知道了") }
            },
        )
    }

    if (showDownloadDialog) {
        DownloadModelDialog(
            onDismiss = { showDownloadDialog = false },
            onDownload = { url, checksum ->
                showDownloadDialog = false
                viewModel.download(url, checksum)
            },
        )
    }

    if (showPathDialog) {
        ImportPathDialog(
            onDismiss = { showPathDialog = false },
            onImport = { path ->
                showPathDialog = false
                viewModel.importPath(path)
            },
        )
    }

    pendingDeleteModel?.let { fileName ->
        DeleteModelConfirmDialog(
            fileName = fileName,
            onConfirm = {
                pendingDeleteModel = null
                viewModel.delete(fileName)
            },
            onDismiss = { pendingDeleteModel = null },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = "本地 LLM",
                onBack = onBack,
                statusText = topBarStatus,
                actions = {
                    if (serviceState is LocalLlmServiceState.Running) {
                        RuntimeOutlinedButton(
                            onClick = viewModel::stop,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Stop, Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("停止")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RuntimeOutlinedButton(
                        onClick = { showDownloadDialog = true },
                        enabled = !transfer.running,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 11.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Download, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("网络下载", maxLines = 1)
                    }
                    RuntimeOutlinedButton(
                        onClick = { showPathDialog = true },
                        enabled = !transfer.running,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 11.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Folder, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("从路径导入", maxLines = 1)
                    }
                    RuntimeOutlinedButton(
                        onClick = { filePicker.launch(arrayOf("application/octet-stream", "application/*", "*/*")) },
                        enabled = !transfer.running,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 11.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.File, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("选择文件", maxLines = 1)
                    }
                }
            }

            if (transfer.running) {
                item {
                    RuntimeCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    transfer.label,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    transfer.totalBytes?.let { "${formatBytes(transfer.copiedBytes)} / ${formatBytes(it)}" }
                                        ?: formatBytes(transfer.copiedBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            RuntimeLinearProgressIndicator(
                                progress = transfer.fraction?.let { fraction -> { fraction } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            RuntimeTextButton(onClick = viewModel::cancelTransfer, modifier = Modifier.align(Alignment.End)) {
                                Text("取消")
                            }
                        }
                    }
                }
            }

            item {
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Info, Modifier.size(23.dp), MaterialTheme.colorScheme.primary)
                            Text("本地 LLM 推理", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        }
                        Text(
                            "通过 llama.cpp 在 Linux 沙箱内运行 GGUF 模型，推理数据不离开设备。支持 HTTPS / Hugging Face 直链断点下载，也可从手机文件选择器导入。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!engineInstalled) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("需要先安装 llama.cpp 推理引擎", fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        RuntimeButton(onClick = viewModel::installEngine) { Text("一键安装") }
                                        RuntimeOutlinedButton(onClick = onOpenEngine) { Text("查看详情") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("手机小模型推荐", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text(
                        "本机内存 ${formatBytes(deviceRamBytes)} · 默认推荐 Q4_K_M，兼顾体积、速度和效果",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(mobileModelPresets, key = MobileModelPreset::id) { preset ->
                val suitable = viewModel.isDeviceSuitable(preset)
                val alreadyDownloaded = models.any { model ->
                    preset.url.substringBefore('?').substringAfterLast('/').equals(model.fileName, ignoreCase = true)
                }
                MobileModelPresetCard(
                    preset = preset,
                    suitable = suitable,
                    alreadyDownloaded = alreadyDownloaded,
                    transferRunning = transfer.running,
                    download = { viewModel.downloadPreset(preset) },
                )
            }

            item {
                ServiceStatusCard(serviceState = serviceState, stop = viewModel::stop)
            }

            item {
                Text("已导入模型", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }

            if (models.isEmpty()) {
                item {
                    RuntimeCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        borderColor = Color.Transparent,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Model, Modifier.size(44.dp), MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
                            Text("暂无模型", style = MaterialTheme.typography.titleSmall)
                            Text("使用上方按钮下载或导入 GGUF 模型", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                items(models, key = LocalGgufModel::fileName) { model ->
                    ModelCard(
                        model = model,
                        serviceState = serviceState,
                        engineInstalled = engineInstalled,
                        start = { viewModel.start(model.fileName) },
                        stop = viewModel::stop,
                        delete = { pendingDeleteModel = model.fileName },
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileModelPresetCard(
    preset: MobileModelPreset,
    suitable: Boolean,
    alreadyDownloaded: Boolean,
    transferRunning: Boolean,
    download: () -> Unit,
) {
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = if (suitable) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Cpu, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
                Text(
                    preset.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = if (suitable) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ) {
                    Text(
                        if (suitable) "适合本机" else "内存不足",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (suitable) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                    )
                }
            }
            Text(preset.purpose, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModelSpecChip(preset.parameterCount)
                        ModelSpecChip(preset.quantization)
                        ModelSpecChip(formatBytes(preset.downloadBytes))
                    }
                    Text(
                        "建议设备内存 ${formatRam(preset.minimumDeviceRamBytes)} 以上",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RuntimeButton(
                    onClick = download,
                    enabled = suitable && !alreadyDownloaded && !transferRunning,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    RuntimeIcon(
                        if (alreadyDownloaded) RuntimeIconName.Check else RuntimeIconName.Download,
                        Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(if (alreadyDownloaded) "已下载" else "下载")
                }
            }
        }
    }
}

@Composable
private fun ModelSpecChip(text: String) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServiceStatusCard(
    serviceState: LocalLlmServiceState,
    stop: () -> Unit,
) {
    val running = serviceState as? LocalLlmServiceState.Running
    val color = when (serviceState) {
        LocalLlmServiceState.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
        is LocalLlmServiceState.Starting -> MaterialTheme.colorScheme.tertiary
        is LocalLlmServiceState.Running -> successStatusColor()
        is LocalLlmServiceState.Failed -> MaterialTheme.colorScheme.error
    }
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = color.copy(alpha = 0.25f),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Surface(modifier = Modifier.size(10.dp), shape = RoundedCornerShape(50), color = color) {}
                Text(
                    when (serviceState) {
                        LocalLlmServiceState.Stopped -> "未运行"
                        is LocalLlmServiceState.Starting -> "正在启动 · ${serviceState.fileName}"
                        is LocalLlmServiceState.Running -> "运行中 · ${serviceState.fileName}"
                        is LocalLlmServiceState.Failed -> "启动失败"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.weight(1f))
                if (running != null) {
                    RuntimeOutlinedButton(onClick = stop, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) {
                        RuntimeIcon(RuntimeIconName.Stop, Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("停止")
                    }
                }
            }
            Text(
                running?.endpoint ?: when (serviceState) {
                    is LocalLlmServiceState.Failed -> serviceState.message
                    else -> "选择下方模型启动服务；启动成功后会自动设为当前对话模型"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: LocalGgufModel,
    serviceState: LocalLlmServiceState,
    engineInstalled: Boolean,
    start: () -> Unit,
    stop: () -> Unit,
    delete: () -> Unit,
) {
    val isRunning = (serviceState as? LocalLlmServiceState.Running)?.fileName == model.fileName
    val isStarting = (serviceState as? LocalLlmServiceState.Starting)?.fileName == model.fileName
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            RuntimeIcon(RuntimeIconName.Model, Modifier.size(28.dp), MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    model.fileName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(formatBytes(model.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isRunning) {
                RuntimeOutlinedButton(onClick = stop, contentPadding = PaddingValues(horizontal = 11.dp, vertical = 8.dp)) {
                    RuntimeIcon(RuntimeIconName.Stop, Modifier.size(17.dp))
                }
            } else {
                RuntimeButton(
                    onClick = start,
                    enabled = engineInstalled && (
                        serviceState is LocalLlmServiceState.Stopped ||
                            serviceState is LocalLlmServiceState.Failed
                        ),
                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 8.dp),
                ) {
                    RuntimeIcon(RuntimeIconName.Play, Modifier.size(17.dp))
                    if (isStarting) {
                        Spacer(Modifier.size(4.dp))
                        Text("启动中")
                    }
                }
            }
            RuntimeIconButton(onClick = delete, enabled = !isRunning && !isStarting) {
                RuntimeIcon(RuntimeIconName.Trash, Modifier.size(19.dp), MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DownloadModelDialog(
    onDismiss: () -> Unit,
    onDownload: (String, String?) -> Unit,
) {
    var url by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var sha256 by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    // URL 校验：必须是 http(s) 直链且指向 .gguf 文件（允许携带 ?download=true 之类查询参数）
    val urlValid = (url.startsWith("http://") || url.startsWith("https://")) &&
        url.substringBefore('?').lowercase().endsWith(".gguf")
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网络下载 GGUF") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("粘贴以 .gguf 结尾的 HTTPS 直链。Hugging Face 文件页请复制 Download 链接。")
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("GGUF 下载地址") },
                    placeholder = { Text("https://.../model.gguf") },
                    isError = url.isNotBlank() && !urlValid,
                    supportingText = {
                        if (url.isNotBlank() && !urlValid) {
                            Text("请输入以 http(s):// 开头且指向 .gguf 文件的直链")
                        }
                    },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = sha256,
                    onValueChange = { sha256 = it },
                    label = { Text("SHA-256（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            RuntimeButton(onClick = { onDownload(url, sha256.ifBlank { null }) }, enabled = urlValid) { Text("开始下载") }
        },
        dismissButton = { RuntimeTextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ImportPathDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var path by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val pathValid = path.substringBefore('?').lowercase().endsWith(".gguf")
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从路径导入") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("输入应用可读取的绝对路径，例如 /storage/emulated/0/Download/model.gguf。受 Android 存储权限限制时请改用“选择文件”。")
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("GGUF 文件路径") },
                    isError = path.isNotBlank() && !pathValid,
                    supportingText = {
                        if (path.isNotBlank() && !pathValid) {
                            Text("路径需指向 .gguf 模型文件")
                        }
                    },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            RuntimeButton(onClick = { onImport(path) }, enabled = pathValid) { Text("导入") }
        },
        dismissButton = { RuntimeTextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 删除 GGUF 模型二次确认弹窗（与 DistroManagementScreen 删除确认同款倒计时锁样式） */
@Composable
private fun DeleteModelConfirmDialog(
    fileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var countdown by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(3) }

    LaunchedEffect(fileName) {
        while (countdown > 0) {
            kotlinx.coroutines.delay(1000)
            countdown--
        }
    }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "删除模型 $fileName？",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "• 将从沙箱中删除该 GGUF 模型文件，释放对应存储空间",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "• 删除后需要重新下载或导入才能再次使用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            RuntimeButton(
                onClick = onConfirm,
                enabled = countdown == 0,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                ),
            ) {
                Text(
                    text = if (countdown > 0) "确认删除 (" + countdown + "s)" else "确认删除",
                    color = if (countdown == 0) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onError.copy(alpha = 0.6f),
                )
            }
        },
        dismissButton = { RuntimeTextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatRam(bytes: Long): String = "${kotlin.math.ceil(bytes / (1024.0 * 1024.0 * 1024.0)).toInt()} GB"
