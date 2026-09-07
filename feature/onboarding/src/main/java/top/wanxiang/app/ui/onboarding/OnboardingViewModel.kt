package top.wanxiang.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wanxiang.app.core.datastore.OnboardingPreferences
import top.wanxiang.app.core.model.RuntimeState
import top.wanxiang.app.core.tools.ProviderRepository
import top.wanxiang.app.core.tools.AgentProviderCatalog
import top.wanxiang.app.core.tools.AgentModelDiscovery
import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.RegistryRoute
import top.wanxiang.app.runtime.RuntimeInstallRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import top.wanxiang.app.core.tools.ProviderEndpointPolicy

data class OnboardingStatus(val loaded: Boolean = false, val completed: Boolean = false)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: OnboardingPreferences,
    private val linuxRuntime: LinuxRuntime,
    private val providerRepository: ProviderRepository,
    private val providerCatalogRepository: AgentProviderCatalog,
    private val modelDiscovery: AgentModelDiscovery,
    private val toolManager: top.wanxiang.app.core.tools.ToolManager,
    private val profileWriter: top.wanxiang.app.core.tools.AiProfileWriter,
    private val profileBackupCodec: top.wanxiang.app.core.tools.AiProfileBackupCodec,
) : ViewModel() {
    val providerCatalog = providerCatalogRepository.providers

    /** DataStore 偏好读盘完成信号：Splash 退场只等这里（毫秒级），不等 Linux Runtime 恢复。 */
    private val prefsLoaded = MutableStateFlow(false)
    val status: StateFlow<OnboardingStatus> = combine(
        prefsLoaded,
        settings.onboardingCompleted,
    ) { loaded, completed ->
        // completed 不再依赖 RuntimeState.Ready：已完成引导的用户直接进主界面，
        // 沙箱恢复在后台继续；首页/向导各自已有初始化进度与错误态展示。
        OnboardingStatus(loaded = loaded, completed = completed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingStatus())
    val runtimeState: StateFlow<RuntimeState> = linuxRuntime.state

    val devSuites: List<top.wanxiang.app.core.model.PluginBundle> = top.wanxiang.app.core.model.BuiltinPluginBundles.bundles

    private val _selectedSuites = MutableStateFlow<Set<String>>(
        top.wanxiang.app.core.model.BuiltinPluginBundles.bundles.map { it.id }.toSet(),
    )
    val selectedSuites = _selectedSuites.asStateFlow()

    private val _isInstallingPlugins = MutableStateFlow(false)
    val isInstallingPlugins = _isInstallingPlugins.asStateFlow()

    private val _pluginInstallProgress = MutableStateFlow<String?>(null)
    val pluginInstallProgress = _pluginInstallProgress.asStateFlow()

    private val _distribution = MutableStateFlow("ubuntu")
    val distribution = _distribution.asStateFlow()
    private val _mirror = MutableStateFlow("auto")
    val mirror = _mirror.asStateFlow()
    private val _page = MutableStateFlow(0)
    val page = _page.asStateFlow()
    private val _modelProvider = MutableStateFlow("自定义 OpenAI 兼容接口")
    val modelProvider = _modelProvider.asStateFlow()
    private val _modelName = MutableStateFlow("")
    val modelName = _modelName.asStateFlow()
    private val _modelId = MutableStateFlow("")
    val modelId = _modelId.asStateFlow()
    private val _baseUrl = MutableStateFlow("")
    val baseUrl = _baseUrl.asStateFlow()
    private val _apiKey = MutableStateFlow("")
    val apiKey = _apiKey.asStateFlow()
    private val _importedProfile = MutableStateFlow<top.wanxiang.app.core.model.AiModelProfileExport?>(null)
    val importedProfile = _importedProfile.asStateFlow()
    private val _discoveredModels = MutableStateFlow<List<String>>(emptyList())
    val discoveredModels = _discoveredModels.asStateFlow()
    private val _discoveringModels = MutableStateFlow(false)
    val discoveringModels = _discoveringModels.asStateFlow()
    private val _modelDiscoveryError = MutableStateFlow<String?>(null)
    val modelDiscoveryError = _modelDiscoveryError.asStateFlow()
    private var discoveryDebounceJob: Job? = null
    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()
    private val _importError = MutableStateFlow<String?>(null)
    val importError = _importError.asStateFlow()

    init {
        viewModelScope.launch {
            _distribution.value = settings.selectedDistribution.first()
            _mirror.value = settings.mirrorPolicy.first()
            prefsLoaded.value = true
        }
        viewModelScope.launch {
            restoreInstalledState()
        }
    }

    fun selectDistribution(distribution: String) {
        _distribution.value = distribution
        viewModelScope.launch { settings.setSelectedDistribution(distribution) }
    }

    fun selectMirror(mirror: String) {
        _mirror.value = mirror
        viewModelScope.launch { settings.setMirrorPolicy(mirror) }
    }

    fun setPage(page: Int) {
        _page.value = page
    }

    fun setModelProvider(provider: String) {
        _modelProvider.value = provider
    }

    fun setModelId(id: String) {
        _modelId.value = id
    }

    fun setBaseUrl(url: String) {
        _baseUrl.value = url
    }

    fun setApiKey(key: String) {
        _apiKey.value = key
    }

    fun selectProvider(id: String) {
        val preset = providerCatalogRepository.find(id)
        _modelProvider.value = preset.name
        _baseUrl.value = preset.baseUrl
        _modelId.value = preset.recommendedModels.firstOrNull().orEmpty()
        _discoveredModels.value = emptyList()
        _modelDiscoveryError.value = null
    }

    private fun scheduleModelDiscovery(delayMillis: Long = 600) {
        discoveryDebounceJob?.cancel()
        val cleanUrl = ProviderEndpointPolicy.normalizeUrl(_baseUrl.value)
        if (!ProviderEndpointPolicy.isSafeBaseUrl(cleanUrl)) return
        discoveryDebounceJob = viewModelScope.launch {
            delay(delayMillis)
            discoveryDebounceJob = null
            startModelDiscovery(cleanUrl)
        }
    }

    fun discoverModels() {
        discoveryDebounceJob?.cancel()
        discoveryDebounceJob = null
        val cleanUrl = ProviderEndpointPolicy.normalizeUrl(_baseUrl.value)
        if (!ProviderEndpointPolicy.isSafeBaseUrl(cleanUrl)) return
        startModelDiscovery(cleanUrl)
    }

    private fun startModelDiscovery(cleanUrl: String) {
        _discoveringModels.value = true
        _modelDiscoveryError.value = null
        _discoveredModels.value = emptyList()
        viewModelScope.launch {
            val preset = providerCatalog.firstOrNull { it.name == _modelProvider.value } ?: providerCatalogRepository.find("custom")
            runCatching { modelDiscovery.discover(preset, cleanUrl, _apiKey.value.ifBlank { null }) }
                .onSuccess { models ->
                    _discoveredModels.value = models
                    models.firstOrNull()?.let { _modelId.value = it }
                }
                .onFailure { _modelDiscoveryError.value = it.message ?: "刷新模型失败" }
            _discoveringModels.value = false
        }
    }

    fun install() {
        if (runtimeState.value is RuntimeState.Initializing) return
        viewModelScope.launch {
            settings.setSelectedDistribution(_distribution.value)
            settings.setMirrorPolicy(_mirror.value)
            linuxRuntime.initialize(
                RuntimeInstallRequest(
                    distributionId = _distribution.value,
                    registryRoute = when (_mirror.value) {
                        "official" -> RegistryRoute.OFFICIAL
                        "china" -> RegistryRoute.CHINA_ACCELERATED
                        else -> RegistryRoute.AUTO
                    },
                ),
            )
            if (linuxRuntime.state.value is RuntimeState.Ready) _page.value = 1
        }
    }

    fun importArchive(uri: Uri) {
        if (runtimeState.value is RuntimeState.Initializing || _isImporting.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val imported = File(context.cacheDir, "rootfs-import-${System.currentTimeMillis()}")
            _isImporting.value = true
            _importError.value = null
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    imported.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取所选文件")
                settings.setSelectedDistribution(_distribution.value)
                settings.setMirrorPolicy(_mirror.value)
                val result = linuxRuntime.importDistro(
                    RuntimeInstallRequest(distributionId = _distribution.value),
                    imported,
                )
                result.errorOrNull()?.let { error ->
                    _importError.value = error.message
                }
                if (linuxRuntime.state.value is RuntimeState.Ready) _page.value = 1
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _importError.value = throwable.message ?: "无法导入所选 RootFS 文件"
            } finally {
                imported.delete()
                _isImporting.value = false
            }
        }
    }

    fun restoreInstalledState() {
        viewModelScope.launch {
            // 后台恢复已安装的 Linux 环境：PRoot 健康探针、镜像配置等重活不再阻塞首帧。
            linuxRuntime.restoreInstalledState()
            if (!settings.onboardingCompleted.first() && linuxRuntime.state.value is RuntimeState.Ready) {
                _page.value = 1
            }
        }
    }

    fun retryReady() {
        if (linuxRuntime.state.value is RuntimeState.Initializing) return
        restoreInstalledState()
    }

    fun toggleSuite(id: String) {
        val current = _selectedSuites.value
        _selectedSuites.value = if (id in current) current - id else current + id
    }

    fun skipSuites() {
        _page.value = 2
    }

    fun installSelectedSuitesAndProceed() {
        val selected = _selectedSuites.value.toSet()
        _page.value = 2
        if (selected.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            _isInstallingPlugins.value = true
            try {
                toolManager.batchInstallSuites(selected).collect { event ->
                    if (event is top.wanxiang.app.runtime.tools.InstallEvent.Progress) {
                        _pluginInstallProgress.value = event.message
                    }
                }
            } catch (e: Exception) {
                // 忽略异常保证流程顺畅
            } finally {
                _isInstallingPlugins.value = false
                _pluginInstallProgress.value = null
            }
        }
    }

    fun setModelName(name: String) {
        _modelName.value = name
    }

    fun importProfileFromJson(rawJson: String): Result<top.wanxiang.app.core.model.AiModelProfileExport> {
        return profileBackupCodec.parseProfiles(rawJson).mapCatching { profiles ->
            val profile = profiles.firstOrNull() ?: error("导入包中没有模型档案")
            require(profile.model.isNotBlank() || profile.name.isNotBlank()) { "模型配置缺少模型名称或 ID" }

            _modelProvider.value = profile.provider.ifBlank { "OpenAI" }
            _baseUrl.value = profile.baseUrl
            val effectiveKey = profile.apiKeys.firstOrNull() ?: profile.apiKey.orEmpty()
            _apiKey.value = effectiveKey
            val primaryModel = profile.model.split(",").firstOrNull { it.isNotBlank() }?.trim() ?: profile.model
            _modelId.value = primaryModel.ifBlank { profile.name }
            _modelName.value = profile.name
            _importedProfile.value = profile
            profile
        }
    }

    fun skipModel() = finish()

    fun saveModelAndFinish() {
        val model = _modelId.value.trim()
        if (model.isBlank()) return
        val imported = _importedProfile.value
        viewModelScope.launch {
            val keyToSave = if (_apiKey.value.isNotBlank()) {
                _apiKey.value.trim()
            } else if (imported != null && imported.apiKeys.isNotEmpty()) {
                imported.apiKeys.joinToString("\n")
            } else {
                imported?.apiKey.orEmpty()
            }
            val modelList = if (imported != null && imported.model.isNotBlank()) {
                imported.model
            } else {
                model
            }
            profileWriter.upsertProfile(
                top.wanxiang.app.core.tools.AiProfileWriter.UpsertRequest(
                    name = _modelName.value.ifBlank { model },
                    provider = _modelProvider.value,
                    model = modelList,
                    baseUrl = _baseUrl.value,
                    apiKey = keyToSave,
                    requestsPerMinutePerKey = imported?.requestsPerMinutePerKey ?: 0,
                    temperature = imported?.temperature,
                    maxTokens = imported?.maxTokens,
                    topP = imported?.topP,
                    reasoningMode = imported?.reasoningMode,
                    reasoningEffort = imported?.reasoningEffort,
                    toolCallMode = imported?.toolCallMode,
                    contextTokens = imported?.contextTokens,
                    customHeaders = imported?.customHeaders.orEmpty(),
                    pureChatMode = imported?.pureChatMode ?: false,
                    visionEnabled = imported?.visionEnabled ?: true,
                    imageGenerationEnabled = imported?.imageGenerationEnabled ?: false,
                    responseApiEnabled = imported?.responseApiEnabled ?: false,
                ),
            )
            finish()
        }
    }

    private fun finish() {
        viewModelScope.launch { settings.setOnboardingCompleted(true) }
    }
}
