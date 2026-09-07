package top.wanxiang.app.harness.events

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.core.datastore.AgentPreferences

/** Agent 侧结构化事件日志（ToolCall / ModelRequest / Steering…），受用户开关门控。 */
@Singleton
class AgentEventLogger @Inject constructor(
    private val preferences: AgentPreferences,
    private val logger: AppLogger,
) {
    suspend fun log(sessionId: String, tag: String, message: String, throwable: Throwable? = null) {
        val enabled = runCatching { preferences.agentLoggingEnabled.first() }.getOrDefault(false)
        if (enabled) {
            logger.logAgent(sessionId, tag, message, throwable)
        }
    }
}
