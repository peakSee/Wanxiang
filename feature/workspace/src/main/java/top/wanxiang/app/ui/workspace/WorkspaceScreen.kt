package top.wanxiang.app.ui.workspace

import top.wanxiang.app.ui.components.RuntimeAlertDialog

import android.Manifest
import android.graphics.BitmapFactory
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.wanxiang.app.ui.components.RuntimeButton as Button
import top.wanxiang.app.ui.components.RuntimeFilledTonalButton as FilledTonalButton
import top.wanxiang.app.ui.components.RuntimeCheckbox as Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import top.wanxiang.app.ui.components.RuntimeIconButton as IconButton
import top.wanxiang.app.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import top.wanxiang.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import top.wanxiang.app.feature.workspace.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wanxiang.app.runtime.ApkImportSource
import top.wanxiang.app.runtime.GitTransport
import top.wanxiang.app.runtime.ProjectArchiveSource
import top.wanxiang.app.runtime.ProjectType
import top.wanxiang.app.runtime.WorkspaceProject
import top.wanxiang.app.ui.components.EmptyPanel
import top.wanxiang.app.ui.components.IconTile
import top.wanxiang.app.ui.components.MainDestination
import top.wanxiang.app.ui.components.NoticeBanner
import top.wanxiang.app.ui.components.RuntimeBottomBar
import top.wanxiang.app.ui.components.liquidGlassContent
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.SpotlightGuideOverlay
import top.wanxiang.app.ui.components.rememberSpotlightAnchor
import top.wanxiang.app.ui.components.spotlightAnchor
import top.wanxiang.app.ui.components.RuntimeTopBar
import top.wanxiang.app.ui.components.SectionHeader
import top.wanxiang.app.ui.theme.LocalLiquidGlassBackdrop
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.flow.Flow
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import top.wanxiang.app.runtime.build.StepDuration
import top.wanxiang.app.template.TemplateProjectType
import top.wanxiang.app.template.InstalledProjectTemplate

private enum class ProjectImportMode { LOCAL, GITHUB }
private enum class CreateProjectStep { PROJECT_TYPE, EMPTY_DETAILS, TEMPLATE, DETAILS }

@Composable
private fun TemplatePreviewImage(file: java.io.File, modifier: Modifier = Modifier) {
    val bitmap = remember(file.absolutePath, file.lastModified()) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

/**
 * 万象 · 工坊空间 (Workspace Space)
 * 管理 Linux 隔离工作区、代码工程与文件项目
 */
/** 骨架屏：shimmer 流动高光 + 卡片占位 */
@Composable
private fun shimmerBrush(): androidx.compose.ui.graphics.Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.3f),
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
    )
    return androidx.compose.ui.graphics.Brush.linearGradient(
        colors = shimmerColors,
        start = androidx.compose.ui.geometry.Offset(translateAnim - 200f, 0f),
        end = androidx.compose.ui.geometry.Offset(translateAnim, 0f),
    )
}
@Composable
private fun ProjectCardSkeleton(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()
    RuntimeCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(brush),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(brush),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(brush),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(brush),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(brush),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(brush),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun WorkspaceScreen(
    onNavigate: (MainDestination) -> Unit,
    onOpenExplorer: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onOpenToolCenter: () -> Unit = {},
    onOpenWorkshopSettings: () -> Unit = {},
    onOpenWorkflows: (String) -> Unit = {},
    viewModel: WorkspaceViewModel = hiltViewModel(),
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val runtimeReady by viewModel.runtimeReady.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val messageIsError by viewModel.messageIsError.collectAsStateWithLifecycle()
    val buildProgress by viewModel.buildProgress.collectAsStateWithLifecycle()
    val activeBuildingProjectName by viewModel.activeBuildingProjectName.collectAsStateWithLifecycle()
    val isBuildDialogVisible by viewModel.isBuildDialogVisible.collectAsStateWithLifecycle()
    val installedComponentIds by viewModel.installedComponentIds.collectAsStateWithLifecycle()
    val keystores by viewModel.keystores.collectAsStateWithLifecycle()
    val loadingProjects by viewModel.loadingProjects.collectAsStateWithLifecycle()
    val githubImportProgress by viewModel.githubImportProgress.collectAsStateWithLifecycle()
    val projectTemplates by viewModel.projectTemplates.collectAsStateWithLifecycle()
    val templateScriptPreview by viewModel.templateScriptPreview.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 弹窗开关与向导状态用 rememberSaveable，旋转后不丢失向导进度（S3）
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var showTemplateManager by rememberSaveable { mutableStateOf(false) }
    var showTemplateSpec by rememberSaveable { mutableStateOf(false) }
    var actionsExpanded by rememberSaveable { mutableStateOf(false) }

    // 首次进入引导：高亮右上角「更多」菜单（内含插件中心与工坊设置入口）
    val firstUseGuidesShown by viewModel.firstUseGuidesShown.collectAsStateWithLifecycle()
    val moreAnchor = rememberSpotlightAnchor()
    var selectedTemplate by rememberSaveable { mutableStateOf(top.wanxiang.app.runtime.ProjectTemplate.EMPTY) }
    var createProjectStep by rememberSaveable { mutableStateOf(CreateProjectStep.PROJECT_TYPE) }
    val nullableProjectTypeSaver = androidx.compose.runtime.saveable.Saver<TemplateProjectType?, String>(
        save = { it?.name },
        restore = { name -> runCatching { TemplateProjectType.valueOf(name) }.getOrNull() },
    )
    var selectedProjectType by rememberSaveable(stateSaver = nullableProjectTypeSaver) { mutableStateOf<TemplateProjectType?>(null) }
    var selectedTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var projectName by rememberSaveable { mutableStateOf("") }
    var packageName by rememberSaveable { mutableStateOf("") }
    // Map 不能直接进 Bundle，用 mapSaver 保存（键值均为 String）
    val templateVariableValuesSaver = mapSaver(
        save = { map -> map },
        restore = { saved -> saved.mapValues { it.value.toString() } },
    )
    var templateVariableValues by rememberSaveable(stateSaver = templateVariableValuesSaver) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var trustTemplateScripts by rememberSaveable { mutableStateOf(false) }
    // 复杂对象（工程/模板/来源选择）旋转后需重新选择，保留 remember
    var deleteTarget by remember { mutableStateOf<WorkspaceProject?>(null) }
    var apkSource by remember { mutableStateOf<ApkImportSource?>(null) }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    var exportApkToDownload by rememberSaveable { mutableStateOf(false) }
    var createInProgress by rememberSaveable { mutableStateOf(false) }
    var importMode by rememberSaveable { mutableStateOf(ProjectImportMode.LOCAL) }
    var importProjectName by rememberSaveable { mutableStateOf("") }
    var importDirectoryPath by rememberSaveable { mutableStateOf("") }
    var importDirectoryMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var importProjectType by rememberSaveable { mutableStateOf(ProjectType.ANDROID) }
    var archiveSource by remember { mutableStateOf<ProjectArchiveSource?>(null) }
    var importGitUrl by rememberSaveable { mutableStateOf("") }
    var gitTransport by rememberSaveable { mutableStateOf(GitTransport.HTTP) }
    var importInProgress by rememberSaveable { mutableStateOf(false) }
    var pendingExportProject by remember { mutableStateOf<WorkspaceProject?>(null) }
    var buildConfigTarget by remember { mutableStateOf<WorkspaceProject?>(null) }
    var pendingExportTemplateId by remember { mutableStateOf<String?>(null) }
    var deleteTemplateTarget by remember { mutableStateOf<InstalledProjectTemplate?>(null) }

    // APK 逆向模板：系统文件管理器选择 .apk
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val displayName = queryDisplayName(context, uri) ?: "target.apk"
            apkSource = ApkImportSource.FromFileUri(uri.toString(), displayName)
        }
    }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val displayName = queryDisplayName(context, uri) ?: "project.zip"
            archiveSource = ProjectArchiveSource(uri.toString(), displayName)
            if (importProjectName.isBlank()) {
                importProjectName = displayName.substringBeforeLast('.').filter {
                    it.isLetterOrDigit() || it == '.' || it == '_' || it == '-'
                }
            }
        }
    }
    val templateImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.importProjectTemplate(uri.toString())
        }
    }
    val templateExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val templateId = pendingExportTemplateId
        pendingExportTemplateId = null
        if (uri != null && templateId != null) viewModel.exportProjectTemplate(templateId, uri.toString())
    }
    val exportDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val project = pendingExportProject
        pendingExportProject = null
        if (uri != null && project != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.exportProject(project, uri.toString())
        }
    }

    val glassBackdrop = LocalLiquidGlassBackdrop.current
    // 首次引导遮罩与 Scaffold 放在同一 Box 下（同层兄弟节点），保证聚光灯坐标与按钮 boundsInRoot 同一参照系
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = stringResource(R.string.workspace_title),
                statusText = stringResource(R.string.workspace_active_projects, projects.size),
            ) {
                Box {
                    IconButton(
                        onClick = { actionsExpanded = true },
                        enabled = !busy,
                        modifier = Modifier.spotlightAnchor(moreAnchor),
                        contentDescription = stringResource(R.string.workspace_cd_more),
                    ) {
                        RuntimeIcon(RuntimeIconName.More, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.workspace_menu_create)) }, leadingIcon = { RuntimeIcon(RuntimeIconName.Plus, Modifier.size(18.dp)) }, onClick = { actionsExpanded = false; showCreate = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.workspace_menu_import)) }, leadingIcon = { RuntimeIcon(RuntimeIconName.FolderDownload, Modifier.size(18.dp)) }, onClick = { actionsExpanded = false; showImport = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.workspace_menu_templates)) }, leadingIcon = { RuntimeIcon(RuntimeIconName.Package, Modifier.size(18.dp)) }, onClick = { actionsExpanded = false; showTemplateManager = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.workspace_menu_plugins)) }, leadingIcon = { RuntimeIcon(RuntimeIconName.Package, Modifier.size(18.dp)) }, onClick = { actionsExpanded = false; onOpenToolCenter() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.workspace_menu_workflows)) }, leadingIcon = { RuntimeIcon(RuntimeIconName.Hub, Modifier.size(18.dp)) }, onClick = { actionsExpanded = false; onOpenWorkflows("") })
                        DropdownMenuItem(text = { Text(stringResource(R.string.workspace_menu_settings)) }, leadingIcon = { RuntimeIcon(RuntimeIconName.Settings, Modifier.size(18.dp)) }, onClick = { actionsExpanded = false; onOpenWorkshopSettings() })
                    }
                }
            }
        },
        bottomBar = {
            if (glassBackdrop == null) {
                RuntimeBottomBar(MainDestination.Workspace, onNavigate)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .liquidGlassContent()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                message?.let { notice ->
                    NoticeBanner(
                        text = notice,
                        isError = messageIsError,
                    )
                }

                // 后台构建常驻状态栏 (Banner)
                if (activeBuildingProjectName != null && !isBuildDialogVisible) {
                    Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.showBuildDialog() },
                    ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RuntimeCircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.workspace_building_project, activeBuildingProjectName.orEmpty()),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = buildProgress?.step ?: stringResource(R.string.workspace_building),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { viewModel.showBuildDialog() }) {
                            Text(stringResource(R.string.workspace_view_logs), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    }
                } else if (activeBuildingProjectName == null && buildProgress != null && !isBuildDialogVisible) {
                    val progress = buildProgress!!
                    Surface(
                    color = if (progress.isSuccess == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RuntimeIcon(
                            if (progress.isSuccess == true) RuntimeIconName.Check else RuntimeIconName.Close,
                            Modifier.size(18.dp),
                            tint = if (progress.isSuccess == true) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(if (progress.isSuccess == true) R.string.workspace_build_ready else R.string.workspace_build_failed),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (progress.isSuccess == true) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                            )
                            progress.message?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (progress.isSuccess == true) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (progress.isSuccess == true && progress.apkPath != null) {
                                TextButton(onClick = { viewModel.launchInstaller(progress.apkPath!!) }) {
                                    Text(stringResource(R.string.workspace_install), fontWeight = FontWeight.Bold)
                                }
                            }
                            TextButton(onClick = { viewModel.showBuildDialog() }) {
                                Text(stringResource(R.string.workspace_details))
                            }
                            IconButton(
                                onClick = { viewModel.dismissBuildProgress() },
                                modifier = Modifier
                                    .size(28.dp)
                                    .minimumInteractiveComponentSize(),
                                contentDescription = stringResource(R.string.workspace_cd_dismiss),
                            ) {
                                RuntimeIcon(RuntimeIconName.Close, Modifier.size(14.dp))
                            }
                        }
                    }
                    }
                }
            }

            item {
                // 宿主外部存储快速访问入口
                RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                onClick = { onOpenExplorer("sdcard") },
                contentPadding = PaddingValues(14.dp),
                ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        RuntimeIcon(
                            RuntimeIconName.Folder,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.workspace_shared_storage),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.workspace_shared_storage_description),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    RuntimeIcon(
                        RuntimeIconName.ChevronRight,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.workspace_projects_section),
                    subtitle = stringResource(R.string.workspace_projects_description),
                )
            }

            if (loadingProjects) {
                item {
                    repeat(3) { index ->
                        ProjectCardSkeleton(
                            modifier = Modifier.padding(top = if (index == 0) 16.dp else 0.dp),
                        )
                    }
                }
            } else if (projects.isEmpty()) {
                item {
                    EmptyPanel(
                        icon = RuntimeIconName.Workspace,
                        title = stringResource(R.string.workspace_no_projects),
                        description = stringResource(R.string.workspace_no_projects_description),
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            } else {
                items(projects, key = { it.name }) { project ->
                    ProjectCard(
                        project = project,
                        busy = busy,
                        isBuilding = (activeBuildingProjectName == project.name),
                        onOpenExplorer = { onOpenExplorer(project.name) },
                        onOpenTerminal = { onOpenTerminal(project.name) },
                        onOpenAgent = { onNavigate(MainDestination.Agent) },
                        onRunProject = { buildConfigTarget = project },
                        onShowBuildLog = { viewModel.showBuildDialog() },
                        onExport = {
                            pendingExportProject = project
                            exportDirectoryPicker.launch(null)
                        },
                        onDelete = { deleteTarget = project },
                    )
                }
            }


            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // 首次进入引导：高亮右上角「更多」菜单（内含插件中心与工坊设置入口）
    if ("workspace_more_menu" !in firstUseGuidesShown) {
        SpotlightGuideOverlay(
            anchor = moreAnchor,
            title = stringResource(R.string.workspace_guide_more_title),
            message = stringResource(R.string.workspace_guide_more_message),
            onDismiss = { viewModel.markFirstUseGuideShown("workspace_more_menu") },
        )
    }
    } // Box

    // 运行/构建进度与实时日志弹窗 (支持后台运行与随时最小化)
    if (isBuildDialogVisible && buildProgress != null) {
        val progress = buildProgress!!
        var showBuildLog by remember { mutableStateOf(false) }
        var showStepAnalysis by remember { mutableStateOf(false) }
        // Category collapse states: dependencies & others collapsed by default; compile & package expanded
        var collapsedDeps by remember { mutableStateOf(true) }
        var collapsedCompile by remember { mutableStateOf(false) }
        var collapsedPackage by remember { mutableStateOf(false) }
        var collapsedOther by remember { mutableStateOf(true) }

        RuntimeAlertDialog(
            onDismissRequest = { viewModel.hideBuildDialog() },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (progress.isRunning) {
                        RuntimeCircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Text(stringResource(if (progress.isRunning) R.string.workspace_building_device else if (progress.isSuccess == true) R.string.workspace_run_ready else R.string.workspace_run_failed), fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(progress.step, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                    if (progress.isRunning) {
                        LinearProgressIndicator(
                            progress = { progress.progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        )
                    }
                    if (progress.currentDependency != null || progress.dependencyItemsObserved > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.workspace_dependency_activity),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    val countText = progress.dependenciesTotal?.let { total ->
                                        stringResource(R.string.workspace_dependency_count_total, progress.dependencyItemsObserved, total)
                                    } ?: stringResource(R.string.workspace_dependency_count, progress.dependencyItemsObserved)
                                    Text(countText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                progress.currentDependency?.let { dependency ->
                                    Text(
                                        text = dependency,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                progress.dependencyProgressPercent?.let { percent ->
                                    LinearProgressIndicator(
                                        progress = { percent / 100f },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    )
                                    Text(
                                        text = stringResource(R.string.workspace_dependency_percent, percent),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    progress.message?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (progress.isSuccess == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // ====== 构建阶段耗时分析 ======
                    if (progress.stepDurations.isNotEmpty()) {
                        TextButton(
                            onClick = { showStepAnalysis = !showStepAnalysis },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RuntimeIcon(if (showStepAnalysis) RuntimeIconName.ArrowUp else RuntimeIconName.ChevronDown, Modifier.size(14.dp))
                                Text(stringResource(if (showStepAnalysis) R.string.workspace_hide_timing else R.string.workspace_timing), style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        if (showStepAnalysis) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val maxDuration = (progress.stepDurations.maxOfOrNull { it.durationMs } ?: 1L).coerceAtLeast(1L)
                                    progress.stepDurations.forEach { step ->
                                        val barRatio = (step.durationMs.toFloat() / maxDuration.toFloat()).coerceIn(0.02f, 1f)
                                        val barColor = when {
                                            step.step.contains("拉取") || step.step.contains("依赖") -> androidx.compose.ui.graphics.Color(0xFF2196F3) // 蓝色
                                            step.step.contains("编译") || step.step.contains("Kotlin") || step.step.contains("Java") || step.step.contains("Dex") -> androidx.compose.ui.graphics.Color(0xFF4CAF50) // 绿色
                                            step.step.contains("打包") || step.step.contains("APK") -> androidx.compose.ui.graphics.Color(0xFFFF9800) // 橙色
                                            else -> androidx.compose.ui.graphics.Color(0xFF9E9E9E) // 灰色
                                        }
                                        val durationText = if (step.durationMs >= 1000) "${"%.1f".format(step.durationMs / 1000.0)}s" else "${step.durationMs}ms"
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Text(
                                                    text = step.step,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                Text(
                                                    text = durationText,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth(barRatio)
                                                        .height(6.dp)
                                                        .clip(RoundedCornerShape(3.dp))
                                                        .background(barColor),
                                                )
                                            }
                                        }
                                    }
                                    // 总时长
                                    progress.totalDurationMs?.let { total ->
                                        val totalText = if (total >= 1000) "${"%.1f".format(total / 1000.0)}s" else "${total}ms"
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = stringResource(R.string.workspace_total),
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                text = totalText,
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ====== 构建日志折叠面板 ======
                    // The build task survives destination recreation in the
                    // coordinator. Its first restored snapshot may not have
                    // received log text yet, so keep the entry visible while
                    // running (and for completed tasks) instead of tying it
                    // to logOutput being non-empty.
                    run {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(
                                onClick = { showBuildLog = !showBuildLog },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    RuntimeIcon(if (showBuildLog) RuntimeIconName.ArrowUp else RuntimeIconName.ChevronDown, Modifier.size(14.dp))
                                    Text(stringResource(if (showBuildLog) R.string.workspace_hide_build_log else R.string.workspace_view_build_log), style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            if (showBuildLog && progress.logOutput.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("WanXiang Build Log", progress.logOutput))
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        RuntimeIcon(RuntimeIconName.Copy, Modifier.size(12.dp))
                                        Text(stringResource(R.string.workspace_copy_log), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }

                        if (showBuildLog) {
                            if (progress.logOutput.isBlank()) {
                                Text(
                                    text = stringResource(if (progress.isRunning) R.string.workspace_build_log_receiving else R.string.workspace_no_build_log),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // 分类日志
                            val logLines = progress.logOutput.lines()
                            val depKeywords = listOf("downloading", "fetching", "kilobytes", "megabytes", "get")
                            val compileKeywords = listOf("compile", "kotlin", "javac", "dex")
                            val packageKeywords = listOf("package", "install", "apk")
                            fun classifyLine(line: String): Int {
                                val lower = line.lowercase()
                                return when {
                                    depKeywords.any { lower.contains(it) } -> 0
                                    compileKeywords.any { lower.contains(it) } -> 1
                                    packageKeywords.any { lower.contains(it) } -> 2
                                    else -> 3
                                }
                            }
                            val categorized = logLines.map { line -> classifyLine(line) to line }
                            val depsLogs = categorized.filter { it.first == 0 }.map { it.second }
                            val compileLogs = categorized.filter { it.first == 1 }.map { it.second }
                            val packageLogs = categorized.filter { it.first == 2 }.map { it.second }
                            val otherLogs = categorized.filter { it.first == 3 }.map { it.second }

                            data class Category(val type: Int, val emoji: String, val name: String, val logs: List<String>)
                            val categories = listOf(
                                Category(0, "📥", stringResource(R.string.workspace_log_dependencies), depsLogs),
                                Category(1, "🔨", stringResource(R.string.workspace_log_compile), compileLogs),
                                Category(2, "📦", stringResource(R.string.workspace_log_package), packageLogs),
                                Category(3, "📋", stringResource(R.string.workspace_log_other), otherLogs),
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                categories.forEach { cat ->
                                    if (cat.logs.isNotEmpty()) {
                                        val isCollapsed = when (cat.type) {
                                            0 -> collapsedDeps
                                            1 -> collapsedCompile
                                            2 -> collapsedPackage
                                            else -> collapsedOther
                                        }
                                        TextButton(
                                            onClick = {
                                                when (cat.type) {
                                                    0 -> collapsedDeps = !collapsedDeps
                                                    1 -> collapsedCompile = !collapsedCompile
                                                    2 -> collapsedPackage = !collapsedPackage
                                                    else -> collapsedOther = !collapsedOther
                                                }
                                            },
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    RuntimeIcon(if (isCollapsed) RuntimeIconName.ChevronRight else RuntimeIconName.ArrowUp, Modifier.size(12.dp))
                                                    Text("${cat.emoji} ${cat.name}", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                                }
                                                Text(stringResource(R.string.workspace_log_count, cat.logs.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        if (!isCollapsed) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Column(modifier = Modifier.padding(6.dp)) {
                                                    val displayLogs = if (cat.logs.size > 200) cat.logs.takeLast(200) else cat.logs
                                                    displayLogs.forEach { line ->
                                                        Text(
                                                            text = line,
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontFamily = FontFamily.Monospace,
                                                                fontSize = 10.sp,
                                                                lineHeight = 14.sp,
                                                            ),
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                        )
                                                    }
                                                    if (cat.logs.size > 200) {
                                                        Text(
                                                            text = stringResource(R.string.workspace_log_truncated, cat.logs.size),
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
                        }
                    }
                }
            },
            confirmButton = {
                if (!progress.isRunning) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 缺少环境时展示前往插件中心准备环境按钮
                        if (progress.suggestedSuiteId != null) {
                            Button(
                                onClick = {
                                    viewModel.dismissBuildProgress()
                                    onOpenToolCenter()
                                },
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    RuntimeIcon(RuntimeIconName.Package, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                    Text(stringResource(R.string.workspace_prepare_environment))
                                }
                            }
                        }

                        val path = progress.apkPath
                        if (progress.isSuccess == true && path != null) {
                            Button(onClick = { viewModel.launchInstaller(path) }) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    RuntimeIcon(RuntimeIconName.Download, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                    Text(stringResource(R.string.workspace_launch_install))
                                }
                            }
                        }
                        TextButton(onClick = { viewModel.dismissBuildProgress() }) {
                            Text(stringResource(R.string.workspace_done))
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.cancelBuild() }) {
                            Text(stringResource(R.string.workspace_stop_build), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { viewModel.hideBuildDialog() }) {
                            Text(stringResource(R.string.workspace_background), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            dismissButton = {
                if (progress.isRunning) {
                    TextButton(onClick = { viewModel.hideBuildDialog() }) {
                        Text(stringResource(R.string.workspace_collapse))
                    }
                }
            },
        )
    }

    if (showTemplateManager) {
        TemplateManagerDialog(
            templates = projectTemplates,
            busy = busy,
            onDismiss = { showTemplateManager = false },
            onImport = { templateImportPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
            onExport = { template ->
                pendingExportTemplateId = template.manifest.id
                templateExportPicker.launch("${template.manifest.id}.zip")
            },
            onDelete = {
                showTemplateManager = false
                deleteTemplateTarget = it
            },
            onShowSpec = {
                showTemplateManager = false
                showTemplateSpec = true
            },
        )
    }

    if (showTemplateSpec) {
        ProjectTemplateSpecDialog(onDismiss = { showTemplateSpec = false })
    }

    templateScriptPreview?.let { preview ->
        RuntimeAlertDialog(
            onDismissRequest = viewModel::dismissTemplateScripts,
            title = { Text(stringResource(R.string.workspace_template_scripts_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    preview,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton(onClick = viewModel::dismissTemplateScripts) { Text(stringResource(R.string.workspace_action_close)) } },
        )
    }

    deleteTemplateTarget?.let { template ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteTemplateTarget = null },
            title = { Text(stringResource(R.string.workspace_delete_template_title)) },
            text = { Text(stringResource(R.string.workspace_delete_template_message, template.manifest.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProjectTemplate(template.manifest.id)
                    deleteTemplateTarget = null
                }) { Text(stringResource(R.string.workspace_confirm_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTemplateTarget = null }) { Text(stringResource(R.string.workspace_cancel)) } },
        )
    }

    if (showCreate) {
        // 创建进行中：保持弹窗展示进度，完成后统一关闭并复位（模板实例化可能耗时 60 秒）
        LaunchedEffect(createInProgress, busy) {
            if (createInProgress && !busy) {
                createInProgress = false
                showCreate = false
                projectName = ""
                packageName = ""
                templateVariableValues = emptyMap()
                apkSource = null
                exportApkToDownload = false
                createProjectStep = CreateProjectStep.PROJECT_TYPE
                selectedProjectType = null
                selectedTemplateId = null
                trustTemplateScripts = false
            }
        }
        RuntimeAlertDialog(
            onDismissRequest = {
                if (!createInProgress) {
                    showCreate = false
                    projectName = ""
                    packageName = ""
                    apkSource = null
                    exportApkToDownload = false
                    createProjectStep = CreateProjectStep.PROJECT_TYPE
                    selectedProjectType = null
                    selectedTemplateId = null
                    trustTemplateScripts = false
                }
            },
            title = { Text(stringResource(R.string.workspace_new_project), fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(min = 360.dp, max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (createProjectStep) {
                        CreateProjectStep.PROJECT_TYPE -> {
                            Text("选择项目类型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("先选择要创建的项目平台", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val projectTypes = projectTemplates.map { it.manifest.projectType }.distinct()
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // 空项目：跳过平台与模板选择，直接输入名称创建空文件夹
                                RuntimeCard(
                                    onClick = {
                                        selectedProjectType = null
                                        selectedTemplateId = null
                                        selectedTemplate = top.wanxiang.app.runtime.ProjectTemplate.EMPTY
                                        templateVariableValues = emptyMap()
                                        trustTemplateScripts = false
                                        packageName = ""
                                        createProjectStep = CreateProjectStep.EMPTY_DETAILS
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                                    ) {
                                        RuntimeIcon(RuntimeIconName.Folder, Modifier.size(36.dp), MaterialTheme.colorScheme.primary)
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text("空项目", fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "只创建一个空文件夹，随时手动添加内容",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                projectTypes.chunked(2).forEach { rowTypes ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        rowTypes.forEach { type ->
                                            RuntimeCard(
                                                onClick = {
                                                    selectedProjectType = type
                                                    selectedTemplateId = null
                                                },
                                                modifier = Modifier.weight(1f),
                                                containerColor = if (selectedProjectType == type) {
                                                    MaterialTheme.colorScheme.primaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceContainer
                                                },
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                                    RuntimeIcon(
                                                        when (type) {
                                                            TemplateProjectType.ANDROID -> RuntimeIconName.Android
                                                            TemplateProjectType.FLUTTER -> RuntimeIconName.Flutter
                                                            TemplateProjectType.GENERAL -> RuntimeIconName.Code
                                                        },
                                                        Modifier.size(36.dp),
                                                        MaterialTheme.colorScheme.primary,
                                                    )
                                                    Spacer(Modifier.height(8.dp))
                                                    Text(type.name.lowercase().replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                        repeat(2 - rowTypes.size) {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }

                        CreateProjectStep.EMPTY_DETAILS -> {
                            Text("新建空项目", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("输入名称即创建一个空文件夹", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(
                                value = projectName,
                                onValueChange = { projectName = it },
                                label = { Text(stringResource(R.string.workspace_project_name)) },
                                placeholder = { Text("my-sandbox") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val emptyPath = projectName.trim()
                            if (emptyPath.isNotBlank()) Text(
                                stringResource(R.string.workspace_sandbox_path, "/workspace", emptyPath),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        CreateProjectStep.TEMPLATE -> {
                            Text("选择模板", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("选择一个具体模板后继续", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val filteredTemplates = projectTemplates.filter { it.manifest.projectType == selectedProjectType }
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                filteredTemplates.groupBy { it.manifest.category }.forEach { (category, templates) ->
                                    if (filteredTemplates.map { it.manifest.category.id }.distinct().size > 1) {
                                        Text(category.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    }
                                    templates.chunked(2).forEach { rowTemplates ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            rowTemplates.forEach { template ->
                                                val selected = selectedTemplateId == template.manifest.id
                                                RuntimeCard(
                                                    onClick = {
                                                        selectedTemplateId = template.manifest.id
                                                        trustTemplateScripts = false
                                                        // 标准模板一律通过 manifest id 创建，避免 UI 依赖内置模板枚举。
                                                        selectedTemplate = top.wanxiang.app.runtime.ProjectTemplate.EMPTY
                                                        templateVariableValues = template.manifest.variables
                                                            .filter { it.prompt }
                                                            .associate { variable ->
                                                                variable.name to when {
                                                                    variable.defaultValue.isNotBlank() -> variable.defaultValue
                                                                    variable.inputType == top.wanxiang.app.template.ProjectTemplateInputType.BOOLEAN -> "false"
                                                                    variable.inputType == top.wanxiang.app.template.ProjectTemplateInputType.SELECT && variable.required ->
                                                                        variable.options.firstOrNull()?.value.orEmpty()
                                                                    else -> ""
                                                                }
                                                            }
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                                ) {
                                                    Surface(
                                                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                    ) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            val previewFile = template.previewFile
                                                            if (previewFile != null) {
                                                                TemplatePreviewImage(previewFile, Modifier.fillMaxSize())
                                                            } else {
                                                                RuntimeIcon(
                                                                    when (template.manifest.projectType) {
                                                                        TemplateProjectType.ANDROID -> RuntimeIconName.Android
                                                                        TemplateProjectType.FLUTTER -> RuntimeIconName.Flutter
                                                                        TemplateProjectType.GENERAL -> RuntimeIconName.Code
                                                                    },
                                                                    Modifier.size(42.dp),
                                                                    MaterialTheme.colorScheme.primary,
                                                                )
                                                            }
                                                        }
                                                    }
                                                    Spacer(Modifier.height(8.dp))
                                                    Text(
                                                        template.manifest.name,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        fontSize = 12.sp,
                                                        modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 4.dp),
                                                    )
                                                }
                                            }
                                            repeat(2 - rowTemplates.size) {
                                                Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        CreateProjectStep.DETAILS -> {

                    val selectedManifest = projectTemplates.firstOrNull {
                        it.manifest.id == selectedTemplateId
                    }?.manifest
                    val projectNameVariable = selectedManifest?.variables?.firstOrNull {
                        it.name == "projectName"
                    }
                    val projectNameError = projectNameVariable?.let { variable ->
                        templateVariableError(variable, projectName)?.let {
                            variable.description.ifBlank { it }
                        }
                    }

                    OutlinedTextField(
                        value = projectName,
                        onValueChange = {
                            projectName = it
                            if (packageName.isBlank() || packageName.startsWith("com.example.")) {
                                packageName = "com.example.${it.lowercase().filter { c -> c.isLetterOrDigit() }}"
                            }
                        },
                        label = { Text(stringResource(R.string.workspace_project_name)) },
                        placeholder = { Text("MyApplication / demo-app") },
                        supportingText = projectNameError?.let { error -> { Text(error) } },
                        isError = projectNameError != null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // ============ APK 逆向模板：选择安装包来源 ============
                    if (selectedTemplate == top.wanxiang.app.runtime.ProjectTemplate.APK_REVERSE) {
                        Text(
                            stringResource(R.string.workspace_choose_apk_source),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { showAppPicker = true },
                                modifier = Modifier.weight(1f),
                            ) {
                                RuntimeIcon(RuntimeIconName.Package, Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text(stringResource(R.string.workspace_extract_installed), style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = {
                                    apkPicker.launch(
                                        arrayOf(
                                            "application/vnd.android.package-archive",
                                            "application/octet-stream",
                                        ),
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                RuntimeIcon(RuntimeIconName.Folder, Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text(stringResource(R.string.workspace_choose_apk_file), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Text(
                            stringResource(R.string.workspace_apk_source_description),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 逆向工具链就绪状态：避免"建好工程却发现 jadx/apktool 缺失"
                        val reverseToolReady = "android-re" in installedComponentIds && runtimeReady
                        val statusSurfaceColor = when {
                            reverseToolReady -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                            runtimeReady -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = statusSurfaceColor,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                RuntimeIcon(
                                    when {
                                        reverseToolReady -> RuntimeIconName.Check
                                        runtimeReady -> RuntimeIconName.Alert
                                        else -> RuntimeIconName.Info
                                    },
                                    Modifier.size(17.dp),
                                    tint = when {
                                        reverseToolReady -> MaterialTheme.colorScheme.onSecondaryContainer
                                        runtimeReady -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = when {
                                            reverseToolReady -> stringResource(R.string.workspace_reverse_ready)
                                            runtimeReady -> stringResource(R.string.workspace_reverse_missing)
                                            else -> stringResource(R.string.workspace_runtime_uninitialized)
                                        },
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = when {
                                            reverseToolReady -> MaterialTheme.colorScheme.onSecondaryContainer
                                            runtimeReady -> MaterialTheme.colorScheme.onErrorContainer
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                    Text(
                                        text = when {
                                            reverseToolReady -> stringResource(R.string.workspace_reverse_output_ready)
                                            runtimeReady -> stringResource(R.string.workspace_reverse_component_needed)
                                            else -> stringResource(R.string.workspace_apk_creation_available)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            reverseToolReady -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                                            runtimeReady -> MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f)
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        },
                                    )
                                }
                                if (!reverseToolReady && runtimeReady) {
                                    TextButton(onClick = {
                                        showCreate = false
                                        onOpenToolCenter()
                                    }) {
                                        Text(stringResource(R.string.workspace_install_components), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                        apkSource?.let { source ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    RuntimeIcon(RuntimeIconName.Reverse, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = source.displayName,
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = when (source) {
                                                is ApkImportSource.FromInstalledApp -> stringResource(R.string.workspace_installed_app_source)
                                                is ApkImportSource.FromFileUri -> stringResource(R.string.workspace_apk_file_source)
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = { apkSource = null },
                                        modifier = Modifier
                                            .size(30.dp)
                                            .minimumInteractiveComponentSize(),
                                        contentDescription = stringResource(R.string.workspace_cd_close),
                                    ) {
                                        RuntimeIcon(RuntimeIconName.Close, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        // 同时导出 APK 到宿主公共下载目录（供 MT 管理器等宿主侧工具直接打开）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { exportApkToDownload = !exportApkToDownload }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Checkbox(
                                checked = exportApkToDownload,
                                onCheckedChange = { exportApkToDownload = it },
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    stringResource(R.string.workspace_export_apk),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    stringResource(R.string.workspace_export_apk_description),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    val path = projectName.trim()
                    if (path.isNotBlank()) Text(
                        stringResource(R.string.workspace_sandbox_path, "/workspace", path),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (selectedManifest != null) {
                        TemplateVariableFields(
                            variables = selectedManifest.variables,
                            values = templateVariableValues + ("packageName" to packageName),
                            onValueChange = { name, value ->
                                if (name == "packageName") packageName = value
                                else templateVariableValues = templateVariableValues + (name to value)
                            },
                        )
                        val hasTemplateScripts = selectedManifest.hooks.beforeCreate.isNotBlank() ||
                            selectedManifest.hooks.afterCreate.isNotBlank()
                        if (hasTemplateScripts) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clickable { trustTemplateScripts = !trustTemplateScripts }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Checkbox(checked = trustTemplateScripts, onCheckedChange = { trustTemplateScripts = it })
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(stringResource(R.string.workspace_trust_scripts_title), fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (runtimeReady) {
                                                stringResource(R.string.workspace_trust_scripts_hint_runtime)
                                            } else {
                                                stringResource(R.string.workspace_trust_scripts_hint_not_ready)
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        )
                                        TextButton(onClick = {
                                            selectedTemplateId?.let(viewModel::showTemplateScripts)
                                        }) { Text(stringResource(R.string.workspace_view_scripts)) }
                                    }
                                }
                            }
                        }
                    }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (createProjectStep) {
                            CreateProjectStep.PROJECT_TYPE -> createProjectStep = CreateProjectStep.TEMPLATE
                            CreateProjectStep.EMPTY_DETAILS -> {
                                createInProgress = true
                                viewModel.create(
                                    name = projectName,
                                    template = top.wanxiang.app.runtime.ProjectTemplate.EMPTY,
                                )
                            }
                            CreateProjectStep.TEMPLATE -> createProjectStep = CreateProjectStep.DETAILS
                            CreateProjectStep.DETAILS -> {
                                createInProgress = true
                                viewModel.create(
                                    name = projectName,
                                    template = selectedTemplate,
                                    packageName = packageName,
                                    apkSource = apkSource,
                                    exportApkToDownload = exportApkToDownload,
                                    templateVariables = templateVariableValues + ("packageName" to packageName),
                                    templateId = selectedTemplateId.orEmpty(),
                                    trustTemplateScripts = trustTemplateScripts,
                                )
                            }
                        }
                    },
                    enabled = !createInProgress && when (createProjectStep) {
                        CreateProjectStep.PROJECT_TYPE -> selectedProjectType != null
                        CreateProjectStep.EMPTY_DETAILS -> projectName.isNotBlank() && !busy
                        CreateProjectStep.TEMPLATE -> selectedTemplateId != null
                        CreateProjectStep.DETAILS -> projectName.isNotBlank() && !busy &&
                            run {
                                val variables = projectTemplates.firstOrNull { it.manifest.id == selectedTemplateId }
                                    ?.manifest?.variables.orEmpty().filter { it.prompt }
                                val values = templateVariableValues + ("packageName" to packageName)
                                val manifest = projectTemplates.firstOrNull { it.manifest.id == selectedTemplateId }?.manifest
                                val hasScripts = manifest != null &&
                                    (manifest.hooks.beforeCreate.isNotBlank() || manifest.hooks.afterCreate.isNotBlank())
                                val scriptsReady = !hasScripts || (trustTemplateScripts && runtimeReady)
                                val projectNameRule = manifest?.variables?.firstOrNull { it.name == "projectName" }
                                val projectNameValid = projectNameRule == null ||
                                    templateVariableError(projectNameRule, projectName) == null
                                scriptsReady && projectNameValid && variables.none {
                                    templateVariableError(it, values[it.name] ?: it.defaultValue) != null
                                }
                            }
                    },
                ) {
                    if (createInProgress) {
                        RuntimeCircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.workspace_creating), color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(
                            if (createProjectStep == CreateProjectStep.DETAILS || createProjectStep == CreateProjectStep.EMPTY_DETAILS) {
                                stringResource(R.string.workspace_create)
                            } else {
                                stringResource(R.string.workspace_action_next)
                            },
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        when (createProjectStep) {
                            CreateProjectStep.PROJECT_TYPE -> {
                                showCreate = false
                                projectName = ""
                                packageName = ""
                                templateVariableValues = emptyMap()
                                selectedProjectType = null
                                selectedTemplateId = null
                                trustTemplateScripts = false
                            }
                            CreateProjectStep.EMPTY_DETAILS -> createProjectStep = CreateProjectStep.PROJECT_TYPE
                            CreateProjectStep.TEMPLATE -> createProjectStep = CreateProjectStep.PROJECT_TYPE
                            CreateProjectStep.DETAILS -> createProjectStep = CreateProjectStep.TEMPLATE
                        }
                    },
                    enabled = !createInProgress,
                ) {
                    Text(if (createProjectStep == CreateProjectStep.PROJECT_TYPE) stringResource(R.string.workspace_cancel) else stringResource(R.string.workspace_action_previous))
                }
            },
        )
    }

    if (showImport) {
        fun resetImportDialog() {
            showImport = false
            importMode = ProjectImportMode.LOCAL
            importProjectName = ""
            importDirectoryPath = ""
            importProjectType = ProjectType.ANDROID
            archiveSource = null
            importGitUrl = ""
            gitTransport = GitTransport.HTTP
            importInProgress = false
        }
        // GitHub 拉取进行中：保持弹窗展示克隆进度，完成后统一关闭并复位（克隆可能耗时数分钟）
        LaunchedEffect(importInProgress, busy) {
            if (importInProgress && !busy) resetImportDialog()
        }
        // GitHub HTTP(S) 导入地址前置校验：必须是 http:// 或 https:// 开头
        val gitUrlError = if (
            importMode == ProjectImportMode.GITHUB &&
            gitTransport == GitTransport.HTTP &&
            importGitUrl.isNotBlank() &&
            !importGitUrl.trim().startsWith("http://") &&
            !importGitUrl.trim().startsWith("https://")
        ) stringResource(R.string.workspace_git_url_invalid) else null
        RuntimeAlertDialog(
            onDismissRequest = { if (!importInProgress) resetImportDialog() },
            title = { Text(stringResource(R.string.workspace_import_project), fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // GitHub 拉取进度：git 克隆可能耗时数分钟，实时展示最新进度与百分比
                    if (importInProgress) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    RuntimeCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text(
                                        stringResource(R.string.workspace_git_clone_in_progress),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    githubImportProgress?.percent?.let { percent ->
                                        Text(
                                            "$percent%",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                val clonePercent = githubImportProgress?.percent
                                LinearProgressIndicator(
                                    progress = clonePercent?.let { percent -> { percent / 100f } },
                                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                                )
                                githubImportProgress?.let { progress ->
                                    Text(
                                        progress.text,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.workspace_import_source),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = importMode == ProjectImportMode.LOCAL,
                            onClick = { importMode = ProjectImportMode.LOCAL },
                            leadingIcon = { RuntimeIcon(RuntimeIconName.FolderDownload, Modifier.size(16.dp)) },
                            label = { Text(stringResource(R.string.workspace_local_archive)) },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = importMode == ProjectImportMode.GITHUB,
                            onClick = { importMode = ProjectImportMode.GITHUB },
                            leadingIcon = { RuntimeIcon(RuntimeIconName.Github, Modifier.size(16.dp)) },
                            label = { Text("GitHub") },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (importMode == ProjectImportMode.LOCAL) {
                        OutlinedButton(
                            onClick = {
                                archivePicker.launch(
                                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RuntimeIcon(RuntimeIconName.FolderOpen, Modifier.size(17.dp))
                            Spacer(Modifier.size(7.dp))
                            Text(stringResource(R.string.workspace_choose_project_archive))
                        }
                        archiveSource?.let { source ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    RuntimeIcon(RuntimeIconName.Compress, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                                    Text(
                                        source.fileName,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(
                                        onClick = { archiveSource = null },
                                        modifier = Modifier
                                            .size(30.dp)
                                            .minimumInteractiveComponentSize(),
                                        contentDescription = stringResource(R.string.workspace_cd_close),
                                    ) {
                                        RuntimeIcon(RuntimeIconName.Close, Modifier.size(15.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            stringResource(R.string.workspace_git_transport),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = gitTransport == GitTransport.HTTP,
                                onClick = { gitTransport = GitTransport.HTTP },
                                label = { Text("HTTP(S)") },
                                modifier = Modifier.weight(1f),
                            )
                            FilterChip(
                                selected = gitTransport == GitTransport.SSH,
                                onClick = { gitTransport = GitTransport.SSH },
                                label = { Text("SSH") },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedTextField(
                            value = importGitUrl,
                            onValueChange = { importGitUrl = it },
                            label = { Text(stringResource(R.string.workspace_git_url)) },
                            placeholder = {
                                Text(
                                    if (gitTransport == GitTransport.HTTP) "https://github.com/user/project.git"
                                    else "git@github.com:user/project.git",
                                )
                            },
                            supportingText = {
                                Text(gitUrlError ?: stringResource(R.string.workspace_git_import_description))
                            },
                            isError = gitUrlError != null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Text(
                        stringResource(R.string.workspace_project_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            ProjectType.ANDROID to ("Android" to RuntimeIconName.Android),
                            ProjectType.FLUTTER to ("Flutter" to RuntimeIconName.Flutter),
                            ProjectType.REVERSE to (stringResource(R.string.workspace_template_reverse) to RuntimeIconName.Reverse),
                            ProjectType.GENERAL to (stringResource(R.string.workspace_template_empty) to RuntimeIconName.Code),
                        ).forEach { (type, pair) ->
                            FilterChip(
                                selected = importProjectType == type,
                                onClick = { importProjectType = type },
                                leadingIcon = { RuntimeIcon(pair.second, Modifier.size(16.dp)) },
                                label = { Text(pair.first, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = importProjectName,
                        onValueChange = { importProjectName = it },
                        label = { Text(stringResource(R.string.workspace_project_name)) },
                        placeholder = { Text("my-imported-project") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    val importPathName = importProjectName.trim().ifBlank { "my-project" }
                    val commonDirectories = listOf(
                        "" to stringResource(R.string.workspace_location_project, importPathName),
                        "projects/$importPathName" to stringResource(R.string.workspace_location_projects, importPathName),
                        "repos/$importPathName" to stringResource(R.string.workspace_location_repos, importPathName),
                        "work/$importPathName" to stringResource(R.string.workspace_location_work, importPathName),
                    )
                    ExposedDropdownMenuBox(
                        expanded = importDirectoryMenuExpanded,
                        onExpandedChange = { importDirectoryMenuExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = importDirectoryPath,
                            onValueChange = {
                                importDirectoryPath = it
                                importDirectoryMenuExpanded = true
                            },
                            label = { Text(stringResource(R.string.workspace_linked_directory)) },
                            placeholder = { Text(stringResource(R.string.workspace_directory_default)) },
                            supportingText = { Text(stringResource(R.string.workspace_import_directory_hint)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(importDirectoryMenuExpanded) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().menuAnchor(
                                ExposedDropdownMenuAnchorType.PrimaryEditable,
                                true,
                            ),
                        )
                        ExposedDropdownMenu(
                            expanded = importDirectoryMenuExpanded,
                            onDismissRequest = { importDirectoryMenuExpanded = false },
                        ) {
                            commonDirectories.forEach { (path, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        importDirectoryPath = path
                                        importDirectoryMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    val targetPath = importDirectoryPath.trim().ifBlank { importProjectName.trim() }
                    if (targetPath.isNotBlank()) {
                        Text(
                            stringResource(R.string.workspace_sandbox_path, "/workspace", targetPath.trimStart('/')),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (importMode == ProjectImportMode.LOCAL) {
                            archiveSource?.let {
                                viewModel.importLocalProject(importProjectName, importDirectoryPath, importProjectType, it)
                            }
                            resetImportDialog()
                        } else {
                            // 拉取期间保持弹窗打开展示 git 克隆进度，完成后由 LaunchedEffect 统一关闭
                            importInProgress = true
                            viewModel.importGithubProject(
                                importProjectName,
                                importDirectoryPath,
                                importProjectType,
                                importGitUrl,
                                gitTransport,
                            )
                        }
                    },
                    enabled = importProjectName.isNotBlank() && !busy && gitUrlError == null &&
                        ((importMode == ProjectImportMode.LOCAL && archiveSource != null) ||
                            (importMode == ProjectImportMode.GITHUB && importGitUrl.isNotBlank())),
                ) { Text(stringResource(R.string.workspace_import), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { resetImportDialog() }, enabled = !importInProgress) { Text(stringResource(R.string.workspace_cancel)) }
            },
        )
    }

    // APK 逆向模板：已安装应用选择弹窗
    if (showAppPicker) {
        AppPickerDialog(
            onDismiss = { showAppPicker = false },
            onSelect = { app ->
                apkSource = ApkImportSource.FromInstalledApp(app.packageName, app.appLabel(context))
                showAppPicker = false
            },
        )
    }

    deleteTarget?.let { project ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.workspace_delete_project_title, project.name), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (project.ownsDirectory) stringResource(R.string.workspace_delete_owned_project, project.linuxPath)
                    else stringResource(R.string.workspace_unlink_project),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { deleteTarget = null; viewModel.delete(project.name) },
                    enabled = !busy,
                ) {
                    if (busy) {
                        RuntimeCircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.workspace_confirm_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !busy) { Text(stringResource(R.string.workspace_cancel)) } },
        )
    }

    // 构建类型选择：Debug 直接构建；Release 需选择已登记签名，没有签名则引导去创建
    buildConfigTarget?.let { target ->
        BuildTypePickerDialog(
            project = target,
            keystores = keystores,
            onDismiss = { buildConfigTarget = null },
            onConfirm = { type, keystore ->
                buildConfigTarget = null
                viewModel.runProject(target, type, keystore)
            },
            onManageSigning = {
                buildConfigTarget = null
                onOpenWorkshopSettings()
            },
        )
    }
}

@Composable
private fun TemplateManagerDialog(
    templates: List<InstalledProjectTemplate>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onExport: (InstalledProjectTemplate) -> Unit,
    onDelete: (InstalledProjectTemplate) -> Unit,
    onShowSpec: () -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_menu_templates), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onImport, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.workspace_template_import_zip))
                    }
                    OutlinedButton(onClick = onShowSpec, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.workspace_template_spec))
                    }
                }
                templates.groupBy { it.manifest.projectType }.forEach { (type, typeTemplates) ->
                    Text(
                        type.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    typeTemplates.forEach { template ->
                        RuntimeCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    modifier = Modifier.size(56.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                ) {
                                    template.previewFile?.let { TemplatePreviewImage(it, Modifier.fillMaxSize()) }
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(template.manifest.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        "${template.manifest.category.name} · ${if (template.isBundled) stringResource(R.string.workspace_template_bundled) else stringResource(R.string.workspace_template_user)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        template.manifest.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TextButton(onClick = { onExport(template) }, enabled = !busy) { Text(stringResource(R.string.workspace_action_export)) }
                                if (!template.isBundled) {
                                    IconButton(
                                        onClick = { onDelete(template) },
                                        enabled = !busy,
                                        contentDescription = stringResource(R.string.workspace_cd_delete_template),
                                    ) {
                                        RuntimeIcon(RuntimeIconName.Trash, Modifier.size(18.dp), MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_action_done)) } },
    )
}

@Composable
private fun ProjectTemplateSpecDialog(onDismiss: () -> Unit) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_spec_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.workspace_spec_intro))
                Text(stringResource(R.string.workspace_spec_min_structure), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.workspace_spec_min_structure_body),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.workspace_spec_manifest), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.workspace_spec_manifest_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.workspace_spec_variables), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.workspace_spec_variables_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.workspace_spec_preview), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.workspace_spec_preview_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.workspace_spec_hooks), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.workspace_spec_hooks_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.workspace_spec_validation), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.workspace_spec_validation_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.workspace_spec_footer), color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_spec_got_it)) } },
    )
}

@Composable
private fun BuildTypePickerDialog(
    project: WorkspaceProject,
    keystores: List<top.wanxiang.app.core.datastore.WorkshopKeystore>,
    onDismiss: () -> Unit,
    onConfirm: (top.wanxiang.app.runtime.build.WorkshopBuildType, top.wanxiang.app.core.datastore.WorkshopKeystore?) -> Unit,
    onManageSigning: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(top.wanxiang.app.runtime.build.WorkshopBuildType.DEBUG) }
    var selectedKeystoreId by remember { mutableStateOf(keystores.firstOrNull()?.id.orEmpty()) }
    val isRelease = selectedType == top.wanxiang.app.runtime.build.WorkshopBuildType.RELEASE
    val selectedKeystore = keystores.firstOrNull { it.id == selectedKeystoreId }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_build_type_title, project.name), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BuildTypeOption(
                    title = stringResource(R.string.workspace_build_type_debug),
                    description = stringResource(R.string.workspace_build_type_debug_description),
                    selected = !isRelease,
                    onClick = { selectedType = top.wanxiang.app.runtime.build.WorkshopBuildType.DEBUG },
                )
                BuildTypeOption(
                    title = stringResource(R.string.workspace_build_type_release),
                    description = stringResource(R.string.workspace_build_type_release_description),
                    selected = isRelease,
                    onClick = { selectedType = top.wanxiang.app.runtime.build.WorkshopBuildType.RELEASE },
                )
                if (isRelease) {
                    if (keystores.isEmpty()) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.workspace_build_no_keystore), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                Button(onClick = onManageSigning) { Text(stringResource(R.string.workspace_build_go_create_keystore)) }
                            }
                        }
                    } else {
                        Text(stringResource(R.string.workspace_build_pick_keystore), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        keystores.forEach { keystore ->
                            BuildTypeOption(
                                title = keystore.name,
                                description = "alias ${keystore.alias}",
                                selected = keystore.id == selectedKeystoreId,
                                onClick = { selectedKeystoreId = keystore.id },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedType, if (isRelease) selectedKeystore else null) },
                enabled = !isRelease || selectedKeystore != null,
            ) { Text(stringResource(R.string.workspace_build_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_cancel)) }
        },
    )
}

@Composable
private fun BuildTypeOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RuntimeIcon(
                if (selected) RuntimeIconName.Check else RuntimeIconName.Key,
                Modifier.size(18.dp),
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: WorkspaceProject,
    busy: Boolean,
    isBuilding: Boolean,
    onOpenExplorer: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenAgent: () -> Unit,
    onRunProject: () -> Unit,
    onShowBuildLog: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var moreExpanded by remember { mutableStateOf(false) }

    val typeBadgeColor = when (project.projectType) {
        ProjectType.ANDROID -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
        ProjectType.FLUTTER -> androidx.compose.ui.graphics.Color(0xFF0288D1)
        ProjectType.REVERSE -> androidx.compose.ui.graphics.Color(0xFF6A1B9A)
        ProjectType.GENERAL -> MaterialTheme.colorScheme.primary
    }

    val typeIcon = when (project.projectType) {
        ProjectType.ANDROID -> RuntimeIconName.Android
        ProjectType.FLUTTER -> RuntimeIconName.Flutter
        ProjectType.REVERSE -> RuntimeIconName.Reverse
        ProjectType.GENERAL -> RuntimeIconName.Code
    }

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (isBuilding) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        onClick = onOpenExplorer,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconTile(typeIcon, size = 40.dp, color = typeBadgeColor)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Surface(
                            color = if (isBuilding) MaterialTheme.colorScheme.primaryContainer else typeBadgeColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = if (isBuilding) stringResource(R.string.workspace_building_badge) else project.projectType.displayName,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = if (isBuilding) MaterialTheme.colorScheme.primary else typeBadgeColor,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Text(
                        text = "${project.linuxPath} · ${project.sizeBytes.toReadableSize()}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Box {
                    IconButton(
                        onClick = { moreExpanded = true },
                        enabled = !busy,
                        modifier = Modifier
                            .size(32.dp)
                            .minimumInteractiveComponentSize(),
                        contentDescription = stringResource(R.string.workspace_cd_more),
                    ) {
                        RuntimeIcon(RuntimeIconName.More, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(
                        expanded = moreExpanded,
                        onDismissRequest = { moreExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_open_terminal)) },
                            leadingIcon = {
                                RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(17.dp))
                            },
                            onClick = {
                                moreExpanded = false
                                onOpenTerminal()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_export_project)) },
                            leadingIcon = {
                                RuntimeIcon(RuntimeIconName.Compress, Modifier.size(17.dp))
                            },
                            onClick = {
                                moreExpanded = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_delete_project), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                RuntimeIcon(RuntimeIconName.Trash, Modifier.size(17.dp), MaterialTheme.colorScheme.error)
                            },
                            onClick = {
                                moreExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            if (isBuilding) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp)),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // 快捷操作栏：精简去噪，只保留最核心的直达动作
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                // 只有可编译运行工程（Android / Flutter）显示"运行到手机"按钮；逆向/通用工程走终端
                if (project.projectType == top.wanxiang.app.runtime.ProjectType.ANDROID ||
                    project.projectType == top.wanxiang.app.runtime.ProjectType.FLUTTER
                ) {
                    FilledTonalButton(
                        onClick = if (isBuilding) onShowBuildLog else onRunProject,
                        enabled = !busy || isBuilding,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (isBuilding) {
                                RuntimeCircularProgressIndicator(
                                    modifier = Modifier.size(13.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(stringResource(R.string.workspace_compiling), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                            } else {
                                RuntimeIcon(RuntimeIconName.Play, Modifier.size(14.dp), tint = typeBadgeColor)
                                Text(stringResource(R.string.workspace_run_on_device), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = typeBadgeColor)
                            }
                        }
                    }
                } else {
                    TextButton(
                        onClick = onOpenTerminal,
                        enabled = !busy,
                        modifier = Modifier.padding(end = 4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(15.dp), MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.workspace_terminal), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

            }
        }
    }
}

private fun Long.toReadableSize(): String = when {
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> "%.1f KB".format(this / 1024.0)
    this < 1024 * 1024 * 1024 -> "%.1f MB".format(this / (1024.0 * 1024))
    else -> "%.1f GB".format(this / (1024.0 * 1024 * 1024))
}

// ==================== APK 逆向模板：已安装应用选择器 ====================

/** 已安装应用的应用名（label），失败时回退包名。 */
private fun ApplicationInfo.appLabel(context: Context): String =
    runCatching { loadLabel(context.packageManager).toString() }.getOrDefault(packageName)

/** 通过 SAF 查询 URI 的显示文件名。 */
private fun queryDisplayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

@Composable
private fun AppPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (ApplicationInfo) -> Unit,
) {
    val context = LocalContext.current
    val apps = remember {
        runCatching {
            context.packageManager.getInstalledApplications(0)
                .filter { it.sourceDir != null && java.io.File(it.sourceDir).isFile }
                .sortedBy { it.appLabel(context).lowercase() }
        }.getOrDefault(emptyList())
    }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_choose_installed_app), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (apps.isEmpty()) {
                    Text(
                        stringResource(R.string.workspace_apps_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.workspace_choose_app_description),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        val label = app.appLabel(context)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(app) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) {
                                RuntimeIcon(
                                    RuntimeIconName.Package,
                                    Modifier.size(22.dp),
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(start = 52.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_cancel)) } },
    )
}
