package top.wanxiang.app.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the environment visible inside Debian.
 *
 * **代理注入**：读取 Android 系统 `Settings.Global.http_proxy`（用户在 WLAN 设置里配的或 adb 改的），
 * 转成 libcurl 认的 `http_proxy` / `https_proxy` 环境变量塞进沙箱。
 * 这样：
 * - `git clone https://github.com/...` 里的 libcurl 会自动走代理；
 * - 我在 bootstrap 里 `curl https://api.github.com/...`（凭证健康检查 / 仓库列表）也走代理；
 * - 只影响**沙箱内**，宿主 OkHttp（万象自身网络）不受这里控制，走它自己的 ProxySelector。
 * 用户没设全局代理时（值 null 或空）不注入任何 proxy 变量。
 */
@Singleton
class EnvironmentResolver @Inject constructor(
    @ApplicationContext private val context: Context?,
) {
    /** 无 Context 构造：JVM 单测用（跳过代理注入，其他环境变量正常）。 */
    internal constructor() : this(null)

    /**
     * 用户在设置里配的**沙箱内置代理**（`http://host:port`）。优先级高于 Android 系统 proxy。
     * 由 SettingsViewModel 通过 `observeSandboxProxy().collect { resolver.overrideProxy = it }` 持续同步。
     */
    @Volatile var overrideProxy: String? = null
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
        // 沙箱 git/curl 走代理：优先用户 in-app 设置 → 回落 Android 全局 http_proxy
        resolveProxyUrl()?.let { proxyUrl ->
            put("http_proxy", proxyUrl)
            put("https_proxy", proxyUrl)
            put("HTTP_PROXY", proxyUrl)
            put("HTTPS_PROXY", proxyUrl)
            put("no_proxy", "localhost,127.0.0.1,::1")
        }
        if (interactive) {
            put("TERM", "xterm-256color")
        } else {
            put("TERM", "dumb")
            put("DEBIAN_FRONTEND", "noninteractive")
            put("CI", "true")
            put("NONINTERACTIVE", "1")
        }
    }

    /**
     * 沙箱代理解析优先级：
     * 1. **用户设置里的 [overrideProxy]**（形如 `http://host:port`）— 万象内置的"沙箱代理"入口。
     * 2. Android 全局 `Settings.Global.http_proxy`（形如 `host:port`）— 用户手机网络设置层的。
     * 3. 都没有 → null（不注入）。
     */
    private fun resolveProxyUrl(): String? {
        overrideProxy?.trim()?.takeIf { it.isNotEmpty() && (it.startsWith("http://") || it.startsWith("socks5://")) }
            ?.let { return it }
        val hostPort = androidProxyHostPort() ?: return null
        return "http://$hostPort"
    }

    /** 读 Android 全局 http_proxy（形如 `host:port` 或 `:0` 表示关），null 表示未配置或无 Context。 */
    private fun androidProxyHostPort(): String? {
        val ctx = context ?: return null
        return runCatching {
            android.provider.Settings.Global.getString(
                ctx.contentResolver,
                android.provider.Settings.Global.HTTP_PROXY,
            )
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != ":0" && it != "0:0" && it.contains(":") }
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
