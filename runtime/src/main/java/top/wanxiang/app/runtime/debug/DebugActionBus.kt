package top.wanxiang.app.runtime.debug

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 全局调试动作总线。仅 debug 构建生效（AndroidManifest 用 `<queries>` + `<receiver>` 只在 debug 注册）。
 *
 * 用途：从 adb 广播（或直接调用）注入 UI 难以自动化的动作——尤其是「触发 git clone」这种
 * 走 IME 会被小米输入法改写 URL 的场景。ChatViewModel 订阅 [flow]，收到 Clone 就调 `gitClone(url)`。
 */
@Singleton
class DebugActionBus @Inject constructor() {
    sealed interface Action {
        data class CloneRepo(val url: String) : Action
        /** 沙箱内跑一条诊断命令（结果进 app 日志方便 adb 验证）。 */
        data class Diagnostic(val command: String) : Action
    }

    private val _flow = MutableSharedFlow<Action>(extraBufferCapacity = 8)
    val flow: SharedFlow<Action> = _flow.asSharedFlow()

    fun emit(action: Action) { _flow.tryEmit(action) }
}
