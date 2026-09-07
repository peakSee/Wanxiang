package top.wanxiang.app.runtime.proot

import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.runtime.EnvironmentResolver
import top.wanxiang.app.runtime.shell.ShellCommand
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProotCommandBuilder private constructor(
    private val environmentResolver: EnvironmentResolver,
    private val logWarning: (String) -> Unit,
) {

    @Inject
    constructor(
        environmentResolver: EnvironmentResolver,
        logger: AppLogger,
    ) : this(environmentResolver, logger::w)

    /** JVM tests do not need Android logging while validating pure argument construction. */
    internal constructor(environmentResolver: EnvironmentResolver) : this(environmentResolver, {})

    fun build(
        prootBinary: File,
        rootfsDir: File,
        workspaceDir: File,
        homeDir: File = File(rootfsDir.parentFile, "home"),
        optDir: File = File(rootfsDir.parentFile, "opt/wanxiang"),
        tmpDir: File = File(rootfsDir.parentFile, "tmp"),
        attachmentsDir: File = File(rootfsDir.parentFile, "attachments"),
        command: ShellCommand,
        mounts: List<top.wanxiang.app.core.model.StorageMountBinding> = emptyList(),
        emulatorBinary: File? = null,
    ): List<String> = buildList {
        add(prootBinary.absolutePath)
        addEmulator(emulatorBinary)
        add("--kill-on-exit")
        add("--link2symlink")
        add("-L")
        add("--sysvipc")
        add("--kernel-release=$GUEST_KERNEL_RELEASE")
        add("--change-id=0:0")
        add("-r")
        add(rootfsDir.absolutePath)
        addLink2SymlinkBackingStoreBinding(rootfsDir)
        add("-b")
        add("/dev")
        add("-b")
        add("/proc")
        add("-b")
        add("/sys")
        add("-b")
        add("${tmpDir.absolutePath}:/tmp")
        add("-b")
        add("${workspaceDir.absolutePath}:/workspace")
        add("-b")
        add("${homeDir.absolutePath}:/root")
        add("-b")
        add("${optDir.absolutePath}:/opt/wanxiang")
        attachmentsDir.mkdirs()
        add("-b")
        add("${attachmentsDir.absolutePath}:/attachments")
        addHostSystemBindings()
        addStorageMountBindings(mounts)
        add("-w")
        add(command.workingDirectory)
        add(GUEST_SHELL)
        add("-lc")
        val resolvedCommand = shellCommand(
            commandLine = command.commandLine,
            environment = environmentResolver.merge(provider = command.environment),
        )
        add(if (command.forcePty) wrapInPty(resolvedCommand) else resolvedCommand)
    }

    fun buildInteractive(
        prootBinary: File,
        rootfsDir: File,
        workspaceDir: File,
        homeDir: File = File(rootfsDir.parentFile, "home"),
        optDir: File = File(rootfsDir.parentFile, "opt/wanxiang"),
        tmpDir: File = File(rootfsDir.parentFile, "tmp"),
        attachmentsDir: File = File(rootfsDir.parentFile, "attachments"),
        config: top.wanxiang.app.runtime.shell.SessionConfig,
        ptyMarker: String? = null,
        nativePty: Boolean = false,
        mounts: List<top.wanxiang.app.core.model.StorageMountBinding> = emptyList(),
        emulatorBinary: File? = null,
    ): List<String> = buildList {
        val columns = config.columns.coerceIn(20, 400)
        val rows = config.rows.coerceIn(5, 200)
        add(prootBinary.absolutePath)
        addEmulator(emulatorBinary)
        add("--kill-on-exit")
        add("--link2symlink")
        add("-L")
        add("--sysvipc")
        add("--kernel-release=$GUEST_KERNEL_RELEASE")
        add("--change-id=0:0")
        add("-r")
        add(rootfsDir.absolutePath)
        addLink2SymlinkBackingStoreBinding(rootfsDir)
        add("-b")
        add("/dev")
        add("-b")
        add("/proc")
        add("-b")
        add("/sys")
        add("-b")
        add("${tmpDir.absolutePath}:/tmp")
        add("-b")
        add("${workspaceDir.absolutePath}:/workspace")
        add("-b")
        add("${homeDir.absolutePath}:/root")
        add("-b")
        add("${optDir.absolutePath}:/opt/wanxiang")
        attachmentsDir.mkdirs()
        add("-b")
        add("${attachmentsDir.absolutePath}:/attachments")
        addHostSystemBindings()
        addStorageMountBindings(mounts)
        add("-w")
        add(config.workingDirectory)
        add(GUEST_SHELL)
        add("-lc")
        val environment = environmentResolver.merge(
            provider = config.environment,
            interactive = true,
        )
        val bannerPrelude = if (config.showBanner) "cat $GUEST_MOTD 2>/dev/null; " else ""
        if (nativePty) {
            // 真 PTY：命令串直接交给 bash 解析。commandLine 已由调用方完成 shell 引用
            // （如 MCP STDIO 的单引号包裹），此处严禁再做引号替换——单层 bash 下
            // '\'' 类转义会把命令名变成带引号的字面量（exec: "'python3'": not found）。
            add(shellCommand(commandLine = bannerPrelude + config.commandLine, environment = environment))
        } else {
            val marker = ptyMarker?.also {
                require(PTY_MARKER.matches(it)) { "PTY marker path is invalid" }
            }
            val markerPrelude = marker?.let { "tty > $it; " }.orEmpty()
            // fallback：整条命令会被塞进 script -qfec '...' 的单引号里，再由 script 内层
            // shell 解析，需要 '\'' 双层转义。
            val scriptEmbed = config.commandLine.replace("'", "'\\''")
            add(
                shellCommand(
                    commandLine =
                        bannerPrelude +
                            "if command -v script >/dev/null 2>&1; then " +
                            "exec script -qfec '$markerPrelude stty cols $columns rows $rows; " +
                            "$scriptEmbed' /dev/null; " +
                            "else ${config.commandLine}; fi",
                    environment = environment,
                ),
            )
        }
    }

    /**
     * Wrap a long-running build command in a Debian `script` PTY. Java/Gradle fully buffer
     * stdout when it is a pipe (non-TTY), so the app stops receiving logs mid-build.
     * A real TTY makes the child line-buffer and flush, streaming progress to the UI.
     */
    private fun wrapInPty(commandLine: String): String =
        "if command -v script >/dev/null 2>&1; then " +
            "exec script -qfec " + shellQuote(commandLine) + " /dev/null; " +
            "else $commandLine; fi"

    private fun shellCommand(
        commandLine: String,
        environment: Map<String, String>,
    ): String {
        val exports = environment.entries.joinToString("; ") { (key, value) ->
            require(ENVIRONMENT_KEY.matches(key)) { "Invalid environment variable name: $key" }
            "export $key=${shellQuote(value)}"
        }
        return if (exports.isBlank()) commandLine else "$exports; $commandLine"
    }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\\\''")}'"

    /** Add PRoot QEMU user-mode emulation for a dedicated x86_64 guest only. */
    private fun MutableList<String>.addEmulator(emulatorBinary: File?) {
        if (emulatorBinary == null) return
        require(emulatorBinary.isFile && emulatorBinary.canExecute()) {
            "QEMU emulator 不可执行：${emulatorBinary.absolutePath}"
        }
        add("-q")
        add(emulatorBinary.absolutePath)
    }

    /**
     * The Termux PRoot build stores emulated hard-link payloads under the host-side
     * [RuntimePathManager] `PROOT_L2S_DIR`. Link proxies contain that absolute host
     * path. Expose the same path inside the guest so dpkg can lchown/lstat a newly
     * unpacked hard link instead of following a dangling proxy and reporting ENOENT.
     */
    private fun MutableList<String>.addLink2SymlinkBackingStoreBinding(rootfsDir: File) {
        val backingStore = File(rootfsDir, LINK2SYMLINK_DIRECTORY).absolutePath
        add("-b")
        add("$backingStore:$backingStore")
    }

    /** Android host paths used by the PRoot tracer and Android linker. */
    private fun MutableList<String>.addHostSystemBindings() {
        val skipped = mutableListOf<String>()
        listOf(
            "/apex",
            "/data/app",
            "/data/dalvik-cache",
            "/data/misc/apexdata/com.android.art/dalvik-cache",
            "/system",
            "/system_ext",
            "/vendor",
            "/product",
            "/odm",
            "/linkerconfig/com.android.art/ld.config.txt",
            "/linkerconfig/ld.config.txt",
            "/plat_property_contexts",
            "/property_contexts",
        ).forEach { path ->
            val hostPath = File(path)
            if (hostPath.exists() && hostPath.canRead()) {
                add("-b")
                add(path)
            } else {
                skipped.add(path)
            }
        }
        // 记录被跳过的绑定——这些缺失会导致沙箱内 Android 二进制无法执行（"无 linker"问题）
        if (skipped.isNotEmpty()) {
            logWarning("HostSystemBindings: ${skipped.size} path(s) skipped (not exist/unreadable): $skipped")
        }
    }

    /** 宿主外部存储映射绑定 (如 /storage/emulated/0/Download -> /sdcard/Download) */
    private fun MutableList<String>.addStorageMountBindings(
        mounts: List<top.wanxiang.app.core.model.StorageMountBinding>,
    ) {
        if (mounts.isNotEmpty()) {
            mounts.filter { it.enabled }.forEach { binding ->
                val argument = validateStorageMount(binding)
                add("-b")
                add(argument)
            }
        }
    }

    private fun validateStorageMount(
        binding: top.wanxiang.app.core.model.StorageMountBinding,
    ): String {
        require(':' !in binding.hostPath && '\u0000' !in binding.hostPath) { "宿主挂载路径包含非法字符" }
        require(':' !in binding.guestPath && '\u0000' !in binding.guestPath) { "容器挂载路径包含非法字符" }

        val guest = normalizeGuestPath(binding.guestPath)
        require(ALLOWED_GUEST_MOUNT_ROOTS.any { guest == it || guest.startsWith("$it/") }) {
            "容器挂载仅允许位于 /mnt 或 /sdcard 内"
        }

        val sharedRoot = File(SHARED_STORAGE_ROOT).canonicalFile
        val host = File(binding.hostPath).canonicalFile
        require(host.isDirectory && host.canRead()) { "宿主挂载目录不可访问：${binding.hostPath}" }
        require(isInside(sharedRoot, host)) { "宿主挂载仅允许位于 $SHARED_STORAGE_ROOT 内" }
        return "${host.absolutePath}:$guest"
    }

    private fun normalizeGuestPath(path: String): String {
        require(path.startsWith('/')) { "容器挂载路径必须是绝对路径" }
        val segments = path.split('/').filter { it.isNotBlank() && it != "." }
        require(segments.none { it == ".." }) { "容器挂载路径不允许包含 .." }
        return "/${segments.joinToString("/")}".trimEnd('/').ifBlank { "/" }
    }

    private fun isInside(root: File, candidate: File): Boolean =
        candidate == root || candidate.absolutePath.startsWith(root.absolutePath + File.separator)

    private companion object {
        const val GUEST_SHELL = "/bin/sh"
        const val GUEST_KERNEL_RELEASE = "6.17.0-WanXiang"
        const val GUEST_MOTD = "/opt/wanxiang/motd"
        const val LINK2SYMLINK_DIRECTORY = ".l2s"
        val PTY_MARKER = Regex("/opt/wanxiang/\\.pty-[A-Za-z0-9-]{8,64}")
        val ENVIRONMENT_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")
        const val SHARED_STORAGE_ROOT = "/storage/emulated/0"
        val ALLOWED_GUEST_MOUNT_ROOTS = setOf("/mnt", "/sdcard")
    }
}
