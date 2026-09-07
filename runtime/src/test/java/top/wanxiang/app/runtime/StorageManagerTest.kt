package top.wanxiang.app.runtime

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import top.wanxiang.app.runtime.rootfs.RootfsValidator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var pathManager: RuntimePathManager
    private lateinit var storageManager: StorageManager

    private val distroId = "debian"

    @Before
    fun setUp() {
        baseDir = temporaryFolder.newFolder("linux-runtime")
        val validator = RootfsValidator(ElfInspector())
        val context = TestContext(baseDir)
        pathManager = RuntimePathManager(context, validator)
        storageManager = StorageManager(context, pathManager)

        // 1. 设置 Linux 系统基础底模
        val rootfsDir = pathManager.rootfsDir(distroId).apply { mkdirs() }
        File(rootfsDir, "usr/bin").apply { mkdirs() }.resolve("bash").writeText("x".repeat(1024 * 10)) // 10 KB
        File(rootfsDir, "lib").apply { mkdirs() }.resolve("libc.so").writeText("x".repeat(1024 * 20)) // 20 KB

        // 2. 设置沙箱内各种包管理器缓存与临时文件 (SAFE)
        File(rootfsDir, "var/cache/apt/archives").apply { mkdirs() }.resolve("demo.deb").writeText("x".repeat(1024 * 5)) // 5 KB
        File(rootfsDir, "root/.cache/pip").apply { mkdirs() }.resolve("demo.whl").writeText("x".repeat(1024 * 8)) // 8 KB
        File(rootfsDir, "root/.npm").apply { mkdirs() }.resolve("pkg.tgz").writeText("x".repeat(1024 * 6)) // 6 KB
        File(rootfsDir, "root/.cargo/registry").apply { mkdirs() }.resolve("index").writeText("x".repeat(1024 * 4)) // 4 KB
        File(rootfsDir, "root/.gradle/caches").apply { mkdirs() }.resolve("gradle.bin").writeText("x".repeat(1024 * 12)) // 12 KB
        File(rootfsDir, "root/.pub-cache").apply { mkdirs() }.resolve("pub.bin").writeText("x".repeat(1024 * 7)) // 7 KB
        File(rootfsDir, "tmp").apply { mkdirs() }.resolve("sandbox.tmp").writeText("x".repeat(1024 * 3)) // 3 KB
        File(rootfsDir, "var/log").apply { mkdirs() }.resolve("syslog").writeText("x".repeat(1024 * 2)) // 2 KB

        // 3. 设置宿主缓存与临时文件 (SAFE)
        pathManager.cacheDir.apply { mkdirs() }.resolve("rootfs.tar.xz").writeText("x".repeat(1024 * 15)) // 15 KB
        pathManager.tmpDir.apply { mkdirs() }.resolve("proot.sock").writeText("x".repeat(1024 * 1)) // 1 KB
        pathManager.logsDir.apply { mkdirs() }.resolve("agent.log").writeText("x".repeat(1024 * 2)) // 2 KB

        // 4. 设置工作区项目与构建产物 (CAUTION / READONLY)
        val project1 = File(pathManager.workspaceDir, "my-app").apply { mkdirs() }
        File(project1, "src").apply { mkdirs() }.resolve("Main.kt").writeText("fun main() {}") // 源码
        File(project1, "build").apply { mkdirs() }.resolve("app.apk").writeText("x".repeat(1024 * 30)) // 30 KB 构建产物

        val project2 = File(pathManager.workspaceDir, "flutter-demo").apply { mkdirs() }
        File(project2, "lib").apply { mkdirs() }.resolve("main.dart").writeText("void main() {}") // 源码
        File(project2, ".dart_tool").apply { mkdirs() }.resolve("config.json").writeText("x".repeat(1024 * 10)) // 10 KB 构建产物

        // 5. 设置插件工具与数据 (DANGEROUS / READONLY)
        val toolsDir = pathManager.wanxiangToolsDir(distroId).apply { mkdirs() }
        val dataDir = pathManager.wanxiangDataDir(distroId).apply { mkdirs() }
        File(toolsDir, "python-tool").apply { mkdirs() }.resolve("python3").writeText("x".repeat(1024 * 16))
        File(dataDir, "python-tool").apply { mkdirs() }.resolve("state.json").writeText("x".repeat(1024 * 4))

        // 6. 设置开发套件与 SDK 工具链 (Android SDK, Flutter SDK, Gradle 发行版)
        val androidSdkDir = File(rootfsDir, "opt/android-sdk/platforms/android-34").apply { mkdirs() }
        File(androidSdkDir, "android.jar").writeText("x".repeat(1024 * 50)) // 50 KB
        val flutterDir = File(rootfsDir, "opt/flutter/bin/cache/dart-sdk").apply { mkdirs() }
        File(flutterDir, "dart").writeText("x".repeat(1024 * 40)) // 40 KB
        val gradleDir = File(rootfsDir, "opt/gradle-8.14.2/lib").apply { mkdirs() }
        File(gradleDir, "gradle-core.jar").writeText("x".repeat(1024 * 25)) // 25 KB
    }

    private fun runTest(block: suspend () -> Unit) = runBlocking { block() }

    @Test
    fun testInspectSeparatesBaseLinuxFromPackageCaches() = runTest {
        val usage = storageManager.inspect()

        assertNotNull(usage)
        assertTrue(usage.categories.isNotEmpty())

        // 验证包管理依赖缓存大类 (SAFE)
        val packageCacheCat = usage.categories.find { it.id == "package_cache" }
        assertNotNull(packageCacheCat)
        assertEquals(StorageRiskLevel.SAFE, packageCacheCat?.riskLevel)
        assertTrue(packageCacheCat?.cleanable == true)
        assertTrue((packageCacheCat?.bytes ?: 0L) > 0L)

        // 验证包含 APT, pip, npm, cargo, gradle, pub, sandbox_tmp
        val entryIds = packageCacheCat?.entries.orEmpty().map { it.id }
        assertTrue(entryIds.contains("apt_cache_$distroId"))
        assertTrue(entryIds.contains("pip_cache_$distroId"))
        assertTrue(entryIds.contains("npm_cache_$distroId"))
        assertTrue(entryIds.contains("cargo_cache_$distroId"))
        assertTrue(entryIds.contains("gradle_cache_$distroId"))
        assertTrue(entryIds.contains("pub_cache_$distroId"))
        assertTrue(entryIds.contains("sandbox_tmp_$distroId"))

        // 验证 Linux 基础系统大类 (READONLY) - 不应该包含已被包管理器抽离的缓存
        val linuxSysCat = usage.categories.find { it.id == "linux_system" }
        assertNotNull(linuxSysCat)
        assertEquals(StorageRiskLevel.READONLY, linuxSysCat?.riskLevel)
        assertFalse(linuxSysCat?.cleanable ?: true)
        // 纯净的基础系统应只统计 /usr, /lib 等基础组件
        assertTrue((linuxSysCat?.bytes ?: 0L) >= 1024 * 30) // bash (10KB) + libc (20KB)

        // 验证工作区代码工程大类 (CAUTION)
        val workspaceCat = usage.categories.find { it.id == "workspace_projects" }
        assertNotNull(workspaceCat)
        assertEquals(StorageRiskLevel.CAUTION, workspaceCat?.riskLevel)
        assertTrue(workspaceCat?.cleanable == true)

        val myAppEntry = workspaceCat?.entries?.find { it.name == "my-app" }
        assertNotNull(myAppEntry)
        assertTrue(myAppEntry?.cleanable == true)
        val myAppBuildSub = myAppEntry?.subItems?.find { it.id == "my-app_build" }
        assertNotNull(myAppBuildSub)
        assertEquals(1024 * 30L, myAppBuildSub?.bytes)
        assertEquals(StorageRiskLevel.CAUTION, myAppBuildSub?.riskLevel)

        // 验证开发套件与 SDK 工具链大类 (READONLY / CAUTION)
        val sdkCat = usage.categories.find { it.id == "sdk_toolchains" }
        assertNotNull(sdkCat)
        assertTrue((sdkCat?.bytes ?: 0L) >= 1024 * 115L) // 50KB Android + 40KB Flutter + 25KB Gradle
        val sdkEntryIds = sdkCat?.entries.orEmpty().map { it.id }
        assertTrue(sdkEntryIds.contains("sdk_android_$distroId"))
        assertTrue(sdkEntryIds.contains("sdk_flutter_$distroId"))
        assertTrue(sdkEntryIds.contains("sdk_gradle_$distroId"))

        // 验证指标计算：safeCleanableBytes 和 cautionCleanableBytes
        assertTrue(usage.safeCleanableBytes > 0L)
        assertTrue(usage.cautionCleanableBytes >= 1024 * 40L) // 30 KB (my-app) + 10 KB (flutter-demo)
    }

    @Test
    fun testQuickSafeCleanClearsCachesWhilePreservingBaseOsAndSource() = runTest {
        val rootfsDir = pathManager.rootfsDir(distroId)
        val bashFile = File(rootfsDir, "usr/bin/bash")
        val sourceFile = File(pathManager.workspaceDir, "my-app/src/Main.kt")
        val pipCacheDir = File(rootfsDir, "root/.cache/pip")
        val aptCacheDir = File(rootfsDir, "var/cache/apt/archives")

        assertTrue(bashFile.exists())
        assertTrue(sourceFile.exists())
        assertTrue(pipCacheDir.exists())
        assertTrue(aptCacheDir.exists())

        // 执行一键安全清理
        val cleanResult = storageManager.quickSafeClean()
        assertTrue(cleanResult.isSuccess)
        val released = cleanResult.getOrNull() ?: 0L
        assertTrue(released > 0L)

        // 验证系统底模与源码完全不受影响
        assertTrue(bashFile.exists())
        assertTrue(sourceFile.exists())

        // 验证缓存文件已被清空
        assertFalse(File(pipCacheDir, "demo.whl").exists())
        assertFalse(File(aptCacheDir, "demo.deb").exists())
        assertFalse(File(pathManager.cacheDir, "rootfs.tar.xz").exists())
    }

    @Test
    fun testCleanProjectBuildArtifactsCleansOnlyBuildDirs() = runTest {
        val myAppBuild = File(pathManager.workspaceDir, "my-app/build")
        val myAppSource = File(pathManager.workspaceDir, "my-app/src/Main.kt")
        val flutterDartTool = File(pathManager.workspaceDir, "flutter-demo/.dart_tool")
        val flutterSource = File(pathManager.workspaceDir, "flutter-demo/lib/main.dart")

        assertTrue(myAppBuild.exists())
        assertTrue(myAppSource.exists())
        assertTrue(flutterDartTool.exists())
        assertTrue(flutterSource.exists())

        // 清理单个项目构建产物
        val resultSingle = storageManager.cleanProjectBuildArtifacts("my-app")
        assertTrue(resultSingle.isSuccess)
        assertEquals(1024 * 30L, resultSingle.getOrNull())
        assertFalse(myAppBuild.exists())
        assertTrue(myAppSource.exists()) // 源码完好保留
        assertTrue(flutterDartTool.exists()) // 另一个项目尚未清理

        // 清理所有项目构建产物
        val resultAll = storageManager.cleanProjectBuildArtifacts(null)
        assertTrue(resultAll.isSuccess)
        assertFalse(flutterDartTool.exists())
        assertTrue(flutterSource.exists()) // 源码完好保留
    }

    @Test
    fun testClearCategoryAndEntry() = runTest {
        // 清理日志分类
        val logFile = File(pathManager.logsDir, "agent.log")
        assertTrue(logFile.exists())
        val catResult = storageManager.clearCategory("logs")
        assertTrue(catResult.isSuccess)
        assertFalse(logFile.exists())

        // 清理特定 entry: cache_dir
        val cacheFile = File(pathManager.cacheDir, "rootfs.tar.xz")
        cacheFile.writeText("new download")
        assertTrue(cacheFile.exists())
        val entryResult = storageManager.clearEntry("cache", "cache_dir")
        assertTrue(entryResult.isSuccess)
        assertFalse(cacheFile.exists())
    }

    private class TestContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = baseDir
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo().apply {
            nativeLibraryDir = File(baseDir, "lib").apply { mkdirs() }.absolutePath
        }
        override fun getDatabasePath(name: String): File = File(baseDir, "databases/$name").apply { parentFile?.mkdirs() }
    }
}
