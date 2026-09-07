package top.wanxiang.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wanxiang.app.runtime.credentials.GitCredentialIpcBridge

/**
 * 全局凭据弹窗宿主。挂在 App 根节点（MainActivity.setContent 顶层），
 * 无论用户在哪个页面（智枢 / 终端 / 工坊 / 设置），git 缺凭据时弹窗都能立即出现。
 *
 * 数据源是 [GitCredentialIpcBridge.request] StateFlow（helper 写 cred-req → FileObserver/轮询捕获 → 置流），
 * 用户填完 → [GitCredentialIpcBridge.respond] 写 cred-resp → helper 读到 → git 自动续跑。
 */
@Composable
fun GlobalCredentialDialogHost(bridge: GitCredentialIpcBridge) {
    val request by bridge.request.collectAsStateWithLifecycle()
    request?.let { req ->
        CredentialPromptDialog(
            host = req.host,
            onConfirm = { username, token ->
                bridge.respond(req.requestId, username, token)
            },
            onDismiss = { bridge.cancel(req.requestId) },
        )
    }
}
