package top.wanxiang.app.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wanxiang.app.runtime.WorkspaceFileItem
import top.wanxiang.app.runtime.WorkspaceManager
import top.wanxiang.app.runtime.WorkspaceProject
import top.wanxiang.app.runtime.WorkspaceStorage
import top.wanxiang.app.core.common.result.AppResult
import top.wanxiang.app.template.InstalledProjectTemplate
import top.wanxiang.app.template.ProjectTemplateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import top.wanxiang.app.feature.workspace.R
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspaceManager: WorkspaceManager,
    private val buildCoordinator: WorkspaceBuildTaskCoordinator,
    private val toolManager: top.wanxiang.app.core.tools.ToolManager,
    private val linuxRuntime: top.wanxiang.app.runtime.LinuxRuntime,
    private val workshopPreferences: top.wanxiang.app.core.datastore.WorkshopPreferences,
    private val projectTemplateStore: ProjectTemplateStore,
    private val settingsDataStore: top.wanxiang.app.core.datastore.SettingsDataStore,
) : ViewModel() {

    /** 首次使用引导登记（统一存于 SettingsDataStore，设置页可整体清空重看）。 */
    val firstUseGuidesShown: StateFlow<Set<String>> = settingsDataStore.firstUseGuidesShown
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun markFirstUseGuideShown(id: String) {
        viewModelScope.launch { settingsDataStore.markFirstUseGuideShown(id) }
    }

    private val _projectTemplates = MutableStateFlow<List<InstalledProjectTemplate>>(emptyList())
    val projectTemplates: StateFlow<List<InstalledProjectTemplate>> = _projectTemplates.asStateFlow()
    private val _templateScriptPreview = MutableStateFlow<String?>(null)
    val templateScriptPreview: StateFlow<String?> = _templateScriptPreview.asStateFlow()

    // ==================== 聚合开发套件与子组件状态 ====================
    val pluginBundles: List<top.wanxiang.app.core.model.PluginBundle> = top.wanxiang.app.core.model.BuiltinPluginBundles.bundles

    private val _installedComponentIds = MutableStateFlow<Set<String>>(emptySet())
    val installedComponentIds: StateFlow<Set<String>> = _installedComponentIds.asStateFlow()

    /** Linux 沙箱是否已初始化就绪（组件探针依赖它，未就绪时探针结果不可信）。 */
    val runtimeReady: StateFlow<Boolean> = linuxRuntime.state
        .map { it is top.wanxiang.app.core.model.RuntimeState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _activeBundleForSetup = MutableStateFlow<top.wanxiang.app.core.model.PluginBundle?>(null)
    val activeBundleForSetup: StateFlow<top.wanxiang.app.core.model.PluginBundle?> = _activeBundleForSetup.asStateFlow()

    private val _selectedComponents = MutableStateFlow<Set<String>>(emptySet())
    val selectedComponents: StateFlow<Set<String>> = _selectedComponents.asStateFlow()

    private val _isInstallingComponents = MutableStateFlow(false)
    val isInstallingComponents: StateFlow<Boolean> = _isInstallingComponents.asStateFlow()

    private val _suiteInstallProgress = MutableStateFlow<String?>(null)
    val suiteInstallProgress: StateFlow<String?> = _suiteInstallProgress.asStateFlow()

    init {
        refreshInstalledStatus()
        refreshProjectTemplates()
    }

    fun refreshProjectTemplates() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _projectTemplates.value = projectTemplateStore.list()
        }
    }

    fun importProjectTemplate(uri: String) {
        if (_busy.value) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _busy.value = true
            runCatching {
                val input = requireNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(uri))) {
                    "无法读取模板文件"
                }
                input.use(projectTemplateStore::importZip)
            }.onSuccess { template ->
                refreshProjectTemplates()
                notify(context.getString(R.string.workspace_template_imported, template.manifest.name))
            }.onFailure { error ->
                notify(error.message ?: context.getString(R.string.workspace_template_import_failed), isError = true)
            }
            _busy.value = false
        }
    }

    fun exportProjectTemplate(id: String, uri: String) {
        if (_busy.value) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _busy.value = true
            runCatching {
                val output = requireNotNull(context.contentResolver.openOutputStream(android.net.Uri.parse(uri), "wt")) {
                    "无法创建模板文件"
                }
                output.use { projectTemplateStore.exportZip(id, it) }
            }.onSuccess {
                notify(context.getString(R.string.workspace_template_exported))
            }.onFailure { error ->
                notify(error.message ?: context.getString(R.string.workspace_template_export_failed), isError = true)
            }
            _busy.value = false
        }
    }

    fun deleteProjectTemplate(id: String) {
        if (_busy.value) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _busy.value = true
            runCatching { projectTemplateStore.delete(id) }
                .onSuccess {
                    refreshProjectTemplates()
                    notify(context.getString(R.string.workspace_template_deleted))
                }
                .onFailure { error -> notify(error.message ?: context.getString(R.string.workspace_template_delete_failed), isError = true) }
            _busy.value = false
        }
    }

    fun showTemplateScripts(id: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _templateScriptPreview.value = runCatching {
                projectTemplateStore.hookScripts(id).joinToString("\n\n") { (path, script) ->
                    "===== $path =====\n$script"
                }.ifBlank { "该模板没有构造脚本" }
            }.getOrElse { error -> "读取脚本失败：${error.message.orEmpty()}" }
        }
    }

    fun dismissTemplateScripts() {
        _templateScriptPreview.value = null
    }

    fun refreshInstalledStatus() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                _installedComponentIds.value = toolManager.probeInstalledComponents()
            } catch (_: Exception) {}
        }
    }

    fun openDevEnvironmentDialog(suggestedSuiteId: String? = null) {
        val targetBundle = if (suggestedSuiteId != null) {
            when (suggestedSuiteId) {
                "android_sdk", "android-suite" -> pluginBundles.firstOrNull { it.id == "android-suite" }
                "flutter", "flutter-suite", "flutter-core" -> pluginBundles.firstOrNull { it.id == "android-suite" }
                else -> pluginBundles.firstOrNull { it.id == suggestedSuiteId }
            } ?: pluginBundles.first()
        } else {
            pluginBundles.first()
        }
        val installed = _installedComponentIds.value
        val initial = targetBundle.components.filter { it.isRequired || it.id in installed }.map { it.id }.toSet()
        _selectedComponents.value = if (initial.isEmpty()) targetBundle.components.map { it.id }.toSet() else initial
        _activeBundleForSetup.value = targetBundle
    }

    fun selectBundleTab(bundle: top.wanxiang.app.core.model.PluginBundle) {
        val installed = _installedComponentIds.value
        val initial = bundle.components.filter { it.isRequired || it.id in installed }.map { it.id }.toSet()
        _selectedComponents.value = if (initial.isEmpty()) bundle.components.map { it.id }.toSet() else initial
        _activeBundleForSetup.value = bundle
    }

    fun closeDevEnvironmentDialog() {
        if (!_isInstallingComponents.value) {
            _activeBundleForSetup.value = null
        }
    }

    fun toggleComponent(component: top.wanxiang.app.core.model.PluginComponent) {
        if (component.isRequired) return // 锁定必选
        val current = _selectedComponents.value
        _selectedComponents.value = if (component.id in current) current - component.id else current + component.id
    }

    fun installSelectedComponents() {
        val selected = _selectedComponents.value
        if (selected.isEmpty()) return

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isInstallingComponents.value = true
            try {
                toolManager.batchInstallComponents(selected).collect { event ->
                    if (event is top.wanxiang.app.runtime.tools.InstallEvent.Progress) {
                        _suiteInstallProgress.value = event.message
                    }
                }
                refreshInstalledStatus()
                notify(context.getString(R.string.workspace_suite_installed))
                _activeBundleForSetup.value = null
            } catch (e: Exception) {
                notify(context.getString(R.string.workspace_deploy_failed, e.message.orEmpty()), isError = true)
            } finally {
                _isInstallingComponents.value = false
                _suiteInstallProgress.value = null
            }
        }
    }

    // 兼容原 devSuites 接口
    val devSuites: List<top.wanxiang.app.core.model.PluginBundle> get() = pluginBundles
    val showDevSuiteDialog: StateFlow<Boolean> get() = MutableStateFlow(_activeBundleForSetup.value != null).asStateFlow()
    val selectedDevSuites: StateFlow<Set<String>> get() = _selectedComponents
    val isInstallingSuites: StateFlow<Boolean> get() = _isInstallingComponents

    // ==================== 项目列表状态 ====================
    private val _loadingProjects = MutableStateFlow(true)
    val loadingProjects: StateFlow<Boolean> = _loadingProjects.asStateFlow()

    val projects: StateFlow<List<WorkspaceProject>> = workspaceManager.observeProjects()
        .onEach { if (_loadingProjects.value) _loadingProjects.value = false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 与 [message] 平行的错误标记：UI 依据它决定横幅样式，避免用文案关键字判错。 */
    private val _messageIsError = MutableStateFlow(false)
    val messageIsError: StateFlow<Boolean> = _messageIsError.asStateFlow()

    private fun notify(text: String, isError: Boolean = false) {
        _message.value = text
        _messageIsError.value = isError
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * GitHub 拉取进度：仅在进行中的 git clone 期间非空。
     * [text] 为 git 输出的最新一行（remote:/Receiving objects: 等，\r 分隔的进度更新取末段），
     * [percent] 从输出中解析的百分比（0-100），无法解析时为 null（UI 显示不确定进度条）。
     */
    data class GithubImportProgress(val text: String, val percent: Int?)

    private val _githubImportProgress = MutableStateFlow<GithubImportProgress?>(null)
    val githubImportProgress: StateFlow<GithubImportProgress?> = _githubImportProgress.asStateFlow()

    // 运行/构建状态
    val buildProgress: StateFlow<top.wanxiang.app.runtime.build.BuildRunProgress?> = buildCoordinator.state
        .map { it?.progress }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildCoordinator.state.value?.progress)

    val activeBuildingProjectName: StateFlow<String?> = buildCoordinator.state
        .map { state -> state?.takeIf { it.progress.isRunning }?.project?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _isBuildDialogVisible = MutableStateFlow<Boolean>(false)
    val isBuildDialogVisible: StateFlow<Boolean> = _isBuildDialogVisible.asStateFlow()

    /** 工坊已登记的 Android 签名（Release 构建时选择）。 */
    val keystores: StateFlow<List<top.wanxiang.app.core.datastore.WorkshopKeystore>> = workshopPreferences.keystores
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ==================== 文件浏览器状态 ====================
    private val _selectedProject = MutableStateFlow<String?>(null)
    val selectedProject: StateFlow<String?> = _selectedProject.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _fileItems = MutableStateFlow<List<WorkspaceFileItem>>(emptyList())
    val fileItems: StateFlow<List<WorkspaceFileItem>> = _fileItems.asStateFlow()

    private val _loadingFiles = MutableStateFlow(false)
    val loadingFiles: StateFlow<Boolean> = _loadingFiles.asStateFlow()

    /**
     * 当前浏览的项目位于宿主共享存储（/storage/emulated/0），
     * 且缺少"所有文件访问"权限——此时系统会过滤其他应用的文件（只显示文件夹），
     * 文件浏览器需要展示授权引导横幅。
     */
    private val _sharedStorageAccessLimited = MutableStateFlow(false)
    val sharedStorageAccessLimited: StateFlow<Boolean> = _sharedStorageAccessLimited.asStateFlow()

    // ==================== 代码编辑器状态 ====================
    private val _openedFilePath = MutableStateFlow<String?>(null)
    val openedFilePath: StateFlow<String?> = _openedFilePath.asStateFlow()

    private val _openedFileExtension = MutableStateFlow("")
    val openedFileExtension: StateFlow<String> = _openedFileExtension.asStateFlow()

    private val _fileContent = MutableStateFlow("")
    val fileContent: StateFlow<String> = _fileContent.asStateFlow()

    private var originalContent: String = ""

    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    init {
        refresh()
    }

    fun clearMessage() {
        _message.value = null
        _messageIsError.value = false
    }

    fun showBuildDialog() {
        _isBuildDialogVisible.value = true
    }

    fun hideBuildDialog() {
        _isBuildDialogVisible.value = false
    }

    fun dismissBuildProgress() {
        _isBuildDialogVisible.value = false
        buildCoordinator.dismiss()
    }

    fun refresh() {
        viewModelScope.launch {
            workspaceManager.listProjects()
        }
    }

    // ==================== 项目级操作 ====================

    fun create(
        name: String,
        storage: WorkspaceStorage = WorkspaceStorage.INTERNAL,
        directoryPath: String = "",
        template: top.wanxiang.app.runtime.ProjectTemplate = top.wanxiang.app.runtime.ProjectTemplate.EMPTY,
        packageName: String = "",
        apkSource: top.wanxiang.app.runtime.ApkImportSource? = null,
        exportApkToDownload: Boolean = false,
        gitUrl: String = "",
        templateVariables: Map<String, String> = emptyMap(),
        templateId: String = "",
        trustTemplateScripts: Boolean = false,
    ) {
        if (_busy.value) return
        // 同步置 busy：UI 依据它把创建弹窗保持在"进行中"状态（模板实例化可能耗时 60 秒）
        _busy.value = true
        viewModelScope.launch {
            val result = workspaceManager.createProject(
                name,
                storage,
                directoryPath,
                template,
                packageName,
                apkSource,
                exportApkToDownload,
                gitUrl,
                templateVariables,
                templateId,
                trustTemplateScripts,
            )
            _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_project_created)
            _messageIsError.value = result.isFailure
            if (result.isSuccess) workspaceManager.listProjects()
            _busy.value = false
        }
    }

    fun importLocalProject(
        name: String,
        directoryPath: String,
        projectType: top.wanxiang.app.runtime.ProjectType,
        source: top.wanxiang.app.runtime.ProjectArchiveSource,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = workspaceManager.importProjectArchive(name, directoryPath, projectType, source)
            _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_project_imported)
            _messageIsError.value = result.isFailure
            if (result.isSuccess) workspaceManager.listProjects()
            _busy.value = false
        }
    }

    fun importGithubProject(
        name: String,
        directoryPath: String,
        projectType: top.wanxiang.app.runtime.ProjectType,
        gitUrl: String,
        transport: top.wanxiang.app.runtime.GitTransport,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _githubImportProgress.value = GithubImportProgress(
                text = context.getString(R.string.workspace_git_clone_connecting),
                percent = null,
            )
            val result = workspaceManager.importGithubProject(name, directoryPath, projectType, gitUrl, transport) { chunk ->
                // git 进度用 \r 原地刷新，取 chunk 内最后一个非空片段作为最新状态
                val latest = chunk.split('\r', '\n').lastOrNull { it.isNotBlank() }?.trim()
                if (latest != null) {
                    _githubImportProgress.value = GithubImportProgress(
                        text = latest,
                        percent = GIT_PERCENT_REGEX.findAll(latest).lastOrNull()
                            ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100),
                    )
                }
            }
            _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_project_imported)
            _messageIsError.value = result.isFailure
            if (result.isSuccess) workspaceManager.listProjects()
            _githubImportProgress.value = null
            _busy.value = false
        }
    }

    fun exportProject(project: WorkspaceProject, targetTreeUri: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = workspaceManager.exportProject(project.name, targetTreeUri)
            _message.value = result.errorOrNull()?.message
                ?: context.getString(R.string.workspace_project_exported, result.getOrNull().orEmpty())
            _messageIsError.value = result.isFailure
            _busy.value = false
        }
    }

    fun runProject(
        project: WorkspaceProject,
        buildType: top.wanxiang.app.runtime.build.WorkshopBuildType = top.wanxiang.app.runtime.build.WorkshopBuildType.DEBUG,
        keystore: top.wanxiang.app.core.datastore.WorkshopKeystore? = null,
    ) {
        if (buildCoordinator.state.value?.progress?.isRunning == true) {
            _isBuildDialogVisible.value = true
            return
        }
        _isBuildDialogVisible.value = true
        buildCoordinator.start(project, buildType, keystore)
    }

    /** 用户主动取消编译任务 */
    fun cancelBuild() {
        buildCoordinator.cancel()
    }

    fun launchInstaller(apkPath: String) {
        buildCoordinator.launchPackageInstaller(apkPath)
    }

    fun delete(name: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = workspaceManager.deleteProject(name)
            _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_project_deleted)
            _messageIsError.value = result.isFailure
            if (result.isSuccess) workspaceManager.listProjects()
            _busy.value = false
        }
    }

    // ==================== 文件浏览器操作 ====================

    fun loadExplorer(projectName: String, relativePath: String = "") {
        _selectedProject.value = projectName
        _currentPath.value = relativePath.trim().removePrefix("/")
        refreshDirectory()
    }

    fun navigateToDirectory(relativePath: String) {
        _currentPath.value = relativePath.trim().removePrefix("/")
        refreshDirectory()
    }

    fun navigateUp() {
        val current = _currentPath.value
        if (current.isBlank()) return
        val parent = current.substringBeforeLast('/', "")
        _currentPath.value = parent
        refreshDirectory()
    }

    fun refreshDirectory() {
        val proj = _selectedProject.value ?: return
        val path = _currentPath.value
        viewModelScope.launch {
            _loadingFiles.value = true
            refreshSharedStorageAccessLimited(proj)
            val result = workspaceManager.listFiles(proj, path)
            if (result.isSuccess) {
                _fileItems.value = result.getOrNull().orEmpty()
            } else {
                notify(result.errorOrNull()?.message ?: context.getString(R.string.workspace_read_directory_failed), isError = true)
            }
            _loadingFiles.value = false
        }
    }

    /** 从系统授权页返回后调用：重新评估权限状态并刷新目录。 */
    fun refreshAfterPermissionReturn() {
        val proj = _selectedProject.value ?: return
        viewModelScope.launch {
            refreshSharedStorageAccessLimited(proj)
            refreshDirectory()
        }
    }

    private suspend fun refreshSharedStorageAccessLimited(projectName: String) {
        _sharedStorageAccessLimited.value =
            workspaceManager.usesSharedStorage(projectName) && !hasSharedStorageAccess()
    }

    private fun hasSharedStorageAccess(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** 文件增删改的统一模板：取项目 → 执行 → 成功刷新目录/失败写消息 → 复位 busy */
    private fun runFileOp(
        successMessage: suspend () -> String,
        fallbackError: Int,
        op: suspend () -> AppResult<Unit>,
    ) {
        val proj = _selectedProject.value ?: return
        viewModelScope.launch {
            _busy.value = true
            val result = op()
            if (result.isSuccess) {
                notify(successMessage())
                refreshDirectory()
            } else {
                notify(result.errorOrNull()?.message ?: context.getString(fallbackError), isError = true)
            }
            _busy.value = false
        }
    }

    fun createFile(name: String) {
        val proj = _selectedProject.value ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        if (!isValidWorkspaceEntryName(trimmed)) {
            notify(context.getString(R.string.workspace_invalid_name), isError = true)
            return
        }
        val fullRelative = if (_currentPath.value.isBlank()) trimmed else "${_currentPath.value}/$trimmed"
        runFileOp(
            successMessage = { context.getString(R.string.workspace_file_created, trimmed) },
            fallbackError = R.string.workspace_create_file_failed,
        ) { workspaceManager.createFile(proj, fullRelative) }
    }

    fun createDirectory(name: String) {
        val proj = _selectedProject.value ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        if (!isValidWorkspaceEntryName(trimmed)) {
            notify(context.getString(R.string.workspace_invalid_name), isError = true)
            return
        }
        val fullRelative = if (_currentPath.value.isBlank()) trimmed else "${_currentPath.value}/$trimmed"
        runFileOp(
            successMessage = { context.getString(R.string.workspace_directory_created, trimmed) },
            fallbackError = R.string.workspace_create_directory_failed,
        ) { workspaceManager.createDirectory(proj, fullRelative) }
    }

    fun renameItem(oldRelativePath: String, newName: String) {
        val proj = _selectedProject.value ?: return
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        if (!isValidWorkspaceEntryName(trimmed)) {
            notify(context.getString(R.string.workspace_invalid_name), isError = true)
            return
        }
        runFileOp(
            successMessage = { context.getString(R.string.workspace_renamed, trimmed) },
            fallbackError = R.string.workspace_rename_failed,
        ) { workspaceManager.renameItem(proj, oldRelativePath, trimmed) }
    }

    fun deleteItem(relativePath: String) {
        val proj = _selectedProject.value ?: return
        runFileOp(
            successMessage = { context.getString(R.string.workspace_deleted) },
            fallbackError = R.string.workspace_delete_failed,
        ) { workspaceManager.deleteItem(proj, relativePath) }
    }

    // ==================== 编辑器操作 ====================

    fun openFile(projectName: String, relativePath: String) {
        _selectedProject.value = projectName
        _openedFilePath.value = relativePath
        val ext = relativePath.substringAfterLast('.', "")
        _openedFileExtension.value = ext
        viewModelScope.launch {
            _loadingFiles.value = true
            val result = workspaceManager.readFile(projectName, relativePath)
            if (result.isSuccess) {
                val content = result.getOrNull().orEmpty()
                originalContent = content
                _fileContent.value = content
                _isDirty.value = false
            } else {
                notify(result.errorOrNull()?.message ?: context.getString(R.string.workspace_open_file_failed), isError = true)
            }
            _loadingFiles.value = false
        }
    }

    fun onContentChanged(newText: String) {
        _fileContent.value = newText
        _isDirty.value = newText != originalContent
    }

    fun resetContent() {
        _fileContent.value = originalContent
        _isDirty.value = false
    }

    fun saveFile(onSuccess: (() -> Unit)? = null) {
        val proj = _selectedProject.value ?: return
        val path = _openedFilePath.value ?: return
        val text = _fileContent.value
        viewModelScope.launch {
            _isSaving.value = true
            val result = workspaceManager.writeFile(proj, path, text)
            if (result.isSuccess) {
                originalContent = text
                _isDirty.value = false
                notify(context.getString(R.string.workspace_file_saved))
                onSuccess?.invoke()
            } else {
                notify(result.errorOrNull()?.message ?: context.getString(R.string.workspace_save_failed), isError = true)
            }
            _isSaving.value = false
        }
    }

    fun closeFile() {
        _openedFilePath.value = null
        _fileContent.value = ""
        originalContent = ""
        _isDirty.value = false
    }
}

/**
 * 文件/文件夹名称合法性校验（UI 与 VM 共用同一规则）：
 * 不允许路径分隔符（/ 与 \\）、首尾空格、以 `.` 开头（隐藏文件）以及 `.` / `..` 等特殊名称。
 */
internal fun isValidWorkspaceEntryName(name: String): Boolean {
    if (name.isEmpty()) return false
    if (name.trim() != name) return false
    if (name.startsWith(".")) return false
    if (name == "." || name == "..") return false
    if (name.contains('/') || name.contains('\\')) return false
    return true
}

/** 从 git 克隆进度行提取百分比，如 "Receiving objects: 45% (50/110), 1.2 MiB"。 */
private val GIT_PERCENT_REGEX = Regex("""(\d{1,3})%""")
