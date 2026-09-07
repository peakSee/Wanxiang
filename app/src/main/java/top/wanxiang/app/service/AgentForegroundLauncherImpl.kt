package top.wanxiang.app.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import top.wanxiang.app.harness.AgentForegroundLauncher
import javax.inject.Inject

/** 壳层适配：harness 不依赖 app，前台保活服务只能经该桥接接口拉起。 */
class AgentForegroundLauncherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentForegroundLauncher {
    override fun start(sessionId: String?) {
        runCatching { AgentForegroundService.start(context, sessionId) }
    }
}
