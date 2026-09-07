package top.wanxiang.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import top.wanxiang.app.runtime.debug.DebugActionBus

/**
 * Debug 构建专属：adb 广播入口。用途：绕过不可靠的 adb + IME 手敲，直接触发常用 UI 动作以做真验证。
 *
 * 用法：
 *   adb shell am broadcast -a top.wanxiang.app.DEBUG_CLONE --es url "https://github.com/xxx/yyy.git" -n <pkg>/top.wanxiang.app.debug.DebugReceiver
 *
 * 只在 debug variant 编译（`src/debug/AndroidManifest.xml` 里注册），release 里没这个 receiver，
 * 生产环境无法被外部广播触发。
 */
@AndroidEntryPoint
class DebugReceiver : BroadcastReceiver() {
    @Inject lateinit var bus: DebugActionBus

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CLONE -> {
                val url = intent.getStringExtra("url").orEmpty()
                if (url.isNotBlank()) bus.emit(DebugActionBus.Action.CloneRepo(url))
            }
            ACTION_DIAG -> {
                // adb 触发沙箱内诊断命令，结果写 runtime.log 供 grep
                val cmd = intent.getStringExtra("cmd").orEmpty()
                if (cmd.isNotBlank()) bus.emit(DebugActionBus.Action.Diagnostic(cmd))
            }
        }
    }

    companion object {
        const val ACTION_CLONE = "top.wanxiang.app.DEBUG_CLONE"
        const val ACTION_DIAG = "top.wanxiang.app.DEBUG_DIAG"
    }
}
