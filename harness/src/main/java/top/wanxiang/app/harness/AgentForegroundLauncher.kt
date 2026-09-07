package top.wanxiang.app.harness

/**
 * 前台保活服务的拉起桥：Service 注册在壳层 app 模块，harness 不能反向依赖，
 * 通过该接口 + Hilt 绑定解耦（实现在 app 的 AgentForegroundLauncherImpl）。
 */
interface AgentForegroundLauncher {
    fun start(sessionId: String? = null)
}
