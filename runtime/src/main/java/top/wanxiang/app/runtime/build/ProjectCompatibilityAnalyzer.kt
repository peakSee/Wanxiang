package top.wanxiang.app.runtime.build

import java.io.File
import top.wanxiang.app.runtime.ProjectType

enum class CompatibilitySeverity {
    INFO,
    WARNING,
    ERROR,
}

data class CompatibilityFinding(
    val id: String,
    val severity: CompatibilitySeverity,
    val message: String,
    val current: String? = null,
    val expected: String? = null,
    val remediation: String? = null,
)

data class ProjectCompatibilityReport(
    val projectPath: String,
    val projectType: ProjectType,
    val filesChecked: List<String>,
    val findings: List<CompatibilityFinding>,
) {
    val canUseArm64Toolchain: Boolean
        get() = findings.none { it.severity == CompatibilitySeverity.ERROR }

    val requiresUserAlignment: Boolean
        get() = findings.any { it.id == "compile_sdk" || it.id == "gradle_wrapper" || it.id == "agp" || it.id == "kotlin" }

    val hasX86NativeInputs: Boolean
        get() = findings.any { it.id == "x86_abi" }
}

/**
 * A conservative, side-effect-free analyzer for third-party Android/Flutter projects.
 * It only reads project files and never rewrites them. The shell doctor remains the
 * authority inside PRoot; this class gives the app and tests a deterministic report model.
 */
object ProjectCompatibilityAnalyzer {
    private val compileSdkPattern = Regex("compileSdk(?:Version)?\\s*[= (:]\\s*(\\d+)")
    private val gradleVersionPattern = Regex("gradle-([0-9]+(?:\\.[0-9]+)*)-(?:bin|all)\\.zip")
    private val agpPattern = Regex("(?:com\\.android\\.tools\\.build:gradle|com\\.android\\.application)[:\\\"' =]+(?:[\\\"']?)([0-9]+(?:\\.[0-9]+)*)")
    private val kotlinPattern = Regex("(?:kotlin(?:Version)?|org\\.jetbrains\\.kotlin\\.android)[^0-9]*([0-9]+(?:\\.[0-9]+){1,2})")
    private val ndkVersionPattern = Regex("ndkVersion[^0-9]*(\\d+(?:\\.\\d+)+)")
    private val cmakeVersionPattern = Regex("cmake[^\\n]*version[^0-9]*(\\d+(?:\\.\\d+)+)", RegexOption.IGNORE_CASE)

    fun analyze(projectDir: File, offline: Boolean = false): ProjectCompatibilityReport {
        val root = projectDir.canonicalFile
        val files = root.walkTopDown()
            .filter { it.isFile && it.length() <= 512 * 1024L }
            .filter { it.extension in setOf("gradle", "kts", "yaml", "properties", "toml") }
            .take(80)
            .toList()
        val names = files.map { it.relativeToOrNull(root)?.path ?: it.name }
        val allText = files.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        val type = when {
            File(root, "pubspec.yaml").isFile -> ProjectType.FLUTTER
            File(root, "settings.gradle").isFile || File(root, "settings.gradle.kts").isFile ||
                File(root, "build.gradle").isFile || File(root, "build.gradle.kts").isFile -> ProjectType.ANDROID
            else -> ProjectType.GENERAL
        }
        val findings = mutableListOf<CompatibilityFinding>()

        val compileSdk = compileSdkPattern.find(allText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (compileSdk != null) {
            if (compileSdk > 34) findings += CompatibilityFinding(
                id = "compile_sdk",
                severity = CompatibilitySeverity.ERROR,
                message = "项目 compileSdk 高于 WanXiang 内置 Android Platform 34",
                current = compileSdk.toString(),
                expected = "<= 34",
                remediation = "由 mobile_project_align 生成最小降级方案，或安装匹配的 ARM64 Platform",
            ) else findings += CompatibilityFinding(
                id = "compile_sdk",
                severity = CompatibilitySeverity.INFO,
                message = "项目 compileSdk 可使用内置 Platform",
                current = compileSdk.toString(),
                expected = "34",
            )
        }

        val wrapper = listOf(File(root, "gradle/wrapper/gradle-wrapper.properties"), File(root, "android/gradle/wrapper/gradle-wrapper.properties"))
            .firstOrNull { it.isFile }
        val wrapperVersion = wrapper?.let { gradleVersionPattern.find(runCatching { it.readText() }.getOrDefault(""))?.groupValues?.getOrNull(1) }
        if (wrapperVersion != null && wrapperVersion != "8.14.2") findings += CompatibilityFinding(
            id = "gradle_wrapper",
            severity = CompatibilitySeverity.WARNING,
            message = "项目 Gradle Wrapper 与 WanXiang 固定版本不同",
            current = wrapperVersion,
            expected = "8.14.2",
            remediation = "优先使用 WanXiang 本地 Gradle；需要改项目时先生成对齐计划",
        )

        agpPattern.find(allText)?.groupValues?.getOrNull(1)?.let { version ->
            findings += CompatibilityFinding(
                id = "agp",
                severity = CompatibilitySeverity.INFO,
                message = "检测到 Android Gradle Plugin 版本",
                current = version,
                remediation = "若构建失败，再由对齐 Skill 判断兼容范围",
            )
        }
        kotlinPattern.find(allText)?.groupValues?.getOrNull(1)?.let { version ->
            findings += CompatibilityFinding(
                id = "kotlin",
                severity = CompatibilitySeverity.INFO,
                message = "检测到 Kotlin 版本",
                current = version,
                remediation = "不自动改写，待用户确认后对齐",
            )
        }

        ndkVersionPattern.find(allText)?.groupValues?.getOrNull(1)?.let { version ->
            findings += CompatibilityFinding(
                id = "ndk_version",
                severity = CompatibilitySeverity.INFO,
                message = "项目显式声明了 NDK 版本",
                current = version,
                remediation = "构建时与工坊所选 NDK 的 source.properties 比对；不自动修改项目",
            )
        }
        cmakeVersionPattern.find(allText)?.groupValues?.getOrNull(1)?.let { version ->
            findings += CompatibilityFinding(
                id = "cmake_version",
                severity = CompatibilitySeverity.INFO,
                message = "项目显式声明了 CMake 版本",
                current = version,
                remediation = "构建时与工坊所选 CMake 比对；不自动修改项目",
            )
        }

        if (Regex("abiFilters[^\\n]*(x86_64|x86|armeabi-v7a)", RegexOption.IGNORE_CASE).containsMatchIn(allText)) {
            findings += CompatibilityFinding(
                id = "x86_abi",
                severity = CompatibilitySeverity.ERROR,
                message = "项目显式声明了非 ARM64 ABI",
                expected = "arm64-v8a only",
                remediation = "将 abiFilters 对齐为仅 arm64-v8a；生成的 lib/<abi> 目录由构建入口自动清理",
            )
        }

        val cacheHints = if (type == ProjectType.FLUTTER) {
            listOf(File(System.getenv("PUB_CACHE") ?: "", "hosted"))
        } else {
            listOf(File(System.getenv("GRADLE_USER_HOME") ?: File(System.getProperty("user.home"), ".gradle").path, "caches/modules-2/files-2.1"))
        }
        if (offline && cacheHints.none { it.isDirectory }) findings += CompatibilityFinding(
            id = "offline_cache",
            severity = CompatibilitySeverity.ERROR,
            message = "离线模式已开启，但本地项目依赖缓存不存在",
            remediation = "先在线完成一次依赖解析，或把项目依赖缓存放入 WanXiang 离线缓存目录",
        )

        if (findings.isEmpty()) findings += CompatibilityFinding(
            id = "no_constraints",
            severity = CompatibilitySeverity.INFO,
            message = "未发现超出当前 ARM64 工具链的显式约束",
        )
        return ProjectCompatibilityReport(root.absolutePath, type, names, findings)
    }
}
