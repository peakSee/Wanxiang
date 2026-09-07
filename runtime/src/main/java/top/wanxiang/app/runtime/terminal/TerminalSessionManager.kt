package top.wanxiang.app.runtime.terminal

import top.wanxiang.app.core.database.TerminalSessionRepository
import top.wanxiang.app.core.database.TerminalSessionEntity
import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.shell.LinuxSession
import top.wanxiang.app.runtime.shell.SessionConfig
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 一个活动的终端会话：真 PTY + 独立渲染缓冲。 */
class TerminalSessionHandle internal constructor(
    val id: String,
    val label: String,
    val workingDirectory: String,
    val distributionId: String = "ubuntu",
    internal val session: LinuxSession,
    internal val buffer: AnsiTerminalBuffer,
) {
    private val _screen = MutableStateFlow(buffer.snapshot())
    val screen: StateFlow<List<TerminalLine>> = _screen.asStateFlow()

    private val _cursor = MutableStateFlow(buffer.cursor())
    val cursor: StateFlow<TerminalCursor> = _cursor.asStateFlow()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    internal fun publish(snapshot: List<TerminalLine>? = null) {
        _screen.value = snapshot ?: buffer.snapshot()
        _cursor.value = buffer.cursor()
        _revision.value += 1
    }
}

/**
 * 多会话终端管理器（单例，跨页面存活）。
 *
 * 每个会话 = 一个真 PTY（NativePtySession）+ 独立 AnsiTerminalBuffer。
 * 会话元数据（标签/工作目录/顺序/发行版）持久化到 Room，App 重启后重建同名会话壳；
 * 切换会话时原会话继续在后台运行（命令不中断）。
 */
@Singleton
class TerminalSessionManager @Inject constructor(
    private val linuxRuntime: LinuxRuntime,
    private val terminalSessionDao: TerminalSessionRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _handles = MutableStateFlow<List<TerminalSessionHandle>>(emptyList())
    val handles: StateFlow<List<TerminalSessionHandle>> = _handles.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    val activeHandle: StateFlow<TerminalSessionHandle?> =
        combine(_handles, _activeId) { handles, id -> handles.firstOrNull { it.id == id } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    @Volatile
    private var restoredOnce = false

    /**
     * 保证至少一个活动会话：首次调用时从 Room 恢复已有会话；没有则新建。
     * 由终端页面在 Runtime 已就绪后调用。
     */
    suspend fun ensureActive(initialWorkingDirectory: String = DEFAULT_CWD) {
        val aliveHandles = _handles.value.filter { it.session.isAlive }
        if (aliveHandles.size != _handles.value.size) {
            _handles.value = aliveHandles
            _activeId.value = _activeId.value?.takeIf { id -> aliveHandles.any { it.id == id } }
            if (aliveHandles.isEmpty()) restoredOnce = false
        }
        if (!restoredOnce) {
            restoredOnce = true
            val rows = terminalSessionDao.listAll()
            if (rows.isNotEmpty()) {
                rows.forEach { row ->
                    createSession(
                        id = row.id,
                        label = row.label,
                        workingDirectory = row.workingDirectory,
                        distributionId = row.distributionId,
                    )
                }
                _activeId.value = _handles.value.firstOrNull()?.id ?: _activeId.value
                return
            }
        }
        if (_handles.value.isEmpty()) {
            createSession(workingDirectory = initialWorkingDirectory)
        }
    }

    /** 新建会话并设为活动。会持久化元数据。 */
    suspend fun createSession(
        label: String = "会话 ${_handles.value.size + 1}",
        workingDirectory: String = DEFAULT_CWD,
        distributionId: String? = null,
        id: String = UUID.randomUUID().toString(),
    ): TerminalSessionHandle {
        val targetDistro = distributionId?.trim()?.takeIf { it.isNotBlank() } ?: linuxRuntime.activeDistroId.value
        val session = linuxRuntime.startSession(
            config = SessionConfig(workingDirectory = workingDirectory, showBanner = true),
            distroId = targetDistro,
        )
        val handle = TerminalSessionHandle(
            id = id,
            label = label,
            workingDirectory = workingDirectory,
            distributionId = targetDistro,
            session = session,
            buffer = AnsiTerminalBuffer(),
        )
        terminalSessionDao.upsert(
            TerminalSessionEntity(
                id = id,
                label = label,
                workingDirectory = workingDirectory,
                distributionId = targetDistro,
                createdAt = System.currentTimeMillis(),
                sortOrder = terminalSessionDao.nextOrder(),
            ),
        )
        scope.launch {
            val pending = StringBuilder()
            var flushJob: Job? = null

            suspend fun flushPending() {
                val text = synchronized(pending) {
                    if (pending.isEmpty()) "" else pending.toString().also { pending.setLength(0) }
                }
                if (text.isNotEmpty()) {
                    // append already creates the immutable screen snapshot; do not snapshot twice.
                    handle.publish(handle.buffer.append(text))
                }
            }

            session.output.collect { output ->
                synchronized(pending) { pending.append(output.text) }
                if (flushJob?.isActive != true) {
                    flushJob = launch {
                        // Bound UI publication to roughly 30 FPS under bursty PTY output.
                        delay(32L)
                        flushPending()
                    }
                }
            }
            flushJob?.join()
            flushPending()
        }
        _handles.value = _handles.value + handle
        _activeId.value = handle.id
        return handle
    }

    /**
     * 打开或切换到指定工作区的终端会话：
     * 1. 若已有该工作目录的会话，直接切换为活动会话；
     * 2. 若没有，创建以项目名称命名且处于该工作目录的新会话，并切换为活动。
     */
    suspend fun openOrSwitchToProject(project: String, workingDirectory: String, distributionId: String? = null): TerminalSessionHandle {
        ensureActive()
        val existing = _handles.value.firstOrNull { it.workingDirectory == workingDirectory }
        if (existing != null) {
            _activeId.value = existing.id
            return existing
        }
        val handle = createSession(
            label = project.ifBlank { "工作区" },
            workingDirectory = workingDirectory,
            distributionId = distributionId,
        )
        _activeId.value = handle.id
        return handle
    }

    fun switchTo(id: String) {
        if (_handles.value.any { it.id == id }) {
            _activeId.value = id
        }
    }

    /** 关闭会话并从 Room 移除；若关闭的是活动会话，则切到相邻会话；若所有会话都已关闭，自动重建一个默认会话防止黑屏。 */
    suspend fun closeSession(id: String) {
        val handle = _handles.value.find { it.id == id } ?: return
        runCatching { handle.session.close() }
        terminalSessionDao.delete(id)
        val remaining = _handles.value.filterNot { it.id == id }
        _handles.value = remaining
        if (remaining.isEmpty()) {
            val fallback = createSession(label = "主终端", workingDirectory = DEFAULT_CWD)
            _activeId.value = fallback.id
        } else if (_activeId.value == id) {
            _activeId.value = remaining.lastOrNull()?.id ?: remaining.firstOrNull()?.id
        }
    }

    /**
     * 关闭并销毁所有终端会话，清空 Room 记录与内存状态。
     * 切换发行版前必须调用，防止旧系统 PTY 与新系统 Buffer 并发冲突。
     * 调用后调用方应在新发行版就绪后再调用 [ensureActive] 重建默认会话。
     */
    suspend fun closeAllSessions() {
        val snapshot = _handles.value.toList()
        snapshot.forEach { handle ->
            runCatching { handle.session.close() }
        }
        terminalSessionDao.deleteAll()
        _handles.value = emptyList()
        _activeId.value = null
        restoredOnce = false   // 下次 ensureActive 可重新从 Room 恢复（此时 Room 已空，会新建）
    }

    fun write(id: String, data: ByteArray) {
        val handle = _handles.value.find { it.id == id } ?: return
        scope.launch { runCatching { handle.session.write(data) } }
    }

    fun paste(id: String, text: String) {
        val handle = _handles.value.find { it.id == id } ?: return
        val payload = if (handle.buffer.isBracketedPasteEnabled()) {
            "\u001B[200~$text\u001B[201~"
        } else text
        scope.launch { runCatching { handle.session.write(payload.toByteArray(Charsets.UTF_8)) } }
    }

    fun interrupt(id: String) {
        write(id, byteArrayOf(3))
    }

    fun resizeSession(id: String, columns: Int, rows: Int) {
        val handle = _handles.value.find { it.id == id } ?: return
        scope.launch {
            runCatching { handle.session.resize(columns.coerceIn(20, 400), rows.coerceIn(5, 200)) }
        }
    }

    fun resizeBuffer(id: String, columns: Int) {
        val handle = _handles.value.find { it.id == id } ?: return
        handle.buffer.resize(columns)
        handle.publish()
    }

    private companion object {
        const val DEFAULT_CWD = "/root"
    }
}
