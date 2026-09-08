package top.wanxiang.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.wanxiang.app.runtime.debug.DebugActionBus

/**
 * Debug 构建专属：adb 广播入口，触发 ChatViewModel 里各个 git 相关动作以做端到端验证。
 *
 * 用法示例：
 *   adb shell am broadcast -a top.wanxiang.app.DEBUG_GIT --es act pull
 *   adb shell am broadcast -a top.wanxiang.app.DEBUG_GIT --es act clone --es url <url>
 *   adb shell am broadcast -a top.wanxiang.app.DEBUG_GIT --es act add_cred --es name X --es host Y --es user Z --es token T
 *   adb shell am broadcast -a top.wanxiang.app.DEBUG_GIT --es act rename --es old main --es new dev
 */
@AndroidEntryPoint
class DebugReceiver : BroadcastReceiver() {
    @Inject lateinit var bus: DebugActionBus
    @Inject lateinit var updatePreferences: top.wanxiang.app.core.datastore.UpdatePreferences

    override fun onReceive(context: Context, intent: Intent) {
        val act = intent.getStringExtra("act").orEmpty()
        when (intent.action) {
            ACTION_RESET_COOLDOWN -> {
                CoroutineScope(Dispatchers.IO).launch {
                    updatePreferences.clearUpdateCooldown()
                    android.util.Log.i("WanxiangUpdate", "DEBUG: cooldown cleared")
                }
            }
            ACTION_CLONE -> {
                val url = intent.getStringExtra("url").orEmpty()
                if (url.isNotBlank()) bus.emit(DebugActionBus.Action.CloneRepo(url))
            }
            ACTION_DIAG -> {
                val cmd = intent.getStringExtra("cmd").orEmpty()
                if (cmd.isNotBlank()) bus.emit(DebugActionBus.Action.Diagnostic(cmd))
            }
            ACTION_GIT -> when (act) {
                "clone" -> intent.getStringExtra("url")?.let { bus.emit(DebugActionBus.Action.CloneRepo(it)) }
                "switch_ws" -> intent.getStringExtra("path")?.let { bus.emit(DebugActionBus.Action.SwitchWorkspace(it)) }
                "refresh" -> bus.emit(DebugActionBus.Action.RefreshStatus)
                "add_cred" -> bus.emit(
                    DebugActionBus.Action.AddCred(
                        name = intent.getStringExtra("name").orEmpty(),
                        host = intent.getStringExtra("host").orEmpty(),
                        user = intent.getStringExtra("user").orEmpty(),
                        token = intent.getStringExtra("token").orEmpty(),
                    ),
                )
                "clear_creds" -> bus.emit(DebugActionBus.Action.ClearCreds)
                "verify_cred" -> intent.getStringExtra("id")?.let { bus.emit(DebugActionBus.Action.VerifyCred(it)) }
                "fetch_repos" -> intent.getStringExtra("host")?.let { bus.emit(DebugActionBus.Action.FetchRepos(it)) }
                "ai_commit" -> bus.emit(DebugActionBus.Action.AiGenerateCommit)
                "pull" -> bus.emit(DebugActionBus.Action.GitPull)
                "push" -> bus.emit(DebugActionBus.Action.GitPush)
                "stash" -> bus.emit(DebugActionBus.Action.GitStash)
                "stash_pop" -> bus.emit(DebugActionBus.Action.GitStashPop)
                "revert_all" -> bus.emit(DebugActionBus.Action.GitRevertAll)
                "rename" -> bus.emit(DebugActionBus.Action.GitRenameBranch(
                    old = intent.getStringExtra("old").orEmpty(),
                    new = intent.getStringExtra("new").orEmpty(),
                ))
                "delete_remote" -> intent.getStringExtra("name")?.let { bus.emit(DebugActionBus.Action.GitDeleteRemote(it)) }
                "checkout" -> intent.getStringExtra("branch")?.let { bus.emit(DebugActionBus.Action.GitCheckout(it)) }
                "extract" -> {
                    val p = intent.getStringExtra("path").orEmpty()
                    val n = intent.getStringExtra("name").orEmpty()
                    if (p.isNotBlank()) bus.emit(DebugActionBus.Action.ExtractText(p, n.ifBlank { p.substringAfterLast('/') }))
                }
                "set_proxy" -> bus.emit(DebugActionBus.Action.SetProxy(intent.getStringExtra("value").orEmpty()))
                "sim_att" -> {
                    val p = intent.getStringExtra("path").orEmpty()
                    val n = intent.getStringExtra("name").orEmpty()
                    if (p.isNotBlank()) bus.emit(
                        DebugActionBus.Action.SimulateAttachment(
                            guestPath = p,
                            name = n.ifBlank { p.substringAfterLast('/') },
                            sizeBytes = intent.getLongExtra("size", 0L),
                        ),
                    )
                }
                "stage_all" -> bus.emit(DebugActionBus.Action.GitStageAll(intent.getBooleanExtra("on", true)))
                "commit" -> intent.getStringExtra("msg")?.let { bus.emit(DebugActionBus.Action.GitCommit(it)) }
                "create_tag" -> intent.getStringExtra("name")?.let { bus.emit(DebugActionBus.Action.GitCreateTag(it)) }
                "push_tag" -> intent.getStringExtra("name")?.let { bus.emit(DebugActionBus.Action.GitPushTag(it)) }
                "del_tag_local" -> intent.getStringExtra("name")?.let { bus.emit(DebugActionBus.Action.GitDeleteTagLocal(it)) }
                "del_tag_remote" -> intent.getStringExtra("name")?.let { bus.emit(DebugActionBus.Action.GitDeleteTagRemote(it)) }
                "raw" -> intent.getStringExtra("cmd")?.let { bus.emit(DebugActionBus.Action.GitRaw(it)) }
                "create_proj" -> {
                    val n = intent.getStringExtra("name").orEmpty()
                    val t = intent.getStringExtra("template").orEmpty()
                    val p = intent.getStringExtra("pkg").orEmpty()
                    if (n.isNotBlank()) bus.emit(DebugActionBus.Action.CreateProject(n, t.ifBlank { "builtin.android-compose" }, p.ifBlank { "com.example.wetest" }))
                }
                "diag" -> intent.getStringExtra("cmd")?.let { bus.emit(DebugActionBus.Action.Diagnostic(it)) }
            }
        }
    }

    companion object {
        const val ACTION_CLONE = "top.wanxiang.app.DEBUG_CLONE"
        const val ACTION_DIAG = "top.wanxiang.app.DEBUG_DIAG"
        const val ACTION_GIT = "top.wanxiang.app.DEBUG_GIT"
        const val ACTION_RESET_COOLDOWN = "top.wanxiang.app.DEBUG_RESET_COOLDOWN"
    }
}
