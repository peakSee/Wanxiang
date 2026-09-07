package top.wanxiang.app.ui.workspace

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.wanxiang.app.core.datastore.WorkshopPreferences
import top.wanxiang.app.core.database.BuildScriptEntity
import top.wanxiang.app.core.database.BuildScriptRepository
import top.wanxiang.app.core.database.ProjectBuildScriptBindingEntity
import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.ProjectType
import top.wanxiang.app.runtime.RuntimePathManager
import top.wanxiang.app.runtime.WorkspaceManager
import top.wanxiang.app.runtime.WorkspaceProject
import top.wanxiang.app.runtime.scripts.RuntimeAssetSynchronizer
import top.wanxiang.app.runtime.shell.ShellCommand
import java.io.File
import java.util.UUID

data class ToolchainOption(
    val path: String,
    val label: String,
    val isDetected: Boolean = false,
    val description: String = "",
)

data class DetectedToolchains(
    val gradleOptions: List<ToolchainOption> = emptyList(),
    val javaOptions: List<ToolchainOption> = emptyList(),
    val ndkOptions: List<ToolchainOption> = emptyList(),
    val aapt2Options: List<ToolchainOption> = emptyList(),
    val isScanning: Boolean = false,
)

data class WorkshopEnvironmentDraft(
    val androidSdkPath: String = "",
    val ndkPath: String = "",
    val flutterSdkPath: String = "",
    val javaPath: String = "",
    val gradlePath: String = "",
    val cmakePath: String = "",
    val ninjaPath: String = "",
    val aapt2Path: String = "",
    val gradleUserHome: String = "",
    val pubCache: String = "",
    val toolDir: String = "",
    val androidScript: String = "",
    val flutterScript: String = "",
)

enum class WorkshopScriptType(val title: String, val defaultPath: String, val customPath: String) {
    ANDROID("Android 打包脚本", "/opt/wanxiang/scripts/build_android.sh", "/opt/wanxiang/scripts/workshop-build-android.sh"),
    FLUTTER("Flutter 打包脚本", "/opt/wanxiang/scripts/build_flutter.sh", "/opt/wanxiang/scripts/workshop-build-flutter.sh"),
}

@HiltViewModel
class WorkshopSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: WorkshopPreferences,
    private val linuxRuntime: LinuxRuntime,
    private val pathManager: RuntimePathManager,
    private val assetSynchronizer: RuntimeAssetSynchronizer,
    private val buildScripts: BuildScriptRepository,
    workspaceManager: WorkspaceManager,
) : ViewModel() {
    private val defaults = WorkshopEnvironmentDraft(
        androidSdkPath = "/opt/android-sdk",
        ndkPath = "/opt/wanxiang/toolchains/android/ndk",
        flutterSdkPath = "/opt/flutter",
        javaPath = "/opt/wanxiang/toolchains/android/jdk",
        gradlePath = "/opt/gradle-8.14.2",
        cmakePath = "/opt/wanxiang/tools/android-suite-offline/cmake",
        ninjaPath = "/opt/wanxiang/tools/android-suite-offline/bin",
        aapt2Path = "/opt/android-sdk/build-tools/35.0.0/aapt2",
        gradleUserHome = "/root/.gradle",
        pubCache = "/opt/wanxiang/cache/flutter-pub",
        toolDir = "/opt/wanxiang/tools",
        androidScript = readDefaultScript("build_android.sh"),
        flutterScript = readDefaultScript("build_flutter.sh"),
    )
    private val _draft = MutableStateFlow(WorkshopEnvironmentDraft())
    val draft: StateFlow<WorkshopEnvironmentDraft> = _draft.asStateFlow()
    val projects: StateFlow<List<WorkspaceProject>> = workspaceManager.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _selectedProject = MutableStateFlow<WorkspaceProject?>(null)
    val selectedProject: StateFlow<WorkspaceProject?> = _selectedProject.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()
    private val _customScripts = MutableStateFlow<Set<WorkshopScriptType>>(emptySet())
    val customScripts: StateFlow<Set<WorkshopScriptType>> = _customScripts.asStateFlow()
    val managedScripts: StateFlow<List<BuildScriptEntity>> = buildScripts.observeScripts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val projectBindings: StateFlow<List<ProjectBuildScriptBindingEntity>> = buildScripts.observeBindings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _detectedToolchains = MutableStateFlow(DetectedToolchains())
    val detectedToolchains: StateFlow<DetectedToolchains> = _detectedToolchains.asStateFlow()

    init {
        reload()
        viewModelScope.launch { ensureBuiltinScripts() }
        rescanToolchains()
    }

    fun rescanToolchains() = viewModelScope.launch(Dispatchers.IO) {
        _detectedToolchains.value = _detectedToolchains.value.copy(isScanning = true)
        val distroId = linuxRuntime.activeDistroId.value
        // 1. 优先直接扫描宿主文件系统，0 延迟获取真实存在的目录
        val initialDetected = scanLocalDistroToolchains(distroId)
        _detectedToolchains.value = initialDetected.copy(isScanning = true)

        // 2. 结合沙箱环境内部探针补全挂载路径
        val probeCmd = """
            sh -c '
            echo "===GRADLE==="
            ls -d /opt/gradle* /opt/wanxiang/toolchains/gradle* /usr/share/gradle* /usr/lib/gradle* 2>/dev/null || true
            echo "===JAVA==="
            ls -d /opt/wanxiang/toolchains/android/jdk /usr/lib/jvm/* /opt/jdk* 2>/dev/null || true
            echo "===NDK==="
            ls -d /opt/wanxiang/toolchains/android/ndk /opt/android-sdk/ndk/* 2>/dev/null || true
            echo "===AAPT2==="
            ls /opt/android-sdk/build-tools/*/aapt2 2>/dev/null || true
            '
        """.trimIndent()
        val result = runCatching {
            linuxRuntime.execute(ShellCommand(probeCmd, timeoutMs = 8_000L))
        }.getOrNull()

        if (result != null && result.isSuccess) {
            val lines = result.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
            var section = ""
            val detectedGradle = initialDetected.gradleOptions.map { it.path }.toMutableSet()
            val detectedJava = initialDetected.javaOptions.map { it.path }.toMutableSet()
            val detectedNdk = initialDetected.ndkOptions.map { it.path }.toMutableSet()
            val detectedAapt2 = initialDetected.aapt2Options.map { it.path }.toMutableSet()

            for (line in lines) {
                when {
                    line.startsWith("===GRADLE===") -> section = "GRADLE"
                    line.startsWith("===JAVA===") -> section = "JAVA"
                    line.startsWith("===NDK===") -> section = "NDK"
                    line.startsWith("===AAPT2===") -> section = "AAPT2"
                    line.startsWith("===") -> section = ""
                    else -> when (section) {
                        "GRADLE" -> if (line.startsWith("/")) detectedGradle.add(line)
                        "JAVA" -> if (line.startsWith("/")) detectedJava.add(line)
                        "NDK" -> if (line.startsWith("/")) detectedNdk.add(line)
                        "AAPT2" -> if (line.startsWith("/")) detectedAapt2.add(line)
                    }
                }
            }

            fun toOptions(paths: Set<String>, labelBuilder: (String) -> String): List<ToolchainOption> =
                paths.map { path -> ToolchainOption(path = path, label = labelBuilder(path), isDetected = true) }
                    .sortedBy { it.path }

            _detectedToolchains.value = DetectedToolchains(
                gradleOptions = toOptions(detectedGradle) { path ->
                    val ver = path.substringAfterLast("gradle-", "").takeIf { it.isNotEmpty() }
                    if (ver != null) "Gradle $ver" else path
                },
                javaOptions = toOptions(detectedJava) { path ->
                    if (path.contains("wanxiang/toolchains/android/jdk")) "Adoptium OpenJDK 17 (内置)"
                    else "JDK ${path.substringAfterLast('/')}"
                },
                ndkOptions = toOptions(detectedNdk) { path ->
                    if (path.contains("wanxiang/toolchains/android/ndk")) "固定 ARM64 NDK r29 (内置)"
                    else "NDK ${path.substringAfterLast('/')}"
                },
                aapt2Options = toOptions(detectedAapt2) { path ->
                    "AAPT2 ${path.removeSuffix("/aapt2").substringAfterLast('/')}"
                },
                isScanning = false,
            )
        } else {
            _detectedToolchains.value = initialDetected.copy(isScanning = false)
        }
    }

    private fun scanLocalDistroToolchains(distroId: String): DetectedToolchains {
        val safeDistro = distroId.lowercase().trim().ifBlank { "ubuntu" }
        val rootfs = pathManager.rootfsDir(safeDistro)
        val hostOpt = pathManager.distroOptDir(safeDistro)
        val guestOpt = File(rootfs, "opt")

        val gradlePaths = mutableSetOf<String>()
        val javaPaths = mutableSetOf<String>()
        val ndkPaths = mutableSetOf<String>()
        val aapt2Paths = mutableSetOf<String>()

        // 1. 扫描 /opt 下存在的 Gradle
        listOf(hostOpt, guestOpt).forEach { dir ->
            dir.listFiles()?.filter { it.isDirectory && it.name.startsWith("gradle") }?.forEach {
                gradlePaths.add("/opt/${it.name}")
            }
        }
        if (File(rootfs, "usr/share/gradle/bin/gradle").exists() || File(rootfs, "usr/bin/gradle").exists()) {
            gradlePaths.add("/usr/share/gradle")
        }

        // 2. 扫描 Java / JDK
        val jvmDir = File(rootfs, "usr/lib/jvm")
        jvmDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach {
            javaPaths.add("/usr/lib/jvm/${it.name}")
        }
        val wanxiangJdk = File(hostOpt, "wanxiang/toolchains/android/jdk")
        if (wanxiangJdk.exists() || File(guestOpt, "wanxiang/toolchains/android/jdk").exists()) {
            javaPaths.add("/opt/wanxiang/toolchains/android/jdk")
        }

        // 3. 扫描 NDK
        val wanxiangNdk = File(hostOpt, "wanxiang/toolchains/android/ndk")
        if (wanxiangNdk.exists() || File(guestOpt, "wanxiang/toolchains/android/ndk").exists()) {
            ndkPaths.add("/opt/wanxiang/toolchains/android/ndk")
        }
        listOf(File(hostOpt, "android-sdk/ndk"), File(guestOpt, "android-sdk/ndk")).forEach { ndkParent ->
            ndkParent.listFiles()?.filter { it.isDirectory }?.forEach {
                ndkPaths.add("/opt/android-sdk/ndk/${it.name}")
            }
        }

        // 4. 扫描 AAPT2
        listOf(File(hostOpt, "android-sdk/build-tools"), File(guestOpt, "android-sdk/build-tools")).forEach { btParent ->
            btParent.listFiles()?.filter { it.isDirectory && File(it, "aapt2").exists() }?.forEach {
                aapt2Paths.add("/opt/android-sdk/build-tools/${it.name}/aapt2")
            }
        }

        fun toOptions(paths: Set<String>, labelBuilder: (String) -> String): List<ToolchainOption> =
            paths.map { path -> ToolchainOption(path = path, label = labelBuilder(path), isDetected = true) }
                .sortedBy { it.path }

        return DetectedToolchains(
            gradleOptions = toOptions(gradlePaths) { path ->
                val ver = path.substringAfterLast("gradle-", "").takeIf { it.isNotEmpty() }
                if (ver != null) "Gradle $ver" else path
            },
            javaOptions = toOptions(javaPaths) { path ->
                if (path.contains("wanxiang/toolchains/android/jdk")) "Adoptium OpenJDK 17 (内置)"
                else "JDK ${path.substringAfterLast('/')}"
            },
            ndkOptions = toOptions(ndkPaths) { path ->
                if (path.contains("wanxiang/toolchains/android/ndk")) "固定 ARM64 NDK r29 (内置)"
                else "NDK ${path.substringAfterLast('/')}"
            },
            aapt2Options = toOptions(aapt2Paths) { path ->
                "AAPT2 ${path.removeSuffix("/aapt2").substringAfterLast('/')}"
            },
            isScanning = false,
        )
    }

    private suspend fun ensureBuiltinScripts() {
        buildScripts.ensureBuiltinScripts(defaults.androidScript, defaults.flutterScript)
    }

    fun saveManagedScript(
        id: String?,
        name: String,
        description: String,
        projectType: ProjectType,
        content: String,
    ) = viewModelScope.launch {
        require(name.isNotBlank()) { "脚本名称不能为空" }
        require(content.isNotBlank()) { "脚本内容不能为空" }
        require(content.length <= 200_000) { "脚本不能超过 200 KB" }
        val old = id?.let { buildScripts.findScript(it) }
        val now = System.currentTimeMillis()
        buildScripts.upsertScript(
            BuildScriptEntity(
                id = old?.id ?: UUID.randomUUID().toString(),
                name = name.trim(),
                description = description.trim(),
                projectType = projectType.name,
                content = content.removePrefix("\uFEFF").replace("\r\n", "\n"),
                isBuiltin = old?.isBuiltin ?: false,
                createdAt = old?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    fun cloneScript(script: BuildScriptEntity) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        buildScripts.upsertScript(script.copy(id = UUID.randomUUID().toString(), name = "${script.name} 副本", isBuiltin = false, createdAt = now, updatedAt = now))
    }

    fun deleteManagedScript(id: String) = viewModelScope.launch { buildScripts.deleteScript(id) }
    fun bindProject(projectName: String, scriptId: String?) = viewModelScope.launch {
        if (scriptId == null) buildScripts.unbind(projectName) else buildScripts.bind(projectName, scriptId)
    }

    fun reload() = viewModelScope.launch {
        val storedAndroidScript = preferences.androidScript.first()
        val storedFlutterScript = preferences.flutterScript.first()
        _customScripts.value = buildSet {
            if (storedAndroidScript.isNotBlank()) add(WorkshopScriptType.ANDROID)
            if (storedFlutterScript.isNotBlank()) add(WorkshopScriptType.FLUTTER)
        }
        _draft.value = WorkshopEnvironmentDraft(
            preferences.androidSdkPath.first().ifBlank { defaults.androidSdkPath },
            preferences.ndkPath.first().ifBlank { defaults.ndkPath },
            preferences.flutterSdkPath.first().ifBlank { defaults.flutterSdkPath },
            preferences.javaPath.first().ifBlank { defaults.javaPath },
            preferences.gradlePath.first().ifBlank { defaults.gradlePath },
            preferences.cmakePath.first().ifBlank { defaults.cmakePath },
            preferences.ninjaPath.first().ifBlank { defaults.ninjaPath },
            preferences.aapt2Path.first().ifBlank { defaults.aapt2Path },
            preferences.gradleUserHome.first().ifBlank { defaults.gradleUserHome },
            preferences.pubCache.first().ifBlank { defaults.pubCache },
            preferences.toolDir.first().ifBlank { defaults.toolDir },
            storedAndroidScript.ifBlank { defaults.androidScript },
            storedFlutterScript.ifBlank { defaults.flutterScript },
        )
    }

    fun selectProject(project: WorkspaceProject) { _selectedProject.value = project }
    fun update(value: WorkshopEnvironmentDraft) { _draft.value = value }

    fun saveEnvironment() = viewModelScope.launch {
        val d = _draft.value
        preferences.setAndroidSdkPath(d.androidSdkPath)
        preferences.setNdkPath(d.ndkPath)
        preferences.setFlutterSdkPath(d.flutterSdkPath)
        preferences.setJavaPath(d.javaPath)
        preferences.setGradlePath(d.gradlePath)
        preferences.setCmakePath(d.cmakePath)
        preferences.setNinjaPath(d.ninjaPath)
        preferences.setAapt2Path(d.aapt2Path)
        preferences.setGradleUserHome(d.gradleUserHome)
        preferences.setPubCache(d.pubCache)
        preferences.setToolDir(d.toolDir)
    }

    fun saveScripts() = viewModelScope.launch {
        val d = _draft.value
        preferences.setAndroidScript(d.androidScript)
        preferences.setFlutterScript(d.flutterScript)
        reload()
    }

    fun saveScript(type: WorkshopScriptType, content: String, onSaved: () -> Unit = {}) = viewModelScope.launch {
        when (type) {
            WorkshopScriptType.ANDROID -> preferences.setAndroidScript(content)
            WorkshopScriptType.FLUTTER -> preferences.setFlutterScript(content)
        }
        reload()
        onSaved()
    }

    fun resetScript(type: WorkshopScriptType, onReset: () -> Unit = {}) = viewModelScope.launch {
        when (type) {
            WorkshopScriptType.ANDROID -> preferences.setAndroidScript("")
            WorkshopScriptType.FLUTTER -> preferences.setFlutterScript("")
        }
        reload()
        onReset()
    }

    fun scriptContent(type: WorkshopScriptType): String = when (type) {
        WorkshopScriptType.ANDROID -> _draft.value.androidScript
        WorkshopScriptType.FLUTTER -> _draft.value.flutterScript
    }

    fun effectiveScriptPath(type: WorkshopScriptType): String =
        if (type in _customScripts.value) type.customPath else type.defaultPath

    fun resetEnvironment() = viewModelScope.launch {
        preferences.resetEnvironment()
        reload()
    }

    fun resetScripts() = viewModelScope.launch {
        preferences.resetScripts()
        reload()
    }

    fun runSelected() {
        val project = _selectedProject.value ?: return
        if (_running.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _running.value = true
            _output.value = ""
            val d = _draft.value
            val managed = buildScripts.resolvedScript(project.name)?.takeIf { it.projectType == project.projectType.name }
            val custom = managed?.content ?: if (project.projectType == ProjectType.FLUTTER) d.flutterScript else d.androidScript
            val fileName = managed?.id?.replace(Regex("[^A-Za-z0-9._-]"), "-")?.let { "workshop-managed-$it.sh" }
                ?: if (project.projectType == ProjectType.FLUTTER) "workshop-build-flutter.sh" else "workshop-build-android.sh"
            val script = if (custom.isBlank()) "/opt/wanxiang/scripts/wanxiang-build.sh" else assetSynchronizer.syncWorkshopScript(linuxRuntime.activeDistroId.value, fileName, custom)
            val command = if (script.endsWith("wanxiang-build.sh")) {
                "/bin/sh $script ${if (project.projectType == ProjectType.FLUTTER) "flutter" else "android"} \"${project.linuxPath}\" ${if (project.projectType == ProjectType.FLUTTER) "apk --debug --target-platform android-arm64" else "assembleDebug"}"
            } else {
                "/bin/sh $script \"${project.linuxPath}\" ${if (project.projectType == ProjectType.FLUTTER) "\"apk --debug --target-platform android-arm64\"" else "assembleDebug"}"
            }
            val env = buildMap {
                d.androidSdkPath.takeIf(String::isNotBlank)?.let { put("ANDROID_HOME", it); put("ANDROID_SDK_ROOT", it) }
                d.ndkPath.takeIf(String::isNotBlank)?.let { put("ANDROID_NDK_HOME", it); put("WANXIANG_NDK_PATH", it) }
                d.flutterSdkPath.takeIf(String::isNotBlank)?.let { put("FLUTTER_HOME", it) }
                d.javaPath.takeIf(String::isNotBlank)?.let { put("JAVA_HOME", it) }
                d.gradlePath.takeIf(String::isNotBlank)?.let { put("GRADLE_HOME", it) }
                d.cmakePath.takeIf(String::isNotBlank)?.let { put("WANXIANG_CMAKE_HOME", it) }
                d.ninjaPath.takeIf(String::isNotBlank)?.let { put("WANXIANG_NINJA_HOME", it) }
                d.aapt2Path.takeIf(String::isNotBlank)?.let { put("WANXIANG_AAPT2_PATH", it) }
                d.gradleUserHome.takeIf(String::isNotBlank)?.let { put("GRADLE_USER_HOME", it) }
                d.pubCache.takeIf(String::isNotBlank)?.let { put("PUB_CACHE", it) }
                d.toolDir.takeIf(String::isNotBlank)?.let { put("WANXIANG_TOOL_DIR", it) }
            }
            val result = linuxRuntime.execute(ShellCommand(command, environment = env, forcePty = true, timeoutMs = 1_800_000L, onOutput = ::appendOutput))
            appendOutput("\n\n退出码: ${result.exitCode}\n${result.stderr}")
            _running.value = false
        }
    }

    private fun appendOutput(chunk: String) {
        val combined = _output.value + chunk
        _output.value = if (combined.length <= 60_000) combined else "…前面日志已截断…\n" + combined.takeLast(60_000)
    }

    private fun readDefaultScript(name: String): String = runCatching {
        context.assets.open("scripts/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrDefault("")
}
