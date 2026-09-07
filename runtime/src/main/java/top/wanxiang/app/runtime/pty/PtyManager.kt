package top.wanxiang.app.runtime.pty

import top.wanxiang.app.runtime.shell.LinuxSession
import top.wanxiang.app.runtime.shell.ProcessLinuxSession
import top.wanxiang.app.runtime.shell.SessionConfig
import javax.inject.Inject
import javax.inject.Singleton

interface PtyManager {
    /** True when the JNI forkpty backend is loadable on this device. */
    val nativeAvailable: Boolean

    /** Debian `script`-based backend (fallback). */
    suspend fun open(
        command: List<String>,
        hostEnvironment: Map<String, String>,
        config: SessionConfig,
        resize: (suspend (columns: Int, rows: Int) -> Unit)? = null,
        cleanup: suspend () -> Unit = {},
    ): LinuxSession

    /** 真 PTY 后端：JNI forkpty，命令直接 exec 在 pty slave 上。 */
    suspend fun openNative(
        command: List<String>,
        hostEnvironment: Map<String, String>,
        config: SessionConfig,
        cleanup: suspend () -> Unit = {},
    ): LinuxSession
}

/**
 * Android-compatible PTY backend using Debian's util-linux `script` command.
 * It records the PTY slave path so resize can be applied with `stty -F`.
 * Used only when the JNI forkpty library cannot be loaded.
 */
@Singleton
class ScriptPtyManager @Inject constructor() : PtyManager {
    override val nativeAvailable: Boolean get() = false

    override suspend fun open(
        command: List<String>,
        hostEnvironment: Map<String, String>,
        config: SessionConfig,
        resize: (suspend (columns: Int, rows: Int) -> Unit)?,
        cleanup: suspend () -> Unit,
    ): LinuxSession = ProcessLinuxSession(
        command = command,
        hostEnvironment = hostEnvironment,
        allowSttyResize = config.allowSttyResize,
        resizeCallback = resize,
        cleanupCallback = cleanup,
    )

    override suspend fun openNative(
        command: List<String>,
        hostEnvironment: Map<String, String>,
        config: SessionConfig,
        cleanup: suspend () -> Unit,
    ): LinuxSession = error("native PTY backend unavailable")
}

/** 主后端：优先 JNI forkpty，库缺失时回退到 script 后端。 */
@Singleton
class NativePtyManager @Inject constructor(
    private val scriptFallback: ScriptPtyManager,
) : PtyManager {
    override val nativeAvailable: Boolean get() = NativePty.tryLoad()

    override suspend fun open(
        command: List<String>,
        hostEnvironment: Map<String, String>,
        config: SessionConfig,
        resize: (suspend (columns: Int, rows: Int) -> Unit)?,
        cleanup: suspend () -> Unit,
    ): LinuxSession = scriptFallback.open(command, hostEnvironment, config, resize, cleanup)

    override suspend fun openNative(
        command: List<String>,
        hostEnvironment: Map<String, String>,
        config: SessionConfig,
        cleanup: suspend () -> Unit,
    ): LinuxSession {
        if (!NativePty.tryLoad()) {
            throw IllegalStateException("JNI forkpty 后端不可用，请使用兼容后端")
        }
        return NativePtySession(command, hostEnvironment, config, cleanup)
    }
}
