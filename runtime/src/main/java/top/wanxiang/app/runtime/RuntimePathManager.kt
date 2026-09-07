package top.wanxiang.app.runtime

import android.content.Context
import top.wanxiang.app.runtime.rootfs.RootfsValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuntimePathManager @Inject constructor(
    @ApplicationContext context: Context,
    private val rootfsValidator: RootfsValidator,
) {
    private val appFilesDir: File = context.filesDir
    val bundledProotFile: File = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
    private val nativeLibraryDir: File = File(context.applicationInfo.nativeLibraryDir)
    val bundledProotLoaderFile: File = File(nativeLibraryDir, "libproot-loader.so")
    val bundledProotLoader32File: File = File(nativeLibraryDir, "libproot-loader32.so")

    val baseDir: File = File(appFilesDir, "linux-runtime")
    val binDir: File = File(baseDir, "bin")
    val prootFile: File = File(binDir, "proot")
    val distrosDir: File = File(baseDir, "distros")
    val rootfsDir: File get() {
        val installed = listInstalledDistroIds().firstOrNull() ?: "ubuntu"
        return rootfsDir(installed)
    }
    val stagingRootfsDir: File = File(baseDir, "rootfs.staging")
    val homeDir: File get() {
        val installed = listInstalledDistroIds().firstOrNull() ?: "ubuntu"
        return homeDir(installed)
    }
    val optDir: File = File(baseDir, "opt")
    val workspaceDir: File = File(baseDir, "workspace")
    /** Host-side shared attachment directory, mounted into every distro at /attachments. */
    val attachmentsDir: File = File(baseDir, "attachments")
    private val legacyAttachmentsDir: File = File(appFilesDir, "attachments")
    val cacheDir: File = File(baseDir, "cache")
    val tmpDir: File = File(baseDir, "tmp")
    val logsDir: File = File(baseDir, "logs")
    val metadataDir: File = File(baseDir, "metadata")

    /**
     * Git 凭证 IPC 桥接目录：跨发行版共享，绑定到沙箱 `/wanxiang-ipc`。
     *
     * 容器内 git 缺凭据时自定义 credential.helper 写 `cred-req-<id>` 到这里 → app FileObserver
     * 捕获 → 全局弹窗让用户填 → 写 `cred-resp-<id>` → helper 读回喂 git → git 自动续跑。
     * 同时 `git-credentials`（`credential.helper=store --file=...` 的目标）与 `git-credential-wanxiang`
     * 也放这里，UI 与容器 git 共用同一份文件真源。
     */
    val gitIpcDir: File = File(baseDir, "git-ipc")

    // ==================== 插件目录（按发行版隔离） ====================
    // v17 起插件本体/数据/命令与依赖 Runtime 均随发行版走：
    // linux-runtime/distros/<id>/opt/wanxiang/{tools,data,bin,runtimes,...}

    fun distroOptDir(distroId: String): File = File(distroDir(distroId), "opt")

    fun wanxiangRootDir(distroId: String): File = File(distroOptDir(distroId), "wanxiang")

    fun wanxiangRuntimesDir(distroId: String): File = File(wanxiangRootDir(distroId), "runtimes")

    fun wanxiangToolsDir(distroId: String): File = File(wanxiangRootDir(distroId), "tools")

    fun wanxiangDataDir(distroId: String): File = File(wanxiangRootDir(distroId), "data")

    fun wanxiangScriptsDir(distroId: String): File = File(wanxiangRootDir(distroId), "scripts")

    fun wanxiangBinDir(distroId: String): File = File(wanxiangRootDir(distroId), "bin")

    fun ensureDistroDirectories(distroId: String) {
        val safeId = distroId.lowercase().trim()
        listOf(
            wanxiangRootDir(safeId),
            wanxiangRuntimesDir(safeId),
            wanxiangToolsDir(safeId),
            wanxiangScriptsDir(safeId),
            wanxiangDataDir(safeId),
            wanxiangBinDir(safeId),
        ).forEach { it.mkdirs() }
    }
    private val hostLibraries = mapOf(
        "libtalloc.so" to "libtalloc.so.2",
        "libandroid-shmem.so" to "libandroid-shmem.so",
    )

    // ==================== 多系统发行版路径解析 ====================

    fun distroDir(distroId: String): File = File(distrosDir, distroId.lowercase().trim())
    fun rootfsDir(distroId: String): File = File(distroDir(distroId), "rootfs")
    fun homeDir(distroId: String): File = File(distroDir(distroId), "home")
    fun metadataDir(distroId: String): File = File(distroDir(distroId), "metadata")
    fun stagingRootfsDir(distroId: String): File = File(distroDir(distroId), "rootfs.staging")
    fun rootfsPreviousDir(distroId: String): File = File(distroDir(distroId), "rootfs.previous")
    fun rootfsInstalledMarker(distroId: String): File = File(metadataDir(distroId), "rootfs.installed")
    fun rootfsUpdatePendingMarker(distroId: String): File = File(metadataDir(distroId), "rootfs.update.pending")
    fun distroLayersFile(distroId: String): File = File(metadataDir(distroId), "layers.txt")

    fun isDistroInstalled(distroId: String): Boolean =
        rootfsInstalledMarker(distroId).isFile && rootfsValidator.isValid(rootfsDir(distroId))

    /**
     * 已安装发行版 ID 缓存。
     *
     * `rootfsDir` / `homeDir` getter 与每条命令构建都会走到这里；原实现每次访问都重新
     * 扫描 distros 目录并对每个候选目录做 ELF 校验，是冷启动与首次执行命令的隐形热点。
     * 只缓存非空结果：空结果的目录扫描本身开销极小（marker 文件缺失即短路）。
     * 结构性变更（安装/更新/重置/卸载）后由 RootfsInstaller / refreshInstalledDistros 显式失效。
     */
    @Volatile
    private var installedDistroIdsCache: List<String>? = null

    fun listInstalledDistroIds(): List<String> {
        installedDistroIdsCache?.let { return it }
        if (!distrosDir.exists()) return emptyList()
        val ids = distrosDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && isDistroInstalled(it.name) }
            .map { it.name }
        if (ids.isNotEmpty()) installedDistroIdsCache = ids
        return ids
    }

    fun invalidateInstalledDistrosCache() {
        installedDistroIdsCache = null
    }

    /** 发行版磁盘占用快照缓存文件（metadata/distro.size），避免启动路径全树遍历。 */
    fun distroSizeMarker(distroId: String): File = File(metadataDir(distroId.lowercase().trim()), "distro.size")

    fun invalidateDistroSizeCache(distroId: String) {
        runCatching { distroSizeMarker(distroId).delete() }
    }

    fun rootfsVersion(distroId: String): String? = rootfsInstalledMarker(distroId)
        .takeIf { it.isFile }
        ?.useLines { lines ->
            lines.firstOrNull()?.substringAfter("rootfs-version=", "")?.trim()
        }
        ?.takeIf { it.isNotBlank() }

    fun rootfsDigest(distroId: String): String? = rootfsInstalledMarker(distroId)
        .takeIf { it.isFile }
        ?.useLines { lines ->
            lines.firstOrNull { it.startsWith("rootfs-digest=") }
                ?.substringAfter("rootfs-digest=", "")
                ?.trim()
        }
        ?.takeIf { it.isNotBlank() }

    fun distroSizeBytes(distroId: String): Long {
        val safeId = distroId.lowercase().trim()
        val dir = distroDir(safeId)
        if (!dir.exists()) return 0L
        // 启动路径高频调用：优先读安装/更新时缓存的体积快照，避免对数万文件的 rootfs 做 walkTopDown。
        distroSizeMarker(safeId).takeIf { it.isFile }
            ?.useLines { lines -> lines.firstOrNull()?.trim()?.toLongOrNull() }
            ?.takeIf { it >= 0L }
            ?.let { return it }
        val computed = runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
        if (computed > 0L) {
            runCatching {
                metadataDir(safeId).mkdirs()
                distroSizeMarker(safeId).writeText(computed.toString())
            }
        }
        return computed
    }

    /**
     * Android 10+ blocks execve() from writable app data for apps targeting API 29+.
     * The small Android-native PRoot fallback is therefore shipped as an extracted
     * native library, while the Debian Linux system remains an online download.
     */
    fun activeProotFile(): File = bundledProotFile.takeIf { isUsableProot(it) } ?: prootFile

    fun ensureDirectories() {
        listOf(
            baseDir,
            binDir,
            distrosDir,
            optDir,
            workspaceDir,
            attachmentsDir,
            cacheDir,
            tmpDir,
            logsDir,
            metadataDir,
            gitIpcDir,
        ).forEach { it.mkdirs() }
        migrateLegacyAttachments()
        listInstalledDistroIds().forEach(::ensureDistroDirectories)
    }

    /** Preserve attachments created before the shared runtime mount was introduced. */
    private fun migrateLegacyAttachments() {
        if (!legacyAttachmentsDir.isDirectory) return
        legacyAttachmentsDir.listFiles().orEmpty()
            .filter { it.isFile }
            .forEach { source ->
                val target = File(attachmentsDir, source.name)
                if (!target.exists()) runCatching { source.copyTo(target, overwrite = false) }
            }
    }

    fun cleanupStalePtyMarkers(distroId: String = "ubuntu") {
        val safeId = distroId.lowercase().trim()
        wanxiangRootDir(safeId)
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith(".pty-") }
            .forEach { it.delete() }
    }

    /**
     * PRoot is an Android-native executable, but its Termux build keeps talloc as a
     * dynamically linked library. Android's APK native-library packaging only accepts
     * the `.so` suffix, so copy the bundled library to the exact SONAME expected by
     * the linker in the app-private runtime directory before launching PRoot.
     */
    fun hostProcessEnvironment(distroId: String = "ubuntu", rootfsOverride: File? = null): Map<String, String> {
        ensureDirectories()
        val rfs = rootfsOverride ?: rootfsDir(distroId)
        // Remove the app-managed QEMU payload left by versions that used an
        // x86_64 AAPT2 fallback. Current Android tooling is ARM64-native.
        File(wanxiangBinDir(distroId), "qemu-x86_64-static").delete()
        hostLibraries.forEach { (bundledName, hostName) ->
            val bundledFile = File(nativeLibraryDir, bundledName)
            val hostFile = File(binDir, hostName)
            if (bundledFile.isFile && bundledFile.length() > MIN_TALLOC_BYTES) {
                // Refresh tiny native dependencies after an APK update; equal
                // file length alone does not prove that an old SONAME copy matches.
                val needsCopy = !hostFile.isFile ||
                    hostFile.length() != bundledFile.length() ||
                    !bundledFile.readBytes().contentEquals(hostFile.readBytes())
                if (needsCopy) bundledFile.copyTo(hostFile, overwrite = true)
                hostFile.setReadable(true, false)
                hostFile.setWritable(true, true)
            }
        }
        return buildMap {
            put("PROOT_L2S_DIR", File(rfs, ".l2s").apply { mkdirs() }.absolutePath)
            if (isUsableNativeArtifact(bundledProotLoaderFile, MIN_PROOT_LOADER_BYTES)) {
                put("PROOT_LOADER", bundledProotLoaderFile.absolutePath)
            }
            if (isUsableNativeArtifact(bundledProotLoader32File, MIN_PROOT_LOADER_BYTES)) {
                put("PROOT_LOADER_32", bundledProotLoader32File.absolutePath)
            }
            put("TMPDIR", tmpDir.absolutePath)
            put("TMP", tmpDir.absolutePath)
            put("TEMP", tmpDir.absolutePath)
            put("PROOT_TMP_DIR", tmpDir.absolutePath)
            if (hostLibraries.values.any { File(binDir, it).isFile }) {
                put("LD_LIBRARY_PATH", binDir.absolutePath)
            }
        }
    }

    fun rootfsInstalledMarker(): File = File(metadataDir, "rootfs.installed")

    fun rootfsUpdatePendingMarker(): File = File(metadataDir, "rootfs.update.pending")

    fun rootfsPreviousDir(): File = File(baseDir, "rootfs.previous")

    fun rootfsVersion(): String? = rootfsInstalledMarker()
        .takeIf { it.isFile }
        ?.useLines { lines ->
            lines.firstOrNull()?.substringAfter("rootfs-version=", "")?.trim()
        }
        ?.takeIf { it.isNotBlank() }

    /** PRoot is usable only when both the tracer and its external ARM64 loader are in the APK. */
    fun isProotInstalled(): Boolean =
        isUsableProot(activeProotFile()) &&
            isUsableNativeArtifact(bundledProotLoaderFile, MIN_PROOT_LOADER_BYTES)

    fun isRootfsInstalled(): Boolean = listInstalledDistroIds().isNotEmpty()

    private fun isUsableProot(file: File): Boolean =
        file.isFile && file.length() > MIN_PROOT_BYTES && (file.canExecute() || file == bundledProotFile)

    private fun isUsableNativeArtifact(file: File, minimumBytes: Long): Boolean =
        file.isFile && file.length() > minimumBytes && file.canRead()

    private companion object {
        const val MIN_PROOT_BYTES = 4096L
        const val MIN_PROOT_LOADER_BYTES = 4096L
        const val MIN_TALLOC_BYTES = 4096L
    }
}
