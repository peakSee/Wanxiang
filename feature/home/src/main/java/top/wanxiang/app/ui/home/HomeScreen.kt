package top.wanxiang.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.wanxiang.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator
import top.wanxiang.app.ui.components.RuntimeFilledTonalButton as FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import top.wanxiang.app.ui.components.RuntimeIconButton as IconButton
import top.wanxiang.app.ui.components.RuntimeLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import top.wanxiang.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.wanxiang.app.ui.components.RuntimeSwitch
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import top.wanxiang.app.feature.home.R
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wanxiang.app.core.model.DoctorItem
import top.wanxiang.app.core.model.DoctorReport
import top.wanxiang.app.core.model.DoctorStatus
import top.wanxiang.app.core.model.RepairProgress
import top.wanxiang.app.core.model.RuntimeState
import top.wanxiang.app.ui.components.MainDestination
import top.wanxiang.app.ui.components.NoticeBanner
import top.wanxiang.app.ui.components.RuntimeBottomBar
import top.wanxiang.app.ui.components.liquidGlassContent
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeTopBar
import top.wanxiang.app.ui.components.RuntimeButton
import top.wanxiang.app.ui.components.distroIconFor
import top.wanxiang.app.ui.components.StatusBadge
import top.wanxiang.app.ui.components.isLiquidGlassThemeActive

/** 自动体检防抖间隔：ON_RESUME 触发时距上次不足该间隔则跳过。 */
private const val DOCTOR_CHECK_MIN_INTERVAL_MS = 30_000L

/**
 * 主题感知的健康绿 / 警告橙：替代硬编码深色，暗色主题下自动换用高亮度变体保证对比。
 */
@Composable
private fun healthyStatusColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF2E7D32)

@Composable
private fun warningStatusColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFFFB74D) else Color(0xFFE65100)

/**
 * 万象 · 运行仪表盘 (WanXiang Linux Runtime Dashboard)
 * 集成沙箱引擎状态、运行环境体检自愈中心、实时内存/存储监控与规格信息
 */
@Composable
fun HomeScreen(
    onNavigate: (MainDestination) -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenToolCenter: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastAutoDoctorCheckAt by remember { mutableLongStateOf(0L) }

    val state by viewModel.runtimeState.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val doctorReport by viewModel.doctorReport.collectAsStateWithLifecycle()
    val isCheckingDoctor by viewModel.isCheckingDoctor.collectAsStateWithLifecycle()
    val repairProgress by viewModel.repairProgress.collectAsStateWithLifecycle()
    val isRepairing by viewModel.isRepairing.collectAsStateWithLifecycle()
    val installedDistros by viewModel.installedDistros.collectAsStateWithLifecycle()
    val activeDistroId by viewModel.activeDistroId.collectAsStateWithLifecycle()
    val switchingDistro by viewModel.switchingDistro.collectAsStateWithLifecycle()
    val modeStatus by viewModel.executionModeStatus.collectAsStateWithLifecycle()
    val webChatStatus by viewModel.webChatStatus.collectAsStateWithLifecycle()
    val announcements by viewModel.announcements.collectAsStateWithLifecycle()
    val announcementLoading by viewModel.announcementLoading.collectAsStateWithLifecycle()
    val hasUnreadAnnouncement by viewModel.hasUnreadAnnouncement.collectAsStateWithLifecycle()
    val showAnnouncementPopup by viewModel.showAnnouncementPopup.collectAsStateWithLifecycle()
    var showAnnouncementSheet by remember { mutableStateOf(false) }

    val allFilesPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.runDoctorCheck()
    }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.runDoctorCheck()
    }

    val requestAllFilesAccess: () -> Unit = remember(context) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    allFilesPermissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                }.onFailure {
                    runCatching {
                        allFilesPermissionLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        )
                    }.onFailure {
                        allFilesPermissionLauncher.launch(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }
                }
            } else {
                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 防抖：间隔过短（如切 Tab / 亮灭屏）时跳过自动体检，避免每次切回都闪检查态。
                // 用户点顶栏刷新仍无条件执行。
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastAutoDoctorCheckAt > DOCTOR_CHECK_MIN_INTERVAL_MS) {
                    lastAutoDoctorCheckAt = now
                    viewModel.runDoctorCheck()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 首页可见性联动：进入时恢复指标轮询，切走（离开组合）即暂停，避免后台空转。
    DisposableEffect(Unit) {
        viewModel.onScreenVisible()
        viewModel.checkNewAnnouncements()
        onDispose { viewModel.onScreenHidden() }
    }

    val isLiquidGlassTheme = isLiquidGlassThemeActive()

    // 公告(type=2)自动弹出 或 点击顶栏公告图标
    if (showAnnouncementPopup || showAnnouncementSheet) {
        HomeAnnouncementDialog(
            announcements = announcements,
            loading = announcementLoading,
            onDismiss = {
                showAnnouncementSheet = false
                viewModel.markAnnouncementsRead()
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = stringResource(R.string.home_dashboard_title),
                statusText = "${metrics.linuxDistro} · ${metrics.cpuArch}",
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.checkNewAnnouncements()
                            viewModel.markAnnouncementsRead()
                            showAnnouncementSheet = true
                        },
                        contentDescription = "公告",
                    ) {
                        Box {
                            RuntimeIcon(name = RuntimeIconName.Mail, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (hasUnreadAnnouncement) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .align(Alignment.TopEnd)
                                        .background(MaterialTheme.colorScheme.error, androidx.compose.foundation.shape.CircleShape)
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            viewModel.refreshMetrics()
                            viewModel.runDoctorCheck()
                            viewModel.refreshExecutionModeStatus()
                        },
                        enabled = !isCheckingDoctor,
                        contentDescription = stringResource(R.string.home_refresh),
                    ) {
                        if (isCheckingDoctor) {
                            // 刷新进行中：显示加载指示并禁用，防止重复触发
                            RuntimeCircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
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
        bottomBar = {
            if (!isLiquidGlassTheme) {
                RuntimeBottomBar(MainDestination.Home, onNavigate)
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .liquidGlassContent()
                .padding(top = innerPadding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .padding(bottom = if (isLiquidGlassTheme) 104.dp else innerPadding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. 运行时引擎主状态卡片 (Status Banner)
            RuntimeEngineStatusCard(
                state = state,
                metrics = metrics,
                installedDistros = installedDistros,
                activeDistroId = activeDistroId,
                switchingDistro = switchingDistro,
                modeStatus = modeStatus,
                onSwitchDistro = viewModel::switchDistro,
                onInitialize = viewModel::initializeRuntime,
                onCancel = viewModel::cancelInitialization,
                onOpenTerminal = onOpenTerminal,
                onOpenModeSettings = { onNavigate(MainDestination.Settings) },
            )

            // 2. WebChat 电脑大屏协作卡片 (Dashboard Bridge Card)
            WebChatDashboardCard(
                status = webChatStatus,
                onToggle = viewModel::toggleWebChat,
            )

            // 3. 运行与开发环境体检自愈中心 (WanXiang Doctor & Auto-Fix)
            EnvironmentDoctorCard(
                report = doctorReport,
                isChecking = isCheckingDoctor,
                isRepairing = isRepairing,
                repairProgress = repairProgress,
                runtimeReady = state is RuntimeState.Ready,
                onRunCheck = viewModel::runDoctorCheck,
                onStartAutoRepair = {
                    if (doctorReport?.items?.any { it.id == "host_all_files_access" && it.status != DoctorStatus.HEALTHY } == true) {
                        requestAllFilesAccess()
                    }
                    viewModel.startAutoRepair()
                },
                onCancelRepair = viewModel::cancelAutoRepair,
                onRequestAllFilesAccess = requestAllFilesAccess,
                onOpenToolCenter = onOpenToolCenter,
            )

            // 3. 核心指标看板 (Live Resource Metrics Grid)
            Text(
                text = stringResource(R.string.home_system_resources),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 内存指标
                ResourceMetricCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_memory),
                    primaryValue = "${metrics.memoryUsedMb} MB",
                    secondaryValue = stringResource(R.string.home_memory_total, metrics.memoryTotalMb),
                    progress = (metrics.memoryUsagePercent / 100f).coerceIn(0f, 1f),
                    progressText = stringResource(R.string.home_memory_used, metrics.memoryUsagePercent),
                    extraInfo = stringResource(R.string.home_app_heap, metrics.appHeapUsedMb),
                    accentColor = MaterialTheme.colorScheme.primary,
                    icon = RuntimeIconName.Cpu,
                )

                // 存储指标
                ResourceMetricCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_storage),
                    primaryValue = "${metrics.storageUsedGb} GB",
                    secondaryValue = stringResource(R.string.home_storage_total, metrics.storageTotalGb),
                    progress = (metrics.storageUsagePercent / 100f).coerceIn(0f, 1f),
                    progressText = stringResource(R.string.home_storage_used, metrics.storageUsagePercent),
                    extraInfo = stringResource(R.string.home_rootfs_healthy),
                    accentColor = MaterialTheme.colorScheme.secondary,
                    icon = RuntimeIconName.Storage,
                )
            }

            // 4. 活跃任务与服务监控卡片
            ActiveTasksStatusCard(
                metrics = metrics,
                onOpenTerminal = onOpenTerminal,
            )

            // 5. 运行环境与规格详情
            SystemSpecsCard(metrics = metrics, modeStatus = modeStatus)

            Spacer(Modifier.height(16.dp))
        }
    }
}
/**
 * 运行与开发环境体检自愈卡片 (WanXiang Doctor & Auto-Fix)
 */
@Composable
private fun EnvironmentDoctorCard(
    report: DoctorReport?,
    isChecking: Boolean,
    isRepairing: Boolean,
    repairProgress: RepairProgress?,
    runtimeReady: Boolean,
    onRunCheck: () -> Unit,
    onStartAutoRepair: () -> Unit,
    onCancelRepair: () -> Unit,
    onRequestAllFilesAccess: () -> Unit = {},
    onOpenToolCenter: () -> Unit = {},
) {
    var isCardExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedDetails by rememberSaveable { mutableStateOf(false) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    // 仅在实际触发自愈修复时自动展开
    LaunchedEffect(isRepairing) {
        if (isRepairing) isCardExpanded = true
    }

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { isCardExpanded = !isCardExpanded },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (report?.isAllHealthy == true) healthyStatusColor().copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.primaryContainer,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        RuntimeIcon(
                            name = if (report?.isAllHealthy == true) RuntimeIconName.Check else RuntimeIconName.Shield,
                            modifier = Modifier.size(20.dp),
                            tint = if (report?.isAllHealthy == true) healthyStatusColor() else MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.home_doctor_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = when {
                                isRepairing -> stringResource(R.string.home_doctor_repairing)
                                isChecking -> stringResource(R.string.home_doctor_checking)
                                report == null -> stringResource(if (runtimeReady) R.string.home_doctor_ready else R.string.home_waiting_sandbox)
                                report.isAllHealthy -> stringResource(R.string.home_development_ready)
                                report.needsFix -> stringResource(R.string.home_doctor_pending, report.warningCount + report.errorCount)
                                else -> stringResource(R.string.home_doctor_complete)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (report?.needsFix == true && !isCardExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isChecking) {
                        RuntimeCircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else if (runtimeReady) {
                        IconButton(
                            onClick = { onRunCheck() },
                            enabled = !isRepairing,
                            modifier = Modifier.size(32.dp),
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Refresh,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    RuntimeIcon(
                        name = RuntimeIconName.ChevronDown,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(if (isCardExpanded) 180f else 0f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }

            // 展开区域：修复进度与体检指标详情
            AnimatedVisibility(
                visible = isCardExpanded || isRepairing,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 修复中状态展示
                    if (isRepairing && repairProgress != null) {
                        RuntimeCard(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.home_repair_step, repairProgress.stepIndex, repairProgress.totalSteps, repairProgress.stepTitle),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${(repairProgress.progress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 8.dp),
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }

                                RuntimeLinearProgressIndicator(
                                    progress = { repairProgress.progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        onClick = { showLogs = !showLogs },
                                        contentPadding = PaddingValues(horizontal = 4.dp),
                                    ) {
                                        Text(
                                            text = if (showLogs) stringResource(R.string.home_hide_logs) else stringResource(R.string.home_view_live_logs, repairProgress.logs.size),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }

                                    TextButton(
                                        onClick = onCancelRepair,
                                        contentPadding = PaddingValues(horizontal = 4.dp),
                                    ) {
                                        Text(stringResource(R.string.home_cancel_repair), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }

                                AnimatedVisibility(visible = showLogs) {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 140.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp)
                                                .verticalScroll(rememberScrollState()),
                                        ) {
                                            repairProgress.logs.takeLast(20).forEach { logLine ->
                                                Text(
                                                    text = logLine,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp,
                                                    ),
                                                    color = if (logLine.startsWith("ERR:")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 体检报告列表展示
                    if (report != null && !isRepairing) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val displayedItems = if (expandedDetails || report.needsFix) report.items else report.items.take(3)
                            displayedItems.forEach { item ->
                                DoctorItemRow(
                                    item = item,
                                    onRequestAllFilesAccess = onRequestAllFilesAccess,
                                )
                            }

                            if (report.items.size > 3) {
                                TextButton(
                                    onClick = { expandedDetails = !expandedDetails },
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                ) {
                                    Text(
                                        text = if (expandedDetails) stringResource(R.string.home_hide_details) else stringResource(R.string.home_view_checks, report.items.size),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }

                            // Android 环境未安装：展示离线包 / 在线插件两条获取路径
                            val missingAndroidEnv = report.items.any {
                                it.id == "android_environment" && it.status != DoctorStatus.HEALTHY
                            }
                            if (missingAndroidEnv) {
                                AndroidEnvAcquisitionCard(
                                    onJoinQqGroup = { joinQqGroup(context) },
                                    onOpenToolCenter = onOpenToolCenter,
                                )
                            }
                        }

                        // 一键修复按钮或就绪横幅
                        if (report.needsFix) {
                            RuntimeButton(
                                onClick = onStartAutoRepair,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 12.dp),
                            ) {
                                RuntimeIcon(
                                    name = RuntimeIconName.Play,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.home_repair),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        } else if (report.isAllHealthy) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(healthyStatusColor().copy(alpha = 0.1f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RuntimeIcon(
                                    name = RuntimeIconName.Check,
                                    modifier = Modifier.size(16.dp),
                                    tint = healthyStatusColor(),
                                )
                                Text(
                                    text = stringResource(R.string.home_environment_ready_description),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = healthyStatusColor(),
                                )
                            }
                        }
                    } else if (!runtimeReady) {
                        Text(
                            text = stringResource(R.string.home_initialize_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
/**
 * 体检条目单行展示
 */
@Composable
private fun DoctorItemRow(
    item: DoctorItem,
    onRequestAllFilesAccess: () -> Unit = {},
) {
    val isAllFilesIssue = item.id == "host_all_files_access" && item.status != DoctorStatus.HEALTHY
    val statusColor = when (item.status) {
        DoctorStatus.HEALTHY -> healthyStatusColor()
        DoctorStatus.WARNING -> warningStatusColor()
        DoctorStatus.ERROR -> MaterialTheme.colorScheme.error
        DoctorStatus.CHECKING -> MaterialTheme.colorScheme.tertiary
    }

    val statusIcon = when (item.status) {
        DoctorStatus.HEALTHY -> RuntimeIconName.Check
        DoctorStatus.WARNING -> RuntimeIconName.Alert
        DoctorStatus.ERROR -> RuntimeIconName.Alert
        DoctorStatus.CHECKING -> RuntimeIconName.Refresh
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(
                if (isAllFilesIssue) Modifier.clickable { onRequestAllFilesAccess() }
                else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                RuntimeIcon(
                    name = statusIcon,
                    modifier = Modifier.size(14.dp),
                    tint = statusColor,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "· ${item.category.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status != DoctorStatus.HEALTHY) statusColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val detail = item.detail
                if (!detail.isNullOrBlank() && item.status != DoctorStatus.HEALTHY) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (isAllFilesIssue) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onRequestAllFilesAccess,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text(stringResource(R.string.home_grant_access), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}

/**
 * Android 环境未安装引导卡：提供「QQ 群全量离线插件包」与「插件中心在线安装」两条获取路径。
 */
@Composable
private fun AndroidEnvAcquisitionCard(
    onJoinQqGroup: () -> Unit,
    onOpenToolCenter: () -> Unit,
) {
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    RuntimeIcon(
                        name = RuntimeIconName.Android,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.home_android_env_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.home_android_env_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RuntimeButton(
                    onClick = onJoinQqGroup,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    RuntimeIcon(RuntimeIconName.Qq, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_android_env_qq),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalButton(
                    onClick = onOpenToolCenter,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    RuntimeIcon(RuntimeIconName.Extension, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_android_env_tool_center),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private const val WANXIANG_QQ_GROUP_ID = "905971993"

/** 跳转 QQ 加群；未安装 QQ 时兜底复制群号并提示。 */
private fun joinQqGroup(context: Context) {
    val uri = Uri.parse(
        "https://qm.qq.com/q/mWMli9UriE",
    )
    val intent = Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.home_qq_clipboard_label), WANXIANG_QQ_GROUP_ID),
        )
        Toast.makeText(context, context.getString(R.string.home_qq_copied, WANXIANG_QQ_GROUP_ID), Toast.LENGTH_LONG).show()
    }
}

/**
 * 运行时引擎主状态卡片
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RuntimeEngineStatusCard(
    state: RuntimeState,
    metrics: SystemResourceMetrics,
    installedDistros: List<top.wanxiang.app.core.model.InstalledDistro> = emptyList(),
    activeDistroId: String = "ubuntu",
    switchingDistro: Boolean = false,
    modeStatus: ExecutionModeStatus = ExecutionModeStatus(),
    onSwitchDistro: (String) -> Unit = {},
    onInitialize: () -> Unit,
    onCancel: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenModeSettings: () -> Unit = {},
) {
    val ready = state is RuntimeState.Ready
    val error = state is RuntimeState.Error
    val initializing = state as? RuntimeState.Initializing

    val statusColor = when {
        ready -> healthyStatusColor()
        error -> MaterialTheme.colorScheme.error
        initializing != null -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = PaddingValues(18.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    PulsingStatusDot(color = statusColor, isPulsing = ready || initializing != null)
                    Column {
                        Text(
                            text = when {
                                ready -> stringResource(R.string.home_runtime_ready)
                                error -> stringResource(R.string.home_runtime_error)
                                initializing != null -> stringResource(R.string.home_runtime_initializing)
                                else -> stringResource(R.string.home_runtime_uninitialized)
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${metrics.linuxDistro} · ${metrics.engineVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 右侧展示当前发行版官方精确 Logo 徽章
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    RuntimeIcon(
                        name = distroIconFor(activeDistroId),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // 当前激活的运行特权模式徽章（点击跳转设置切换）
            ExecutionModeBadge(
                modeStatus = modeStatus,
                onClick = onOpenModeSettings,
            )

            // 多系统快速切换 Chips（安装了 2 套及以上时展示，自动换行，避免横向滚动）
            if (ready && installedDistros.size > 1) {
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.home_switch_system),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 2.dp)
                            .align(androidx.compose.ui.Alignment.CenterVertically),
                    )
                    if (switchingDistro) {
                        RuntimeCircularProgressIndicator(
                            modifier = Modifier
                                .size(12.dp)
                                .align(androidx.compose.ui.Alignment.CenterVertically),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    installedDistros.forEach { d ->
                        val isSelected = d.id.equals(activeDistroId, ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent,
                            ),
                            modifier = Modifier.clickable(enabled = !switchingDistro) {
                                if (!isSelected) onSwitchDistro(d.id)
                            },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                RuntimeIcon(distroIconFor(d.id), Modifier.size(13.dp))
                                Text(
                                    text = if (isSelected && switchingDistro) stringResource(R.string.home_switching) else d.displayName,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (initializing != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuntimeLinearProgressIndicator(
                        progress = { initializing.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = initializing.step,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = onCancel,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(stringResource(R.string.home_cancel), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (state is RuntimeState.Error) {
                val context = LocalContext.current
                val errMsg = state.throwable.message ?: stringResource(R.string.home_runtime_error_fallback)
                NoticeBanner(
                    text = errMsg,
                    isError = true,
                    onCopy = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("WanXiang Error", errMsg))
                        Toast.makeText(context, "错误信息已复制", Toast.LENGTH_SHORT).show()
                    },
                )
            }

            // 快捷控制操作区
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ready) {
                    RuntimeButton(
                        onClick = onOpenTerminal,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Terminal,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_open_console), fontWeight = FontWeight.SemiBold)
                    }
                } else if (initializing == null) {
                    RuntimeButton(
                        onClick = onInitialize,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Play,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (error) R.string.home_retry_initialization else R.string.home_initialize_now), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * 当前激活的运行特权模式徽章：展示模式短标签与授权生效状态，点击跳转设置页切换。
 */
@Composable
private fun ExecutionModeBadge(
    modeStatus: ExecutionModeStatus,
    onClick: () -> Unit,
) {
    // 状态色：生效=绿色 / 探测中=主题色 / 未生效=琥珀警示
    val statusColor = when {
        modeStatus.checking -> MaterialTheme.colorScheme.primary
        modeStatus.active -> healthyStatusColor()
        else -> warningStatusColor()
    }
    val statusText = when {
        modeStatus.checking -> stringResource(R.string.home_mode_checking)
        modeStatus.degraded -> stringResource(R.string.home_mode_degraded)
        modeStatus.active -> stringResource(R.string.home_mode_active)
        else -> stringResource(R.string.home_mode_inactive)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(statusColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                RuntimeIcon(
                    name = RuntimeIconName.Shield,
                    modifier = Modifier.size(16.dp),
                    tint = statusColor,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_current_mode, modeStatus.mode.shortLabel),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (modeStatus.degraded) {
                        stringResource(R.string.home_mode_degraded_reason, modeStatus.preferredMode.shortLabel, modeStatus.reason)
                    } else {
                        modeStatus.mode.summary
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (modeStatus.checking) {
                RuntimeCircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = statusColor,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = statusColor,
            )
        }
    }
}

/**
 * 资源监控指标卡片
 */
@Composable
private fun ResourceMetricCard(
    modifier: Modifier = Modifier,
    title: String,
    primaryValue: String,
    secondaryValue: String,
    progress: Float,
    progressText: String,
    extraInfo: String,
    accentColor: Color,
    icon: RuntimeIconName,
) {
    val effectiveAccent = when {
        progress >= 0.9f -> MaterialTheme.colorScheme.error
        progress >= 0.8f -> MaterialTheme.colorScheme.tertiary
        else -> accentColor
    }
    RuntimeCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RuntimeIcon(
                    name = icon,
                    modifier = Modifier.size(16.dp),
                    tint = effectiveAccent,
                )
            }

            Text(
                text = primaryValue,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            RuntimeLinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = effectiveAccent,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )

            Text(
                text = "$progressText · $secondaryValue",
                style = MaterialTheme.typography.labelSmall,
                color = if (progress >= 0.8f) effectiveAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )

            Text(
                text = if (progress >= 0.9f) stringResource(R.string.home_cleanup_recommended, extraInfo) else extraInfo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 活跃任务与后台服务卡片
 */
@Composable
private fun ActiveTasksStatusCard(
    metrics: SystemResourceMetrics,
    onOpenTerminal: () -> Unit,
) {
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(16.dp),
    ) {
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
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    RuntimeIcon(
                        name = RuntimeIconName.List,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.home_active_processes, metrics.activeProcessCount),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.home_uptime, metrics.uptimeFormatted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            RuntimeButton(
                onClick = onOpenTerminal,
                tonal = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(stringResource(R.string.home_view), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * 宿主与运行环境规格卡片
 */
@Composable
private fun SystemSpecsCard(metrics: SystemResourceMetrics, modeStatus: ExecutionModeStatus) {
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.home_environment_details),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            SpecRow(label = stringResource(R.string.home_cpu_architecture), value = metrics.cpuArch)
            SpecRow(label = stringResource(R.string.home_host_os), value = metrics.hostAndroidVersion)
            SpecRow(label = stringResource(R.string.home_runtime_engine), value = metrics.engineVersion)
            SpecRow(label = stringResource(R.string.home_guest_os), value = metrics.linuxDistro)
            SpecRow(
                label = stringResource(R.string.home_privilege_mode),
                value = modeStatus.mode.shortLabel +
                    if (modeStatus.active || modeStatus.checking) "" else " · ${stringResource(R.string.home_mode_inactive)}",
            )
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(0.58f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 状态呼吸灯圆点
 */
@Composable
private fun PulsingStatusDot(color: Color, isPulsing: Boolean) {
    // 仅在需要脉冲时才创建无限动画，非脉冲状态不驱动每帧重组
    val alpha by if (isPulsing) {
        val transition = rememberInfiniteTransition(label = "status_dot_pulse")
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse_alpha",
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    }

    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * WebChat 电脑大屏协作卡片 (Dashboard Bridge Card)
 */
@Composable
private fun WebChatDashboardCard(
    status: top.wanxiang.app.runtime.webchat.WebChatServerStatus,
    onToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val directUrl = "${status.accessUrl}?token=${status.pinCode}"

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (status.isRunning) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f) else MaterialTheme.colorScheme.surfaceContainer,
        borderColor = if (status.isRunning) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else Color.Transparent,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(
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
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (status.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Globe,
                            modifier = Modifier.size(20.dp),
                            tint = if (status.isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.home_webchat_title),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (status.isRunning) stringResource(R.string.home_webchat_running) else stringResource(R.string.home_webchat_idle),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                RuntimeSwitch(
                    checked = status.isRunning,
                    onCheckedChange = onToggle,
                )
            }

            if (status.isRunning) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // 访问地址独立成行：统一 Monospace 等宽字体、颜色与大小
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = stringResource(R.string.home_webchat_url_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = status.accessUrl,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 配对码独立成行显示
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = stringResource(R.string.home_webchat_pin_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = status.pinCode,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp,
                        ),
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }

                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("WebChat Direct URL", directUrl))
                        Toast.makeText(context, context.getString(R.string.home_webchat_copied), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                ) {
                    RuntimeIcon(RuntimeIconName.Copy, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.home_webchat_copy), fontWeight = FontWeight.SemiBold)
                }

                Text(
                    text = stringResource(R.string.home_webchat_hint, status.activeConnections),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.home_webchat_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

