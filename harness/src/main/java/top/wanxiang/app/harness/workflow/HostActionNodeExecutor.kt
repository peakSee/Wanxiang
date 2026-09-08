package top.wanxiang.app.harness.workflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.wanxiang.app.core.model.ExecutionMode
import top.wanxiang.app.core.model.workflow.HostWorkflowActions
import top.wanxiang.app.core.model.workflow.HostWorkflowPrivilege
import top.wanxiang.app.core.model.workflow.NodeExecutionOutput
import top.wanxiang.app.core.model.workflow.NodeRunStatus
import top.wanxiang.app.core.model.workflow.WorkflowConditionEvaluator
import top.wanxiang.app.core.model.workflow.WorkflowNode
import top.wanxiang.app.core.model.workflow.WorkflowNodeType
import top.wanxiang.app.core.model.workflow.WorkflowRuntimeContext
import top.wanxiang.app.core.model.workflow.previousOutput
import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.gui.HostGuiController
import top.wanxiang.app.runtime.privilege.PrivilegeManager
import top.wanxiang.app.runtime.shell.ShellCommand

class ConditionNodeExecutor @Inject constructor() : NodeExecutor {
    override val supportedTypes = setOf(WorkflowNodeType.CONDITION_BRANCH)

    override suspend fun execute(
        node: WorkflowNode,
        context: WorkflowRuntimeContext,
        onProgress: suspend (NodeRunStatus, String) -> Unit,
    ): NodeExecutionOutput {
        val upstreamId = context.upstreamNodeIds?.lastOrNull()
        val upstream = upstreamId?.let { context.nodeOutputs[it] }
        val expression = node.config["expression"]?.takeIf { it.isNotBlank() }
            ?: node.config["condition"]?.takeIf { it.isNotBlank() }
            ?: "exitCode == 0"
        onProgress(NodeRunStatus.RUNNING, "求值条件：$expression")
        val result = WorkflowConditionEvaluator.evaluate(expression, context, upstream)
        val matched = result.matched
        return NodeExecutionOutput(
            status = NodeRunStatus.SUCCESS,
            exitCode = if (matched) 0 else 1,
            textOutput = buildString {
                append(if (matched) "✔ 条件成立" else "✘ 条件不成立")
                append('\n').append(result.detail)
                upstream?.textOutput?.takeIf { it.isNotBlank() }?.let {
                    append("\n\n—— 上游输出 ——\n").append(it.take(8_000))
                }
            },
            variables = mapOf(
                "CONDITION_RESULT" to matched.toString(),
                "CONDITION_EXPRESSION" to expression,
            ),
        )
    }
}

class DelayNodeExecutor @Inject constructor() : NodeExecutor {
    override val supportedTypes = setOf(WorkflowNodeType.DELAY)

    override suspend fun execute(
        node: WorkflowNode,
        context: WorkflowRuntimeContext,
        onProgress: suspend (NodeRunStatus, String) -> Unit,
    ): NodeExecutionOutput {
        val seconds = interpolate(node.config["seconds"] ?: node.config["delaySeconds"] ?: "1", context)
            .trim().toDoubleOrNull()?.coerceIn(0.0, 600.0) ?: 1.0
        val millis = (seconds * 1000).toLong().coerceAtLeast(0L)
        onProgress(NodeRunStatus.RUNNING, "等待 ${seconds}s…")
        delay(millis)
        return NodeExecutionOutput(
            status = NodeRunStatus.SUCCESS,
            textOutput = "已等待 ${seconds}s",
            variables = mapOf("DELAY_SECONDS" to seconds.toString()),
            durationMs = millis,
        )
    }
}

class SetVariableNodeExecutor @Inject constructor() : NodeExecutor {
    override val supportedTypes = setOf(WorkflowNodeType.SET_VARIABLE)

    override suspend fun execute(
        node: WorkflowNode,
        context: WorkflowRuntimeContext,
        onProgress: suspend (NodeRunStatus, String) -> Unit,
    ): NodeExecutionOutput {
        val assignments = linkedMapOf<String, String>()
        node.config["variables"]?.lines().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val idx = line.indexOf('=')
                require(idx > 0) { "变量赋值格式应为 KEY=value：$line" }
                val key = line.substring(0, idx).trim()
                val value = interpolate(line.substring(idx + 1).trim(), context)
                require(KEY.matches(key)) { "变量名不合法：$key" }
                assignments[key] = value
            }
        // Also accept single key/value fields for simple editor forms.
        val singleKey = node.config["key"]?.trim().orEmpty()
        if (singleKey.isNotEmpty()) {
            require(KEY.matches(singleKey)) { "变量名不合法：$singleKey" }
            assignments[singleKey] = interpolate(node.config["value"].orEmpty(), context)
        }
        if (assignments.isEmpty()) {
            return NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 2, error = "未配置任何变量赋值")
        }
        onProgress(NodeRunStatus.RUNNING, "写入 ${assignments.size} 个变量")
        return NodeExecutionOutput(
            status = NodeRunStatus.SUCCESS,
            textOutput = assignments.entries.joinToString("\n") { "${it.key}=${it.value}" },
            variables = assignments,
        )
    }

    private companion object {
        val KEY = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }
}

class HostActionNodeExecutor @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val gui: HostGuiController,
    private val privilegeManager: PrivilegeManager,
    private val linuxRuntime: LinuxRuntime,
    private val guiPilot: WorkflowGuiPilot,
) : NodeExecutor {
    override val supportedTypes = setOf(WorkflowNodeType.HOST_ACTION)

    override suspend fun execute(
        node: WorkflowNode,
        context: WorkflowRuntimeContext,
        onProgress: suspend (NodeRunStatus, String) -> Unit,
    ): NodeExecutionOutput = withContext(Dispatchers.IO) {
        val action = node.config["action"]?.trim().orEmpty().ifBlank { "status" }
        val def = HostWorkflowActions.find(action)
        onProgress(NodeRunStatus.RUNNING, "宿主动作：$action")
        if (def?.privilege == HostWorkflowPrivilege.PRIVILEGED) {
            requirePrivilege()?.let { err ->
                return@withContext NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 13, error = err)
            }
        }
        runCatching { dispatch(action, node, context, onProgress) }
            .fold(
                onSuccess = { it },
                onFailure = { err ->
                    if (err is kotlinx.coroutines.CancellationException) throw err
                    NodeExecutionOutput(
                        status = NodeRunStatus.FAILED,
                        exitCode = 1,
                        error = err.message ?: err::class.java.simpleName,
                        textOutput = err.message.orEmpty(),
                    )
                },
            )
    }

    private suspend fun dispatch(
        action: String,
        node: WorkflowNode,
        context: WorkflowRuntimeContext,
        onProgress: suspend (NodeRunStatus, String) -> Unit,
    ): NodeExecutionOutput {
        fun cfg(key: String): String = interpolate(node.config[key].orEmpty(), context).trim()
        fun requireCfg(key: String): String = cfg(key).also { require(it.isNotBlank()) { "缺少参数：$key" } }

        return when (action) {
            "status" -> {
                val info = privilegeManager.getPrivilegeInfo()
                ok(
                    buildString {
                        append("当前生效模式：").append(info.mode.title)
                        append("\n权限状态：").append(if (info.modeActive) "已授权" else "未授权")
                        append("\nShizuku：").append(if (info.shizukuAvailable) "可用" else "不可用")
                        append("\nRoot：").append(if (info.rootAvailable) "可用" else "不可用或未选择")
                    },
                    mapOf(
                        "HOST_MODE" to info.mode.name,
                        "HOST_PRIVILEGED" to (info.mode != ExecutionMode.PROOT && info.modeActive).toString(),
                    ),
                )
            }
            "health" -> shellViaBridge("taixu-host health", context)
            "install-apk" -> {
                val artifact = node.config["artifactFrom"]?.let { context.nodeOutputs[it]?.artifacts?.lastOrNull() }
                    ?: context.globalVariables["APK_PATH"]?.takeIf { it.isNotBlank() }
                    ?: cfg("path").takeIf { it.isNotBlank() }
                    ?: error("未找到 APK：请配置 artifactFrom、path 或变量 APK_PATH")
                shellViaBridge("taixu-host install-apk ${posixQuote(artifact)}", context, artifacts = listOf(artifact))
            }
            "device_status" -> privileged(
                "echo '[battery]'; dumpsys battery | grep -E 'level|status|temperature'" +
                    "; echo '[network]'; dumpsys connectivity | head -30" +
                    "; echo '[foreground]'; dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -6" +
                    "; echo '[storage]'; df -h /data | tail -2",
            )
            "logcat" -> {
                val lines = cfg("tail_lines").toIntOrNull()?.coerceIn(1, 2_000) ?: 200
                val tag = cfg("tag")
                val cmd = if (tag.isBlank()) "/system/bin/logcat -d -t $lines"
                else "/system/bin/logcat -d -t $lines -s ${shellQuote("$tag:*")}"
                privileged(cmd)
            }
            "app_launch" -> gui.launchApp(requireCfg("package")).toOutput()
            "app_force_stop" -> gui.forceStopApp(requireCfg("package")).toOutput()
            "app_clear_data" -> gui.clearAppData(requireCfg("package")).toOutput()
            "app_freeze", "package_disable" -> {
                val user = cfg("user").ifBlank { "0" }
                privileged("/system/bin/pm disable-user --user $user ${shellQuote(requireCfg("package"))}")
            }
            "app_unfreeze", "package_enable" -> {
                val user = cfg("user").ifBlank { "0" }
                privileged("/system/bin/pm enable --user $user ${shellQuote(requireCfg("package"))}")
            }
            "app_grant_permission" -> privileged(
                "/system/bin/pm grant ${shellQuote(requireCfg("package"))} ${shellQuote(requireCfg("permission"))}",
            )
            "app_revoke_permission" -> privileged(
                "/system/bin/pm revoke ${shellQuote(requireCfg("package"))} ${shellQuote(requireCfg("permission"))}",
            )
            "wait_foreground" -> {
                val timeout = (cfg("timeoutSeconds").toDoubleOrNull() ?: 15.0).coerceIn(1.0, 120.0)
                gui.waitForForeground(requireCfg("package"), (timeout * 1000).toLong()).toOutput(
                    extraVars = { mapOf("FOREGROUND_PKG" to requireCfg("package")) },
                )
            }
            "send_broadcast" -> sendBroadcast(node, context)
            "start_activity" -> startActivity(node, context)
            "start_service" -> {
                val component = requireCfg("component")
                val fg = cfg("foreground").equals("true", true)
                val verb = if (fg) "start-foreground-service" else "startservice"
                val cmd = buildString {
                    append("/system/bin/am $verb -n ${shellQuote(component)}")
                    cfg("intentAction").takeIf { it.isNotBlank() }?.let { append(" -a ${shellQuote(it)}") }
                    append(amExtras(parseExtras(cfg("extras"))))
                }
                privileged(cmd)
            }
            "settings_get" -> {
                val ns = requireNamespace(cfg("namespace"))
                val key = requireCfg("key")
                val result = privileged("/system/bin/settings get $ns ${shellQuote(key)}")
                val value = result.textOutput.lines().lastOrNull { it.isNotBlank() }.orEmpty().trim()
                val varName = cfg("outputVariable").ifBlank { "SETTINGS_VALUE" }
                result.copy(variables = result.variables + mapOf(varName to value, "SETTINGS_KEY" to key))
            }
            "settings_put" -> {
                val ns = requireNamespace(cfg("namespace"))
                val key = requireCfg("key")
                val value = requireCfg("value")
                if (ns == "system" && privilegeManager.writeSystemSetting(key, value)) {
                    ok("[Android API] settings put system $key = $value")
                } else {
                    val cmd = if (ns == "system" && key == "screen_brightness") {
                        val quoted = shellQuote(value)
                        "/system/bin/settings put system screen_brightness_mode 0; " +
                            "/system/bin/settings put system screen_brightness $quoted; " +
                            "echo \"requested=$quoted actual=\$(/system/bin/settings get system screen_brightness)\""
                    } else {
                        "/system/bin/settings put $ns ${shellQuote(key)} ${shellQuote(value)}"
                    }
                    privileged(cmd)
                }
            }
            "airplane_mode" -> {
                val enabled = parseBool(requireCfg("enabled"))
                val value = if (enabled) "1" else "0"
                privileged(
                    "/system/bin/settings put global airplane_mode_on $value; " +
                        "/system/bin/am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled",
                )
            }
            "wifi_set" -> {
                val enabled = parseBool(requireCfg("enabled"))
                privileged("/system/bin/cmd wifi set-wifi-enabled ${if (enabled) "enabled" else "disabled"}")
            }
            "volume_set" -> {
                val value = requireCfg("value").toIntOrNull()?.coerceIn(0, 100)
                    ?: error("volume_set.value 必须是整数")
                privileged("/system/bin/media volume --show --stream 3 --set $value")
            }
            "screen_observe" -> {
                val only = cfg("onlyInteractive").ifBlank { "true" }.toBooleanStrictOrNull() ?: true
                gui.observeScreen(only).fold(
                    onSuccess = { obs ->
                        val (pkg, act) = obs.packageName to obs.activityName
                        ok(
                            obs.toAgentSummary(),
                            mapOf("FOREGROUND_PKG" to pkg, "FOREGROUND_ACTIVITY" to act),
                        )
                    },
                    onFailure = { error(it.message ?: "感知屏幕失败") },
                )
            }
            "screen_click" -> gui.click(requireInt(cfg("x"), "x"), requireInt(cfg("y"), "y")).toOutput()
            "screen_double_click" -> gui.doubleClick(requireInt(cfg("x"), "x"), requireInt(cfg("y"), "y")).toOutput()
            "screen_long_press" -> gui.longPress(
                requireInt(cfg("x"), "x"),
                requireInt(cfg("y"), "y"),
                cfg("durationMs").toLongOrNull()?.coerceIn(200L, 5_000L) ?: 800L,
            ).toOutput()
            "screen_swipe" -> gui.swipe(
                requireInt(cfg("x1"), "x1"),
                requireInt(cfg("y1"), "y1"),
                requireInt(cfg("x2"), "x2"),
                requireInt(cfg("y2"), "y2"),
                cfg("durationMs").toLongOrNull()?.coerceIn(50L, 5_000L) ?: 300L,
            ).toOutput()
            "screen_scroll" -> {
                val direction = when (requireCfg("direction").lowercase()) {
                    "up" -> top.wanxiang.app.runtime.gui.ScrollDirection.UP
                    "down" -> top.wanxiang.app.runtime.gui.ScrollDirection.DOWN
                    "left" -> top.wanxiang.app.runtime.gui.ScrollDirection.LEFT
                    "right" -> top.wanxiang.app.runtime.gui.ScrollDirection.RIGHT
                    else -> error("direction 仅支持 up/down/left/right")
                }
                val ratio = cfg("distanceRatio").toFloatOrNull()?.coerceIn(0.15f, 0.8f) ?: 0.45f
                val dur = cfg("durationMs").toLongOrNull()?.coerceIn(50L, 5_000L) ?: 350L
                gui.scroll(direction, ratio, dur).toOutput()
            }
            "screen_input_text", "paste_text" -> gui.inputText(requireCfg("text")).toOutput()
            "screen_key" -> gui.sendKey(requireCfg("key")).toOutput()
            "screen_capture" -> gui.captureScreenshot(requireCfg("path")).toOutput(
                artifacts = { listOf(requireCfg("path")) },
            )
            "gui_pilot" -> {
                val goal = cfg("goal").ifBlank { context.globalVariables["GUI_GOAL"].orEmpty() }
                val pkg = cfg("package").ifBlank { context.globalVariables["TARGET_PACKAGE"].orEmpty() }
                val maxSteps = cfg("maxSteps").toIntOrNull()?.coerceIn(1, 40) ?: 18
                guiPilot.run(
                    goal = goal,
                    targetPackage = pkg.takeIf { it.isNotBlank() },
                    modelId = context.globalVariables["WORKFLOW_MODEL_ID"],
                    modelVariant = context.globalVariables["WORKFLOW_MODEL_VARIANT"],
                    maxSteps = maxSteps,
                    onProgress = { msg -> onProgress(NodeRunStatus.STREAMING, msg) },
                )
            }
            "toast" -> gui.showToast(requireCfg("text")).toOutput()
            "vibrate" -> gui.vibrate(cfg("durationMs").toLongOrNull() ?: 200L).toOutput()
            "clipboard_set" -> gui.clipboardSet(requireCfg("text")).toOutput()
            "clipboard_get" -> gui.clipboardGet().fold(
                onSuccess = { text -> ok(text.ifBlank { "(空)" }, mapOf("CLIPBOARD" to text)) },
                onFailure = { error(it.message ?: "读取剪贴板失败") },
            )
            "notification" -> postNotification(requireCfg("title"), requireCfg("text"))
            "shell" -> privileged(requireCfg("command"))
            "cmd" -> {
                val service = requireCfg("service")
                val args = cfg("args")
                privileged("/system/bin/cmd $service" + if (args.isBlank()) "" else " $args")
            }
            else -> {
                // Escape hatch: treat unknown action as host shell command template.
                val command = cfg("command").ifBlank { error("未知宿主动作：$action；请在编辑器中选择支持的动作，或提供 command") }
                requirePrivilege()?.let { err -> error(err) }
                privileged(command)
            }
        }.also { onProgress(NodeRunStatus.STREAMING, it.textOutput.takeLast(4_000)) }
    }

    private suspend fun sendBroadcast(node: WorkflowNode, context: WorkflowRuntimeContext): NodeExecutionOutput {
        fun cfg(key: String) = interpolate(node.config[key].orEmpty(), context).trim()
        val action = cfg("intentAction").ifBlank { error("缺少 intentAction") }
        val pkg = cfg("package").ifBlank { null }
        val component = cfg("component").ifBlank { null }
        val extras = parseExtras(cfg("extras"))
        val forceShell = cfg("preferShell").equals("true", true)
        if (!forceShell) {
            val soft = gui.sendBroadcastIntent(action, pkg, component, extras)
            if (soft.isSuccess) return soft.toOutput()
        }
        requirePrivilege()?.let { error(it) }
        val cmd = buildString {
            append("/system/bin/am broadcast -a ${shellQuote(action)}")
            pkg?.let { append(" -p ${shellQuote(it)}") }
            component?.let { append(" -n ${shellQuote(it)}") }
            append(amExtras(extras))
        }
        return privileged(cmd)
    }

    private suspend fun startActivity(node: WorkflowNode, context: WorkflowRuntimeContext): NodeExecutionOutput {
        fun cfg(key: String) = interpolate(node.config[key].orEmpty(), context).trim()
        val component = cfg("component").ifBlank { null }
        val intentAction = cfg("intentAction").ifBlank { null }
        val dataUri = cfg("dataUri").ifBlank { null }
        val mime = cfg("mimeType").ifBlank { null }
        val extras = parseExtras(cfg("extras"))
        val forceShell = cfg("preferShell").equals("true", true)
        if (!forceShell) {
            val soft = gui.startActivityIntent(component, intentAction, dataUri, mime, extras)
            if (soft.isSuccess) return soft.toOutput()
        }
        requirePrivilege()?.let { error(it) }
        val cmd = buildString {
            append("/system/bin/am start")
            component?.let { append(" -n ${shellQuote(it)}") }
            intentAction?.let { append(" -a ${shellQuote(it)}") }
            dataUri?.let { append(" -d ${shellQuote(it)}") }
            mime?.let { append(" -t ${shellQuote(it)}") }
            append(amExtras(extras))
        }
        return privileged(cmd)
    }

    private fun postNotification(title: String, text: String): NodeExecutionOutput {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        val channelId = "taixu_workflow"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "太墟工作流", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val notification = Notification.Builder(appContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        manager.notify(id, notification)
        return ok("已发送通知：$title", mapOf("NOTIFICATION_ID" to id.toString()))
    }

    private suspend fun privileged(command: String): NodeExecutionOutput {
        requirePrivilege()?.let { return NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 13, error = it) }
        val result = privilegeManager.executeShellCommand(command)
        return NodeExecutionOutput(
            status = if (result.success) NodeRunStatus.SUCCESS else NodeRunStatus.FAILED,
            exitCode = result.exitCode,
            textOutput = listOf(result.stdout, result.stderr).filter { it.isNotBlank() }.joinToString("\n").trim(),
            error = result.stderr.takeIf { !result.success }?.ifBlank { "宿主命令失败 exit=${result.exitCode}" },
        )
    }

    private suspend fun shellViaBridge(
        command: String,
        context: WorkflowRuntimeContext,
        artifacts: List<String> = emptyList(),
    ): NodeExecutionOutput {
        val result = linuxRuntime.execute(
            ShellCommand(commandLine = command, workingDirectory = context.workspacePath, timeoutMs = 120_000L),
        )
        return NodeExecutionOutput(
            status = if (result.isSuccess) NodeRunStatus.SUCCESS else NodeRunStatus.FAILED,
            exitCode = result.exitCode,
            textOutput = listOf(result.stdout, result.stderr).filter { it.isNotBlank() }.joinToString("\n").trim(),
            artifacts = artifacts,
            durationMs = result.durationMs,
            error = result.stderr.takeIf { !result.isSuccess },
        )
    }

    private suspend fun requirePrivilege(): String? {
        val info = privilegeManager.getPrivilegeInfo()
        return if (info.mode == ExecutionMode.PROOT || !info.modeActive) {
            "此动作需要 Shizuku 或 Root。请到设置中授权并切换执行模式后再运行。"
        } else null
    }

    private fun ok(text: String, variables: Map<String, String> = emptyMap()) =
        NodeExecutionOutput(NodeRunStatus.SUCCESS, textOutput = text, variables = variables)

    private fun Result<String>.toOutput(
        artifacts: () -> List<String> = { emptyList() },
        extraVars: () -> Map<String, String> = { emptyMap() },
    ): NodeExecutionOutput = fold(
        onSuccess = { ok(it, extraVars()) .let { out -> out.copy(artifacts = artifacts()) } },
        onFailure = { NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 1, error = it.message, textOutput = it.message.orEmpty()) },
    )

    private fun requireNamespace(raw: String): String {
        val ns = raw.lowercase()
        require(ns in setOf("system", "secure", "global")) { "namespace 仅支持 system/secure/global" }
        return ns
    }

    private fun requireInt(raw: String, name: String): Int =
        raw.toIntOrNull() ?: error("$name 必须是整数")

    private fun parseBool(raw: String): Boolean =
        raw.equals("true", true) || raw == "1" || raw.equals("on", true) || raw.equals("yes", true)

    private fun parseExtras(raw: String): Map<String, String> = raw.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }.toMap()

    private fun amExtras(extras: Map<String, String>): String = buildString {
        extras.forEach { (rawKey, value) ->
            val parts = rawKey.split(':', limit = 2)
            val (key, type) = when {
                parts.size == 2 && parts[1] in setOf("int", "long", "bool", "boolean", "float", "uri", "string") ->
                    parts[0] to parts[1]
                parts.size == 2 && parts[0] in setOf("int", "long", "bool", "boolean", "float", "uri", "string") ->
                    parts[1] to parts[0]
                else -> rawKey to "string"
            }
            when (type) {
                "int" -> append(" --ei ${shellQuote(key)} ${value.toInt()}")
                "long" -> append(" --el ${shellQuote(key)} ${value.toLong()}")
                "bool", "boolean" -> append(" --ez ${shellQuote(key)} ${parseBool(value)}")
                "float" -> append(" --ef ${shellQuote(key)} ${value.toFloat()}")
                "uri" -> append(" --eu ${shellQuote(key)} ${shellQuote(value)}")
                else -> append(" --es ${shellQuote(key)} ${shellQuote(value)}")
            }
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    private fun posixQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}

internal fun interpolate(template: String, context: WorkflowRuntimeContext): String =
    HOST_VARIABLE.replace(template) { match ->
        val key = match.groupValues[1]
        when {
            key == "WORKSPACE_PATH" -> context.workspacePath
            key == "previous.output" -> context.previousOutput()
            key.endsWith(".output") -> context.nodeOutputs[key.removeSuffix(".output")]?.textOutput.orEmpty()
            else -> context.globalVariables[key].orEmpty()
        }
    }

private val HOST_VARIABLE = Regex("\\$\\{([A-Za-z0-9_.\\-]+)\\}")
