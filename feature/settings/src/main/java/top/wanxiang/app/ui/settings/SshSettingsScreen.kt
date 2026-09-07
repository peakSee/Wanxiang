package top.wanxiang.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wanxiang.app.runtime.SshServiceState
import top.wanxiang.app.ui.components.NoticeBanner
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import top.wanxiang.app.ui.components.RuntimeButton
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeOutlinedButton
import top.wanxiang.app.ui.components.RuntimeSwitch
import top.wanxiang.app.ui.components.RuntimeTextButton
import top.wanxiang.app.ui.components.RuntimeTopBar
import top.wanxiang.app.ui.settings.LocalizedText as Text

@Composable
fun SshSettingsScreen(
    onBack: () -> Unit,
    viewModel: SshSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val operating by viewModel.operating.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val messageIsError by viewModel.messageIsError.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val vpnActive by viewModel.vpnActive.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var portText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(settings.port.toString()) }
    var authorizedKeysText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(settings.authorizedKeys) }
    var showPasswordDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings.distroId, settings.port) { portText = settings.port.toString() }
    LaunchedEffect(settings.distroId, settings.authorizedKeys) { authorizedKeysText = settings.authorizedKeys }

    message?.let { current ->
        RuntimeAlertDialog(
            onDismissRequest = viewModel::consumeMessage,
            title = { Text(if (messageIsError) "SSH 操作未完成" else "SSH 远程访问") },
            text = { Text(current) },
            confirmButton = { RuntimeTextButton(onClick = viewModel::consumeMessage) { Text("知道了") } },
        )
    }

    if (showPasswordDialog) {
        PasswordSettingsDialog(
            passwordConfigured = settings.passwordConfigured,
            onDismiss = { showPasswordDialog = false },
            onSave = { password ->
                showPasswordDialog = false
                viewModel.savePassword(password)
            },
            onClear = {
                showPasswordDialog = false
                viewModel.clearPassword()
            },
        )
    }

    val statusText = when (serviceState) {
        is SshServiceState.Stopped -> "已停止"
        is SshServiceState.Installing -> "正在安装 OpenSSH"
        is SshServiceState.Starting -> "正在启动"
        is SshServiceState.Running -> "运行中"
        is SshServiceState.Failed -> "启动失败"
    }
    val busy = operating || serviceState is SshServiceState.Installing || serviceState is SshServiceState.Starting

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("SSH 远程访问", onBack, statusText = statusText) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                NoticeBanner(
                    text = "SSH 按当前 Linux 发行版独立配置。首次启用会按需安装 OpenSSH；支持传统 root + 密码登录，也可使用公钥。",
                )
            }

            item {
                SectionLabel("服务状态")
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Server,
                        title = "启用 SSH 服务",
                        subtitle = "保持启用并随 Linux 运行时自动启动",
                        value = if (busy) {
                            when (serviceState) {
                                is SshServiceState.Installing -> "正在安装 OpenSSH"
                                is SshServiceState.Starting -> "正在等待 SSH 服务就绪"
                                else -> "正在处理"
                            }
                        } else {
                            when (serviceState) {
                                is SshServiceState.Running -> "运行中"
                                is SshServiceState.Installing -> "安装中"
                                is SshServiceState.Starting -> "启动中"
                                is SshServiceState.Failed -> "失败"
                                is SshServiceState.Stopped -> if ((serviceState as SshServiceState.Stopped).installed) "已安装" else "未安装"
                            }
                        },
                        trailing = {
                            if (busy) {
                                RuntimeCircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.5.dp,
                                )
                            } else {
                                RuntimeSwitch(
                                    checked = settings.enabled,
                                    onCheckedChange = viewModel::toggleEnabled,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            }
                        },
                    )
                }
                (serviceState as? SshServiceState.Failed)?.let { failed ->
                    RuntimeCard(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
                    ) {
                        Text(failed.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                SectionLabel("登录认证")
                SettingsGroup {
                    SettingsRow(
                        icon = RuntimeIconName.Admin,
                        title = "登录用户名",
                        subtitle = "PRoot Linux 沙箱管理员账户",
                        value = "root",
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Key,
                        title = "登录密码",
                        subtitle = if (settings.passwordConfigured) "密码已加密保存，可点击修改或清除" else "尚未设置，使用密码登录前必须配置",
                        value = if (settings.passwordConfigured) "已设置" else "未设置",
                        onClick = { showPasswordDialog = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingsRow(
                        icon = RuntimeIconName.Shield,
                        title = "允许密码登录",
                        subtitle = if (settings.passwordAuthEnabled) "连接时输入 root 对应密码" else "密码认证已关闭",
                        trailing = {
                            RuntimeSwitch(
                                checked = settings.passwordAuthEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled && !settings.passwordConfigured) {
                                        showPasswordDialog = true
                                    } else {
                                        viewModel.setPasswordAuthEnabled(enabled)
                                    }
                                },
                                enabled = !busy,
                            )
                        },
                    )
                }
            }

            item {
                SectionLabel("连接")
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = settings.connectionCommand,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "首次连接输入 yes 确认服务器指纹，然后输入 root 登录密码",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        RuntimeOutlinedButton(
                            onClick = { copyText(context, settings.connectionCommand, "SSH 命令已复制") },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            RuntimeIcon(RuntimeIconName.Copy, Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("复制命令")
                        }
                    }
                }
            }

            if (vpnActive) {
                item {
                    NoticeBanner(
                        text = "检测到手机正处于 VPN 连接中，当前 VPN 会接管本应用流量，同一局域网的电脑连接可能超时。请先关闭 VPN，或将本应用加入 VPN 直连/排除名单。",
                        isError = true,
                    )
                }
            }

            item {
                SectionLabel("网络配置")
                SettingsGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Network, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                        val portValue = portText.toIntOrNull()
                        val portValid = portValue != null && portValue in 1024..65535
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                            label = { Text("SSH 端口") },
                            isError = portText.isNotBlank() && !portValid,
                            supportingText = {
                                Text(
                                    if (portText.isNotBlank() && !portValid) "端口需在 1024–65535 范围内"
                                    else "允许范围：1024–65535",
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        RuntimeOutlinedButton(onClick = { viewModel.savePort(portText) }, enabled = !busy && portValid) { Text("保存") }
                    }
                }
            }

            item {
                SectionLabel("公钥登录（可选）")
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "无需公钥也可以使用密码登录。需要免密连接时，每行添加一个 OpenSSH 公钥。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = authorizedKeysText,
                            onValueChange = { authorizedKeysText = it },
                            label = { Text("authorized_keys") },
                            placeholder = { Text("ssh-ed25519 AAAAC3... user@device") },
                            minLines = 4,
                            maxLines = 8,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        RuntimeButton(
                            onClick = { viewModel.saveAuthorizedKeys(authorizedKeysText) },
                            enabled = !busy,
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Save, Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("保存公钥")
                        }
                    }
                }
            }

            if (logs.isNotEmpty()) {
                item {
                    SectionLabel("最近日志")
                    RuntimeCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    ) {
                        Text(
                            logs.takeLast(12).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

private fun copyText(context: Context, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("SSH command", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

@Composable
private fun PasswordSettingsDialog(
    passwordConfigured: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var password by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var confirmation by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val matches = password == confirmation
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (passwordConfigured) "修改 SSH 登录密码" else "设置 SSH 登录密码") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "密码将通过 Android Keystore 加密保存，并应用到当前 Linux 发行版的 root 账户。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(128) },
                    label = { Text("新密码") },
                    supportingText = { Text("至少 8 个字符，不能包含冒号") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it.take(128) },
                    label = { Text("再次输入密码") },
                    isError = confirmation.isNotEmpty() && !matches,
                    supportingText = {
                        if (confirmation.isNotEmpty() && !matches) Text("两次输入的密码不一致")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            RuntimeButton(
                onClick = { onSave(password) },
                enabled = password.length >= 8 && matches && ':' !in password,
            ) { Text("保存并启用") }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (passwordConfigured) {
                    RuntimeTextButton(onClick = onClear) { Text("清除密码") }
                }
                RuntimeTextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
