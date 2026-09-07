package top.wanxiang.app.ui.developer

import top.wanxiang.app.ui.components.RuntimeAlertDialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import top.wanxiang.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.HorizontalDivider
import top.wanxiang.app.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import top.wanxiang.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import top.wanxiang.app.ui.components.RuntimeSwitch as Switch
import top.wanxiang.app.ui.developer.LocalizedText as Text
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wanxiang.app.core.model.InstalledRuntime
import top.wanxiang.app.core.model.RuntimeState
import top.wanxiang.app.ui.components.EmptyPanel
import top.wanxiang.app.ui.components.IconTile
import top.wanxiang.app.ui.components.InfoRow
import top.wanxiang.app.ui.components.NoticeBanner
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeTopBar
import top.wanxiang.app.ui.components.SectionHeader
import top.wanxiang.app.ui.components.StatusBadge
import top.wanxiang.app.runtime.bridge.adb.EmbeddedAdbManager

@Composable
fun DeveloperScreen(
    onBack: () -> Unit,
    viewModel: DeveloperViewModel = hiltViewModel(),
) {
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val commandInput by viewModel.commandInput.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val commandResult by viewModel.commandResult.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val messageIsError by viewModel.messageIsError.collectAsStateWithLifecycle()
    val registryStatusIsError by viewModel.registryStatusIsError.collectAsStateWithLifecycle()
    val unusedRuntimes by viewModel.unusedRuntimes.collectAsStateWithLifecycle()
    val processes by viewModel.processes.collectAsStateWithLifecycle()
    val rootfsVersion by viewModel.rootfsVersion.collectAsStateWithLifecycle()
    val rootfsUpdate by viewModel.rootfsUpdate.collectAsStateWithLifecycle()
    val savedManifestUrl by viewModel.registryManifestUrl.collectAsStateWithLifecycle()
    val savedSignatureUrl by viewModel.registrySignatureUrl.collectAsStateWithLifecycle()
    val savedPublicKey by viewModel.registryPublicKey.collectAsStateWithLifecycle()
    val registryStatus by viewModel.registryStatus.collectAsStateWithLifecycle()
    val agentLoggingEnabled by viewModel.agentLoggingEnabled.collectAsStateWithLifecycle()
    val agentLogSize by viewModel.agentLogSize.collectAsStateWithLifecycle()
    val agentLogLocation by viewModel.agentLogLocation.collectAsStateWithLifecycle()
    val adbState by viewModel.adbState.collectAsStateWithLifecycle()
    val adbDiscovery by viewModel.adbDiscovery.collectAsStateWithLifecycle()
    val adbBusy by viewModel.adbBusy.collectAsStateWithLifecycle()
    val adbMessage by viewModel.adbMessage.collectAsStateWithLifecycle()
    val logcatOutput by viewModel.logcatOutput.collectAsStateWithLifecycle()
    var manifestUrl by remember(savedManifestUrl) { mutableStateOf(savedManifestUrl) }
    var signatureUrl by remember(savedSignatureUrl) { mutableStateOf(savedSignatureUrl) }
    var publicKey by remember(savedPublicKey) { mutableStateOf(savedPublicKey) }
    var cleanupTarget by remember { mutableStateOf<InstalledRuntime?>(null) }
    var showRootfsUpdateConfirmation by remember { mutableStateOf(false) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showAgentLogDialog by remember { mutableStateOf(false) }
    var agentLogText by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var logcatPackage by remember { mutableStateOf("") }
    var logcatTag by remember { mutableStateOf("") }
    var logcatKeyword by remember { mutableStateOf("") }
    var logcatLines by remember { mutableStateOf("200") }
    var logcatPriority by remember { mutableStateOf('V') }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(adbState) {
        if (adbState is EmbeddedAdbManager.ConnectionState.Connected) pairingCode = ""
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("开发者控制台", onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            RuntimeControlCard(
                state = runtimeState,
                rootfsVersion = rootfsVersion,
                rootfsUpdate = rootfsUpdate,
                busy = busy,
                onInitialize = viewModel::initialize,
                onCancel = viewModel::cancelInitialization,
                onUpdate = { showRootfsUpdateConfirmation = true },
                onCheckUpdate = viewModel::checkRootfsUpdate,
                onHealthCheck = viewModel::runHealthCheck,
                onReset = { showResetConfirmation = true },
            )

            message?.let { NoticeBanner(it, isError = messageIsError) }

            health?.let { result ->
                RuntimeCard(
                    Modifier.fillMaxWidth(),
                    containerColor = if (result.isHealthy) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(
                            if (result.isHealthy) RuntimeIconName.Shield else RuntimeIconName.Alert,
                            color = if (result.isHealthy) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (result.isHealthy) "健康检查通过" else "健康检查异常", style = MaterialTheme.typography.titleLarge)
                            Text(result.detail ?: "Runtime 核心能力已完成检测", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(15.dp))
                    InfoRow("系统", result.osRelease ?: "-")
                    Spacer(Modifier.height(8.dp))
                    InfoRow("架构", result.architecture ?: "-")
                    Spacer(Modifier.height(8.dp))
                    InfoRow("工作区可写", if (result.workspaceWritable) "是" else "否")
                }
            }

            SectionHeader("无线 ADB · Logcat", "mDNS 自动发现动态端口；成功配对一次后自动重连")
            RuntimeCard(Modifier.fillMaxWidth()) {
                val connected = adbState as? EmbeddedAdbManager.ConnectionState.Connected
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconTile(RuntimeIconName.Terminal)
                    Column(Modifier.weight(1f)) {
                        Text("内置无线 ADB", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (val current = adbState) {
                                EmbeddedAdbManager.ConnectionState.Disconnected -> "未连接"
                                EmbeddedAdbManager.ConnectionState.Discovering -> "正在通过 mDNS 发现端口"
                                EmbeddedAdbManager.ConnectionState.Pairing -> "正在安全配对"
                                EmbeddedAdbManager.ConnectionState.Connecting -> "正在连接"
                                is EmbeddedAdbManager.ConnectionState.Connected -> "${current.host}:${current.port}"
                                is EmbeddedAdbManager.ConnectionState.Failed -> current.message
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    StatusBadge(if (connected != null) "已连接" else "未就绪", if (connected != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "已发现：配对端口 ${adbDiscovery.pairingEndpoints.size} 个 · 连接端口 ${adbDiscovery.connectEndpoints.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { value -> pairingCode = value.filter(Char::isDigit).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("一次性 6 位配对码") },
                    supportingText = { Text("先在系统“无线调试”中打开“使用配对码配对设备”；端口无需手填") },
                    singleLine = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.pairWirelessAdb(pairingCode) },
                        enabled = !adbBusy && pairingCode.length == 6 && adbDiscovery.pairingEndpoints.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("配对并连接") }
                    OutlinedButton(
                        onClick = viewModel::connectWirelessAdb,
                        enabled = !adbBusy && adbDiscovery.connectEndpoints.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("重新连接") }
                }
                adbMessage?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(18.dp))
                Text("应用日志抓取", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = logcatPackage,
                    onValueChange = { logcatPackage = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("包名（留空抓取全部）") },
                    singleLine = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = logcatTag,
                        onValueChange = { logcatTag = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Tag（可选）") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = logcatLines,
                        onValueChange = { logcatLines = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.weight(0.55f),
                        label = { Text("行数") },
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = logcatKeyword,
                    onValueChange = { logcatKeyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("关键词（可选，忽略大小写）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf('V', 'D', 'I', 'W', 'E', 'F').forEach { priority ->
                        if (priority == logcatPriority) {
                            Button(onClick = { logcatPriority = priority }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) { Text(priority.toString()) }
                        } else {
                            OutlinedButton(onClick = { logcatPriority = priority }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) { Text(priority.toString()) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.captureLogcat(logcatPackage, logcatTag, logcatPriority, logcatKeyword, logcatLines.toIntOrNull() ?: 200) },
                        enabled = connected != null && !adbBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text("抓取日志") }
                    OutlinedButton(onClick = viewModel::clearDeviceLogcat, enabled = connected != null && !adbBusy) { Text("清空") }
                }
                if (logcatOutput.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            logcatOutput,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Logcat", logcatOutput))
                            android.widget.Toast.makeText(context, "日志已复制", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("复制日志") }
                }
            }

            SectionHeader("工具源", "远程清单更新：HTTPS + Ed25519 验签（自定义公钥仅防传输损坏，非防篡改信任锚）")
            RuntimeCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 保存前校验：两个地址必须是 HTTPS，公钥不能为空
                    val manifestUrlValid = manifestUrl.trim().startsWith("https://")
                    val signatureUrlValid = signatureUrl.trim().startsWith("https://")
                    val publicKeyValid = publicKey.isNotBlank()
                    OutlinedTextField(
                        value = manifestUrl,
                        onValueChange = { manifestUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("清单 HTTPS 地址") },
                        isError = manifestUrl.isNotBlank() && !manifestUrlValid,
                        supportingText = {
                            if (manifestUrl.isNotBlank() && !manifestUrlValid) Text("地址需以 https:// 开头")
                        },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = signatureUrl,
                        onValueChange = { signatureUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("签名 HTTPS 地址") },
                        isError = signatureUrl.isNotBlank() && !signatureUrlValid,
                        supportingText = {
                            if (signatureUrl.isNotBlank() && !signatureUrlValid) Text("地址需以 https:// 开头")
                        },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = publicKey,
                        onValueChange = { publicKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Ed25519 公钥（Base64）") },
                        isError = !publicKeyValid,
                        supportingText = {
                            if (!publicKeyValid) Text("公钥不能为空")
                        },
                        minLines = 2,
                    )
                    Text(
                        "提示：能控制清单地址的人也能提供公钥，自填密钥只用于防传输损坏/校验内容一致性，不能提供端到端防篡改。如需真正的信任锚，请在 APK 内置固定公钥。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Button(
                            onClick = { viewModel.saveRegistryConfig(manifestUrl.trim(), signatureUrl.trim(), publicKey.trim()) },
                            enabled = manifestUrlValid && signatureUrlValid && publicKeyValid,
                        ) { Text("保存配置") }
                        OutlinedButton(
                            onClick = viewModel::updateRegistry,
                            enabled = manifestUrlValid && signatureUrlValid && publicKeyValid,
                        ) { Text("检查更新") }
                    }
                    registryStatus?.let { NoticeBanner(it, isError = registryStatusIsError) }
                }
            }

            SectionHeader("资源管理", "后台 Linux 进程与共享依赖")

            RuntimeCard(Modifier.fillMaxWidth()) {
                ResourceHeader(RuntimeIconName.Cpu, "后台进程", "${processes.size} 个活动进程", viewModel::refreshProcesses)
                Spacer(Modifier.height(14.dp))
                if (processes.isEmpty()) {
                    Text("当前没有登记中的后台进程。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    processes.forEachIndexed { index, process ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(process.toolId ?: process.id, style = MaterialTheme.typography.titleMedium)
                                Text("${process.type} · PID ${process.pid ?: "未知"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { viewModel.stopProcess(process.id) }, enabled = !busy) { Text("停止") }
                        }
                    }
                }
            }

            RuntimeCard(Modifier.fillMaxWidth()) {
                ResourceHeader(RuntimeIconName.Package, "可清理 Runtime", "引用计数为 0", viewModel::refreshUnusedRuntimes)
                Spacer(Modifier.height(8.dp))
                Text("这里只列出未被任何工具引用的共享依赖，清理会在 Linux 沙箱内执行包管理清理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                if (unusedRuntimes.isEmpty()) {
                    Text("当前没有可安全清理的共享 Runtime。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    unusedRuntimes.forEachIndexed { index, runtime ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(runtime.name.name, style = MaterialTheme.typography.titleMedium)
                                Text(runtime.version ?: "版本未知", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { cleanupTarget = runtime }, enabled = !busy) { Text("清理") }
                        }
                    }
                }
            }

            SectionHeader("智能体诊断", "智能体执行流、工具调用与错误详情本地持久化")
            RuntimeCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(RuntimeIconName.Shield)
                    Column(Modifier.weight(1f)) {
                        Text("智能体本地调试日志", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (agentLoggingEnabled) "已启用（文件大小：${agentLogSize.toReadableSize()}）" else "已关闭（不写入本地日志文件）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (agentLogLocation.isNotBlank()) {
                            Text(
                                "日志文件：$agentLogLocation",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = agentLoggingEnabled,
                        onCheckedChange = viewModel::setAgentLoggingEnabled,
                    )
                }
                if (agentLoggingEnabled || agentLogSize > 0L) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                agentLogText = viewModel.readAgentLogs()
                                showAgentLogDialog = true
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("查看日志")
                        }
                        OutlinedButton(
                            onClick = {
                                val logs = viewModel.readAgentLogs()
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WanXiang Agent Logs", logs))
                                android.widget.Toast.makeText(context, "日志已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("复制全部")
                        }
                        TextButton(
                            onClick = viewModel::clearAgentLogs,
                        ) {
                            Text("清空", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            SectionHeader("命令诊断", "在隔离的 Linux 环境中执行一次性 Shell 命令")
            RuntimeCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(RuntimeIconName.Terminal)
                    Column(Modifier.weight(1f)) {
                        Text("Shell Runner", style = MaterialTheme.typography.titleMedium)
                        Text("适合快速诊断，不会创建持久终端会话", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = viewModel::onCommandInputChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Shell 命令") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = viewModel::runCommand, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    RuntimeIcon(RuntimeIconName.Play, Modifier.size(18.dp)); Spacer(Modifier.size(7.dp)); Text("执行命令")
                }
                commandResult?.let { result ->
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Color(0xFF080D16)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text("exit ${result.exitCode}  •  ${result.durationMs} ms", style = MaterialTheme.typography.labelMedium, color = if (result.exitCode == 0) Color(0xFF83EDC9) else Color(0xFFFFA2AE))
                        Text(result.stdout.ifBlank { "(no stdout)" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Color(0xFFD9E2F1))
                        if (result.stderr.isNotBlank()) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                            Text(result.stderr, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Color(0xFFFFA2AE))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    cleanupTarget?.let { runtime ->
        RuntimeAlertDialog(
            onDismissRequest = { cleanupTarget = null },
            title = { Text("清理 ${runtime.name.name}？") },
            text = { Text("将移除未被工具引用的共享 Runtime，此操作无法自动恢复。") },
            confirmButton = { TextButton(onClick = { cleanupTarget = null; viewModel.cleanupRuntime(runtime.id) }) { Text("确认清理", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { cleanupTarget = null }) { Text("取消") } },
        )
    }
    if (showResetConfirmation) {
        var countdown by remember { mutableStateOf(3) }
        LaunchedEffect(Unit) {
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
        }
        RuntimeAlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("重置当前沙箱？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("• 将清除自行安装的软件包与系统修改", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• 不会删除 /workspace 中的项目代码", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showResetConfirmation = false; viewModel.resetLinuxEnvironment() },
                    enabled = countdown == 0,
                ) {
                    Text(
                        if (countdown > 0) "确认重置 (${countdown}s)" else "确认重置",
                        color = if (countdown == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
                    )
                }
            },
            dismissButton = { TextButton(onClick = { showResetConfirmation = false }) { Text("取消") } },
        )
    }
    if (showRootfsUpdateConfirmation) {
        RuntimeAlertDialog(
            onDismissRequest = { showRootfsUpdateConfirmation = false },
            title = { Text("更新 Linux RootFS？") },
            text = { Text("将在线下载并校验新版本，保留 /root 与 /opt/wanxiang。更新期间后台 Linux 进程会停止，失败时自动恢复旧版本。") },
            confirmButton = { TextButton(onClick = { showRootfsUpdateConfirmation = false; viewModel.updateRootfs() }) { Text("确认更新") } },
            dismissButton = { TextButton(onClick = { showRootfsUpdateConfirmation = false }) { Text("取消") } },
        )
    }
    if (showAgentLogDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showAgentLogDialog = false },
            title = { Text("智能体本地调试日志") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        agentLogText.ifBlank { "暂无日志" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAgentLogDialog = false }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WanXiang Agent Logs", agentLogText))
                    android.widget.Toast.makeText(context, "日志已复制", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
            },
        )
    }
}

@Composable
private fun RuntimeControlCard(
    state: RuntimeState,
    rootfsVersion: String?,
    rootfsUpdate: top.wanxiang.app.runtime.RootfsUpdateInfo?,
    busy: Boolean,
    onInitialize: () -> Unit,
    onCancel: () -> Unit,
    onUpdate: () -> Unit,
    onCheckUpdate: () -> Unit,
    onHealthCheck: () -> Unit,
    onReset: () -> Unit,
) {
    val color = when (state) {
        RuntimeState.Ready -> MaterialTheme.colorScheme.secondary
        is RuntimeState.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    RuntimeCard(Modifier.fillMaxWidth(), containerColor = color.copy(alpha = 0.08f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(RuntimeIconName.Terminal, color = color)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Linux Runtime", style = MaterialTheme.typography.titleLarge)
                Text("RootFS ${rootfsVersion ?: "未安装"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                rootfsUpdate?.let { info ->
                    Text(
                        if (info.hasUpdate) "检测到可用更新" else "已是最新",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (info.hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StatusBadge(describeState(state), color)
        }
        val initializing = state as? RuntimeState.Initializing
        if (initializing != null) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { initializing.progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(displayStep(initializing.step), style = MaterialTheme.typography.bodySmall)
                Text("${(initializing.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            }
            initializing.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消下载") }
        } else {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onInitialize, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (state is RuntimeState.Error) "重试初始化" else if (state is RuntimeState.Ready) "重新检查初始化" else "下载并初始化")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onHealthCheck,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text("健康检查", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onCheckUpdate,
                    enabled = !busy && state is RuntimeState.Ready,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text("检查更新", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onUpdate,
                    enabled = !busy && state is RuntimeState.Ready,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text("更新RootFS", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            Spacer(Modifier.height(9.dp))
            OutlinedButton(
                onClick = onReset,
                enabled = !busy && state !is RuntimeState.Initializing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("恢复 Linux 初始状态", color = MaterialTheme.colorScheme.error) }
        }
        (state as? RuntimeState.Error)?.let {
            Spacer(Modifier.height(12.dp))
            // 面向用户的提示与原始异常解耦：原始异常进日志，避免技术文本直接展示
            androidx.compose.runtime.LaunchedEffect(it.throwable) {
                android.util.Log.w("DeveloperScreen", "Runtime error: ${it.throwable.message}")
            }
            NoticeBanner("Runtime 初始化失败，请检查网络与存储空间后点击「重试初始化」", isError = true)
        }
    }
}

@Composable
private fun ResourceHeader(icon: RuntimeIconName, title: String, detail: String, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onRefresh) { Text("刷新") }
    }
}

private fun Long.toReadableSize(): String {
    if (this < 1024L) return "$this B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = this.toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index += 1 }
    return "%.1f %s".format(value, units[index])
}

private fun describeState(state: RuntimeState): String = when (state) {
    RuntimeState.NotInitialized -> "未初始化"
    is RuntimeState.Initializing -> "初始化中"
    RuntimeState.Ready -> "已就绪"
    is RuntimeState.Error -> "异常"
}

private fun displayStep(step: String): String = when (step) {
    "detectArchitecture" -> "检测设备架构"
    "checkStorage" -> "检查可用空间"
    "createDirectories" -> "创建运行目录"
    "准备 PRoot" -> "准备 PRoot 启动组件"
    "configureRootfs" -> "配置 Linux 系统"
    "configureDns" -> "配置 DNS"
    "configureEnvironment" -> "配置环境变量"
    "createWorkspace" -> "创建工作区"
    "runHealthCheck" -> "运行健康检查"
    else -> step
}
