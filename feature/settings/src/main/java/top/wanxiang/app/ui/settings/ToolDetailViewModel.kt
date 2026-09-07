package top.wanxiang.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.core.database.AiModelRepository
import top.wanxiang.app.core.database.AiModelEntity
import top.wanxiang.app.core.database.ToolEntity
import top.wanxiang.app.core.database.ToolSettingsRepository
import top.wanxiang.app.core.datastore.ToolPreferences
import top.wanxiang.app.core.model.ToolManifest
import top.wanxiang.app.core.tools.ProviderRepository
import top.wanxiang.app.core.tools.ToolManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import javax.inject.Inject

data class ToolDetailUiState(
    val tool: ToolEntity? = null,
    val manifest: ToolManifest? = null,
    val gatewayRunning: Boolean = false,
    val autoStartEnabled: Boolean = false,
    val accessToken: String? = null,
    val gatewayOperating: Boolean = false,
    val error: String? = null,
    val models: List<AiModelEntity> = emptyList(),
    val appliedModelId: String? = null,
    val applyingModel: Boolean = false,
    val deviceLanIp: String? = null,
    val serviceLogs: List<String> = emptyList(),
) {
    val isWebService: Boolean get() = manifest?.launchType == "web"
    val servicePort: Int? get() = manifest?.servicePort
    val activeModel: AiModelEntity? get() = models.firstOrNull { it.isActive }

    fun buildUrl(host: String): String? {
        val port = servicePort ?: return null
        val path = manifest?.servicePath ?: "/"
        return if (accessToken != null) {
            "http://$host:$port$path?token=$accessToken"
        } else {
            "http://$host:$port$path"
        }
    }

    /** 局域网访问链接（如 http://192.168.1.100:18789/?token=...） */
    val lanAccessUrl: String? get() = deviceLanIp?.let { buildUrl(it) }

    /** 0.0.0.0 绑定链接 */
    val allInterfacesAccessUrl: String? get() = buildUrl("0.0.0.0")

    /** 127.0.0.1 本地回环链接 */
    val loopbackAccessUrl: String? get() = buildUrl("127.0.0.1")

    /** 默认主推荐链接：局域网 IP > 0.0.0.0 > 127.0.0.1 */
    val accessUrl: String? get() = lanAccessUrl ?: allInterfacesAccessUrl ?: loopbackAccessUrl
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ToolDetailViewModel @Inject constructor(
    private val toolManager: ToolManager,
    private val settingsDataStore: ToolPreferences,
    private val providerRepository: ProviderRepository,
    private val aiModelDao: AiModelRepository,
    private val toolSettingsRepository: ToolSettingsRepository,
    private val linuxRuntime: top.wanxiang.app.runtime.LinuxRuntime,
    private val logger: AppLogger,
) : ViewModel() {

    private val _toolId = MutableStateFlow("")
    val toolId: StateFlow<String> = _toolId.asStateFlow()

    private val _gatewayRunning = MutableStateFlow(false)
    private val _gatewayOperating = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _appliedModelId = MutableStateFlow<String?>(null)
    private val _applyingModel = MutableStateFlow(false)
    private val _deviceLanIp = MutableStateFlow<String?>(null)

    fun setToolId(id: String) {
        if (id.isNotBlank() && _toolId.value != id) {
            _toolId.value = id
            viewModelScope.launch(Dispatchers.IO) {
                val lanIp = detectLanIp()
                val running = toolManager.isGatewayRunning(id)
                withContext(Dispatchers.Main.immediate) {
                    if (_toolId.value == id) {
                        _deviceLanIp.value = lanIp
                        _gatewayRunning.value = running
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<ToolDetailUiState> = combine(_toolId, linuxRuntime.activeDistroId) { id, distroId -> id to distroId }.flatMapLatest { (id, distroId) ->
        if (id.isBlank()) {
            flowOf(ToolDetailUiState())
        } else {
            combine(
                toolManager.observeTools(),
                toolSettingsRepository.autoStart(distroId, id),
                settingsDataStore.toolAccessToken(distroId, id),
                _gatewayRunning,
                _gatewayOperating,
                _error,
                aiModelDao.observeAll(),
                _appliedModelId,
                _applyingModel,
                _deviceLanIp,
                toolManager.observeServiceLogs(id),
            ) { values ->
                val tools = values[0] as List<ToolEntity>
                val autoStart = values[1] as Boolean
                val token = values[2] as String?
                val running = values[3] as Boolean
                val operating = values[4] as Boolean
                val error = values[5] as String?
                val models = values[6] as List<AiModelEntity>
                val appliedId = values[7] as String?
                val applying = values[8] as Boolean
                val lanIp = values[9] as String?
                val logs = values[10] as List<String>

                val tool = tools.firstOrNull { it.id == id }
                val manifest = toolManager.manifest(id)

                ToolDetailUiState(
                    tool = tool,
                    manifest = manifest,
                    gatewayRunning = running,
                    autoStartEnabled = autoStart,
                    accessToken = token,
                    gatewayOperating = operating,
                    error = error,
                    models = models,
                    appliedModelId = appliedId,
                    applyingModel = applying,
                    deviceLanIp = lanIp,
                    serviceLogs = logs,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ToolDetailUiState())

    init {
        pollGatewayStatus()
    }

    private fun pollGatewayStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val currentId = _toolId.value
                if (currentId.isNotBlank()) {
                    val running = toolManager.isGatewayRunning(currentId)
                    val lanIp = detectLanIp()
                    withContext(Dispatchers.Main.immediate) {
                        if (_toolId.value == currentId) {
                            _gatewayRunning.value = running
                            _deviceLanIp.value = lanIp
                        }
                    }
                }
                delay(3000)
            }
        }
    }

    fun startGateway() {
        val currentId = _toolId.value
        if (currentId.isBlank()) return
        _gatewayOperating.value = true
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // startGateway 内部会等待服务端口就绪后才返回，避免"假运行中"
                toolManager.startGateway(currentId)
                _gatewayRunning.value = toolManager.isGatewayRunning(currentId)
            } catch (e: Exception) {
                logger.w("Failed to start gateway for $currentId: ${e.message}", e)
                _error.value = "网关启动失败：${e.message}"
            } finally {
                _gatewayOperating.value = false
            }
        }
    }

    fun stopGateway() {
        val currentId = _toolId.value
        if (currentId.isBlank()) return
        _gatewayOperating.value = true
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                toolManager.stopGateway(currentId)
                delay(500)
                _gatewayRunning.value = toolManager.isGatewayRunning(currentId)
            } catch (e: Exception) {
                logger.w("Failed to stop gateway for $currentId: ${e.message}", e)
                _error.value = "网关停止失败：${e.message}"
            } finally {
                _gatewayOperating.value = false
            }
        }
    }

    fun toggleAutoStart(enabled: Boolean) {
        val currentId = _toolId.value
        if (currentId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                toolSettingsRepository.setAutoStart(linuxRuntime.activeDistroId.value, currentId, enabled)
            } catch (e: Exception) {
                logger.w("Failed to set auto-start for $currentId: ${e.message}", e)
                // 保存失败必须上抛 UI，避免 Switch 显示“假成功”
                withContext(Dispatchers.Main.immediate) {
                    _error.value = "自启动设置保存失败，请稍后重试"
                }
            }
        }
    }

    fun generateToken() {
        val currentId = _toolId.value
        if (currentId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val token = UUID.randomUUID().toString().replace("-", "").take(32)
                settingsDataStore.setToolAccessToken(linuxRuntime.activeDistroId.value, currentId, token)
                // 网关进程启动时通过环境变量注入 Token，运行中的实例必须重启才能应用新 Token
                if (toolManager.isGatewayRunning(currentId)) {
                    _gatewayOperating.value = true
                    _error.value = null
                    try {
                        toolManager.restartGateway(currentId)
                        _gatewayRunning.value = toolManager.isGatewayRunning(currentId)
                    } finally {
                        _gatewayOperating.value = false
                    }
                }
            } catch (e: Exception) {
                logger.w("Failed to generate token for $currentId: ${e.message}", e)
                _error.value = "重新生成 Token 失败：${e.message}"
            }
        }
    }

    fun clearToken() {
        val currentId = _toolId.value
        if (currentId.isBlank()) return
        viewModelScope.launch {
            try {
                settingsDataStore.setToolAccessToken(linuxRuntime.activeDistroId.value, currentId, null)
            } catch (e: Exception) {
                logger.w("Failed to clear token for $currentId: ${e.message}", e)
            }
        }
    }

    fun applyModel(model: AiModelEntity) {
        val currentId = _toolId.value
        if (currentId.isBlank()) return
        _applyingModel.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                providerRepository.setProvider(model.provider)
                if (model.baseUrl.isNotBlank()) {
                    providerRepository.setBaseUrl(model.baseUrl)
                }
                if (model.model.isNotBlank()) {
                    providerRepository.setModel(model.model)
                }
                val actualKey = if (model.secretRef.isNotBlank()) {
                    providerRepository.readModelApiKey(model.secretRef)
                } else null
                if (!actualKey.isNullOrBlank()) {
                    providerRepository.setApiKey(actualKey)
                }
                _appliedModelId.value = model.id
                // 模型配置通过网关启动时的环境变量注入，运行中的实例必须重启才能生效
                if (toolManager.isGatewayRunning(currentId)) {
                    toolManager.restartGateway(currentId)
                    _gatewayRunning.value = toolManager.isGatewayRunning(currentId)
                }
            } catch (e: Exception) {
                logger.w("Failed to apply model ${model.name} for $currentId: ${e.message}", e)
                _error.value = "应用模型失败：${e.message}"
            } finally {
                _applyingModel.value = false
            }
        }
    }

    fun dismissError() {
        _error.value = null
    }

    fun clearServiceLogs() {
        val currentId = _toolId.value
        if (currentId.isNotBlank()) {
            toolManager.clearServiceLogs(currentId)
        }
    }

    fun verifyTool() {
        val currentId = _toolId.value
        if (currentId.isBlank()) return
        viewModelScope.launch {
            try {
                toolManager.verify(currentId)
            } catch (e: Exception) {
                logger.w("Tool verification failed: $currentId, ${e.message}", e)
                _error.value = "自检失败：${e.message}"
            }
        }
    }

    fun uninstallTool() {
        val currentId = _toolId.value
        if (currentId.isBlank()) return
        viewModelScope.launch {
            try {
                toolManager.uninstall(currentId)
            } catch (e: Exception) {
                logger.w("Tool uninstall failed: $currentId, ${e.message}", e)
                _error.value = "卸载失败：${e.message}"
            }
        }
    }

    private fun detectLanIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces.asSequence()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses.asSequence()) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank() && host != "127.0.0.1") {
                            return host
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
