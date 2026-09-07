package top.wanxiang.app.ui.terminal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wanxiang.app.core.datastore.TerminalPreferences
import top.wanxiang.app.runtime.DistributionCatalog
import top.wanxiang.app.runtime.WorkspaceManager
import top.wanxiang.app.runtime.terminal.TerminalCursor
import top.wanxiang.app.runtime.terminal.TerminalLine
import top.wanxiang.app.runtime.terminal.TerminalSessionHandle
import top.wanxiang.app.runtime.terminal.TerminalSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import top.wanxiang.app.feature.terminal.R
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class TerminalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val terminalManager: TerminalSessionManager,
    private val workspaceManager: WorkspaceManager,
    private val settingsDataStore: TerminalPreferences,
    private val appSettingsDataStore: top.wanxiang.app.core.datastore.SettingsDataStore,
    private val linuxRuntime: top.wanxiang.app.runtime.LinuxRuntime,
) : ViewModel() {
    private var initialized = false

    /** 首次使用引导登记（统一存于 SettingsDataStore，设置页可整体清空重看）。 */
    val firstUseGuidesShown: StateFlow<Set<String>> = appSettingsDataStore.firstUseGuidesShown
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun markFirstUseGuideShown(id: String) {
        viewModelScope.launch { appSettingsDataStore.markFirstUseGuideShown(id) }
    }

    val installedDistros = linuxRuntime.installedDistros
    val activeDistroId = linuxRuntime.activeDistroId

    val handles: StateFlow<List<TerminalSessionHandle>> = terminalManager.handles
    val activeId: StateFlow<String?> = terminalManager.activeId

    private val activeHandle: Flow<TerminalSessionHandle?> = terminalManager.activeHandle

    val distributionName: StateFlow<String> = activeHandle
        .flatMapLatest { handle ->
            val distroId = handle?.distributionId ?: linuxRuntime.activeDistroId.value
            flowOf(DistributionCatalog.require(distroId).displayName)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, context.getString(R.string.terminal_loading))

    val terminalFontSize: StateFlow<Int> = settingsDataStore.terminalFontSize
        .stateIn(viewModelScope, SharingStarted.Eagerly, 13)

    val terminalColorScheme: StateFlow<String> = settingsDataStore.terminalColorScheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, "obsidian")

    val terminalHapticsEnabled: StateFlow<Boolean> = settingsDataStore.terminalHapticsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val screen: StateFlow<List<TerminalLine>> = activeHandle
        .flatMapLatest { it?.screen ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val cursor: StateFlow<TerminalCursor> = activeHandle
        .flatMapLatest { it?.cursor ?: flowOf(TerminalCursor(0, 0, false)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TerminalCursor(0, 0, false))
    val screenRevision: StateFlow<Long> = activeHandle
        .flatMapLatest { it?.revision ?: flowOf(0L) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _activeLabel = MutableStateFlow("")
    val activeLabel: StateFlow<String> = activeHandle
        .flatMapLatest { it?.let { h -> flowOf(h.label) } ?: flowOf("") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * 尺寸待应用缓存：首帧 resize 触发时会话往往尚未建立（PRoot 启动是异步的），
     * activeId 为 null 时 resize 直接丢弃会让 PTY 停留在默认 80 列，
     * 而 UI 可视宽度约 40 列——bash 不换行、输入超出可视区。这里把请求存起来，
     * 会话就绪或切换时自动补上。
     */
    private var pendingResize: Pair<Int, Int>? = null

    init {
        viewModelScope.launch {
            activeHandle.collect { handle ->
                if (handle != null) {
                    pendingResize?.let { (columns, rows) ->
                        pendingResize = null
                        terminalManager.resizeSession(handle.id, columns, rows)
                        terminalManager.resizeBuffer(handle.id, columns)
                    }
                }
            }
        }
    }

    fun initialize(project: String) {
        initializeInternal(project, force = false)
    }

    /** 用户主动重试：清除错误并允许在失败后重新初始化。 */
    fun retryInitialize(project: String) {
        initializeInternal(project, force = true)
    }

    private fun initializeInternal(project: String, force: Boolean) {
        if (initialized && !force) return
        initialized = true
        _error.value = null
        viewModelScope.launch {
            if (project.isNotBlank()) {
                val workingDirectory = runCatching { workspaceManager.linuxWorkingDirectory(project) }.getOrNull() ?: "/root"
                runCatching {
                    terminalManager.openOrSwitchToProject(project, workingDirectory)
                }.onFailure { _error.value = it.message ?: context.getString(R.string.terminal_error_workspace_start) }
            } else {
                runCatching {
                    terminalManager.ensureActive("/root")
                }.onFailure { _error.value = it.message ?: context.getString(R.string.terminal_error_start) }
            }
        }
    }

    private fun activeIdOrNull(): String? = terminalManager.activeId.value?.takeIf { id ->
        terminalManager.handles.value.any { it.id == id }
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        activeIdOrNull()?.let { terminalManager.write(it, text.toByteArray(Charsets.UTF_8)) }
    }

    fun pasteText(text: String) {
        if (text.isEmpty()) return
        activeIdOrNull()?.let { terminalManager.paste(it, text) }
    }

    fun setTerminalFontSize(sizeSp: Int) {
        viewModelScope.launch { settingsDataStore.setTerminalFontSize(sizeSp) }
    }

    fun interrupt() {
        activeIdOrNull()?.let { terminalManager.interrupt(it) }
    }

    fun onTerminalKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val codePoint = event.utf16CodePoint
        val bytes: ByteArray? = when {
            ctrl && codePoint in 'a'.code..'z'.code -> byteArrayOf((codePoint - 96).toByte())
            ctrl && event.key == Key.Spacebar -> byteArrayOf(0)
            event.key == Key.DirectionUp -> seq(if (alt) "\u001B\u001B[A" else "\u001B[A")
            event.key == Key.DirectionDown -> seq(if (alt) "\u001B\u001B[B" else "\u001B[B")
            event.key == Key.DirectionLeft -> seq(if (alt) "\u001B\u001B[D" else "\u001B[D")
            event.key == Key.DirectionRight -> seq(if (alt) "\u001B\u001B[C" else "\u001B[C")
            event.key == Key.MoveHome -> seq("\u001B[1~")
            event.key == Key.MoveEnd -> seq("\u001B[4~")
            event.key == Key.PageUp -> seq("\u001B[5~")
            event.key == Key.PageDown -> seq("\u001B[6~")
            event.key == Key.Delete -> seq("\u001B[3~")
            event.key == Key.Insert -> seq("\u001B[2~")
            event.key == Key.Tab -> byteArrayOf(9)
            event.key == Key.Backspace -> byteArrayOf(0x7f)
            event.key == Key.Enter -> byteArrayOf(13)
            event.key == Key.Escape -> byteArrayOf(27)
            codePoint in 0x20..0x7e || codePoint > 0x7f ->
                ((if (alt) "\u001B" else "") + codePoint.toChar()).toByteArray(Charsets.UTF_8)
            else -> null
        }
        if (bytes != null) {
            activeIdOrNull()?.let { terminalManager.write(it, bytes) }
            return true
        }
        return false
    }

    private fun seq(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    fun resize(columns: Int, rows: Int) {
        val id = activeIdOrNull()
        if (id == null) {
            // 会话尚未建立：缓存待应用（见 pendingResize 注释），就绪后由 init 里的 collector 补上
            pendingResize = columns to rows
            return
        }
        terminalManager.resizeSession(id, columns, rows)
        terminalManager.resizeBuffer(id, columns)
    }

    val workspaces: StateFlow<List<top.wanxiang.app.runtime.WorkspaceProject>> = workspaceManager.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createSession(label: String = "", workingDirectory: String = "/root", distroId: String? = null) {
        viewModelScope.launch {
            runCatching {
                val targetDistro = distroId?.takeIf { it.isNotBlank() } ?: activeDistroId.value
                val distroShortName = DistributionCatalog.require(targetDistro).id.replaceFirstChar { it.uppercase() }
                val finalLabel = label.trim().ifBlank { "$distroShortName ${handles.value.size + 1}" }
                terminalManager.createSession(
                    label = finalLabel,
                    workingDirectory = workingDirectory.trim().ifBlank { "/root" },
                    distributionId = targetDistro,
                )
            }.onFailure { _error.value = it.message ?: context.getString(R.string.terminal_error_create_session) }
        }
    }

    fun switchSession(id: String) = terminalManager.switchTo(id)

    /**
     * 关闭会话。[onResult] 在异步关闭完成后回调（成功为 true），
     * 供 UI 在确认真正成功后再提示，而非点击瞬间弹 Toast。
     */
    fun closeSession(id: String, onResult: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val success = runCatching { terminalManager.closeSession(id) }
                .onFailure { _error.value = it.message ?: context.getString(R.string.terminal_error_close_session) }
                .isSuccess
            onResult?.invoke(success)
        }
    }

    fun clearError() {
        _error.value = null
    }
}
