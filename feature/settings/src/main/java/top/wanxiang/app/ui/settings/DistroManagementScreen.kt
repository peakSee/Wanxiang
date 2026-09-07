package top.wanxiang.app.ui.settings

import top.wanxiang.app.ui.components.RuntimeAlertDialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import top.wanxiang.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import top.wanxiang.app.ui.components.RuntimeIconButton as IconButton
import top.wanxiang.app.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import top.wanxiang.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import top.wanxiang.app.ui.components.RuntimeRadioButton as RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import top.wanxiang.app.ui.settings.LocalizedText as Text
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.launch
import top.wanxiang.app.core.model.InstalledDistro
import top.wanxiang.app.core.model.RuntimeState
import top.wanxiang.app.runtime.DistributionCatalog
import top.wanxiang.app.runtime.DistributionSpec
import top.wanxiang.app.runtime.RegistryRoute
import top.wanxiang.app.runtime.RuntimeInstallRequest
import top.wanxiang.app.ui.components.NoticeBanner
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeTopBar
import top.wanxiang.app.ui.components.StatusBadge
import top.wanxiang.app.ui.components.distroIconFor

/**
 * 万象 · Linux 发行版与沙箱多实例管理 (Distro Management Hub)
 */
@Composable
fun DistroManagementScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val installedDistros by viewModel.installedDistros.collectAsStateWithLifecycle()
    val activeDistroId by viewModel.activeDistroId.collectAsStateWithLifecycle()
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val restoringDistroId by viewModel.restoringDistroId.collectAsStateWithLifecycle()
    val deletingDistroId by viewModel.deletingDistroId.collectAsStateWithLifecycle()

    // 安装进度保存在 VM（StateFlow），旋转后进度界面可恢复，安装在后台继续进行
    val installState by viewModel.distroInstallState.collectAsStateWithLifecycle()
    var showInstallDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var distroToReset by remember { mutableStateOf<InstalledDistro?>(null) }
    var distroToUninstall by remember { mutableStateOf<InstalledDistro?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val scope = rememberCoroutineScope()

    // 安装成功（isInstalling 从 true -> false 且无错误）后自动关闭弹窗
    var wasInstalling by remember { mutableStateOf(false) }
    LaunchedEffect(installState.isInstalling) {
        if (wasInstalling && !installState.isInstalling && installState.errorMessage == null) {
            showInstallDialog = false
        }
        wasInstalling = installState.isInstalling
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            RuntimeTopBar(
                title = "Linux 发行版管理",
                statusText = "多沙箱并存与动态切换",
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
                    text = "万象支持多套 Linux 系统并存。所有系统均自动挂载 /workspace 代码工程，/sdcard 外部存储按「存储挂载与共享」页的开关注入，各发行版软件生态与包管理器完全独立隔离。",
                )
            }

            // 1. 已安装系统列表
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已安装沙箱 (${installedDistros.size})",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Button(
                        onClick = { showInstallDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Plus,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "安装新系统", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            items(installedDistros, key = { it.id }) { distro ->
                DistroItemCard(
                    distro = distro,
                    isActive = distro.id.equals(activeDistroId, ignoreCase = true),
                    isRestoring = distro.id.equals(restoringDistroId, ignoreCase = true),
                    isDeleting = distro.id.equals(deletingDistroId, ignoreCase = true),
                    canOperateMulti = installedDistros.size > 1,
                    onSwitchActive = {
                        viewModel.switchActiveDistro(distro.id)
                    },
                    onReset = {
                        distroToReset = distro
                    },
                    onUninstall = {
                        distroToUninstall = distro
                    },
                )
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // 安装新系统对话框（旋转后只要安装仍在进行就会重新显示进度）
    if (showInstallDialog || installState.isInstalling) {
        InstallDistroDialog(
            installedIds = installedDistros.map { it.id }.toSet(),
            isInstalling = installState.isInstalling,
            progressText = installState.progressText,
            progressFraction = installState.progressFraction,
            errorMessage = installState.errorMessage,
            onDismiss = {
                if (!installState.isInstalling) {
                    showInstallDialog = false
                    viewModel.clearDistroInstallError()
                }
            },
            onConfirmInstall = { spec, route ->
                viewModel.installDistro(
                    request = RuntimeInstallRequest(
                        distributionId = spec.id,
                        registryRoute = route,
                    ),
                )
            },
        )
    }

    // 重置沙箱确认对话框（带 3 秒倒计时锁）
    distroToReset?.let { target ->
        ResetDistroConfirmDialog(
            distro = target,
            onConfirm = {
                val toReset = target.id
                distroToReset = null
                viewModel.resetDistro(toReset) { success, msg ->
                    scope.launch {
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            },
            onDismiss = { distroToReset = null },
        )
    }

    // 删除系统确认对话框（带 3 秒倒计时锁）
    distroToUninstall?.let { target ->
        DeleteDistroConfirmDialog(
            distro = target,
            onConfirm = {
                val toDelete = target.id
                distroToUninstall = null
                viewModel.uninstallDistro(toDelete) { success, msg ->
                    scope.launch {
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            },
            onDismiss = { distroToUninstall = null },
        )
    }
}

@Composable
private fun ResetDistroConfirmDialog(
    distro: InstalledDistro,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var countdown by remember { mutableStateOf(3) }

    LaunchedEffect(distro.id) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "重置 ${distro.displayName} 沙箱？",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "• 将清除自行安装的软件包与系统修改",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "• 不会删除 /workspace 中的项目代码",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = countdown == 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                ),
            ) {
                Text(
                    text = if (countdown > 0) "确认重置 (${countdown}s)" else "确认重置",
                    color = if (countdown == 0) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onError.copy(alpha = 0.6f),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        },
    )
}

@Composable
private fun DeleteDistroConfirmDialog(
    distro: InstalledDistro,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var countdown by remember { mutableStateOf(3) }

    LaunchedEffect(distro.id) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "删除 ${distro.displayName} 系统？",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "• 将彻底清除该 Linux 发行版与已安装软件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "• 不会删除 /workspace 中的项目代码",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = countdown == 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                ),
            ) {
                Text(
                    text = if (countdown > 0) "确认删除 (${countdown}s)" else "确认删除",
                    color = if (countdown == 0) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onError.copy(alpha = 0.6f),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        },
    )
}

@Composable
private fun DistroItemCard(
    distro: InstalledDistro,
    isActive: Boolean,
    isRestoring: Boolean,
    isDeleting: Boolean,
    canOperateMulti: Boolean,
    onSwitchActive: () -> Unit,
    onReset: () -> Unit,
    onUninstall: () -> Unit,
) {
    RuntimeCard(
        borderColor = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isActive) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        RuntimeIcon(
                            name = distroIconFor(distro.id),
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = distro.displayName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "标识: ${distro.id} · 包管理: ${distro.packageManager} · 占用: ${formatSize(distro.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (isActive) {
                    StatusBadge(text = "主系统", color = successStatusColor())
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 按钮 1: 设为主系统 (当前为主系统或仅有 1 个系统时置灰禁用)
                OutlinedButton(
                    onClick = onSwitchActive,
                    enabled = !isActive && canOperateMulti && !isRestoring && !isDeleting,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "设为主系统",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }

                // 按钮 2: 重置沙箱 (执行中显示正在还原...并锁死)
                OutlinedButton(
                    onClick = onReset,
                    enabled = !isRestoring && !isDeleting,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (isRestoring) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "正在还原...", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    } else {
                        Text(text = "重置沙箱", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }

                // 按钮 3: 删除系统 (仅有 1 个系统时置灰禁用，执行中显示正在删除...并锁死)
                OutlinedButton(
                    onClick = onUninstall,
                    enabled = canOperateMulti && !isRestoring && !isDeleting,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(
                        1.dp,
                        if (canOperateMulti && !isRestoring && !isDeleting) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "正在删除...", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    } else {
                        Text(
                            text = "删除系统",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }

            // 单系统时「设为主系统」按钮会置灰，补充解释避免用户困惑
            if (!isActive && !canOperateMulti) {
                Text(
                    text = "当前仅有一套系统，无需切换主系统",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InstallDistroDialog(
    installedIds: Set<String>,
    isInstalling: Boolean,
    progressText: String?,
    progressFraction: Float,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirmInstall: (DistributionSpec, RegistryRoute) -> Unit,
) {
    val availableDistros = remember {
        DistributionCatalog.supported.filter { it.id !in installedIds }
    }
    var selectedSpec by remember {
        mutableStateOf(availableDistros.firstOrNull() ?: DistributionCatalog.supported.first())
    }
    var selectedRoute by remember { mutableStateOf(RegistryRoute.AUTO) }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isInstalling) "正在安装 Linux 沙箱..." else "安装新 Linux 发行版",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isInstalling) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { progressFraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        )
                        Text(
                            text = progressText ?: "正在下载并配置 RootFS...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    if (availableDistros.isEmpty()) {
                        Text(text = "所有预置 Linux 发行版均已安装完毕！")
                    } else {
                        Text(
                            text = "选择要下载并安装的 Linux 系统：",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        availableDistros.forEach { spec ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedSpec = spec }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedSpec.id == spec.id,
                                    onClick = { selectedSpec = spec },
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                RuntimeIcon(
                                    name = distroIconFor(spec.id),
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = spec.displayWithVersion,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    )
                                    Text(
                                        text = spec.imageReference,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "下载线路与镜像加速：",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { selectedRoute = RegistryRoute.AUTO },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = if (selectedRoute == RegistryRoute.AUTO) {
                                    ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                } else ButtonDefaults.outlinedButtonColors(),
                            ) {
                                Text("自动加速", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { selectedRoute = RegistryRoute.CHINA_ACCELERATED },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = if (selectedRoute == RegistryRoute.CHINA_ACCELERATED) {
                                    ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                } else ButtonDefaults.outlinedButtonColors(),
                            ) {
                                Text("国内镜像", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    errorMessage?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isInstalling && availableDistros.isNotEmpty()) {
                Button(
                    onClick = { onConfirmInstall(selectedSpec, selectedRoute) },
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(text = "开始安装")
                }
            }
        },
        dismissButton = {
            if (!isInstalling) {
                TextButton(onClick = onDismiss) {
                    Text(text = "取消")
                }
            }
        },
    )
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb >= 1024) {
        String.format("%.2f GB", mb / 1024)
    } else {
        String.format("%.1f MB", mb)
    }
}
