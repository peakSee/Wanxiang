package top.wanxiang.app.runtime.debug

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import android.util.Log

/**
 * 全局调试动作总线。仅 debug 构建生效。用 Channel + receiveAsFlow 而不是 SharedFlow：
 * SharedFlow 无订阅者时事件直接丢，而 adb 广播常在 ChatViewModel 还没 compose 前就到；
 * Channel 有 buffer，消息排队等 ChatViewModel 上线消费。
 */
@Singleton
class DebugActionBus @Inject constructor() {
    sealed interface Action {
        data class CloneRepo(val url: String) : Action
        data class Diagnostic(val command: String) : Action
        /** 用给定 path 切换 ChatViewModel 的 workspace（等价于用户在工作区选择器里点那个项目）。 */
        data class SwitchWorkspace(val path: String) : Action
        /** 触发一次 gitRefresh（对应顶栏刷新按钮）。 */
        data object RefreshStatus : Action
        /** 加一条凭证（对应「凭证」页新增）。 */
        data class AddCred(val name: String, val host: String, val user: String, val token: String) : Action
        /** 清空所有凭证（测试后擦干净用）。 */
        data object ClearCreds : Action
        /** 触发凭证健康检查（对应凭证行右侧圆形按钮）。 */
        data class VerifyCred(val id: String) : Action
        /** 触发 fetch my repos（对应 clone 对话框「浏览我的仓库」）。 */
        data class FetchRepos(val host: String) : Action
        /** 触发 AI 生成 commit（对应提交对话框顶部「✨ AI 生成」按钮）。 */
        data object AiGenerateCommit : Action
        /** 通用 git 命令触发（复用 ChatViewModel 里的 fun）：pull/push/stash/rename/delete-remote/revert-all。 */
        data object GitPull : Action
        data object GitPush : Action
        data object GitStash : Action
        data object GitStashPop : Action
        data object GitRevertAll : Action
        data class GitRenameBranch(val old: String, val new: String) : Action
        data class GitDeleteRemote(val name: String) : Action
    }

    private val _channel = Channel<Action>(capacity = 64)
    val flow: Flow<Action> = _channel.receiveAsFlow()

    fun emit(action: Action) {
        val res = _channel.trySend(action)
        Log.i("WanxiangDiag", "DebugActionBus.emit($action) → $res")
    }
}
