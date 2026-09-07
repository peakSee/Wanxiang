package top.wanxiang.app.ui.settings

import android.content.Context
import android.app.ActivityManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.wanxiang.app.core.database.AiModelEntity
import top.wanxiang.app.core.database.AiModelRepository
import top.wanxiang.app.core.model.ToolState
import top.wanxiang.app.core.tools.ToolManager
import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.LocalLlmManager
import top.wanxiang.app.runtime.LocalModelTransfer

data class LocalModelTransferUiState(
    val running: Boolean = false,
    val label: String = "",
    val copiedBytes: Long = 0L,
    val totalBytes: Long? = null,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0L }?.let { (copiedBytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

data class MobileModelPreset(
    val id: String,
    val name: String,
    val purpose: String,
    val parameterCount: String,
    val quantization: String,
    val downloadBytes: Long,
    val minimumDeviceRamBytes: Long,
    val url: String,
    val sha256: String? = null,
)

@HiltViewModel
class LocalLlmViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localLlmManager: LocalLlmManager,
    private val toolManager: ToolManager,
    private val linuxRuntime: LinuxRuntime,
    private val aiModelRepository: AiModelRepository,
) : ViewModel() {
    val deviceRamBytes: Long = ActivityManager.MemoryInfo().also { info ->
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
    }.totalMem

    val mobileModelPresets: List<MobileModelPreset> = MOBILE_MODEL_PRESETS

    val models = localLlmManager.models
    val serviceState = localLlmManager.serviceState

    val engineInstalled: StateFlow<Boolean> = toolManager.observeTools()
        .map { tools ->
            tools.firstOrNull { it.id == LocalLlmManager.TOOL_ID }?.state in setOf(
                ToolState.INSTALLED.name,
                ToolState.UPDATE_AVAILABLE.name,
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _transfer = MutableStateFlow(LocalModelTransferUiState())
    val transfer: StateFlow<LocalModelTransferUiState> = _transfer.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _messageIsError = MutableStateFlow(false)
    val messageIsError: StateFlow<Boolean> = _messageIsError.asStateFlow()

    fun showMessage(msg: String, isError: Boolean = false) {
        _message.value = msg
        _messageIsError.value = isError
    }

    private var transferJob: Job? = null

    init {
        localLlmManager.refresh()
    }

    fun download(url: String, sha256: String? = null) {
        if (url.isBlank()) {
            showMessage("请先输入 GGUF 的 HTTPS 下载地址", isError = true)
            return
        }
        startTransfer("正在下载模型") { localLlmManager.download(url, sha256) }
    }

    fun downloadPreset(preset: MobileModelPreset) {
        if (!isDeviceSuitable(preset)) {
            showMessage("设备总内存低于该模型建议值，不建议在本机运行 ${preset.name}", isError = true)
            return
        }
        download(preset.url, preset.sha256)
    }

    fun isDeviceSuitable(preset: MobileModelPreset): Boolean =
        deviceRamBytes <= 0L || deviceRamBytes >= preset.minimumDeviceRamBytes * 85L / 100L

    fun importUri(uri: Uri) {
        val resolver = context.contentResolver
        val metadata = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                name to size
            }
        }
        val displayName = metadata?.first ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
        val size = metadata?.second
        startTransfer("正在导入模型") {
            val input = resolver.openInputStream(uri) ?: error("无法读取所选文件")
            localLlmManager.import(displayName, input, size)
        }
    }

    fun importPath(path: String) {
        if (path.isBlank()) {
            showMessage("请输入 GGUF 文件路径", isError = true)
            return
        }
        startTransfer("正在导入模型") { localLlmManager.importPath(path) }
    }

    fun cancelTransfer() {
        transferJob?.cancel()
        transferJob = null
        _transfer.value = LocalModelTransferUiState()
    }

    fun start(fileName: String) {
        viewModelScope.launch {
            _message.value = null
            _messageIsError.value = false
            runCatching {
                localLlmManager.start(fileName)
                activateLocalProfile(fileName)
            }.onSuccess {
                showMessage("模型服务已启动，并已设为当前对话模型", isError = false)
            }.onFailure { throwable ->
                if (throwable !is CancellationException) {
                    showMessage(throwable.message ?: "模型启动失败", isError = true)
                }
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            runCatching { localLlmManager.stop() }
                .onFailure { showMessage(it.message ?: "停止服务失败", isError = true) }
        }
    }

    fun delete(fileName: String) {
        viewModelScope.launch {
            runCatching { localLlmManager.delete(fileName) }
                .onFailure { showMessage(it.message ?: "删除模型失败", isError = true) }
        }
    }

    fun installEngine() {
        toolManager.startInstall(LocalLlmManager.TOOL_ID)
        showMessage("已提交 llama.cpp 推理引擎安装任务，可在工具中心查看进度", isError = false)
    }

    fun consumeMessage() {
        _message.value = null
        _messageIsError.value = false
    }

    private fun startTransfer(
        label: String,
        source: () -> kotlinx.coroutines.flow.Flow<LocalModelTransfer>,
    ) {
        if (transferJob?.isActive == true) {
            showMessage("已有模型传输任务正在进行", isError = true)
            return
        }
        transferJob = viewModelScope.launch {
            _message.value = null
            _messageIsError.value = false
            try {
                source().collect { event ->
                    _transfer.value = when (event) {
                        LocalModelTransfer.Started -> LocalModelTransferUiState(running = true, label = label)
                        is LocalModelTransfer.Progress -> LocalModelTransferUiState(
                            running = true,
                            label = label,
                            copiedBytes = event.copiedBytes,
                            totalBytes = event.totalBytes,
                        )
                        LocalModelTransfer.Verifying -> _transfer.value.copy(running = true, label = "正在校验 GGUF")
                        is LocalModelTransfer.Completed -> LocalModelTransferUiState()
                    }
                    if (event is LocalModelTransfer.Completed) {
                        showMessage("模型已就绪：${event.model.fileName}", isError = false)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                showMessage(throwable.message ?: "模型传输失败", isError = true)
            } finally {
                _transfer.value = LocalModelTransferUiState()
                transferJob = null
            }
        }
    }

    private suspend fun activateLocalProfile(fileName: String) {
        val distroId = linuxRuntime.activeDistroId.value
        val profileId = "local-llamacpp-$distroId"
        val existing = aiModelRepository.findById(profileId)
        aiModelRepository.clearActive()
        aiModelRepository.upsert(
            AiModelEntity(
                id = profileId,
                name = "本地 LLM · $fileName",
                provider = "llama.cpp 沙箱离线模型",
                model = fileName,
                baseUrl = LocalLlmManager.BASE_URL,
                secretRef = existing?.secretRef.orEmpty(),
                isActive = true,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                temperature = existing?.temperature,
                maxTokens = existing?.maxTokens,
                topP = existing?.topP,
                reasoningMode = existing?.reasoningMode,
                reasoningEffort = existing?.reasoningEffort,
                toolCallMode = existing?.toolCallMode,
                contextTokens = existing?.contextTokens ?: if (deviceRamBytes < 6L * GIB) 2048 else 4096,
                customHeaders = existing?.customHeaders.orEmpty(),
                pureChatMode = existing?.pureChatMode ?: false,
                visionEnabled = false,
                apiKeyCount = 0,
                requestsPerMinutePerKey = 0,
            ),
        )
    }

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
        const val MIB = 1024L * 1024L

        val MOBILE_MODEL_PRESETS = listOf(
            MobileModelPreset(
                id = "qwen25-05b-instruct-q4km",
                name = "Qwen2.5 0.5B Instruct",
                purpose = "低内存首选 · 中英日常对话",
                parameterCount = "0.5B",
                quantization = "Q4_K_M",
                downloadBytes = 491L * MIB,
                minimumDeviceRamBytes = 4L * GIB,
                url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true",
                sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            ),
            MobileModelPreset(
                id = "qwen25-coder-05b-instruct-q4km",
                name = "Qwen2.5 Coder 0.5B",
                purpose = "轻量代码补全与简单解释",
                parameterCount = "0.5B",
                quantization = "Q4_K_M",
                downloadBytes = 491L * MIB,
                minimumDeviceRamBytes = 4L * GIB,
                url = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf?download=true",
            ),
            MobileModelPreset(
                id = "qwen35-2b-q4km",
                name = "Qwen3.5 2B",
                purpose = "新一代通用模型 · 当前按文本模式运行",
                parameterCount = "2B",
                quantization = "Q4_K_M",
                downloadBytes = 1280L * MIB,
                minimumDeviceRamBytes = 6L * GIB,
                url = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf?download=true",
                sha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
            ),
            MobileModelPreset(
                id = "qwen25-15b-instruct-q4km",
                name = "Qwen2.5 1.5B Instruct",
                purpose = "效果优先 · 适合 6 GB 以上手机",
                parameterCount = "1.5B",
                quantization = "Q4_K_M",
                downloadBytes = 1120L * MIB,
                minimumDeviceRamBytes = 6L * GIB,
                url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true",
            ),
            MobileModelPreset(
                id = "qwen25-coder-15b-instruct-q4km",
                name = "Qwen2.5 Coder 1.5B",
                purpose = "移动端编程助手 · 适合 6 GB 以上手机",
                parameterCount = "1.5B",
                quantization = "Q4_K_M",
                downloadBytes = 1120L * MIB,
                minimumDeviceRamBytes = 6L * GIB,
                url = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf?download=true",
            ),
            MobileModelPreset(
                id = "gemma4-e2b-it-q4km",
                name = "Gemma 4 E2B IT",
                purpose = "移动端高质量模型 · 当前按文本模式运行",
                parameterCount = "E2B",
                quantization = "Q4_K_M",
                downloadBytes = 3185L * MIB,
                minimumDeviceRamBytes = 8L * GIB,
                url = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf?download=true",
                sha256 = "740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8",
            ),
        )
    }
}
