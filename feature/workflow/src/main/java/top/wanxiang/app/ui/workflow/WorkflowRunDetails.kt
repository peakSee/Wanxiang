package top.wanxiang.app.ui.workflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.wanxiang.app.core.database.AiModelEntity
import top.wanxiang.app.core.model.workflow.*
import top.wanxiang.app.ui.components.*

@Composable
internal fun WorkflowNodeDetails(run: WorkflowNodeRunState?) {
    var expanded by remember { mutableStateOf(false) }
    val output = run?.output
    if (run?.status in setOf(NodeRunStatus.RUNNING, NodeRunStatus.STREAMING)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    output?.let {
        Text("耗时 ${it.durationMs / 1000.0} 秒 · 退出码 ${it.exitCode}", style = MaterialTheme.typography.labelSmall)
        it.error?.let { error -> SelectionContainer { Text(error, color = MaterialTheme.colorScheme.error) } }
        if (it.artifacts.isNotEmpty()) {
            Text("产物路径", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(it.artifacts.joinToString("\n"), fontFamily = FontFamily.Monospace) }
        }
    }
    val log = output?.textOutput?.takeIf { it.isNotBlank() } ?: run?.progressMessage.orEmpty()
    if (log.isNotBlank()) {
        RuntimeOutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起日志" else "展开日志 / Diff") }
        if (expanded) SelectionContainer {
            Text(log, Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
internal fun WorkflowElapsed(state: WorkflowRuntimeState) {
    var now by remember(state.executionId) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.executionId, state.finishedAt) {
        while (state.finishedAt == null) { now = System.currentTimeMillis(); delay(1000) }
    }
    val elapsed = ((state.finishedAt ?: now) - (state.startedAt ?: now)).coerceAtLeast(0) / 1000
    Text("运行耗时 ${elapsed / 60} 分 ${elapsed % 60} 秒", style = MaterialTheme.typography.labelSmall)
}

@Composable
internal fun WorkflowStartDialog(
    definition: WorkflowDefinition,
    supplied: Map<String, String>,
    models: List<AiModelEntity>,
    availableApks: List<DiscoveredApk> = emptyList(),
    onRefreshApks: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onStart: (Map<String, String>) -> Unit,
) {
    val required = remember(definition) {
        definition.nodes.filter { it.type == WorkflowNodeType.TRIGGER }
            .flatMap { it.config["requiredVariables"].orEmpty().split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    val editableDefaults = remember(definition) {
        definition.defaultVariables.keys.filterNot { it in required || it.startsWith("WORKFLOW_MODEL") }.sorted()
    }
    val needsModel = remember(definition) {
        definition.nodes.any {
            it.type == WorkflowNodeType.AGENT_INFERENCE ||
                it.type == WorkflowNodeType.SUBAGENT_DELEGATE ||
                (it.type == WorkflowNodeType.HOST_ACTION && it.config["action"] == "gui_pilot")
        }
    }
    val values = remember(definition.id, supplied) {
        mutableStateMapOf<String, String>().apply { putAll(definition.defaultVariables + supplied) }
    }

    val isApkWorkflow = "APK_PATH" in required
    LaunchedEffect(definition.id, availableApks) {
        if (isApkWorkflow && values["APK_PATH"].isNullOrBlank() && availableApks.isNotEmpty()) {
            values["APK_PATH"] = availableApks.first().sandboxPath
        }
    }

    val active = models.firstOrNull { it.isActive }
    var selectedModelId by remember(definition.id, models) {
        mutableStateOf(
            values["WORKFLOW_MODEL_ID"]
                ?.takeIf { id -> models.any { it.id == id } }
                ?: active?.id
                ?: models.firstOrNull()?.id.orEmpty(),
        )
    }
    val selectedProfile = models.firstOrNull { it.id == selectedModelId }
    val variants = remember(selectedProfile?.model) {
        selectedProfile?.model.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    var selectedVariant by remember(selectedModelId, variants) {
        mutableStateOf(
            values["WORKFLOW_MODEL_VARIANT"]?.takeIf { it in variants }
                ?: variants.firstOrNull().orEmpty(),
        )
    }

    val canStart = required.all { !values[it].isNullOrBlank() } &&
        (!needsModel || (selectedModelId.isNotBlank() && models.any { it.id == selectedModelId }))

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行 ${definition.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(definition.description)
                if (needsModel) {
                    Text("执行模型（点选即可，避免 403 请换可用账号）", style = MaterialTheme.typography.labelLarge)
                    if (models.isEmpty()) {
                        Text(
                            "尚未配置可用模型。请先到设置 → 模型管理添加账号，再回来运行含智能体节点的工作流。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        models.forEach { model ->
                            val selected = model.id == selectedModelId
                            Surface(
                                onClick = {
                                    selectedModelId = model.id
                                    val nextVariants = model.model.split(',')
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                    selectedVariant = nextVariants.firstOrNull().orEmpty()
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text(
                                        text = buildString {
                                            append(if (selected) "✓ " else "")
                                            append(model.name)
                                            if (model.isActive) append("（当前默认）")
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    Text(
                                        text = "${model.provider} · ${model.model.substringBefore(',').ifBlank { model.model }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (model.pureChatMode) {
                                        Text(
                                            "纯聊天模式：可能无法调用 host 工具",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                        if (variants.size > 1) {
                            Text("模型变体", style = MaterialTheme.typography.labelMedium)
                            variants.forEach { variant ->
                                val selected = variant == selectedVariant
                                Surface(
                                    onClick = { selectedVariant = variant },
                                    shape = RoundedCornerShape(50),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = if (selected) "✓ $variant" else variant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        } else if (variants.size == 1) {
                            Text("变体：${variants.first()}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "此处选择只影响本次工作流，不会改全局默认模型。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isApkWorkflow) {
                    ApkSelectorSection(
                        selectedPath = values["APK_PATH"].orEmpty(),
                        availableApks = availableApks,
                        onSelectApk = { values["APK_PATH"] = it },
                        onRefresh = onRefreshApks,
                    )
                }
                required.filterNot { it == "APK_PATH" }.forEach { key ->
                    OutlinedTextField(
                        value = values[key].orEmpty(),
                        onValueChange = { values[key] = it },
                        label = { Text("$key（必填）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                editableDefaults.forEach { key ->
                    OutlinedTextField(
                        value = values[key].orEmpty(),
                        onValueChange = { values[key] = it },
                        label = { Text(key) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        dismissButton = { RuntimeOutlinedButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            RuntimeButton(
                onClick = {
                    val payload = values.toMutableMap()
                    if (needsModel && selectedModelId.isNotBlank()) {
                        payload["WORKFLOW_MODEL_ID"] = selectedModelId
                        val variant = selectedVariant.ifBlank { variants.firstOrNull().orEmpty() }
                        if (variant.isNotBlank()) payload["WORKFLOW_MODEL_VARIANT"] = variant
                    }
                    onStart(payload)
                },
                enabled = canStart,
            ) { Text("开始执行") }
        },
    )
}

@Composable
private fun ApkSelectorSection(
    selectedPath: String,
    availableApks: List<DiscoveredApk>,
    onSelectApk: (String) -> Unit,
    onRefresh: (() -> Unit)?,
) {
    var showManualInput by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RuntimeIcon(RuntimeIconName.Android, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("选择安装 APK", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            if (onRefresh != null) {
                TextButton(
                    onClick = onRefresh,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重新扫描", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (availableApks.isNotEmpty()) {
            availableApks.forEachIndexed { index, apk ->
                val isSelected = selectedPath == apk.sandboxPath ||
                    selectedPath == apk.file.absolutePath ||
                    selectedPath == apk.relativePath
                Surface(
                    onClick = { onSelectApk(apk.sandboxPath) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            if (isSelected) {
                                RuntimeIcon(RuntimeIconName.Check, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = apk.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            if (index == 0) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                ) {
                                    Text(
                                        text = "✨ 最新生成",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = apk.relativePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = formatApkSize(apk.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = formatApkTime(apk.lastModified),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = { showManualInput = !showManualInput },
                contentPadding = PaddingValues(0.dp),
            ) {
                RuntimeIcon(
                    if (showManualInput) RuntimeIconName.ChevronUp else RuntimeIconName.ChevronDown,
                    Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (showManualInput) "收起手动输入" else "手动指定其他 APK 路径...",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } else {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RuntimeIcon(RuntimeIconName.Info, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("未在工程中扫描到 APK", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "若您已在终端完成编译构建，可点击右上角「重新扫描」；或在下方直接输入 APK 文件路径。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (availableApks.isEmpty() || showManualInput) {
            OutlinedTextField(
                value = selectedPath,
                onValueChange = onSelectApk,
                label = { Text("APK 路径 (绝对路径或相对路径)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

private fun formatApkSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

private fun formatApkTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
