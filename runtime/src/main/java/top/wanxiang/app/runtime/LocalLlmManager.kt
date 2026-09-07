package top.wanxiang.app.runtime

import android.os.Build
import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.wanxiang.app.core.network.DownloadEvent
import top.wanxiang.app.core.network.DownloadRequest
import top.wanxiang.app.core.network.FileDownloader
import top.wanxiang.app.runtime.service.LocalServiceLauncher
import top.wanxiang.app.runtime.service.LocalServiceSpec
import top.wanxiang.app.runtime.shell.ManagedProcess
import top.wanxiang.app.runtime.shell.ProcessType
import top.wanxiang.app.runtime.shell.ShellCommand

data class LocalGgufModel(
    val fileName: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

sealed interface LocalModelTransfer {
    data object Started : LocalModelTransfer
    data class Progress(val copiedBytes: Long, val totalBytes: Long?) : LocalModelTransfer
    data object Verifying : LocalModelTransfer
    data class Completed(val model: LocalGgufModel) : LocalModelTransfer
}

sealed interface LocalLlmServiceState {
    data object Stopped : LocalLlmServiceState
    data class Starting(val fileName: String) : LocalLlmServiceState
    data class Running(val fileName: String, val endpoint: String) : LocalLlmServiceState
    data class Failed(val message: String) : LocalLlmServiceState
}

/**
 * Owns GGUF files and the llama-server process for the active distro.
 *
 * Model files live below /opt/wanxiang/data inside the sandbox, so they stay isolated per distro
 * and are already covered by the existing trusted PRoot bind mount.
 */
@Singleton
class LocalLlmManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pathManager: RuntimePathManager,
    private val linuxRuntime: LinuxRuntime,
    private val fileDownloader: FileDownloader,
    private val serviceLauncher: LocalServiceLauncher,
) {
    private val _models = MutableStateFlow<List<LocalGgufModel>>(emptyList())
    val models: StateFlow<List<LocalGgufModel>> = _models.asStateFlow()

    private val _serviceState = MutableStateFlow<LocalLlmServiceState>(LocalLlmServiceState.Stopped)
    val serviceState: StateFlow<LocalLlmServiceState> = _serviceState.asStateFlow()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceMutex = Mutex()
    private var serviceProcess: ManagedProcess? = null
    private var serviceMonitorJob: Job? = null

    init {
        managerScope.launch {
            linuxRuntime.activeDistroId.drop(1).collect {
                stop()
                refresh()
            }
        }
    }

    fun refresh() {
        val directory = modelDirectory()
        directory.mkdirs()
        _models.value = directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals(GGUF_EXTENSION, ignoreCase = true) }
            .map { LocalGgufModel(it.name, it.length(), it.lastModified()) }
            .sortedByDescending(LocalGgufModel::modifiedAt)
            .toList()
    }

    fun download(url: String, sha256: String? = null): Flow<LocalModelTransfer> = flow {
        val fileName = fileNameFromUrl(url)
        val destination = resolveModelFile(fileName)
        fileDownloader.download(
            DownloadRequest(
                url = url.trim(),
                destination = destination,
                sha256 = sha256?.trim()?.takeIf(String::isNotEmpty),
                maxBytes = MAX_MODEL_BYTES,
            ),
        ).collect { event ->
            when (event) {
                DownloadEvent.Started -> emit(LocalModelTransfer.Started)
                is DownloadEvent.Progress -> emit(
                    LocalModelTransfer.Progress(event.downloadedBytes, event.totalBytes),
                )
                DownloadEvent.Verifying -> emit(LocalModelTransfer.Verifying)
                is DownloadEvent.Completed -> {
                    try {
                        validateGguf(event.file)
                    } catch (throwable: Throwable) {
                        event.file.delete()
                        throw throwable
                    }
                    refresh()
                    emit(LocalModelTransfer.Completed(requireModel(event.file.name)))
                }
            }
        }
    }

    fun import(
        displayName: String,
        input: InputStream,
        totalBytes: Long? = null,
    ): Flow<LocalModelTransfer> = flow {
        val destination = resolveModelFile(displayName)
        val partial = File("${destination.absolutePath}.import.part")
        emit(LocalModelTransfer.Started)
        try {
            var copied = 0L
            input.use { source ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copied += read
                        require(copied <= MAX_MODEL_BYTES) { "GGUF 文件超过 32 GB 限制" }
                        output.write(buffer, 0, read)
                        emit(LocalModelTransfer.Progress(copied, totalBytes))
                    }
                }
            }
            emit(LocalModelTransfer.Verifying)
            validateGguf(partial)
            commit(partial, destination)
            refresh()
            emit(LocalModelTransfer.Completed(requireModel(destination.name)))
        } catch (cancellation: CancellationException) {
            partial.delete()
            throw cancellation
        } catch (throwable: Throwable) {
            partial.delete()
            throw throwable
        }
    }.flowOn(Dispatchers.IO)

    fun importPath(path: String): Flow<LocalModelTransfer> {
        val source = File(path.trim()).canonicalFile
        require(source.isFile && source.canRead()) { "文件不存在或不可读取：${source.absolutePath}" }
        require(source.extension.equals(GGUF_EXTENSION, ignoreCase = true)) { "请选择 .gguf 模型文件" }
        return import(source.name, FileInputStream(source), source.length())
    }

    suspend fun start(fileName: String) = serviceMutex.withLock {
        check(Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }) {
            "当前设备不是 ARM64，无法运行本地 llama.cpp 推理引擎"
        }
        val model = resolveModelFile(fileName)
        require(model.isFile) { "模型文件不存在：$fileName" }
        validateGguf(model)

        val currentState = _serviceState.value
        val currentProcess = serviceProcess
        if (currentState is LocalLlmServiceState.Running && currentProcess?.session?.isAlive == true) {
            check(currentState.fileName == model.name) {
                "已有本地模型正在运行，请先停止后再启动其他模型"
            }
            return@withLock
        }

        serviceMonitorJob?.cancel()
        serviceMonitorJob = null
        serviceProcess = null
        _serviceState.value = LocalLlmServiceState.Starting(model.name)
        try {
            val probe = linuxRuntime.execute(
                ShellCommand(
                    commandLine = "command -v llama-server >/dev/null 2>&1 || test -x /opt/wanxiang/bin/llama-server",
                    timeoutMs = ENGINE_PROBE_TIMEOUT_MS,
                ),
            )
            check(probe.isSuccess) { "尚未安装 llama.cpp 推理引擎，请先在工具中心安装" }
            val guestModel = "$GUEST_MODEL_DIRECTORY/${model.name}"
            val contextSize = if (deviceRamBytes() < SIX_GIB) LOW_MEMORY_CONTEXT_SIZE else DEFAULT_CONTEXT_SIZE
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(MIN_INFERENCE_THREADS, MAX_INFERENCE_THREADS)
            val handle = serviceLauncher.start(
                LocalServiceSpec(
                    serviceId = SERVICE_ID,
                    port = SERVICE_PORT,
                    path = "/v1",
                    startupTimeoutMs = SERVICE_START_TIMEOUT_MS,
                ),
            ) {
                linuxRuntime.startBackground(
                    id = SERVICE_PROCESS_ID,
                    toolId = TOOL_ID,
                    type = ProcessType.SERVICE,
                    command = ShellCommand(
                        commandLine = "exec llama-server -m ${shellQuote(guestModel)} --host 127.0.0.1 --port $SERVICE_PORT --ctx-size $contextSize --parallel 1 --threads $threads --threads-batch $threads",
                        environment = mapOf(
                            "LD_LIBRARY_PATH" to "/opt/wanxiang/tools/llama-cpp/lib/release",
                        ),
                        timeoutMs = Long.MAX_VALUE,
                    ),
                )
            }
            serviceProcess = handle.process
            _serviceState.value = LocalLlmServiceState.Running(model.name, handle.url)
            monitorService(handle.process)
        } catch (cancellation: CancellationException) {
            serviceProcess = null
            _serviceState.value = LocalLlmServiceState.Stopped
            throw cancellation
        } catch (throwable: Throwable) {
            serviceProcess = null
            _serviceState.value = LocalLlmServiceState.Failed(
                throwable.message ?: "本地模型服务启动失败",
            )
            throw throwable
        }
    }

    suspend fun stop() = serviceMutex.withLock {
        serviceMonitorJob?.cancel()
        serviceMonitorJob = null
        serviceProcess = null
        serviceLauncher.stop(SERVICE_ID)
        _serviceState.value = LocalLlmServiceState.Stopped
    }

    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        val runningName = (_serviceState.value as? LocalLlmServiceState.Running)?.fileName
        require(runningName != fileName) { "请先停止正在运行的模型" }
        val target = resolveModelFile(fileName)
        check(!target.exists() || target.delete()) { "无法删除模型：$fileName" }
        File("${target.absolutePath}.part").delete()
        refresh()
    }

    private fun modelDirectory(): File = File(
        pathManager.wanxiangDataDir(linuxRuntime.activeDistroId.value),
        "llama-cpp/models",
    )

    private fun resolveModelFile(rawName: String): File {
        val safeName = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        require(SAFE_FILE_NAME.matches(safeName)) { "模型文件名无效" }
        require(safeName.endsWith(".$GGUF_EXTENSION", ignoreCase = true)) { "模型必须是 .gguf 文件" }
        val directory = modelDirectory().apply { mkdirs() }.canonicalFile
        val target = File(directory, safeName).canonicalFile
        require(target.parentFile == directory) { "模型路径越界" }
        return target
    }

    private fun fileNameFromUrl(url: String): String {
        val value = url.trim()
        require(value.startsWith("https://", ignoreCase = true)) { "下载地址必须使用 HTTPS" }
        val path = runCatching { URI(value).path }.getOrNull().orEmpty()
        return path.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("下载地址中缺少 GGUF 文件名")
    }

    private fun requireModel(fileName: String): LocalGgufModel =
        _models.value.first { it.fileName == fileName }

    private fun validateGguf(file: File) {
        require(file.length() >= GGUF_HEADER_BYTES) { "文件过小，不是有效的 GGUF 模型" }
        val magic = ByteArray(GGUF_MAGIC.size)
        file.inputStream().use { input ->
            check(input.read(magic) == magic.size && magic.contentEquals(GGUF_MAGIC)) {
                "文件头不是 GGUF，下载内容可能是登录页或错误响应"
            }
        }
    }

    private fun commit(source: File, destination: File) {
        runCatching {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun deviceRamBytes(): Long = ActivityManager.MemoryInfo().also { info ->
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
    }.totalMem

    private fun monitorService(process: ManagedProcess) {
        serviceMonitorJob = managerScope.launch {
            while (process.session.isAlive) {
                delay(SERVICE_MONITOR_INTERVAL_MS)
            }
            serviceMutex.withLock {
                if (serviceProcess === process) {
                    serviceLauncher.stop(SERVICE_ID)
                    serviceProcess = null
                    serviceMonitorJob = null
                    _serviceState.value = LocalLlmServiceState.Stopped
                }
            }
        }
    }

    companion object {
        const val TOOL_ID = "llama-cpp"
        const val SERVICE_PORT = 8080
        const val BASE_URL = "http://127.0.0.1:$SERVICE_PORT/v1"
        private const val SERVICE_ID = "llama-cpp"
        private const val SERVICE_PROCESS_ID = "llama-cpp-server"
        private const val GUEST_MODEL_DIRECTORY = "/opt/wanxiang/data/llama-cpp/models"
        private const val GGUF_EXTENSION = "gguf"
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val GGUF_HEADER_BYTES = 24L
        private const val MAX_MODEL_BYTES = 32L * 1024L * 1024L * 1024L
        private const val ENGINE_PROBE_TIMEOUT_MS = 10_000L
        private const val SERVICE_START_TIMEOUT_MS = 5 * 60_000L
        private const val SERVICE_MONITOR_INTERVAL_MS = 1_000L
        private const val LOW_MEMORY_CONTEXT_SIZE = 2048
        private const val DEFAULT_CONTEXT_SIZE = 4096
        private const val MIN_INFERENCE_THREADS = 2
        private const val MAX_INFERENCE_THREADS = 4
        private const val SIX_GIB = 6L * 1024L * 1024L * 1024L
        private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+() -]{0,239}")
    }
}
