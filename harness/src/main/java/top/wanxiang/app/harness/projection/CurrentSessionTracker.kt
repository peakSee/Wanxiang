package top.wanxiang.app.harness.projection

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前前台聚焦会话 ID 的单一事实来源。
 *
 * HarnessLoop 负责写入（新建/加载/删除会话时切换），
 * 各投影器（消息、状态镜像等）据此判断是否需要把会话级状态镜像到全局流。
 */
@Singleton
class CurrentSessionTracker @Inject constructor() {

    private val _currentSessionId = MutableStateFlow("")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    val foregroundId: String get() = _currentSessionId.value

    fun isForeground(sessionId: String): Boolean = sessionId == foregroundId

    fun setCurrent(sessionId: String) {
        _currentSessionId.value = sessionId
    }
}
