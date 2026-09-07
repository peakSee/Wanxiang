package top.wanxiang.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import top.wanxiang.app.core.datastore.GitCredential
import top.wanxiang.app.feature.chat.R
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import top.wanxiang.app.ui.components.RuntimeButton
import top.wanxiang.app.ui.components.RuntimeTextButton

/**
 * 三端 git 缺凭据的统一弹窗（UI 按钮 / AI Bash / 交互终端手敲）。host 从 git credential 协议来，只读预填；
 * 用户填完 username/token 点「保存并重试」→ [GitCredentialIpcBridge.respond] 写响应文件 →
 * 容器内 helper 读到喂回 git → git 自动续跑，用户不用切页。
 *
 * 同时通过 [onSaveCredential] 让上层把这条凭据也落到 GitPreferences（下次同 host 直接命中 store 不再弹）。
 */
@Composable
internal fun CredentialPromptDialog(
    host: String,
    onConfirm: (username: String, token: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var username by remember(host) { mutableStateOf("") }
    var token by remember(host) { mutableStateOf("") }
    var tokenVisible by remember(host) { mutableStateOf(false) }
    val canSave = username.isNotBlank() && token.isNotBlank()

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("为 $host 登录") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "为 $host 填一次用户名和 Personal Access Token（PAT），保存后自动重跑刚才那条 git 命令。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("用户名（Gitee/GitHub 用账号名；GitLab 可填 oauth2）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Personal Access Token / 密码") },
                    singleLine = true,
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { if (canSave) onConfirm(username.trim(), token.trim()) },
                    ),
                )
                TextButton(onClick = { tokenVisible = !tokenVisible }) {
                    Text(if (tokenVisible) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            RuntimeButton(
                onClick = { if (canSave) onConfirm(username.trim(), token.trim()) },
                enabled = canSave,
            ) { Text("保存并重试") }
        },
        dismissButton = { RuntimeTextButton(onClick = onDismiss) { Text("取消") } },
    )
}
