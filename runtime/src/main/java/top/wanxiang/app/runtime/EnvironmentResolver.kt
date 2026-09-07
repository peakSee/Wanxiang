package top.wanxiang.app.runtime

import javax.inject.Inject
import javax.inject.Singleton

/** Single source of truth for the environment visible inside Debian. */
@Singleton
class EnvironmentResolver @Inject constructor() {
    fun runtimePath(): String = listOf(
        "/root/.local/bin",
        "/opt/wanxiang/bin",
        "/usr/local/sbin",
        "/usr/local/bin",
        "/usr/sbin",
        "/usr/bin",
        "/sbin",
        "/bin",
    ).joinToString(":")

    fun baseEnvironment(interactive: Boolean): Map<String, String> = buildMap {
        put("HOME", "/root")
        put("LANG", "C.UTF-8")
        put("TMPDIR", "/tmp")
        put("PATH", runtimePath())
        // HostBridge — 沙箱通过 localhost HTTP 桥接触发宿主侧操作（APK 安装、Shell 执行）
        put("WANXIANG_BRIDGE_URL", "http://127.0.0.1:7980")
        put("WANXIANG_BRIDGE_PORT", "7980")
        // Android 二进制执行参考路径（不加入主 PATH，避免与 Debian 工具冲突）
        // 使用 wanxiang-android-exec 包装器或 wanxiang-host shell 来执行 Android 命令
        put("ANDROID_BIN_PATH", "/system/bin:/system/xbin")
        put("ANDROID_LIB_PATH", "/system/lib64:/system/lib:/vendor/lib64:/vendor/lib")
        if (interactive) {
            put("TERM", "xterm-256color")
        } else {
            put("TERM", "dumb")
            put("DEBIAN_FRONTEND", "noninteractive")
            put("CI", "true")
            put("NONINTERACTIVE", "1")
        }
    }

    fun merge(
        manifest: Map<String, String> = emptyMap(),
        provider: Map<String, String> = emptyMap(),
        interactive: Boolean = false,
    ): Map<String, String> = buildMap {
        putAll(baseEnvironment(interactive))
        putAll(manifest)
        putAll(provider)
    }
}
