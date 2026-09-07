package top.wanxiang.app.runtime.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.runtime.ProjectType

class BuildEnvironmentPreflightTest {
    @Test
    fun androidArmPreflightRequiresPinnedArmArtifactsAndDisablesSdkDownload() {
        val command = BuildEnvironmentPreflight.command("/workspace/a project's app", ProjectType.ANDROID)
        assertTrue(command.contains("android.builder.sdkDownload=false"))
        assertTrue(command.contains("WANXIANG_NDK_PATH"))
        assertTrue(command.contains("WANXIANG_AAPT2_PATH"))
        assertTrue(command.contains("/opt/wanxiang/toolchains/android/jdk/bin/java"))
        assertTrue(command.contains("\$ANDROID_HOME/build-tools/35.0.0/aapt2"))
        assertTrue(command.contains("/opt/wanxiang/toolchains/android/ndk"))
        assertTrue(command.contains("-name clang"))
        assertTrue(command.contains("od -An -t x1 -j 18 -N 2"))
        assertTrue(command.contains("= b700"))
        assertTrue(command.contains("java_arch_machine"))
        assertTrue(command.contains("libjvm.so"))
        assertTrue(command.contains("readlink -f"))
        // Java 启动器必须解析为真正的 ELF：包装脚本回环（脚本 exec 软链、
        // 软链又指回脚本）在 PRoot 下是零输出、CPU 满载的无限 exec 循环，
        // 预检必须在 JVM 启动前用魔数拦下，而不是只查 libjvm.so。
        assertTrue(command.contains("7f454c46"))
        assertTrue(command.contains("not_elf"))
        assertFalse(command.contains("-tu2"))
        assertFalse(command.contains("wrapper_incomplete"))
        assertTrue(command.contains("fail aapt2_arch"))
        assertTrue(command.contains("a project's app".replace("'", "'\\\''")))
    }

    @Test
    fun flutterQemuPreflightRequiresX86DartAndAndroidHost() {
        val command = BuildEnvironmentPreflight.command("/workspace/flutter", ProjectType.FLUTTER, qemu = true)
        assertTrue(command.contains("uname -m"))
        assertTrue(command.contains("= 3e00"))
        assertTrue(command.contains("java_arch_machine"))
        assertTrue(command.contains("libjvm.so"))
        assertTrue(command.contains("fail dart_arch"))
        assertTrue(command.contains("pubspec.yaml"))
        assertTrue(command.contains("android"))
    }

    @Test
    fun flutterArmPreflightAcceptsOfflineSuiteLayoutWithoutLegacyMarker() {
        val command = BuildEnvironmentPreflight.command("/workspace/flutter", ProjectType.FLUTTER)

        assertTrue(command.contains("/opt/wanxiang/toolchains/android/jdk/bin/java"))
        assertTrue(command.contains("/opt/flutter/bin/flutter"))
        assertTrue(command.contains("/opt/flutter/bin/cache/dart-sdk/bin/dart"))
        assertFalse(command.contains("flutter_marker"))
        assertTrue(command.contains("java_arch_machine"))
        assertTrue(command.contains("libjvm.so"))
        assertTrue(command.contains("readlink -f"))
    }

    @Test
    fun androidArmPreflightGatesCMakeNinjaBehindNativeDetection() {
        val command = BuildEnvironmentPreflight.command("/workspace/app", ProjectType.ANDROID)
        // CMake/Ninja 与 wanxiang-build.sh doctor 的 has_native 口径对齐：只有
        // 含 native 代码的工程才强制；纯 Kotlin/Java 工程在线装配（android-core
        // 不装 CMake/Ninja）不能再被误杀成"缺少构建环境"。
        assertTrue(command.contains("has_native=0"))
        assertTrue(command.contains("CMakeLists.txt"))
        assertTrue(command.contains("app/src/main/cpp"))
        assertTrue(command.contains("externalNativeBuild|ndkBuild"))
        assertTrue(command.contains("fail cmake_missing"))
        assertTrue(command.contains("fail ninja_missing"))
        // 校验必须被 has_native 条件包裹，且接受系统路径兜底
        val cmakeCheck = command.substringAfter("if [ \"\$has_native\" = 1 ]; then").substringBefore("fi")
        assertTrue(cmakeCheck.contains("fail cmake_missing"))
        assertTrue(cmakeCheck.contains("fail ninja_missing"))
        assertTrue(cmakeCheck.contains("/usr/bin/cmake"))
        assertTrue(cmakeCheck.contains("/usr/bin/ninja"))
    }

    @Test
    fun describeFailureNamesTheActuallyMissingTool() {
        // 用户反馈"缺 gradle"实际多为 cmake/ninja/java 等单项失败被统一文案掩盖
        assertEquals("缺少 Gradle 8.14.2", BuildEnvironmentPreflight.describeFailure("WANXIANG_PREFLIGHT_FAIL: gradle_missing"))
        assertEquals("缺少 CMake（含 native 代码的工程需要）", BuildEnvironmentPreflight.describeFailure("some log\nWANXIANG_PREFLIGHT_FAIL: cmake_missing"))
        assertEquals("缺少 Ninja（含 native 代码的工程需要）", BuildEnvironmentPreflight.describeFailure("WANXIANG_PREFLIGHT_FAIL: ninja_missing"))
        assertEquals("缺少 JDK 17", BuildEnvironmentPreflight.describeFailure("WANXIANG_PREFLIGHT_FAIL: java_missing"))
        assertEquals(
            "JDK 架构不匹配（java_arch path=/usr/bin/java machine=unreadable expected=b700）",
            BuildEnvironmentPreflight.describeFailure("WANXIANG_PREFLIGHT_FAIL: java_arch path=/usr/bin/java machine=unreadable expected=b700"),
        )
        assertEquals("缺少 Android Platform 34", BuildEnvironmentPreflight.describeFailure("WANXIANG_PREFLIGHT_FAIL: android_platform"))
        assertNull(BuildEnvironmentPreflight.describeFailure("WANXIANG_PREFLIGHT_OK"))
        assertNull(BuildEnvironmentPreflight.describeFailure(""))
    }
}

