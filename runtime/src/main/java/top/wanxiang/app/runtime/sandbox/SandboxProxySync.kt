package top.wanxiang.app.runtime.sandbox

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.wanxiang.app.core.datastore.SettingsDataStore
import top.wanxiang.app.runtime.EnvironmentResolver

/**
 * 把用户在设置里配置的「沙箱内置代理」（`http://host:port` 或 `socks5://host:port`）持续同步到
 * [EnvironmentResolver.overrideProxy]，这样每次 [EnvironmentResolver.baseEnvironment] 都读到最新值。
 *
 * 之所以单独一个组件而不是 EnvironmentResolver 直接依赖 SettingsDataStore（后者在 core:datastore，
 * EnvironmentResolver 在 runtime 模块，倒是有依赖），是因为 DataStore 是 Flow 语义 + 需要长驻协程订阅；
 * EnvironmentResolver 是无状态纯函数式构造，塞入订阅会污染其职责。这个 sync 只做"数据搬运"。
 *
 * 生命周期：`@Singleton`，[start] 幂等；由 WanXiangApplication 或首次 git 操作前触发。
 */
@Singleton
class SandboxProxySync @Inject constructor(
    private val settings: SettingsDataStore,
    private val resolver: EnvironmentResolver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    fun start() {
        if (started) return
        started = true
        scope.launch {
            settings.sandboxHttpProxy.collect { v ->
                resolver.overrideProxy = v.trim().takeIf { it.isNotEmpty() }
            }
        }
    }
}
