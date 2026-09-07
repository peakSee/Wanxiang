package top.wanxiang.app.ui.settings

import top.wanxiang.app.ui.components.RuntimeAlertDialog
import androidx.compose.material3.minimumInteractiveComponentSize

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import top.wanxiang.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import top.wanxiang.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import top.wanxiang.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import top.wanxiang.app.ui.components.RuntimeSwitch as Switch
import androidx.compose.material3.SwitchDefaults
import top.wanxiang.app.ui.settings.LocalizedText as Text
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import top.wanxiang.app.core.model.StorageMountBinding
import top.wanxiang.app.ui.components.NoticeBanner
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeTopBar

/**
 * 宿主与沙箱存储挂载管理 (Storage Mounts & Bindings)
 * 基于 PRoot -b 机制，实现 Android 宿主目录与 Linux 容器内的无缝双向访问
 */
@Composable
fun StorageMountSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val downloadEnabled by viewModel.mountDownloadEnabled.collectAsStateWithLifecycle()
    val documentsEnabled by viewModel.mountDocumentsEnabled.collectAsStateWithLifecycle()
    val sharedStorageEnabled by viewModel.mountSharedStorageEnabled.collectAsStateWithLifecycle()
    val customBindings by viewModel.customMountBindings.collectAsStateWithLifecycle()

    var showAddDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // 待确认删除的挂载点 id（破坏性操作二次确认）
    var pendingDeleteBindingId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val pendingDeleteBinding = pendingDeleteBindingId?.let { id -> customBindings.firstOrNull { it.id == id } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = "存储挂载与共享",
                statusText = "PRoot 宿主存储映射 (-b)",
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                NoticeBanner(
                    text = "挂载仅作用于 Linux 沙箱内的进程（终端、智枢 Agent、构建任务与后台服务），不影响文件浏览器——文件浏览器始终直接访问宿主存储。挂载在会话启动时注入，修改后新建的终端 / 构建任务才会应用。完整读写还需在系统设置中授予「所有文件访问」权限。",
                )
            }

            // 1. 系统预置快捷挂载
            item {
                Text(
                    text = "系统预设快捷挂载",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Column {
                        MountToggleRow(
                            icon = RuntimeIconName.FolderDownload,
                            title = "下载目录 (Download)",
                            hostPath = "/storage/emulated/0/Download",
                            guestPath = "/sdcard/Download",
                            checked = downloadEnabled,
                            onCheckedChange = viewModel::setMountDownloadEnabled,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        MountToggleRow(
                            icon = RuntimeIconName.Document,
                            title = "文档目录 (Documents)",
                            hostPath = "/storage/emulated/0/Documents",
                            guestPath = "/sdcard/Documents",
                            checked = documentsEnabled,
                            onCheckedChange = viewModel::setMountDocumentsEnabled,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        MountToggleRow(
                            icon = RuntimeIconName.SdCard,
                            title = "完整共享存储 (/sdcard)",
                            hostPath = "/storage/emulated/0",
                            guestPath = "/sdcard",
                            checked = sharedStorageEnabled,
                            onCheckedChange = viewModel::setMountSharedStorageEnabled,
                        )
                    }
                }
            }

            // 2. 自定义映射绑定
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "自定义映射绑定",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Plus, Modifier.size(14.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("新增挂载", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (customBindings.isEmpty()) {
                item {
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
                            Text(
                                "暂无自定义挂载点",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "点击上方“新增挂载”将 Android 目录映射进 Linux 沙箱（仅终端 / 智枢 / 构建可见）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(customBindings, key = { it.id }) { binding ->
                    RuntimeCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = binding.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "${binding.hostPath} ➔ ${binding.guestPath}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Switch(
                                checked = binding.enabled,
                                onCheckedChange = { enabled ->
                                    viewModel.toggleCustomMountBinding(binding.id, enabled)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                ),
                            )

                            IconButton(
                                onClick = { pendingDeleteBindingId = binding.id },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(32.dp),
                                contentDescription = "删除挂载点 ${binding.name}",
                            ) {
                                RuntimeIcon(
                                    name = RuntimeIconName.Trash,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDeleteBinding?.let { binding ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingDeleteBindingId = null },
            title = { Text("删除挂载点", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要删除挂载点「${binding.name}」（${binding.hostPath} ➔ ${binding.guestPath}）吗？删除后新建的终端 / 构建任务将不再映射该目录。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeCustomMountBinding(binding.id)
                        pendingDeleteBindingId = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteBindingId = null }) { Text("取消") }
            },
        )
    }

    if (showAddDialog) {
        AddMountDialog(
            existingGuestPaths = customBindings.map { it.guestPath }.toSet(),
            onDismiss = { showAddDialog = false },
            onConfirm = { name, host, guest ->
                viewModel.addCustomMountBinding(name, host, guest)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun MountToggleRow(
    icon: RuntimeIconName,
    title: String,
    hostPath: String,
    guestPath: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            RuntimeIcon(icon, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "$hostPath ➔ $guestPath",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun AddMountDialog(
    existingGuestPaths: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, hostPath: String, guestPath: String) -> Unit,
) {
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var hostPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("/storage/emulated/0/") }
    var guestPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("/mnt/") }

    val nameValid = name.isNotBlank()
    val hostPathValid = hostPath.startsWith("/")
    val guestDuplicate = guestPath.trim() in existingGuestPaths
    val guestPathValid = guestPath.startsWith("/") && !guestDuplicate

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增存储挂载点", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("挂载点名称") },
                    placeholder = { Text("例如：相册照片、项目源码") },
                    isError = !nameValid,
                    supportingText = { if (!nameValid) Text("请输入挂载点名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = hostPath,
                    onValueChange = { hostPath = it },
                    label = { Text("宿主路径 (Android)") },
                    placeholder = { Text("/storage/emulated/0/...") },
                    isError = hostPath.isNotBlank() && !hostPathValid,
                    supportingText = {
                        if (hostPath.isNotBlank() && !hostPathValid) Text("路径必须以 / 开头")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = guestPath,
                    onValueChange = { guestPath = it },
                    label = { Text("容器挂载路径 (Linux)") },
                    placeholder = { Text("/mnt/my_folder") },
                    isError = guestPath.isNotBlank() && !guestPathValid,
                    supportingText = {
                        when {
                            guestPath.isNotBlank() && !guestPath.startsWith("/") -> Text("路径必须以 / 开头")
                            guestDuplicate -> Text("该容器路径已被其他挂载点使用")
                            else -> {}
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, hostPath, guestPath) },
                enabled = nameValid && hostPathValid && guestPathValid,
            ) {
                Text("添加挂载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
