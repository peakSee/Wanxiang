package top.wanxiang.app.runtime

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import top.wanxiang.app.core.common.files.SafeFileTree
import top.wanxiang.app.core.common.result.AppError
import top.wanxiang.app.core.common.result.AppResult
import top.wanxiang.app.core.common.result.ErrorCode
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 存储清理操作的风险等级
 */
enum class StorageRiskLevel {
    /** 安全清理：临时缓存、下载缓存、包管理器依赖归档、日志等，清理后不影响任何代码与核心功能 */
    SAFE,

    /** 谨慎清理：项目编译构建产物 (build/target)、未引用的孤立运行时等，清理后可释放大量空间，后续可自动重新生成 */
    CAUTION,

    /** 深度清理：历史会话记录、特定插件持久化数据等，操作不可逆，需要二次确认 */
    DANGEROUS,

    /** 只读系统/用户源码：系统基础底模镜像、代码工程源码、已安装的开发套件，禁止直接批量清理 */
    READONLY,
}

/**
 * 细化存储条目
 */
data class StorageEntry(
    val id: String,
    val name: String,
    val detail: String = "",
    val bytes: Long,
    val cleanable: Boolean = false,
    val riskLevel: StorageRiskLevel = StorageRiskLevel.READONLY,
    val cleanupHint: String? = null,
    val path: String? = null,
    val subItems: List<StorageEntry> = emptyList(),
) {
    /** 兼容旧构造器 (name, detail, bytes) */
    constructor(name: String, detail: String = "", bytes: Long) : this(
        id = name,
        name = name,
        detail = detail,
        bytes = bytes,
        cleanable = false,
        riskLevel = StorageRiskLevel.READONLY,
    )
}

/**
 * 存储大类分类
 */
data class StorageCategory(
    val id: String,
    val name: String,
    val description: String = "",
    val bytes: Long,
    val cleanable: Boolean = false,
    val riskLevel: StorageRiskLevel = StorageRiskLevel.READONLY,
    val cleanupHint: String? = null,
    val entries: List<StorageEntry> = emptyList(),
) {
    /** 兼容旧构造器 (id, name, bytes, entries) */
    constructor(id: String, name: String, bytes: Long, entries: List<StorageEntry> = emptyList()) : this(
        id = id,
        name = name,
        description = "",
        bytes = bytes,
        cleanable = entries.any { it.cleanable },
        riskLevel = entries.map { it.riskLevel }.minOrNull() ?: StorageRiskLevel.READONLY,
        cleanupHint = null,
        entries = entries,
    )
}

/**
 * 全局存储体检快照
 */
data class StorageUsage(
    val rootfsBytes: Long = 0L,
    val runtimeBytes: Long = 0L,
    val toolBytes: Long = 0L,
    val dataBytes: Long = 0L,
    val workspaceBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val availableBytes: Long = 0L,
    val skillBytes: Long = 0L,
    val attachmentBytes: Long = 0L,
    val databaseBytes: Long = 0L,
    val appDataBytes: Long = 0L,
    val safeCleanableBytes: Long = 0L,
    val cautionCleanableBytes: Long = 0L,
    val categories: List<StorageCategory> = emptyList(),
    val scannedAt: Long = System.currentTimeMillis(),
) {
    val totalManagedBytes: Long
        get() = categories.sumOf { it.bytes }
}

@Singleton
class StorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pathManager: RuntimePathManager,
) {
    private val buildFolderNames = setOf(
        "build",
        "target",
        ".dart_tool",
        "dist",
        ".gradle",
        ".cxx",
        "out",
        ".next",
        ".nuxt",
        "__pycache__",
    )

    /**
     * 对应用所管理的沙箱、SDK 工具链、依赖缓存、项目构建产物与数据库进行精细化深度体检
     */
    suspend fun inspect(): StorageUsage = withContext(Dispatchers.IO) {
        val distroIds = pathManager.distrosDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { it.name }
            .ifEmpty { pathManager.listInstalledDistroIds() }

        // =========================================================================
        // 1. 开发套件与 SDK 工具链 (Android SDK, Flutter, JDK, LLVM, Rust, Gradle)
        // =========================================================================
        val sdkEntries = mutableListOf<StorageEntry>()

        distroIds.forEach { distroId ->
            val rfs = pathManager.rootfsDir(distroId)
            val wanxiangRoot = pathManager.wanxiangRootDir(distroId)
            if (rfs.exists()) {
                // 1.1 Android SDK 套件（检测 rfs/opt/android-sdk 与 wanxiangRoot/toolchains/android）
                val androidSdkDir = listOf(
                    File(rfs, "opt/android-sdk"),
                    File(wanxiangRoot, "toolchains/android/sdk"),
                    File(rfs, "opt/wanxiang/toolchains/android/sdk"),
                ).firstOrNull { it.exists() } ?: File(rfs, "opt/android-sdk")

                val androidToolchainsDir = listOf(
                    File(wanxiangRoot, "toolchains/android"),
                    File(rfs, "opt/wanxiang/toolchains/android"),
                ).firstOrNull { it.exists() } ?: File(wanxiangRoot, "toolchains/android")

                val platformsDir = File(androidSdkDir, "platforms")
                val buildToolsDir = File(androidSdkDir, "build-tools")
                val platformToolsDir = File(androidSdkDir, "platform-tools")
                val ndkDir = File(androidToolchainsDir, "ndk")
                val jdkDir = File(androidToolchainsDir, "jdk")
                val sdkToolsDir = File(androidToolchainsDir, "sdk-tools")

                val subItems = mutableListOf<StorageEntry>()
                if (platformsDir.exists()) {
                    subItems.add(
                        StorageEntry(
                            id = "android_platforms_$distroId",
                            name = "Platform SDK (android.jar)",
                            detail = "Android API 平台组件",
                            bytes = sizeOf(platformsDir),
                            cleanable = false,
                            riskLevel = StorageRiskLevel.READONLY,
                            path = platformsDir.absolutePath,
                        ),
                    )
                }
                if (buildToolsDir.exists()) {
                    subItems.add(
                        StorageEntry(
                            id = "android_build_tools_$distroId",
                            name = "Build-Tools (aapt2, d8, zipalign)",
                            detail = "Android 编译与打包工具",
                            bytes = sizeOf(buildToolsDir),
                            cleanable = false,
                            riskLevel = StorageRiskLevel.READONLY,
                            path = buildToolsDir.absolutePath,
                        ),
                    )
                }
                if (platformToolsDir.exists()) {
                    subItems.add(
                        StorageEntry(
                            id = "android_platform_tools_$distroId",
                            name = "Platform-Tools (adb)",
                            detail = "Android 调试桥与辅助工具",
                            bytes = sizeOf(platformToolsDir),
                            cleanable = false,
                            riskLevel = StorageRiskLevel.READONLY,
                            path = platformToolsDir.absolutePath,
                        ),
                    )
                }
                if (ndkDir.exists()) {
                    subItems.add(
                        StorageEntry(
                            id = "android_ndk_$distroId",
                            name = "Android NDK C/C++ 工具链",
                            detail = "本地交叉编译工具与 LLVM Clang",
                            bytes = sizeOf(ndkDir),
                            cleanable = false,
                            riskLevel = StorageRiskLevel.READONLY,
                            path = ndkDir.absolutePath,
                        ),
                    )
                }
                if (jdkDir.exists()) {
                    subItems.add(
                        StorageEntry(
                            id = "android_jdk_$distroId",
                            name = "Android 编译专用 OpenJDK",
                            detail = "用于 Gradle 构建的 JDK 运行时",
                            bytes = sizeOf(jdkDir),
                            cleanable = false,
                            riskLevel = StorageRiskLevel.READONLY,
                            path = jdkDir.absolutePath,
                        ),
                    )
                }
                if (sdkToolsDir.exists()) {
                    subItems.add(
                        StorageEntry(
                            id = "android_sdk_tools_$distroId",
                            name = "Android SDK 适配与辅助工具",
                            detail = "AArch64 定制打包与签名组件",
                            bytes = sizeOf(sdkToolsDir),
                            cleanable = false,
                            riskLevel = StorageRiskLevel.READONLY,
                            path = sdkToolsDir.absolutePath,
                        ),
                    )
                }

                val androidSdkBytes = if (subItems.isNotEmpty()) {
                    subItems.sumOf { it.bytes }
                } else {
                    sizeOf(androidSdkDir) + sizeOf(androidToolchainsDir)
                }

                if (androidSdkBytes > 0) {
                    sdkEntries.add(
                        StorageEntry(
                            id = "sdk_android_$distroId",
                            name = "Android SDK 开发套件 ($distroId)",
                            detail = "Android API Platform、Build-Tools、NDK 与编译工具",
                            bytes = androidSdkBytes,
                            cleanable = false,
                            riskLevel = StorageRiskLevel.READONLY,
                            cleanupHint = "Android 编译核心组件，由工坊开发套件管理",
                            path = if (androidSdkDir.exists()) androidSdkDir.absolutePath else androidToolchainsDir.absolutePath,
                            subItems = subItems,
                        ),
                    )
                }

                // 1.2 Flutter SDK & Dart SDK
                val flutterDir = listOf(
                    File(rfs, "opt/flutter"),
                    File(wanxiangRoot, "runtimes/flutter"),
                    File(wanxiangRoot, "toolchains/flutter"),
                    File(rfs, "opt/wanxiang/toolchains/flutter"),
                ).firstOrNull { it.exists() }

                if (flutterDir != null) {
                    val flutterBytes = sizeOf(flutterDir)
                    if (flutterBytes > 0) {
                        val dartSdkDir = File(flutterDir, "bin/cache/dart-sdk")
                        val subItemsFlutter = mutableListOf<StorageEntry>()
                        if (dartSdkDir.exists()) {
                            val dartBytes = sizeOf(dartSdkDir)
                            subItemsFlutter.add(
                                StorageEntry(
                                    id = "dart_sdk_$distroId",
                                    name = "内置 Dart SDK 与运行时",
                                    detail = "Dart 编译器与语言工具",
                                    bytes = dartBytes,
                                    cleanable = false,
                                    riskLevel = StorageRiskLevel.READONLY,
                                    path = dartSdkDir.absolutePath,
                                ),
                            )
                            subItemsFlutter.add(
                                StorageEntry(
                                    id = "flutter_engine_$distroId",
                                    name = "Flutter 跨平台框架与引擎",
                                    detail = "Flutter 核心类库与编译工具",
                                    bytes = (flutterBytes - dartBytes).coerceAtLeast(0L),
                                    cleanable = false,
                                    riskLevel = StorageRiskLevel.READONLY,
                                    path = flutterDir.absolutePath,
                                ),
                            )
                        }
                        sdkEntries.add(
                            StorageEntry(
                                id = "sdk_flutter_$distroId",
                                name = "Flutter SDK & Dart 运行时 ($distroId)",
                                detail = "Linux ARM64 Flutter 框架、Dart 编译器与引擎",
                                bytes = flutterBytes,
                                cleanable = false,
                                riskLevel = StorageRiskLevel.READONLY,
                                cleanupHint = "Flutter 跨平台开发核心 SDK",
                                path = flutterDir.absolutePath,
                                subItems = subItemsFlutter,
                            ),
                        )
                    }
                }

                // 1.3 x86_64 跨架构兼容环境
                val compatDir = listOf(
                    File(wanxiangRoot, "compat/x86_64"),
                    File(rfs, "opt/wanxiang/compat/x86_64"),
                ).firstOrNull { it.exists() }

                if (compatDir != null) {
                    val compatBytes = sizeOf(compatDir)
                    if (compatBytes > 0) {
                        sdkEntries.add(
                            StorageEntry(
                                id = "sdk_compat_x64_$distroId",
                                name = "x86_64 QEMU 兼容套件 ($distroId)",
                                detail = "QEMU 仿真引擎、兼容层 RootFS 与 Adoptium JDK 17 (x64)",
                                bytes = compatBytes,
                                cleanable = false,
                                riskLevel = StorageRiskLevel.READONLY,
                                cleanupHint = "用于跨架构运行官方仅提供 x86_64 二进制的构建工具",
                                path = compatDir.absolutePath,
                            ),
                        )
                    }
                }

                // 1.4 Gradle 独立分发版本 (/opt/gradle-* 与 /root/.gradle/wrapper/dists)
                val optGradleDirs = (File(rfs, "opt").listFiles().orEmpty().toList().filter { it.isDirectory && it.name.startsWith("gradle-") } +
                    File(wanxiangRoot, "toolchains").listFiles().orEmpty().toList().filter { it.isDirectory && it.name.startsWith("gradle-") }).distinctBy { it.absolutePath }
                val wrapperDistsDir = File(rfs, "root/.gradle/wrapper/dists")
                val optGradleBytes = optGradleDirs.sumOf { sizeOf(it) }
                val wrapperDistsBytes = sizeOf(wrapperDistsDir)
                val totalGradleSdkBytes = optGradleBytes + wrapperDistsBytes

                if (totalGradleSdkBytes > 0) {
                    val subItemsGradle = mutableListOf<StorageEntry>()
                    optGradleDirs.forEach { dir ->
                        subItemsGradle.add(
                            StorageEntry(
                                id = "gradle_opt_${dir.name}_$distroId",
                                name = "独立分发包 ${dir.name}",
                                detail = "解压的 Gradle 运行时环境",
                                bytes = sizeOf(dir),
                                cleanable = false,
                                riskLevel = StorageRiskLevel.READONLY,
                                path = dir.absolutePath,
                            ),
                        )
                    }
                    if (wrapperDistsBytes > 0) {
                        subItemsGradle.add(
                            StorageEntry(
                                id = "gradle_wrapper_dists_$distroId",
                                name = "Gradle Wrapper 历史分发包 (wrapper/dists)",
                                detail = "各项目 gradlew 自动拉取解压的 Gradle 运行时",
                                bytes = wrapperDistsBytes,
                                cleanable = true,
                                riskLevel = StorageRiskLevel.CAUTION,
                                cleanupHint = "清理历史下载解压的 Gradle Wrapper 分发包，下次 gradlew 时会自动重新拉取",
                                path = wrapperDistsDir.absolutePath,
                            ),
                        )
                    }
                    sdkEntries.add(
                        StorageEntry(
                            id = "sdk_gradle_$distroId",
                            name = "Gradle 构建工具分发版本 ($distroId)",
                            detail = "独立安装与 Wrapper 自动下载的 Gradle 运行环境",
                            bytes = totalGradleSdkBytes,
                            cleanable = wrapperDistsBytes > 0,
                            riskLevel = if (wrapperDistsBytes > 0) StorageRiskLevel.CAUTION else StorageRiskLevel.READONLY,
                            cleanupHint = if (wrapperDistsBytes > 0) "可清理 Wrapper 历史分发包" else null,
                            path = (optGradleDirs.firstOrNull() ?: wrapperDistsDir).absolutePath,
                            subItems = subItemsGradle,
                        ),
                    )
                }

                // 1.5 Rustup 与 Rust 编译工具链 (/root/.rustup)
                val rustupDir = File(rfs, "root/.rustup")
                val rustupBytes = sizeOf(rustupDir)
                if (rustupBytes > 0) {
                    sdkEntries.add(
                        StorageEntry(
                            id = "sdk_rustup_$distroId",
                            name = "Rustup 与 Rust 工具链 ($distroId)",
                            detail = "rustc 编译器、标准库 rust-std 与 cargo 工具链",
                            bytes = rustupBytes,
                            cleanable = false,
                            riskLevel = StorageRiskLevel.READONLY,
                            cleanupHint = "Rust 语言官方编译工具链",
                            path = rustupDir.absolutePath,
                        ),
                    )
                }

                // 1.6 系统级 JVM / LLVM / Go 工具链 (/usr/lib/jvm, /usr/lib/llvm-*, /usr/local/go)
                val jvmDir = File(rfs, "usr/lib/jvm")
                val jvmBytes = sizeOf(jvmDir)
                if (jvmBytes > 0) {
                    sdkEntries.add(
                        StorageEntry(
                            id = "sdk_jvm_$distroId",
                            name = "系统级 Java JDK (/usr/lib/jvm)",
                            detail = "OpenJDK / Temurin 运行时与编译开发包",
                            bytes = jvmBytes,
                            cleanable = false,
                            riskLevel = StorageRiskLevel.READONLY,
                            cleanupHint = "系统已安装的 Java 开发套件",
                            path = jvmDir.absolutePath,
                        ),
                    )
                }

                val llvmDirs = File(rfs, "usr/lib").listFiles().orEmpty()
                    .filter { it.isDirectory && (it.name.startsWith("llvm-") || it.name == "clang") }
                val llvmBytes = llvmDirs.sumOf { sizeOf(it) }
                if (llvmBytes > 0) {
                    sdkEntries.add(
                        StorageEntry(
                            id = "sdk_llvm_$distroId",
                            name = "LLVM / Clang C/C++ 编译器 (${llvmDirs.joinToString { it.name }})",
                            detail = "系统安装的 LLVM 编译器框架与 Clang 工具链",
                            bytes = llvmBytes,
                            cleanable = false,
                            riskLevel = StorageRiskLevel.READONLY,
                            cleanupHint = "C/C++ 核心编译工具链",
                            path = llvmDirs.first().absolutePath,
                        ),
                    )
                }

                val goDir = File(rfs, "usr/local/go").takeIf { it.exists() } ?: File(rfs, "usr/lib/go")
                val goBytes = sizeOf(goDir)
                if (goBytes > 0) {
                    sdkEntries.add(
                        StorageEntry(
                            id = "sdk_go_$distroId",
                            name = "Go 语言 SDK ($distroId)",
                            detail = "Go 编译器、标准库与链接器",
                            bytes = goBytes,
                            cleanable = false,
                            riskLevel = StorageRiskLevel.READONLY,
                            cleanupHint = "Go 语言核心 SDK",
                            path = goDir.absolutePath,
                        ),
                    )
                }

                // 1.7 其他独立工具链 (/opt/wanxiang/toolchains/* 排除已计入的 android 与 flutter)
                val extraToolchains = (File(wanxiangRoot, "toolchains").listFiles().orEmpty().toList() +
                    File(rfs, "opt/wanxiang/toolchains").listFiles().orEmpty().toList())
                    .filter { it.isDirectory && it.name != "android" && it.name != "flutter" }
                    .distinctBy { it.name }
                extraToolchains.forEach { tcDir ->
                    val tcBytes = sizeOf(tcDir)
                    if (tcBytes > 0) {
                        sdkEntries.add(
                            StorageEntry(
                                id = "sdk_custom_${tcDir.name}_$distroId",
                                name = "${tcDir.name} 独立工具链 ($distroId)",
                                detail = "自定义或扩展安装的编译工具链",
                                bytes = tcBytes,
                                cleanable = false,
                                riskLevel = StorageRiskLevel.READONLY,
                                path = tcDir.absolutePath,
                            ),
                        )
                    }
                }
            }
        }
        val totalSdkBytes = sdkEntries.sumOf { it.bytes }

        // =========================================================================
        // 2. 包管理与依赖构建缓存 (Gradle/APT/Pip/NPM/Cargo/Pub/Go/Tmp)
        // =========================================================================
        val packageCacheEntries = mutableListOf<StorageEntry>()
        distroIds.forEach { distroId ->
            val rfs = pathManager.rootfsDir(distroId)
            if (rfs.exists()) {
                // 2.1 Gradle 依赖与转换缓存 (Maven modules-2, transforms)
                val gradleCacheDir = File(rfs, "root/.gradle/caches")
                val gradleCacheBytes = sizeOf(gradleCacheDir)
                val gradleDaemonDir = File(rfs, "root/.gradle/daemon")
                val gradleDaemonBytes = sizeOf(gradleDaemonDir)
                val totalGradleCache = gradleCacheBytes + gradleDaemonBytes
                if (totalGradleCache > 0) {
                    packageCacheEntries.add(
                        StorageEntry(
                            id = "gradle_cache_$distroId",
                            name = "Gradle 依赖与构建缓存 ($distroId)",
                            detail = "Maven 依赖库 (modules-2)、AAR Transform 与 Daemon 日志",
                            bytes = totalGradleCache,
                            cleanable = true,
                            riskLevel = StorageRiskLevel.SAFE,
                            cleanupHint = "清理 Gradle 依赖缓存，下次构建会自动重新下载",
                            path = gradleCacheDir.absolutePath,
                        ),
                    )
                }

                // 2.2 APT 软件包归档与源列表
                val aptArchives = File(rfs, "var/cache/apt/archives")
                val aptArchivesBytes = sizeOfDirectoryContents(aptArchives, ignoreFiles = setOf("lock"))
                if (aptArchivesBytes > 0) {
                    packageCacheEntries.add(
                        StorageEntry(
                            id = "apt_cache_$distroId",
                            name = "APT 软件包下载归档 ($distroId)",
                            detail = "已下载的 deb 安装包，安装完成后可安全清理",
                            bytes = aptArchivesBytes,
                            cleanable = true,
                            riskLevel = StorageRiskLevel.SAFE,
                            cleanupHint = "清理已下载的 deb 包，不影响已安装的软件",
                            path = aptArchives.absolutePath,
                        ),
                    )
                }

                val aptLists = File(rfs, "var/lib/apt/lists")
                val aptListsBytes = sizeOfDirectoryContents(aptLists, ignoreFiles = setOf("lock", "partial"))
                if (aptListsBytes > 0) {
                    packageCacheEntries.add(
                        StorageEntry(
                            id = "apt_lists_$distroId",
                            name = "APT 软件源列表索引 ($distroId)",
                            detail = "apt update 生成的软件源索引列表",
                            bytes = aptListsBytes,
                            cleanable = true,
                            riskLevel = StorageRiskLevel.SAFE,
                            cleanupHint = "清理索引文件，下次 apt update 时会自动更新",
                            path = aptLists.absolutePath,
                        ),
                    )
                }

                // 2.3 Python Pip 缓存
                val pipCache = File(rfs, "root/.cache/pip")
                val wheelCache = File(rfs, "root/.cache/wheel")
                val pipBytes = sizeOf(pipCache) + sizeOf(wheelCache)
                if (pipBytes > 0) {
                    packageCacheEntries.add(
                        StorageEntry(
                            id = "pip_cache_$distroId",
                            name = "Python Pip 依赖包缓存 ($distroId)",
                            detail = "PyPI 依赖包下载归档与预构建 wheel 缓存",
                            bytes = pipBytes,
                            cleanable = true,
                            riskLevel = StorageRiskLevel.SAFE,
                            cleanupHint = "清理 pip 下载缓存，不影响已安装的 Python 包",
                            path = pipCache.absolutePath,
                        ),
                    )
                }

                // 2.4 Node.js NPM / Yarn / Pnpm 缓存
                val npmCache = File(rfs, "root/.npm")
                val yarnCache = File(rfs, "root/.yarn/cache").takeIf { it.exists() } ?: File(rfs, "root/.cache/yarn")
                val pnpmStore = File(rfs, "root/.local/share/pnpm/store")
                val npmBytes = sizeOf(npmCache) + sizeOf(yarnCache) + sizeOf(pnpmStore)
                if (npmBytes > 0) {
                    packageCacheEntries.add(
                        StorageEntry(
                            id = "npm_cache_$distroId",
                            name = "Node.js NPM / Yarn 依赖缓存 ($distroId)",
                            detail = "NPM / Yarn 全局 tarball 归档与依赖缓存",
                            bytes = npmBytes,
                            cleanable = true,
                            riskLevel = StorageRiskLevel.SAFE,
                            cleanupHint = "清理 npm / yarn 缓存，不影响已有项目",
                            path = npmCache.absolutePath,
                        ),
                    )
                }

                // 2.5 Rust Cargo 依赖下载缓存
                val cargoRegistry = File(rfs, "root/.cargo/registry")
                val cargoGit = File(rfs, "root/.cargo/git")
                val cargoBytes = sizeOf(cargoRegistry) + sizeOf(cargoGit)
                if (cargoBytes > 0) {
                    packageCacheEntries.add(
                        StorageEntry(
                            id = "cargo_cache_$distroId",
                            name = "Rust Cargo 依赖源码缓存 ($distroId)",
                            detail = "crates.io 依赖归档与 git 仓库检出缓存",
                            bytes = cargoBytes,
                            cleanable = true,
                            riskLevel = StorageRiskLevel.SAFE,
                            cleanupHint = "清理 crates.io 源码缓存，再次编译时会自动拉取",
                            path = cargoRegistry.absolutePath,
                        ),
                    )
                }

                // 2.6 Dart / Flutter Pub 缓存
                val pubCache = File(rfs, "root/.pub-cache")
                val pubBytes = sizeOf(pubCache)
                if (pubBytes > 0) {
                    packageCacheEntries.add(
                        StorageEntry(
                            id = "pub_cache_$distroId",
                            name = "Dart / Flutter Pub 依赖缓存 ($distroId)",
                            detail = "pub.dev 依赖包与预编译资产缓存",
                            bytes = pubBytes,
                            cleanable = true,
                            riskLevel = StorageRiskLevel.SAFE,
                            cleanupHint = "清理 pub 缓存，下次 flutter pub get 会重新拉取",
                            path = pubCache.absolutePath,
                        ),
                    )
                }

                // 2.7 Go 模块依赖缓存
                val goModCache = File(rfs, "root/go/pkg/mod")
                val goModBytes = sizeOf(goModCache)
                if (goModBytes > 0) {
                    packageCacheEntries.add(
                        StorageEntry(
                            id = "go_mod_cache_$distroId",
                            name = "Go Module 依赖缓存 ($distroId)",
                            detail = "Go 依赖包与模块代理下载缓存",
                            bytes = goModBytes,
                            cleanable = true,
                            riskLevel = StorageRiskLevel.SAFE,
                            cleanupHint = "清理 Go 依赖缓存，下次 go build 会重新下载",
                            path = goModCache.absolutePath,
                        ),
                    )
                }

                // 2.8 沙箱内部 /tmp 与 /var/tmp
                val sandboxTmp = File(rfs, "tmp")
                val sandboxVarTmp = File(rfs, "var/tmp")
                val sandboxTmpBytes = sizeOfDirectoryContents(sandboxTmp) + sizeOfDirectoryContents(sandboxVarTmp)
                if (sandboxTmpBytes > 0) {
                    packageCacheEntries.add(
                        StorageEntry(
                            id = "sandbox_tmp_$distroId",
                            name = "沙箱运行时临时目录 /tmp ($distroId)",
                            detail = "Linux 编译与运行时进程产生的临时文件",
                            bytes = sandboxTmpBytes,
                            cleanable = true,
                            riskLevel = StorageRiskLevel.SAFE,
                            cleanupHint = "清理进程临时文件",
                            path = sandboxTmp.absolutePath,
                        ),
                    )
                }
            }
        }

        // 2.9 宿主 PRoot 桥接临时
        val hostTmpBytes = sizeOf(pathManager.tmpDir)
        if (hostTmpBytes > 0) {
            packageCacheEntries.add(
                StorageEntry(
                    id = "host_tmp",
                    name = "宿主运行临时文件 (tmp)",
                    detail = "PRoot 启动与进程桥接临时文件",
                    bytes = hostTmpBytes,
                    cleanable = true,
                    riskLevel = StorageRiskLevel.SAFE,
                    cleanupHint = "清理宿主 PRoot 桥接临时文件",
                    path = pathManager.tmpDir.absolutePath,
                ),
            )
        }
        val packageCacheCategoryBytes = packageCacheEntries.sumOf { it.bytes }

        // =========================================================================
        // 3. 工作区项目与代码工程 (/workspace)
        // =========================================================================
        val projectEntries = mutableListOf<StorageEntry>()
        pathManager.workspaceDir.listFiles().orEmpty().forEach { projDir ->
            if (projDir.isDirectory) {
                val totalProjBytes = sizeOf(projDir)
                val buildDirs = projDir.walkTopDown()
                    .maxDepth(4)
                    .filter { it.isDirectory && buildFolderNames.contains(it.name) }
                    .toList()
                val buildBytes = buildDirs.sumOf { sizeOf(it) }
                val sourceBytes = (totalProjBytes - buildBytes).coerceAtLeast(0L)

                val subItems = mutableListOf<StorageEntry>()
                subItems.add(
                    StorageEntry(
                        id = "${projDir.name}_source",
                        name = "源代码与工程资源",
                        detail = "核心源码文件与配置",
                        bytes = sourceBytes,
                        cleanable = false,
                        riskLevel = StorageRiskLevel.READONLY,
                        path = projDir.absolutePath,
                    ),
                )
                if (buildBytes > 0) {
                    subItems.add(
                        StorageEntry(
                            id = "${projDir.name}_build",
                            name = "编译构建产物 (${buildDirs.joinToString { it.name }})",
                            detail = "build / target / .dart_tool 等生成物",
                            bytes = buildBytes,
                            cleanable = true,
                            riskLevel = StorageRiskLevel.CAUTION,
                            cleanupHint = "清理构建生成物，保留全部源代码。下次构建会自动重新编译",
                            path = projDir.absolutePath,
                        ),
                    )
                }

                projectEntries.add(
                    StorageEntry(
                        id = "proj_${projDir.name}",
                        name = projDir.name,
                        detail = "源码 ${sourceBytes.readableSize()} · 构建产物 ${buildBytes.readableSize()}",
                        bytes = totalProjBytes,
                        cleanable = buildBytes > 0,
                        riskLevel = if (buildBytes > 0) StorageRiskLevel.CAUTION else StorageRiskLevel.READONLY,
                        cleanupHint = if (buildBytes > 0) "一键清理该项目的 build / target 等构建产物" else null,
                        path = projDir.absolutePath,
                        subItems = subItems,
                    ),
                )
            }
        }
        val workspaceBytes = projectEntries.sumOf { it.bytes }

        // =========================================================================
        // 4. 插件与工具生态 (/opt/wanxiang/tools 与 /opt/wanxiang/data)
        // =========================================================================
        val pluginEntries = distroIds.flatMap { distroId -> pluginEntries(distroId) }
        val toolBytes = pluginEntries.sumOf { it.bytes }

        // =========================================================================
        // 5. 共享 Runtime 运行时 (/opt/wanxiang/runtimes)
        // =========================================================================
        val runtimeEntries = distroIds.flatMap { distroId -> runtimeEntries(distroId) }
        val sharedRuntimeBytes = runtimeEntries.sumOf { it.bytes }

        // =========================================================================
        // 6. 用户家目录与个人配置 (/root 与 /home，扣除 SDK 与依赖缓存)
        // =========================================================================
        val userHomeEntries = distroIds.map { distroId ->
            val rfs = pathManager.rootfsDir(distroId)
            val rootDir = File(rfs, "root")
            val homeDir = File(rfs, "home")
            val totalHome = sizeOf(rootDir) + sizeOf(homeDir)

            // 扣除家目录内的 SDK (rustup, gradle wrapper, nvm, sdkman 等)
            val homeSdkBytes = sizeOf(File(rootDir, ".rustup")) +
                sizeOf(File(rootDir, ".gradle/wrapper")) +
                sizeOf(File(rootDir, ".nvm")) +
                sizeOf(File(rootDir, ".sdkman")) +
                sizeOf(File(rootDir, ".pyenv"))

            // 扣除家目录内的缓存 (gradle caches, npm, cargo, pub, go mod, .cache)
            val homeCacheBytes = sizeOf(File(rootDir, ".gradle/caches")) +
                sizeOf(File(rootDir, ".gradle/daemon")) +
                sizeOf(File(rootDir, ".npm")) +
                sizeOf(File(rootDir, ".cargo")) +
                sizeOf(File(rootDir, ".pub-cache")) +
                sizeOf(File(rootDir, "go/pkg/mod")) +
                sizeOf(File(rootDir, ".cache"))

            val purePersonalBytes = (totalHome - homeSdkBytes - homeCacheBytes).coerceAtLeast(0L)
            StorageEntry(
                id = "home_$distroId",
                name = "/root 与用户家目录 ($distroId)",
                detail = "个人配置文件 (.bashrc/.profile/.ssh)、脚本与 shell 历史",
                bytes = purePersonalBytes,
                cleanable = false,
                riskLevel = StorageRiskLevel.READONLY,
                path = rootDir.absolutePath,
            )
        }
        val userHomeBytes = userHomeEntries.sumOf { it.bytes }

        // =========================================================================
        // 7. Linux 基础系统底模 (Pure RootFS: 真实 Linux 核心系统，扣除 SDK、包缓存、家目录与日志)
        // =========================================================================
        val rootfsEntries = distroIds.map { distroId ->
            val rfs = pathManager.rootfsDir(distroId)
            val rfsTotalBytes = sizeOf(rfs)

            // 1. 位于 rfs 物理目录内的所有 SDK 占用
            val rfsSdks = sdkEntries
                .filter { it.id.endsWith("_$distroId") }
                .flatMap { it.subItems.ifEmpty { listOf(it) } }
                .filter { it.path?.startsWith(rfs.absolutePath) == true }
                .sumOf { it.bytes }

            // 2. 位于 rfs 物理目录内的所有依赖与包管理器缓存
            val rfsCaches = packageCacheEntries
                .filter { it.id.endsWith("_$distroId") }
                .filter { it.path?.startsWith(rfs.absolutePath) == true }
                .sumOf { it.bytes }

            // 3. 位于 rfs/root 与 rfs/home 的个人数据
            val rfsHome = userHomeEntries
                .filter { it.id == "home_$distroId" }
                .sumOf { it.bytes }

            // 4. 位于 rfs 内的系统日志 (/var/log)
            val rfsLogs = sizeOfDirectoryContents(File(rfs, "var/log"))

            // 5. 位于 rfs/opt/wanxiang（若存在）
            val rfsWanxiangOpt = sizeOf(File(rfs, "opt/wanxiang"))

            val pureBaseRfsBytes = (rfsTotalBytes - rfsSdks - rfsCaches - rfsHome - rfsLogs - rfsWanxiangOpt)
                .coerceAtLeast(0L)

            StorageEntry(
                id = "rootfs_$distroId",
                name = "$distroId 纯净系统底模",
                detail = "Linux 核心系统、标准类库与系统工具 (/usr, /lib, /bin, /etc)",
                bytes = pureBaseRfsBytes,
                cleanable = false,
                riskLevel = StorageRiskLevel.READONLY,
                path = rfs.absolutePath,
            )
        }
        val rootfsBytes = rootfsEntries.sumOf { it.bytes }

        // =========================================================================
        // 8. 日志与诊断数据
        // =========================================================================
        val logEntries = mutableListOf<StorageEntry>()
        val hostLogsBytes = sizeOf(pathManager.logsDir)
        if (hostLogsBytes > 0) {
            logEntries.add(
                StorageEntry(
                    id = "host_logs",
                    name = "万象运行与智能体日志",
                    detail = "执行轨迹、诊断与调试日志",
                    bytes = hostLogsBytes,
                    cleanable = true,
                    riskLevel = StorageRiskLevel.SAFE,
                    cleanupHint = "清理历史运行日志，不影响任何功能",
                    path = pathManager.logsDir.absolutePath,
                ),
            )
        }
        distroIds.forEach { distroId ->
            val varLog = File(pathManager.rootfsDir(distroId), "var/log")
            val varLogBytes = sizeOfDirectoryContents(varLog)
            if (varLogBytes > 0) {
                logEntries.add(
                    StorageEntry(
                        id = "system_logs_$distroId",
                        name = "Linux 系统日志 /var/log ($distroId)",
                        detail = "系统内核与服务运行日志",
                        bytes = varLogBytes,
                        cleanable = true,
                        riskLevel = StorageRiskLevel.SAFE,
                        cleanupHint = "清空系统日志",
                        path = varLog.absolutePath,
                    ),
                )
            }
        }
        val logCategoryBytes = logEntries.sumOf { it.bytes }

        // =========================================================================
        // 9. 附件与自定义 Skills
        // =========================================================================
        val skillsRoot = File(pathManager.attachmentsDir, "skills")
        val skillEntries = childEntries(skillsRoot, "自定义 Skill")
        val skillBytes = skillEntries.sumOf { it.bytes }
        val attachmentEntries = pathManager.attachmentsDir.listFiles().orEmpty()
            .filterNot { it.name == "skills" }
            .map {
                StorageEntry(
                    id = "attachment_${it.name}",
                    name = it.name,
                    detail = "智能体执行附件",
                    bytes = sizeOf(it),
                    cleanable = true,
                    riskLevel = StorageRiskLevel.CAUTION,
                    cleanupHint = "清理历史任务附件",
                    path = it.absolutePath,
                )
            }
            .sortedByDescending { it.bytes }
        val attachmentBytes = attachmentEntries.sumOf { it.bytes }

        // =========================================================================
        // 10. 下载、升级暂存与系统缓存
        // =========================================================================
        val downloadCacheEntries = mutableListOf<StorageEntry>()

        // 10.1 万象沙箱下载缓存 (linux-runtime/cache)
        val runtimeCacheBytes = sizeOf(pathManager.cacheDir)
        if (runtimeCacheBytes > 0) {
            downloadCacheEntries.add(
                StorageEntry(
                    id = "cache_runtime_dir",
                    name = "沙箱镜像与安装包缓存",
                    detail = "OCI Layers、LXC 镜像与离线依赖归档 (linux-runtime/cache)",
                    bytes = runtimeCacheBytes,
                    cleanable = true,
                    riskLevel = StorageRiskLevel.SAFE,
                    cleanupHint = "清理下载归档包，不影响已解压安装的环境",
                    path = pathManager.cacheDir.absolutePath,
                ),
            )
        }

        // 10.2 Android 原生系统缓存 (context.cacheDir - 桌面显示的 2.11GB 缓存)
        val contextCache = runCatching { context.cacheDir }.getOrNull()
        val contextCacheBytes = contextCache?.let { sizeOf(it) } ?: 0L
        if (contextCacheBytes > 0 && contextCache != null) {
            downloadCacheEntries.add(
                StorageEntry(
                    id = "cache_android_sys",
                    name = "应用系统与导入缓存 (Android Cache)",
                    detail = "导入的 RootFS 离线包、APK 升级包与网络图片缓存",
                    bytes = contextCacheBytes,
                    cleanable = true,
                    riskLevel = StorageRiskLevel.SAFE,
                    cleanupHint = "清理 Android 系统缓存目录，释放系统设置显示的缓存容量",
                    path = contextCache.absolutePath,
                ),
            )
        }

        // 10.3 Android 原生代码编译缓存 (context.codeCacheDir)
        val codeCacheDir = runCatching { context.codeCacheDir }.getOrNull()
        val codeCacheBytes = codeCacheDir?.let { sizeOf(it) } ?: 0L
        if (codeCacheBytes > 0 && codeCacheDir != null) {
            downloadCacheEntries.add(
                StorageEntry(
                    id = "cache_code_cache",
                    name = "JIT 代码编译缓存 (code_cache)",
                    detail = "ART 虚拟机 JIT 编译生成的机器码缓存",
                    bytes = codeCacheBytes,
                    cleanable = true,
                    riskLevel = StorageRiskLevel.SAFE,
                    cleanupHint = "清理 JIT 缓存，应用下次启动时会自动按需生成",
                    path = codeCacheDir.absolutePath,
                ),
            )
        }

        // 10.4 各发行版升级历史备份与解压暂存 (rootfs.previous / staging)
        distroIds.forEach { distroId ->
            val prevDir = pathManager.rootfsPreviousDir(distroId)
            val prevBytes = sizeOf(prevDir)
            if (prevBytes > 0) {
                downloadCacheEntries.add(
                    StorageEntry(
                        id = "rootfs_prev_$distroId",
                        name = "$distroId 升级历史备份镜像",
                        detail = "上次升级时留存的回滚底模 (rootfs.previous)",
                        bytes = prevBytes,
                        cleanable = true,
                        riskLevel = StorageRiskLevel.SAFE,
                        cleanupHint = "升级稳定后可随时清理此备份，立即释放数 GB 空间",
                        path = prevDir.absolutePath,
                    ),
                )
            }

            val stagingDir = pathManager.stagingRootfsDir(distroId)
            val stagingBytes = sizeOf(stagingDir)
            if (stagingBytes > 0) {
                downloadCacheEntries.add(
                    StorageEntry(
                        id = "rootfs_staging_$distroId",
                        name = "$distroId 解压升级暂存目录",
                        detail = "未完成或残留的镜像解压暂存",
                        bytes = stagingBytes,
                        cleanable = true,
                        riskLevel = StorageRiskLevel.SAFE,
                        cleanupHint = "清理升级残留暂存",
                        path = stagingDir.absolutePath,
                    ),
                )
            }

            val flutterCache = File(pathManager.wanxiangRootDir(distroId), "flutter-cache-arm64")
            val flutterCacheBytes = sizeOf(flutterCache)
            if (flutterCacheBytes > 0) {
                downloadCacheEntries.add(
                    StorageEntry(
                        id = "flutter_tar_cache_$distroId",
                        name = "Flutter SDK 下载归档 ($distroId)",
                        detail = "已解压完成的 Flutter 原始 tarball",
                        bytes = flutterCacheBytes,
                        cleanable = true,
                        riskLevel = StorageRiskLevel.SAFE,
                        cleanupHint = "SDK 已成功安装，原始压缩包可安全删除",
                        path = flutterCache.absolutePath,
                    ),
                )
            }

            val importsDir = File(pathManager.wanxiangRootDir(distroId), "imports")
            val importsBytes = sizeOf(importsDir)
            if (importsBytes > 0) {
                val subItemsImports = importsDir.listFiles().orEmpty()
                    .filter { it.isDirectory }
                    .map { toolDir ->
                        StorageEntry(
                            id = "plugin_import_staging_${distroId}_${toolDir.name}",
                            name = "插件 ${toolDir.name} 沙箱安装暂存",
                            detail = "已复制到 /opt/wanxiang/imports/${toolDir.name} 的解压副本",
                            bytes = sizeOf(toolDir),
                            cleanable = true,
                            riskLevel = StorageRiskLevel.SAFE,
                            cleanupHint = "插件已安装完成，该沙箱暂存副本可安全清理",
                            path = toolDir.absolutePath,
                        )
                    }
                downloadCacheEntries.add(
                    StorageEntry(
                        id = "plugin_import_staging_$distroId",
                        name = "本地插件沙箱安装暂存 ($distroId)",
                        detail = "导入插件向沙箱复制的 payload 副本 (/opt/wanxiang/imports)",
                        bytes = importsBytes,
                        cleanable = true,
                        riskLevel = StorageRiskLevel.SAFE,
                        cleanupHint = "插件安装完成后可安全清理，释放数 GB 空间",
                        path = importsDir.absolutePath,
                        subItems = subItemsImports,
                    ),
                )
            }
        }

        // 10.5 全局暂存目录
        val globalStagingBytes = sizeOf(pathManager.stagingRootfsDir)
        if (globalStagingBytes > 0) {
            downloadCacheEntries.add(
                StorageEntry(
                    id = "global_staging_cache",
                    name = "全局解压暂存目录",
                    detail = "沙箱解压中转暂存",
                    bytes = globalStagingBytes,
                    cleanable = true,
                    riskLevel = StorageRiskLevel.SAFE,
                    cleanupHint = "清理临时中转文件",
                    path = pathManager.stagingRootfsDir.absolutePath,
                ),
            )
        }

        val downloadCacheBytes = downloadCacheEntries.sumOf { it.bytes }

        // =========================================================================
        // 11. 会话与应用数据库
        // =========================================================================
        val databaseEntries = context.getDatabasePath("wanxiang.db").parentFile?.listFiles().orEmpty()
            .filter { it.name == "wanxiang.db" || it.name.startsWith("wanxiang.db-") }
            .map {
                StorageEntry(
                    id = "db_${it.name}",
                    name = it.name,
                    detail = if (it.name == "wanxiang.db") "会话、消息与本地持久化数据" else "SQLite WAL/SHM 辅助文件",
                    bytes = sizeOf(it),
                    cleanable = false,
                    riskLevel = StorageRiskLevel.DANGEROUS,
                    path = it.absolutePath,
                )
            }
            .sortedByDescending { it.bytes }
        val databaseBytes = databaseEntries.sumOf { it.bytes }

        // =========================================================================
        // 12. 应用数据与偏好
        // =========================================================================
        val appDataEntries = mutableListOf<StorageEntry>()
        val datastoreDir = File(context.filesDir, "datastore")
        if (datastoreDir.exists()) {
            appDataEntries.addAll(childEntries(datastoreDir, "DataStore 偏好配置"))
        }
        context.filesDir.listFiles().orEmpty()
            .filterNot { it.name == "linux-runtime" || it.name == "datastore" }
            .forEach { dirOrFile ->
                val s = sizeOf(dirOrFile)
                if (s > 0) {
                    if (dirOrFile.name == "plugins" && dirOrFile.isDirectory) {
                        val subItemsPlugins = dirOrFile.listFiles().orEmpty()
                            .filter { it.isDirectory }
                            .map { toolDir ->
                                val versions = toolDir.listFiles().orEmpty().filter { it.isDirectory }
                                StorageEntry(
                                    id = "host_plugin_${toolDir.name}",
                                    name = "本地离线包: ${toolDir.name} (${versions.joinToString { it.name }})",
                                    detail = "宿主存储中的原始离线解包资源 (payload/)",
                                    bytes = sizeOf(toolDir),
                                    cleanable = true,
                                    riskLevel = StorageRiskLevel.CAUTION,
                                    cleanupHint = "删除此插件离线包，已安装到沙箱的工具不受影响",
                                    path = toolDir.absolutePath,
                                )
                            }
                        appDataEntries.add(
                            StorageEntry(
                                id = "app_data_plugins",
                                name = "宿主级本地插件包仓库 (plugins)",
                                detail = "通过文件管理器导入的本地插件原始包与解压资源 (payload/)",
                                bytes = s,
                                cleanable = true,
                                riskLevel = StorageRiskLevel.CAUTION,
                                cleanupHint = "清理宿主已导入的离线插件源包，若已安装则不影响沙箱运行",
                                path = dirOrFile.absolutePath,
                                subItems = subItemsPlugins,
                            ),
                        )
                    } else {
                        appDataEntries.add(
                            StorageEntry(
                                id = "app_data_${dirOrFile.name}",
                                name = when (dirOrFile.name) {
                                    "templates" -> "工程初始化模板库"
                                    "registry" -> "插件注册表元数据"
                                    "adb" -> "嵌入式 ADB 密钥凭据"
                                    "reports" -> "运行与崩溃诊断报告"
                                    "skills" -> "宿主级 Skill 导入库"
                                    else -> "应用数据 (${dirOrFile.name})"
                                },
                                detail = dirOrFile.absolutePath,
                                bytes = s,
                                cleanable = dirOrFile.name in setOf("reports", "plugins"),
                                riskLevel = if (dirOrFile.name in setOf("reports", "plugins")) StorageRiskLevel.SAFE else StorageRiskLevel.READONLY,
                                path = dirOrFile.absolutePath,
                            ),
                        )
                    }
                }
            }
        val appDataBytes = appDataEntries.sumOf { it.bytes }

        // =========================================================================
        // 组装各大分类列表
        // =========================================================================
        val categories = mutableListOf<StorageCategory>()

        // 1. 开发套件与 SDK 工具链 (从 Linux 系统中独立剥离)
        if (totalSdkBytes > 0 || sdkEntries.isNotEmpty()) {
            val cleanableSdks = sdkEntries.filter { it.cleanable }.sumOf { it.bytes }
            categories.add(
                StorageCategory(
                    id = "sdk_toolchains",
                    name = "开发套件与 SDK 工具链",
                    description = "Android SDK、Flutter SDK、Gradle 分发版、JDK、Rust 等编译套件",
                    bytes = totalSdkBytes,
                    cleanable = cleanableSdks > 0,
                    riskLevel = if (cleanableSdks > 0) StorageRiskLevel.CAUTION else StorageRiskLevel.READONLY,
                    cleanupHint = if (cleanableSdks > 0) "可清理已解压的 Gradle Wrapper 历史分发包" else "已安装的编译工具链",
                    entries = sdkEntries.sortedByDescending { it.bytes },
                ),
            )
        }

        // 2. 包管理与依赖构建缓存
        if (packageCacheCategoryBytes > 0 || packageCacheEntries.isNotEmpty()) {
            categories.add(
                StorageCategory(
                    id = "package_cache",
                    name = "包管理与依赖构建缓存",
                    description = "Gradle 依赖 (modules-2)、APT deb 归档、Pip、NPM、Cargo、Pub 缓存",
                    bytes = packageCacheCategoryBytes,
                    cleanable = true,
                    riskLevel = StorageRiskLevel.SAFE,
                    cleanupHint = "一键清理所有包管理器依赖缓存，完全不影响已有项目与代码",
                    entries = packageCacheEntries.sortedByDescending { it.bytes },
                ),
            )
        }

        // 3. 工作区项目与代码工程
        if (workspaceBytes > 0 || projectEntries.isNotEmpty()) {
            val totalCleanableBuild = projectEntries.flatMap { it.subItems }
                .filter { it.cleanable }
                .sumOf { it.bytes }
            categories.add(
                StorageCategory(
                    id = "workspace_projects",
                    name = "代码工程与项目",
                    description = "/workspace 下的代码工程，包含源码资产与可清理的编译生成物",
                    bytes = workspaceBytes,
                    cleanable = totalCleanableBuild > 0,
                    riskLevel = StorageRiskLevel.CAUTION,
                    cleanupHint = if (totalCleanableBuild > 0) "一键清理所有项目的 build / target 等构建产物，保留全部源码" else null,
                    entries = projectEntries.sortedByDescending { it.bytes },
                ),
            )
        }

        // 4. Linux 纯净基础系统 (真实底模)
        categories.add(
            StorageCategory(
                id = "linux_system",
                name = "Linux 基础系统",
                description = "Linux 纯净系统底模核心组件与标准库（大小稳定）",
                bytes = rootfsBytes,
                cleanable = false,
                riskLevel = StorageRiskLevel.READONLY,
                cleanupHint = "系统底层运行环境，大小保持稳定",
                entries = rootfsEntries,
            ),
        )

        // 5. 插件与扩展生态
        if (toolBytes > 0 || pluginEntries.isNotEmpty()) {
            categories.add(
                StorageCategory(
                    id = "plugins",
                    name = "插件与扩展生态",
                    description = "通过工具中心安装的各语言工具、SDK 与持久化数据",
                    bytes = toolBytes,
                    cleanable = false,
                    riskLevel = StorageRiskLevel.DANGEROUS,
                    cleanupHint = "包含插件执行程序与私有数据",
                    entries = pluginEntries.sortedByDescending { it.bytes },
                ),
            )
        }

        // 6. 共享 Runtime 运行时
        if (sharedRuntimeBytes > 0 || runtimeEntries.isNotEmpty()) {
            val cleanableRuntimes = runtimeEntries.filter { it.cleanable }.sumOf { it.bytes }
            categories.add(
                StorageCategory(
                    id = "runtimes",
                    name = "共享 Runtime 运行时",
                    description = "跨插件共享的底层语言运行环境 (Python/Node/Flutter/JDK 等)",
                    bytes = sharedRuntimeBytes,
                    cleanable = cleanableRuntimes > 0,
                    riskLevel = StorageRiskLevel.CAUTION,
                    cleanupHint = if (cleanableRuntimes > 0) "清理未被任何工具引用的共享运行时" else null,
                    entries = runtimeEntries.sortedByDescending { it.bytes },
                ),
            )
        }

        // 7. 用户家目录与个人配置
        if (userHomeBytes > 0 || userHomeEntries.isNotEmpty()) {
            categories.add(
                StorageCategory(
                    id = "user_home",
                    name = "用户家目录与个人配置",
                    description = "/root 与用户家目录下的个人配置文件与脚本 (已排除 SDK 与依赖)",
                    bytes = userHomeBytes,
                    cleanable = false,
                    riskLevel = StorageRiskLevel.READONLY,
                    cleanupHint = "用户自定义配置文件",
                    entries = userHomeEntries.sortedByDescending { it.bytes },
                ),
            )
        }

        // 8. 系统与智能体日志
        if (logCategoryBytes > 0 || logEntries.isNotEmpty()) {
            categories.add(
                StorageCategory(
                    id = "logs",
                    name = "系统与智能体日志",
                    description = "万象运行、诊断与系统执行日志",
                    bytes = logCategoryBytes,
                    cleanable = true,
                    riskLevel = StorageRiskLevel.SAFE,
                    cleanupHint = "清空所有运行日志与调试日志",
                    entries = logEntries.sortedByDescending { it.bytes },
                ),
            )
        }

        // 9. 下载与安装包缓存
        if (downloadCacheBytes > 0 || downloadCacheEntries.isNotEmpty()) {
            categories.add(
                StorageCategory(
                    id = "cache",
                    name = "下载与安装包缓存",
                    description = "RootFS 升级包、离线工具包与分卷解压缓存",
                    bytes = downloadCacheBytes,
                    cleanable = true,
                    riskLevel = StorageRiskLevel.SAFE,
                    cleanupHint = "清理临时下载的安装包，释放存储空间",
                    entries = downloadCacheEntries,
                ),
            )
        }

        // 10. 附件与自定义 Skills
        if (attachmentBytes > 0 || skillBytes > 0) {
            categories.add(
                StorageCategory(
                    id = "attachments",
                    name = "运行附件与自定义 Skills",
                    description = "对话任务附件与导入的自定义 Skills",
                    bytes = attachmentBytes + skillBytes,
                    cleanable = attachmentBytes > 0,
                    riskLevel = StorageRiskLevel.CAUTION,
                    cleanupHint = "清理历史会话附件",
                    entries = (attachmentEntries + skillEntries).sortedByDescending { it.bytes },
                ),
            )
        }

        // 11. 会话与应用数据库
        if (databaseBytes > 0 || databaseEntries.isNotEmpty()) {
            categories.add(
                StorageCategory(
                    id = "database",
                    name = "会话与应用数据库",
                    description = "SQLite 本地数据库（包含对话记录、消息、模型档案等）",
                    bytes = databaseBytes,
                    cleanable = false,
                    riskLevel = StorageRiskLevel.DANGEROUS,
                    cleanupHint = "核心数据库文件，包含重要业务数据",
                    entries = databaseEntries,
                ),
            )
        }

        // 12. 应用数据与偏好
        if (appDataBytes > 0 || appDataEntries.isNotEmpty()) {
            categories.add(
                StorageCategory(
                    id = "app_data",
                    name = "应用偏好与配置",
                    description = "DataStore 用户偏好设置与界面配置",
                    bytes = appDataBytes,
                    cleanable = false,
                    riskLevel = StorageRiskLevel.READONLY,
                    cleanupHint = "偏好配置",
                    entries = appDataEntries,
                ),
            )
        }

        // 计算可安全释放与建议谨慎释放的空间总量
        val safeCleanableBytes = categories
            .filter { it.riskLevel == StorageRiskLevel.SAFE }
            .sumOf { it.bytes }

        val cautionCleanableBytes = categories.flatMap { it.entries }
            .filter { it.cleanable && it.riskLevel == StorageRiskLevel.CAUTION }
            .sumOf { it.bytes }

        val available = availableBytes()

        StorageUsage(
            rootfsBytes = rootfsBytes,
            runtimeBytes = packageCacheCategoryBytes + logCategoryBytes,
            toolBytes = toolBytes,
            dataBytes = 0L,
            workspaceBytes = workspaceBytes,
            cacheBytes = downloadCacheBytes,
            availableBytes = available,
            skillBytes = skillBytes,
            attachmentBytes = attachmentBytes,
            databaseBytes = databaseBytes,
            appDataBytes = appDataBytes,
            safeCleanableBytes = safeCleanableBytes,
            cautionCleanableBytes = cautionCleanableBytes,
            categories = categories,
            scannedAt = System.currentTimeMillis(),
        )
    }

    /**
     * 一键安全清理：并行清理所有 SAFE 级别的缓存（Gradle 依赖缓存、APT 归档/列表、pip、npm、cargo、pub、go 缓存、沙箱 /tmp、日志、下载缓存），不影响任何代码与功能。
     */
    suspend fun quickSafeClean(): AppResult<Long> = withContext(Dispatchers.IO) {
        try {
            var released = 0L
            val distroIds = pathManager.distrosDir.listFiles().orEmpty()
                .filter { it.isDirectory }
                .map { it.name }
                .ifEmpty { pathManager.listInstalledDistroIds() }

            // 1. 下载、系统缓存与升级备份
            val ctxCache = runCatching { context.cacheDir }.getOrNull()
            val ctxCodeCache = runCatching { context.codeCacheDir }.getOrNull()
            val beforeCache = sizeOf(pathManager.cacheDir) + sizeOf(pathManager.stagingRootfsDir) + (ctxCache?.let { sizeOf(it) } ?: 0L) + (ctxCodeCache?.let { sizeOf(it) } ?: 0L)
            clearDirectory(pathManager.cacheDir)
            clearDirectory(pathManager.stagingRootfsDir)
            ctxCache?.let { clearDirectory(it) }
            ctxCodeCache?.let { clearDirectory(it) }
            val afterCache = sizeOf(pathManager.cacheDir) + sizeOf(pathManager.stagingRootfsDir) + (ctxCache?.let { sizeOf(it) } ?: 0L) + (ctxCodeCache?.let { sizeOf(it) } ?: 0L)
            released += (beforeCache - afterCache).coerceAtLeast(0L)

            distroIds.forEach { distroId ->
                val prev = pathManager.rootfsPreviousDir(distroId)
                if (prev.exists()) {
                    val s = sizeOf(prev)
                    SafeFileTree.delete(prev)
                    released += s
                }
                val staging = pathManager.stagingRootfsDir(distroId)
                if (staging.exists()) {
                    val s = sizeOf(staging)
                    SafeFileTree.delete(staging)
                    released += s
                }
                val flutterCache = File(pathManager.wanxiangRootDir(distroId), "flutter-cache-arm64")
                if (flutterCache.exists()) {
                    val s = sizeOf(flutterCache)
                    SafeFileTree.delete(flutterCache)
                    released += s
                }
            }

            // 2. 宿主临时与日志
            val beforeHostTmp = sizeOf(pathManager.tmpDir)
            clearDirectory(pathManager.tmpDir)
            released += (beforeHostTmp - sizeOf(pathManager.tmpDir)).coerceAtLeast(0L)

            val beforeLogs = sizeOf(pathManager.logsDir)
            clearDirectory(pathManager.logsDir)
            released += (beforeLogs - sizeOf(pathManager.logsDir)).coerceAtLeast(0L)

            // 3. 各 Linux 发行版内的依赖缓存与 /tmp
            distroIds.forEach { distroId ->
                val rfs = pathManager.rootfsDir(distroId)
                if (rfs.exists()) {
                    // Gradle caches & daemon
                    val gradleCache = File(rfs, "root/.gradle/caches")
                    val beforeGradle = sizeOf(gradleCache)
                    clearDirectory(gradleCache)
                    released += beforeGradle

                    val gradleDaemon = File(rfs, "root/.gradle/daemon")
                    val beforeDaemon = sizeOf(gradleDaemon)
                    clearDirectory(gradleDaemon)
                    released += beforeDaemon

                    // APT archives & lists
                    val aptArchives = File(rfs, "var/cache/apt/archives")
                    val beforeApt = sizeOfDirectoryContents(aptArchives, ignoreFiles = setOf("lock"))
                    clearDirectory(aptArchives, ignoreFiles = setOf("lock"))
                    released += beforeApt

                    val aptLists = File(rfs, "var/lib/apt/lists")
                    val beforeAptLists = sizeOfDirectoryContents(aptLists, ignoreFiles = setOf("lock", "partial"))
                    clearDirectory(aptLists, ignoreFiles = setOf("lock", "partial"))
                    released += beforeAptLists

                    // Pip & Wheel
                    val pipCache = File(rfs, "root/.cache/pip")
                    val beforePip = sizeOf(pipCache)
                    clearDirectory(pipCache)
                    released += beforePip

                    val wheelCache = File(rfs, "root/.cache/wheel")
                    val beforeWheel = sizeOf(wheelCache)
                    clearDirectory(wheelCache)
                    released += beforeWheel

                    // NPM & Yarn & Pnpm
                    val npmCache = File(rfs, "root/.npm")
                    val beforeNpm = sizeOf(npmCache)
                    clearDirectory(npmCache)
                    released += beforeNpm

                    val yarnCache = File(rfs, "root/.yarn/cache").takeIf { it.exists() } ?: File(rfs, "root/.cache/yarn")
                    val beforeYarn = sizeOf(yarnCache)
                    clearDirectory(yarnCache)
                    released += beforeYarn

                    val pnpmStore = File(rfs, "root/.local/share/pnpm/store")
                    val beforePnpm = sizeOf(pnpmStore)
                    clearDirectory(pnpmStore)
                    released += beforePnpm

                    // Cargo registry & git
                    val cargoRegistry = File(rfs, "root/.cargo/registry")
                    val beforeCargo = sizeOf(cargoRegistry)
                    clearDirectory(cargoRegistry)
                    released += beforeCargo

                    val cargoGit = File(rfs, "root/.cargo/git")
                    val beforeCargoGit = sizeOf(cargoGit)
                    clearDirectory(cargoGit)
                    released += beforeCargoGit

                    // Pub cache
                    val pubCache = File(rfs, "root/.pub-cache")
                    val beforePub = sizeOf(pubCache)
                    clearDirectory(pubCache)
                    released += beforePub

                    // Go mod cache
                    val goModCache = File(rfs, "root/go/pkg/mod/cache")
                    val beforeGoMod = sizeOf(goModCache)
                    clearDirectory(goModCache)
                    released += beforeGoMod

                    // /tmp & /var/tmp
                    val sandboxTmp = File(rfs, "tmp")
                    val beforeTmp = sizeOfDirectoryContents(sandboxTmp)
                    clearDirectory(sandboxTmp)
                    released += beforeTmp

                    val sandboxVarTmp = File(rfs, "var/tmp")
                    val beforeVarTmp = sizeOfDirectoryContents(sandboxVarTmp)
                    clearDirectory(sandboxVarTmp)
                    released += beforeVarTmp

                    // /var/log
                    val varLog = File(rfs, "var/log")
                    val beforeVarLog = sizeOfDirectoryContents(varLog)
                    clearDirectory(varLog)
                    released += beforeVarLog
                }
            }
            AppResult.Success(released)
        } catch (throwable: Throwable) {
            AppResult.Failure(
                AppError(
                    code = ErrorCode.IO,
                    message = throwable.message ?: "安全清理失败",
                    cause = throwable,
                ),
            )
        }
    }

    /**
     * 清理工作区项目编译产物 (build, target, .dart_tool, dist 等)，保留全部源代码
     * @param projectName 可选，指定清理某个项目；为 null 时清理所有项目
     */
    suspend fun cleanProjectBuildArtifacts(projectName: String? = null): AppResult<Long> = withContext(Dispatchers.IO) {
        try {
            var released = 0L
            val targetProjects = if (projectName != null) {
                listOf(File(pathManager.workspaceDir, projectName))
            } else {
                pathManager.workspaceDir.listFiles().orEmpty().filter { it.isDirectory }.toList()
            }

            targetProjects.forEach { projDir ->
                if (projDir.isDirectory) {
                    val buildDirs = projDir.walkTopDown()
                        .maxDepth(4)
                        .filter { it.isDirectory && buildFolderNames.contains(it.name) }
                        .toList()

                    buildDirs.forEach { dir ->
                        val size = sizeOf(dir)
                        SafeFileTree.delete(dir)
                        released += size
                    }
                }
            }
            AppResult.Success(released)
        } catch (throwable: Throwable) {
            AppResult.Failure(
                AppError(
                    code = ErrorCode.IO,
                    message = throwable.message ?: "清理项目构建产物失败",
                    cause = throwable,
                ),
            )
        }
    }

    /**
     * 按分类执行清理
     */
    suspend fun clearCategory(categoryId: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            when (categoryId) {
                "package_cache" -> quickSafeClean()
                "cache" -> clearCache()
                "logs" -> {
                    clearDirectory(pathManager.logsDir)
                    val distroIds = pathManager.distrosDir.listFiles().orEmpty()
                        .filter { it.isDirectory }
                        .map { it.name }
                        .ifEmpty { pathManager.listInstalledDistroIds() }
                    distroIds.forEach {
                        clearDirectory(File(pathManager.rootfsDir(it), "var/log"))
                    }
                }
                "workspace_projects" -> cleanProjectBuildArtifacts(null)
                "attachments" -> clearDirectory(pathManager.attachmentsDir, ignoreFiles = setOf("skills"))
                "sdk_toolchains" -> {
                    val distroIds = pathManager.distrosDir.listFiles().orEmpty()
                        .filter { it.isDirectory }
                        .map { it.name }
                        .ifEmpty { pathManager.listInstalledDistroIds() }
                    distroIds.forEach { distroId ->
                        val wrapperDists = File(pathManager.rootfsDir(distroId), "root/.gradle/wrapper/dists")
                        clearDirectory(wrapperDists)
                    }
                }
                else -> {}
            }
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(
                AppError(
                    code = ErrorCode.IO,
                    message = throwable.message ?: "清理分类失败",
                    cause = throwable,
                ),
            )
        }
    }

    /**
     * 清理指定细项
     */
    suspend fun clearEntry(categoryId: String, entryId: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            when {
                entryId.startsWith("proj_") -> {
                    val projectName = entryId.removePrefix("proj_")
                    cleanProjectBuildArtifacts(projectName)
                }
                entryId.endsWith("_build") -> {
                    val projectName = entryId.removeSuffix("_build")
                    cleanProjectBuildArtifacts(projectName)
                }
                entryId.startsWith("gradle_cache_") -> {
                    val distroId = entryId.removePrefix("gradle_cache_")
                    clearDirectory(File(pathManager.rootfsDir(distroId), "root/.gradle/caches"))
                    clearDirectory(File(pathManager.rootfsDir(distroId), "root/.gradle/daemon"))
                }
                entryId.startsWith("gradle_wrapper_dists_") -> {
                    val distroId = entryId.removePrefix("gradle_wrapper_dists_")
                    clearDirectory(File(pathManager.rootfsDir(distroId), "root/.gradle/wrapper/dists"))
                }
                entryId.startsWith("apt_cache_") -> {
                    val distroId = entryId.removePrefix("apt_cache_")
                    val dir = File(pathManager.rootfsDir(distroId), "var/cache/apt/archives")
                    clearDirectory(dir, ignoreFiles = setOf("lock"))
                }
                entryId.startsWith("apt_lists_") -> {
                    val distroId = entryId.removePrefix("apt_lists_")
                    val dir = File(pathManager.rootfsDir(distroId), "var/lib/apt/lists")
                    clearDirectory(dir, ignoreFiles = setOf("lock", "partial"))
                }
                entryId.startsWith("pip_cache_") -> {
                    val distroId = entryId.removePrefix("pip_cache_")
                    clearDirectory(File(pathManager.rootfsDir(distroId), "root/.cache/pip"))
                    clearDirectory(File(pathManager.rootfsDir(distroId), "root/.cache/wheel"))
                }
                entryId.startsWith("npm_cache_") -> {
                    val distroId = entryId.removePrefix("npm_cache_")
                    clearDirectory(File(pathManager.rootfsDir(distroId), "root/.npm"))
                    clearDirectory(File(pathManager.rootfsDir(distroId), "root/.yarn/cache"))
                    clearDirectory(File(pathManager.rootfsDir(distroId), "root/.cache/yarn"))
                }
                entryId.startsWith("cargo_cache_") -> {
                    val distroId = entryId.removePrefix("cargo_cache_")
                    clearDirectory(File(pathManager.rootfsDir(distroId), "root/.cargo/registry"))
                    clearDirectory(File(pathManager.rootfsDir(distroId), "root/.cargo/git"))
                }
                entryId.startsWith("pub_cache_") -> {
                    val distroId = entryId.removePrefix("pub_cache_")
                    clearDirectory(File(pathManager.rootfsDir(distroId), "root/.pub-cache"))
                }
                entryId.startsWith("go_mod_cache_") -> {
                    val distroId = entryId.removePrefix("go_mod_cache_")
                    clearDirectory(File(pathManager.rootfsDir(distroId), "root/go/pkg/mod/cache"))
                }
                entryId.startsWith("sandbox_tmp_") -> {
                    val distroId = entryId.removePrefix("sandbox_tmp_")
                    clearDirectory(File(pathManager.rootfsDir(distroId), "tmp"))
                    clearDirectory(File(pathManager.rootfsDir(distroId), "var/tmp"))
                }
                entryId.startsWith("system_logs_") -> {
                    val distroId = entryId.removePrefix("system_logs_")
                    clearDirectory(File(pathManager.rootfsDir(distroId), "var/log"))
                }
                entryId == "host_tmp" -> clearDirectory(pathManager.tmpDir)
                entryId == "host_logs" -> clearDirectory(pathManager.logsDir)
                entryId == "cache_dir" || entryId == "cache_runtime_dir" -> clearDirectory(pathManager.cacheDir)
                entryId == "cache_android_sys" -> runCatching { context.cacheDir }.getOrNull()?.let { clearDirectory(it) }
                entryId == "cache_code_cache" -> runCatching { context.codeCacheDir }.getOrNull()?.let { clearDirectory(it) }
                entryId == "global_staging_cache" -> clearDirectory(pathManager.stagingRootfsDir)
                entryId.startsWith("rootfs_prev_") -> {
                    val distroId = entryId.removePrefix("rootfs_prev_")
                    val prev = pathManager.rootfsPreviousDir(distroId)
                    if (prev.exists()) SafeFileTree.delete(prev)
                }
                entryId.startsWith("rootfs_staging_") -> {
                    val distroId = entryId.removePrefix("rootfs_staging_")
                    val staging = pathManager.stagingRootfsDir(distroId)
                    if (staging.exists()) SafeFileTree.delete(staging)
                }
                entryId.startsWith("flutter_tar_cache_") -> {
                    val distroId = entryId.removePrefix("flutter_tar_cache_")
                    val flutterCache = File(pathManager.wanxiangRootDir(distroId), "flutter-cache-arm64")
                    if (flutterCache.exists()) SafeFileTree.delete(flutterCache)
                }
                entryId.startsWith("plugin_import_staging_") -> {
                    val rest = entryId.removePrefix("plugin_import_staging_")
                    val parts = rest.split("_")
                    if (parts.size >= 2) {
                        val distroId = parts[0]
                        val toolId = parts.subList(1, parts.size).joinToString("_")
                        val toolImportDir = File(pathManager.wanxiangRootDir(distroId), "imports/$toolId")
                        if (toolImportDir.exists()) SafeFileTree.delete(toolImportDir)
                    } else {
                        val distroId = rest
                        val importsDir = File(pathManager.wanxiangRootDir(distroId), "imports")
                        if (importsDir.exists()) SafeFileTree.delete(importsDir)
                    }
                }
                entryId.startsWith("host_plugin_") -> {
                    val toolId = entryId.removePrefix("host_plugin_")
                    val toolPluginDir = File(context.filesDir, "plugins/$toolId")
                    if (toolPluginDir.exists()) SafeFileTree.delete(toolPluginDir)
                }
                entryId.startsWith("app_data_") -> {
                    val name = entryId.removePrefix("app_data_")
                    if (name in setOf("reports", "plugins")) {
                        clearDirectory(File(context.filesDir, name))
                    }
                }
                entryId.startsWith("attachment_") -> {
                    val name = entryId.removePrefix("attachment_")
                    val file = File(pathManager.attachmentsDir, name)
                    if (file.exists()) SafeFileTree.delete(file)
                }
                else -> {}
            }
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(
                AppError(
                    code = ErrorCode.IO,
                    message = throwable.message ?: "清理细项失败",
                    cause = throwable,
                ),
            )
        }
    }

    /**
     * 清理下载与系统缓存（涵盖沙箱镜像、Android 缓存与 RootFS 历史升级备份）
     */
    suspend fun clearCache(): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            clearDirectory(pathManager.cacheDir)
            clearDirectory(pathManager.stagingRootfsDir)
            runCatching { context.cacheDir }.getOrNull()?.let { clearDirectory(it) }
            runCatching { context.codeCacheDir }.getOrNull()?.let { clearDirectory(it) }
            val distroIds = pathManager.distrosDir.listFiles().orEmpty()
                .filter { it.isDirectory }
                .map { it.name }
                .ifEmpty { pathManager.listInstalledDistroIds() }
            distroIds.forEach { distroId ->
                val prev = pathManager.rootfsPreviousDir(distroId)
                if (prev.exists()) SafeFileTree.delete(prev)
                val staging = pathManager.stagingRootfsDir(distroId)
                if (staging.exists()) SafeFileTree.delete(staging)
                val flutterCache = File(pathManager.wanxiangRootDir(distroId), "flutter-cache-arm64")
                if (flutterCache.exists()) SafeFileTree.delete(flutterCache)
                val importsDir = File(pathManager.wanxiangRootDir(distroId), "imports")
                if (importsDir.exists()) SafeFileTree.delete(importsDir)
            }
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(
                AppError(
                    code = ErrorCode.IO,
                    message = throwable.message ?: "清理缓存失败",
                    cause = throwable,
                ),
            )
        }
    }

    fun hasEnoughSpace(requiredBytes: Long): Boolean = availableBytes() >= requiredBytes

    private fun availableBytes(): Long {
        return try {
            val path = pathManager.baseDir.parentFile ?: pathManager.baseDir
            StatFs(path.absolutePath).availableBytes
        } catch (_: Throwable) {
            val path = pathManager.baseDir.parentFile ?: pathManager.baseDir
            path.usableSpace
        }
    }

    private fun sizeOf(file: File): Long {
        if (!file.exists()) return 0L
        return try {
            val path = file.toPath()
            if (Files.isSymbolicLink(path)) return 0L
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                return runCatching { Files.size(path) }.getOrDefault(file.length())
            }
            var total = 0L
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink && dir != path) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && !attrs.isSymbolicLink) {
                        total += attrs.size()
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                    return FileVisitResult.CONTINUE
                }
            })
            total
        } catch (_: Throwable) {
            file.walkTopDown()
                .onEnter { !Files.isSymbolicLink(it.toPath()) }
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                .sumOf { it.length() }
        }
    }

    private fun sizeOfDirectoryContents(directory: File, ignoreFiles: Set<String> = emptySet()): Long {
        if (!directory.exists() || !directory.isDirectory) return 0L
        return directory.listFiles().orEmpty()
            .filterNot { ignoreFiles.contains(it.name) }
            .sumOf { sizeOf(it) }
    }

    private fun clearDirectory(directory: File, ignoreFiles: Set<String> = emptySet()) {
        if (!directory.exists() || !directory.isDirectory) return
        directory.listFiles().orEmpty().forEach { file ->
            if (!ignoreFiles.contains(file.name)) {
                SafeFileTree.delete(file)
            }
        }
    }

    private fun childEntries(directory: File, detail: String): List<StorageEntry> = directory.listFiles()
        .orEmpty()
        .map {
            StorageEntry(
                id = it.name,
                name = it.name,
                detail = detail,
                bytes = sizeOf(it),
                cleanable = false,
                riskLevel = StorageRiskLevel.READONLY,
                path = it.absolutePath,
            )
        }
        .sortedByDescending { it.bytes }

    /** 统计各插件的程序本体与持久化数据占用 */
    private fun pluginEntries(distroId: String): List<StorageEntry> {
        val tools = pathManager.wanxiangToolsDir(distroId)
        val data = pathManager.wanxiangDataDir(distroId)
        return (tools.listFiles().orEmpty().map { it.name } + data.listFiles().orEmpty().map { it.name })
            .distinct()
            .map { id ->
                val toolFile = File(tools, id)
                val dataFile = File(data, id)
                val toolSize = sizeOf(toolFile)
                val dataSize = sizeOf(dataFile)
                val total = toolSize + dataSize
                val subItems = mutableListOf<StorageEntry>()
                if (toolSize > 0) {
                    subItems.add(
                        StorageEntry(
                            id = "${id}_bin",
                            name = "程序与执行组件",
                            detail = "工具二进制与核心库",
                            bytes = toolSize,
                            cleanable = false,
                            riskLevel = StorageRiskLevel.READONLY,
                            path = toolFile.absolutePath,
                        ),
                    )
                }
                if (dataSize > 0) {
                    subItems.add(
                        StorageEntry(
                            id = "${id}_data",
                            name = "插件持久化数据",
                            detail = "工作状态、配置与模型数据",
                            bytes = dataSize,
                            cleanable = true,
                            riskLevel = StorageRiskLevel.DANGEROUS,
                            cleanupHint = "清空该插件的数据目录，不可撤销",
                            path = dataFile.absolutePath,
                        ),
                    )
                }
                StorageEntry(
                    id = "plugin_${id}_$distroId",
                    name = id,
                    detail = "$distroId · 程序 ${toolSize.readableSize()} · 数据 ${dataSize.readableSize()}",
                    bytes = total,
                    cleanable = dataSize > 0,
                    riskLevel = if (dataSize > 0) StorageRiskLevel.DANGEROUS else StorageRiskLevel.READONLY,
                    cleanupHint = if (dataSize > 0) "清理该插件持久化数据" else null,
                    path = toolFile.absolutePath,
                    subItems = subItems,
                )
            }
            .sortedByDescending { it.bytes }
    }

    /** 统计各独立共享运行时 */
    private fun runtimeEntries(distroId: String): List<StorageEntry> {
        val runtimes = pathManager.wanxiangRuntimesDir(distroId)
        return runtimes.listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { dir ->
                val size = sizeOf(dir)
                StorageEntry(
                    id = "runtime_${dir.name}_$distroId",
                    name = "${dir.name} ($distroId)",
                    detail = "共享语言或框架运行环境",
                    bytes = size,
                    cleanable = false,
                    riskLevel = StorageRiskLevel.CAUTION,
                    cleanupHint = "如无插件引用可清理",
                    path = dir.absolutePath,
                )
            }
            .sortedByDescending { it.bytes }
    }

    private fun Long.readableSize(): String {
        if (this < 1024L) return "$this B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = this.toDouble()
        var index = -1
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index += 1
        }
        return "%.1f %s".format(value, units[index])
    }
}

