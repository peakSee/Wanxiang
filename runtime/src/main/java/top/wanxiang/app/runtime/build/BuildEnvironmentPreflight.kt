package top.wanxiang.app.runtime.build

import top.wanxiang.app.runtime.ProjectType

/**
 * Generates a side-effect-free command used before a build starts. Keeping
 * this as a small pure component makes the contract testable without Android
 * or a live PRoot session.
 */
object BuildEnvironmentPreflight {
    private const val ARM64_ELF_MACHINE = "b700"
    private const val X86_64_ELF_MACHINE = "3e00"

    fun command(projectPath: String, projectType: ProjectType, qemu: Boolean = false): String {
        require(projectType == ProjectType.ANDROID || projectType == ProjectType.FLUTTER) {
            "Preflight is only supported for Android and Flutter projects"
        }
        val project = shellQuote(projectPath)
        val lines = mutableListOf(
            "set -eu",
            "fail() { echo \"WANXIANG_PREFLIGHT_FAIL: ${'$'}1\"; exit 2; }",
            "PROJECT_PATH=$project",
            "test -x /bin/sh || fail shell",
            "test -d \"\$PROJECT_PATH\" || fail project_missing",
            "elf_machine() { od -An -t x1 -j 18 -N 2 \"\$1\" 2>/dev/null | tr -d '[:space:]'; }",
            "java_arch_machine() { bin=\"\$1\"; real=\$(readlink -f \"\$bin\" 2>/dev/null || echo \"\$bin\"); magic=\$(od -An -t x1 -N 4 \"\$real\" 2>/dev/null | tr -d '[:space:]'); if test \"\$magic\" != 7f454c46; then echo not_elf; return 0; fi; home=\$(dirname \"\$(dirname \"\$real\")\"); lib=\$(find \"\$home\" \\( -type f -o -type l \\) -name libjvm.so -print -quit 2>/dev/null || true); if test -z \"\$lib\"; then echo unreadable; return 0; fi; od -An -t x1 -j 18 -N 2 \"\$lib\" 2>/dev/null | tr -d '[:space:]'; }",
        )
        if (projectType == ProjectType.ANDROID) {
            lines += "test -f \"\$PROJECT_PATH/settings.gradle\" -o -f \"\$PROJECT_PATH/settings.gradle.kts\" || fail android_settings"
            lines += "test -f \"\$PROJECT_PATH/build.gradle\" -o -f \"\$PROJECT_PATH/build.gradle.kts\" || fail android_build_file"
        } else {
            lines += "test -f \"\$PROJECT_PATH/pubspec.yaml\" || fail flutter_pubspec"
            lines += "test -d \"\$PROJECT_PATH/android\" || fail flutter_android_host"
        }

        if (qemu) {
            lines += "test \"\$(uname -m)\" = x86_64 || fail qemu_guest"
            lines += "COMPAT_ROOT=/opt/wanxiang/compat/x86_64"
            lines += "JAVA_HOME=\$COMPAT_ROOT/jdk-17"
            lines += "ANDROID_HOME=\$COMPAT_ROOT/android-sdk"
            lines += "GRADLE_HOME=\$COMPAT_ROOT/gradle-8.14.2"
            lines += "JAVA_BIN=\$JAVA_HOME/bin/java"
            lines += "test -x \"\$JAVA_BIN\" || fail java_missing"
            lines += "JAVA_MACHINE=\$(java_arch_machine \"\$JAVA_BIN\")"
            lines += "test \"\$JAVA_MACHINE\" = $X86_64_ELF_MACHINE || { echo \"WANXIANG_PREFLIGHT_FAIL: java_arch path=\$JAVA_BIN machine=\${JAVA_MACHINE:-unreadable} expected=$X86_64_ELF_MACHINE\"; exit 2; }"
            lines += "test -f \"\$ANDROID_HOME/platforms/android-34/android.jar\" || fail android_platform"
            lines += "AAPT2=\$ANDROID_HOME/build-tools/35.0.0/aapt2"
            lines += "test -x \"\$AAPT2\" || fail aapt2_missing"
            lines += "test \"\$(elf_machine \"\$AAPT2\")\" = $X86_64_ELF_MACHINE || fail aapt2_arch"
            lines += "test -f \"\$ANDROID_HOME/build-tools/35.0.0/lib/d8.jar\" || fail build_tools"
            lines += "test -x \"\$GRADLE_HOME/bin/gradle\" -o -d \"\$GRADLE_HOME/lib\" || test -f \"\$PROJECT_PATH/gradle/wrapper/gradle-wrapper.jar\" || fail gradle_missing"
            if (projectType == ProjectType.FLUTTER) {
                lines += "FLUTTER_HOME=\$COMPAT_ROOT/flutter"
                lines += "FLUTTER=\$FLUTTER_HOME/bin/flutter"
                lines += "DART=\$FLUTTER_HOME/bin/cache/dart-sdk/bin/dart"
                lines += "test -x \"\$FLUTTER\" -a -x \"\$DART\" || fail flutter_missing"
                lines += "test \"\$(elf_machine \"\$DART\")\" = $X86_64_ELF_MACHINE || fail dart_arch"
            }
        } else {
            lines += "ANDROID_HOME=\${ANDROID_HOME:-/opt/android-sdk}"
            lines += "# default JAVA_BIN=/opt/wanxiang/toolchains/android/jdk/bin/java"
            lines += "JAVA_BIN=\${JAVA_HOME:-/opt/wanxiang/toolchains/android/jdk}/bin/java"
            lines += "test -x \"\$JAVA_BIN\" || JAVA_BIN=\$(command -v java 2>/dev/null || true)"
            lines += "test -n \"\$JAVA_BIN\" -a -x \"\$JAVA_BIN\" || fail java_missing"
            lines += "JAVA_MACHINE=\$(java_arch_machine \"\$JAVA_BIN\")"
            lines += "test \"\$JAVA_MACHINE\" = $ARM64_ELF_MACHINE || { echo \"WANXIANG_PREFLIGHT_FAIL: java_arch path=\$JAVA_BIN machine=\${JAVA_MACHINE:-unreadable} expected=$ARM64_ELF_MACHINE\"; exit 2; }"
            lines += "test -f \"\$ANDROID_HOME/platforms/android-34/android.jar\" || fail android_platform"
            lines += "test -f \"\$ANDROID_HOME/build-tools/35.0.0/lib/d8.jar\" || fail build_tools"
            lines += "AAPT2=\${WANXIANG_AAPT2_PATH:-\$ANDROID_HOME/build-tools/35.0.0/aapt2}"
            lines += "test -x \"\$AAPT2\" -a \"\$(elf_machine \"\$AAPT2\")\" = $ARM64_ELF_MACHINE || fail aapt2_arch"
            lines += "NDK_PATH=\${WANXIANG_NDK_PATH:-\${ANDROID_NDK_HOME:-/opt/wanxiang/toolchains/android/ndk}}"
            lines += "NDK_CLANG=\$(find \"\$NDK_PATH/toolchains/llvm/prebuilt\" \\( -type f -o -type l \\) -name clang -print -quit 2>/dev/null)"
            lines += "NDK_STRIP=\$(find \"\$NDK_PATH/toolchains/llvm/prebuilt\" \\( -type f -o -type l \\) -name llvm-strip -print -quit 2>/dev/null)"
            lines += "test -f \"\$NDK_PATH/source.properties\" -a -x \"\$NDK_CLANG\" -a -x \"\$NDK_STRIP\" || fail ndk_missing"
            lines += "test \"\$(elf_machine \"\$NDK_CLANG\")\" = $ARM64_ELF_MACHINE -a \"\$(elf_machine \"\$NDK_STRIP\")\" = $ARM64_ELF_MACHINE || fail ndk_arch"
            // CMake/Ninja 只对含 native 代码的工程是硬需求（与 wanxiang-build.sh doctor
            // 的 has_native 口径一致）。在线装配（android-core）不安装它们到固定路径，
            // 无条件强校验会把纯 Kotlin/Java 工程误杀成"缺少构建环境"。
            lines += "has_native=0"
            lines += "if test -f \"\$PROJECT_PATH/CMakeLists.txt\" -o -f \"\$PROJECT_PATH/app/CMakeLists.txt\" -o -d \"\$PROJECT_PATH/app/src/main/cpp\" -o -d \"\$PROJECT_PATH/app/src/main/jni\"; then has_native=1; fi"
            lines += "if grep -Eq 'externalNativeBuild|ndkBuild' \"\$PROJECT_PATH/build.gradle\" \"\$PROJECT_PATH/build.gradle.kts\" \"\$PROJECT_PATH/app/build.gradle\" \"\$PROJECT_PATH/app/build.gradle.kts\" 2>/dev/null; then has_native=1; fi"
            lines += "if [ \"\$has_native\" = 1 ]; then"
            lines += "CMAKE_HOME=\${WANXIANG_CMAKE_HOME:-/opt/wanxiang/tools/android-suite-offline/cmake}"
            lines += "NINJA_HOME=\${WANXIANG_NINJA_HOME:-/opt/wanxiang/tools/android-suite-offline/bin}"
            lines += "test -x \"\$CMAKE_HOME/bin/cmake\" -o -x /usr/bin/cmake -o -x /usr/local/bin/cmake || fail cmake_missing"
            lines += "test -x \"\$NINJA_HOME/ninja\" -o -x /usr/bin/ninja -o -x /usr/local/bin/ninja || fail ninja_missing"
            lines += "fi"
            lines += "GRADLE_USER_HOME=\${GRADLE_USER_HOME:-/root/.gradle}"
            lines += "grep -Fqx 'android.builder.sdkDownload=false' \"\$GRADLE_USER_HOME/gradle.properties\" 2>/dev/null || fail sdk_download_enabled"
            lines += "GRADLE_HOME=\${GRADLE_HOME:-/opt/gradle-8.14.2}"
            lines += "test -x \"\$GRADLE_HOME/bin/gradle\" -o -d \"\$GRADLE_HOME/lib\" -o -f \"\$PROJECT_PATH/gradle/wrapper/gradle-wrapper.jar\" -o -n \"\$(command -v gradle 2>/dev/null || true)\" || fail gradle_missing"
            if (projectType == ProjectType.FLUTTER) {
                lines += "# default FLUTTER=/opt/flutter/bin/flutter DART=/opt/flutter/bin/cache/dart-sdk/bin/dart"
                lines += "FLUTTER=\${FLUTTER_BIN:-\${FLUTTER_HOME:-/opt/flutter}/bin/flutter}"
                lines += "DART=\${DART_BIN:-\${FLUTTER_HOME:-/opt/flutter}/bin/cache/dart-sdk/bin/dart}"
                lines += "test -x \"\$FLUTTER\" -a -x \"\$DART\" || fail flutter_missing"
                lines += "test \"\$(elf_machine \"\$DART\")\" = $ARM64_ELF_MACHINE || fail dart_arch"
            }
        }
        lines += "echo WANXIANG_PREFLIGHT_OK"
        return lines.joinToString("; ")
    }

    /**
     * 将预检失败输出（stdout/stderr）解析为用户可读的缺失项描述。
     * 之前所有失败一律提示"未检测到构建环境 (OpenJDK 17 / Gradle)"，
     * 用户会把 cmake/ninja/java 等失败误报成"缺 gradle"。
     */
    fun describeFailure(output: String): String? {
        val token = output.lineSequence()
            .mapNotNull { line -> PREFLIGHT_FAIL.find(line)?.groupValues?.getOrNull(1)?.trim() }
            .lastOrNull()
            ?: return null
        return when (token.substringBefore(' ')) {
            "shell" -> "沙箱缺少 /bin/sh"
            "project_missing" -> "项目目录在沙箱内不存在"
            "android_settings" -> "项目缺少 settings.gradle/settings.gradle.kts"
            "android_build_file" -> "项目缺少 build.gradle/build.gradle.kts"
            "flutter_pubspec" -> "项目缺少 pubspec.yaml"
            "flutter_android_host" -> "Flutter 工程缺少 android 宿主目录"
            "qemu_guest" -> "QEMU 隔离环境架构异常"
            "java_missing" -> "缺少 JDK 17"
            "java_arch" -> "JDK 架构不匹配（$token）"
            "android_platform" -> "缺少 Android Platform 34"
            "build_tools" -> "缺少 Android Build-Tools 35"
            "aapt2_missing" -> "缺少 AAPT2"
            "aapt2_arch" -> "AAPT2 不是 ARM64 制品"
            "ndk_missing" -> "缺少 ARM64 NDK"
            "ndk_arch" -> "NDK 主机工具不是 ARM64 制品"
            "cmake_missing" -> "缺少 CMake（含 native 代码的工程需要）"
            "ninja_missing" -> "缺少 Ninja（含 native 代码的工程需要）"
            "gradle_missing" -> "缺少 Gradle 8.14.2"
            "sdk_download_enabled" -> "Gradle SDK 自动下载禁用策略未配置"
            "flutter_missing" -> "缺少 Flutter SDK"
            "dart_arch" -> "Dart SDK 不是 ARM64 制品"
            else -> "预检失败：$token"
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\\''")}'"

    private val PREFLIGHT_FAIL = Regex("WANXIANG_PREFLIGHT_FAIL:\\s*(\\S.*)")
}
