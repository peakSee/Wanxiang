package top.wanxiang.app.harness.browser

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import top.wanxiang.app.harness.mcp.server.BuiltinBrowserMcpAccess
import top.wanxiang.app.harness.mcp.server.McpServerRuntime
import top.wanxiang.app.runtime.browser.BrowserRegistry
import top.wanxiang.app.runtime.browser.BrowserRegistryImpl
import top.wanxiang.app.runtime.browser.engine.AndroidInAppBrowserEngine
import top.wanxiang.app.runtime.browser.engine.WebViewTabPool
import top.wanxiang.app.core.browser.BrowserFamily

/**
 * 把 in-process 浏览器 MCP server 注入到 harness 流水线，并负责：
 *  - 在 ApplicationContext 上拉起 [AndroidInAppBrowserEngine] 注册到 [BrowserRegistry];
 *  - 按用户偏好（allowRemoteConnect / desktopUserAgent）在 loopback 或 0.0.0.0 端口上启动 [McpServerRuntime];
 *  - 无论 loopback 还是外接都生成 Bearer Token（[top.wanxiang.app.harness.mcp.server.McpAuthFilter] 恒强制校验），
 *    并写入 [BuiltinBrowserMcpAccess]（token + 实际端口）供自环客户端使用。
 *
 * 调用时机：Application.onCreate 后由 AppScope 协程调用一次 [bootstrap]。
 * [McpServerRuntime] 经 [Lazy] 注入：其构造图（含 BrowserMcpTools 的 DataStore 快照读取）
 * 推迟到 bootstrap() 的 IO 协程内才展开，不阻塞主线程。
 */
@Singleton
class BrowserMcpBootstrap @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: Lazy<McpServerRuntime>,
    private val registry: BrowserRegistry,
    private val browserPrefs: top.wanxiang.app.core.datastore.BrowserPreferences,
) {
    /** 注册引擎并启动 HTTP server；幂等。按用户偏好（#4）决定绑定面：allowRemote 时绑定 0.0.0.0。 */
    fun bootstrap(): Boolean {
        val server = runtime.get()
        if (server.isRunning) return true
        val regImpl = registry as? BrowserRegistryImpl ?: return false
        val prefs = readPrefs()
        if (registry.get(BrowserFamily.IN_APP) == null) {
            // hooksEnabled/cdpEnabled 与 desktopUserAgent 一样：池级开关，切换需重启（或新引擎注册）才生效
            val pool = WebViewTabPool(
                context, registry.eventBus,
                desktopUserAgent = prefs.desktopUserAgent,
                hooksEnabled = prefs.allowHooks,
                cdpEnabled = prefs.allowCdp,
                maxCaptureBytes = prefs.maxCaptureBytes.toLong(),
            )
            val engine = AndroidInAppBrowserEngine(context, registry.eventBus, pool)
            regImpl.registerEngine(engine)
        }
        val allowRemote = prefs.allowRemoteConnect
        // loopback 也必须带 token：Android 回环地址不按 UID 隔离，认证恒开启
        val token = generateToken()
        BuiltinBrowserMcpAccess.token = token
        // 首选端口被占用时 start 内部会自动顺延尝试相邻端口，自环客户端经 BuiltinBrowserMcpAccess 感知实际端口
        val ok = server.start(loopbackOnly = !allowRemote, token = token, port = McpServerRuntime.defaultPort)
        if (ok) {
            val host = if (allowRemote) "0.0.0.0" else McpServerRuntime.loopbackHost
            Log.i(TAG, "BrowserMcpServer 已启动 http://$host:${server.port}/mcp（Bearer 认证已启用）")
        } else {
            Log.w(TAG, "BrowserMcpServer 启动失败：候选端口 ${McpServerRuntime.defaultPort}..${McpServerRuntime.defaultPort + 9} 均不可用")
        }
        return ok
    }

    /** 启动时读一次真实偏好（IO 协程内调用，单次 DataStore first() 毫秒级；失败兜底 DEFAULT）。 */
    private fun readPrefs(): top.wanxiang.app.core.browser.BrowserPreferences = runCatching {
        runBlocking {
            top.wanxiang.app.core.browser.BrowserPreferences(
                defaultFamily = browserPrefs.defaultFamily().first(),
                homeUrl = browserPrefs.homeUrl().first(),
                coBrowsingEnabled = browserPrefs.coBrowsingEnabled().first(),
                allowRemoteConnect = browserPrefs.allowRemoteConnect().first(),
                allowEvalJs = browserPrefs.allowEvalJs().first(),
                allowHooks = browserPrefs.allowHooks().first(),
                allowCdp = browserPrefs.allowCdp().first(),
                desktopUserAgent = browserPrefs.desktopUserAgent().first(),
                maxCaptureBytes = browserPrefs.maxCaptureBytes().first(),
            )
        }
    }.getOrDefault(top.wanxiang.app.core.browser.BrowserPreferences.DEFAULT)

    private fun generateToken(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    fun stop() {
        try {
            runtime.get().stop()
            BuiltinBrowserMcpAccess.token = null
        } catch (t: Throwable) { Log.w(TAG, "stop: ${t.message}") }
    }

    companion object {
        const val TAG = "WanXiangMcpBootstrap"
    }
}
