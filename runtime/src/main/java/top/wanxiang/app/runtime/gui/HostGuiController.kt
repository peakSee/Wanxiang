package top.wanxiang.app.runtime.gui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.wanxiang.app.runtime.privilege.PrivilegeManager
import javax.inject.Inject
import javax.inject.Singleton

data class ScreenObservation(
    val packageName: String,
    val activityName: String,
    val nodes: List<GuiNode>,
    val rawXml: String = "",
) {
    fun toAgentSummary(maxNodes: Int = 80): String = buildString {
        appendLine("【当前前台应用】$packageName (Activity: $activityName)")
        if (nodes.isEmpty()) {
            appendLine("【屏幕控件】当前界面未检测到交互节点或正在加载动画中")
        } else {
            appendLine("【交互与可视节点】(共 ${nodes.size} 个，展示前 ${minOf(nodes.size, maxNodes)} 个)")
            nodes.take(maxNodes).forEach { node ->
                appendLine("- ${node.toCompactString()}")
            }
            if (nodes.size > maxNodes) {
                appendLine("[其余 ${nodes.size - maxNodes} 个节点已省略...]")
            }
        }
    }
}

@Singleton
class HostGuiController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val privilegeManager: PrivilegeManager,
    private val toolkit: HostGuiToolkit,
    private val hud: WorkflowGuiHudBridge,
) {
    /**
     * 感知屏幕状态：获取当前前台应用、Activity 及 UI 控件树
     */
    suspend fun observeScreen(onlyInteractive: Boolean = true): Result<ScreenObservation> = withContext(Dispatchers.IO) {
        hud.beginScreenOp("感知屏幕…")
        try {
            runCatching {
                val foreground = getForegroundInfo()
                val dumpPath = "/data/local/tmp/taixu_gui_dump.xml"
                val fallbackDumpPath = "/sdcard/taixu_gui_dump.xml"

                // 优先 dump 到 /data/local/tmp，失败则回退 /sdcard
                val dumpResult = privilegeManager.executeShellCommand(
                    "/system/bin/uiautomator dump $dumpPath >/dev/null 2>&1 && /system/bin/cat $dumpPath; /system/bin/rm -f $dumpPath"
                )

                val xmlContent = if (dumpResult.success && dumpResult.stdout.isNotBlank()) {
                    dumpResult.stdout
                } else {
                    val fallback = privilegeManager.executeShellCommand(
                        "/system/bin/uiautomator dump $fallbackDumpPath >/dev/null 2>&1 && /system/bin/cat $fallbackDumpPath; /system/bin/rm -f $fallbackDumpPath"
                    )
                    fallback.stdout
                }

                val nodes = AndroidGuiXmlParser.parse(xmlContent, onlyInteractive)
                ScreenObservation(
                    packageName = foreground.first,
                    activityName = foreground.second,
                    nodes = nodes,
                    rawXml = xmlContent,
                )
            }
        } finally {
            hud.endScreenOp()
        }
    }

    suspend fun execute(action: GuiPrimitive): Result<String> {
        hud.beginScreenOp(actionHudLabel(action))
        return try {
            runCatching {
                val result = toolkit.execute(action)
                if (result.success) result.toAgentLine() else error(result.toAgentLine())
            }
        } finally {
            hud.endScreenOp()
        }
    }

    private fun actionHudLabel(action: GuiPrimitive): String = when (action) {
        is GuiPrimitive.Tap -> "点击中…"
        is GuiPrimitive.DoubleTap -> "双击中…"
        is GuiPrimitive.LongPress -> "长按中…"
        is GuiPrimitive.Swipe -> "滑动中…"
        is GuiPrimitive.Scroll -> "滚动中…"
        is GuiPrimitive.Key -> "按键 ${action.key.name.lowercase()}…"
        is GuiPrimitive.PasteText -> "粘贴/输入中…"
    }

    /** 点击屏幕坐标 (x, y) — 自动降级：无障碍手势 → cmd input → bin input */
    suspend fun click(x: Int, y: Int): Result<String> = execute(GuiPrimitive.Tap(x, y))

    suspend fun doubleClick(x: Int, y: Int): Result<String> = execute(GuiPrimitive.DoubleTap(x, y))

    suspend fun longPress(x: Int, y: Int, durationMs: Long = 800L): Result<String> =
        execute(GuiPrimitive.LongPress(x, y, durationMs))

    /** 滑动屏幕：从 (x1, y1) 滑动至 (x2, y2) */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Result<String> =
        execute(GuiPrimitive.Swipe(x1, y1, x2, y2, durationMs))

    suspend fun scroll(
        direction: ScrollDirection,
        distanceRatio: Float = 0.45f,
        durationMs: Long = 350L,
    ): Result<String> = execute(GuiPrimitive.Scroll(direction, distanceRatio, durationMs))

    /**
     * 向当前焦点控件输入文本（CJK 走剪贴板粘贴，多后端降级）。
     */
    suspend fun inputText(text: String): Result<String> = execute(GuiPrimitive.PasteText(text))

    /** 发送系统导航或功能按键 */
    suspend fun sendKey(keyName: String): Result<String> {
        val key = GuiKey.parse(keyName)
            ?: return Result.failure(IllegalArgumentException("未知按键：$keyName（支持 back/home/recents/enter/delete/paste/power）"))
        return execute(GuiPrimitive.Key(key))
    }

    /**
     * 启动指定 Android 应用
     */
    suspend fun launchApp(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                "已启动应用：$packageName"
            } else {
                // 回退 monkey 唤醒
                val res = privilegeManager.executeShellCommand(
                    "/system/bin/monkey -p ${shellQuote(packageName)} -c android.intent.category.LAUNCHER 1"
                )
                if (res.success) "已通过 shell 唤起应用：$packageName" else error("无法启动应用 $packageName：${res.stderr}")
            }
        }
    }

    /**
     * 截取当前屏幕并保存至指定路径
     */
    suspend fun captureScreenshot(targetPath: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val res = privilegeManager.executeShellCommand("/system/bin/screencap -p ${shellQuote(targetPath)}")
            if (res.success) "屏幕截图已保存至 $targetPath" else error(res.stderr.ifBlank { "截图失败" })
        }
    }

    /** 通过 Context 发送广播；失败时由调用方决定是否回退特权 am broadcast。 */
    fun sendBroadcastIntent(
        action: String,
        packageName: String? = null,
        component: String? = null,
        extras: Map<String, String> = emptyMap(),
    ): Result<String> = runCatching {
        val intent = Intent(action).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        packageName?.takeIf { it.isNotBlank() }?.let { intent.setPackage(it) }
        component?.takeIf { it.isNotBlank() }?.let { intent.component = parseComponent(it) }
        putExtras(intent, extras)
        context.sendBroadcast(intent)
        "已发送广播：$action" + (packageName?.let { " → $it" } ?: "")
    }

    fun startActivityIntent(
        component: String? = null,
        action: String? = null,
        dataUri: String? = null,
        mimeType: String? = null,
        extras: Map<String, String> = emptyMap(),
    ): Result<String> = runCatching {
        require(!component.isNullOrBlank() || !action.isNullOrBlank() || !dataUri.isNullOrBlank()) {
            "start_activity 至少需要 component、action 或 dataUri 之一"
        }
        val intent = Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        component?.takeIf { it.isNotBlank() }?.let { intent.component = parseComponent(it) }
        action?.takeIf { it.isNotBlank() }?.let { intent.action = it }
        dataUri?.takeIf { it.isNotBlank() }?.let { uri ->
            if (mimeType.isNullOrBlank()) intent.data = Uri.parse(uri) else intent.setDataAndType(Uri.parse(uri), mimeType)
        }
        putExtras(intent, extras)
        context.startActivity(intent)
        "已启动 Activity：" + listOfNotNull(component, action, dataUri).joinToString(" ")
    }

    suspend fun forceStopApp(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val res = privilegeManager.executeShellCommand("/system/bin/am force-stop ${shellQuote(packageName)}")
            if (res.success) "已强制停止：$packageName" else error(res.stderr.ifBlank { "force-stop 失败" })
        }
    }

    suspend fun clearAppData(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val res = privilegeManager.executeShellCommand("/system/bin/pm clear ${shellQuote(packageName)}")
            if (res.success) "已清除数据：$packageName\n${res.stdout}".trim() else error(res.stderr.ifBlank { "pm clear 失败" })
        }
    }

    suspend fun waitForForeground(packageName: String, timeoutMs: Long = 15_000L): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(500L)
            while (System.currentTimeMillis() < deadline) {
                val (pkg, activity) = getForegroundInfo()
                if (pkg.equals(packageName, ignoreCase = true)) {
                    return@runCatching "前台已就绪：$pkg/$activity"
                }
                delay(400)
            }
            val (pkg, activity) = getForegroundInfo()
            error("等待前台超时：期望 $packageName，当前 $pkg/$activity")
        }
    }

    fun showToast(text: String): Result<String> = runCatching {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
        "已弹出 Toast：$text"
    }

    @Suppress("DEPRECATION")
    fun vibrate(durationMs: Long = 200L): Result<String> = runCatching {
        val ms = durationMs.coerceIn(10L, 5_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager.defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(ms)
            }
        }
        "已震动 ${ms}ms"
    }

    fun clipboardSet(text: String): Result<String> = runCatching {
        val latch = java.util.concurrent.CountDownLatch(1)
        var error: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("taixu-workflow", text))
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
            error("写入剪贴板超时")
        }
        error?.let { throw it }
        "已写入剪贴板（${text.length} 字符）"
    }

    fun clipboardGet(): Result<String> = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        text
    }

    suspend fun foregroundPackage(): Pair<String, String> = getForegroundInfo()

    private fun putExtras(intent: Intent, extras: Map<String, String>) {
        extras.forEach { (rawKey, rawValue) ->
            val (key, type) = parseExtraKey(rawKey)
            when (type) {
                "int" -> intent.putExtra(key, rawValue.toInt())
                "long" -> intent.putExtra(key, rawValue.toLong())
                "bool", "boolean" -> intent.putExtra(key, rawValue.toBooleanStrictOrNull() ?: rawValue.equals("1"))
                "float" -> intent.putExtra(key, rawValue.toFloat())
                "uri" -> intent.putExtra(key, Uri.parse(rawValue))
                else -> intent.putExtra(key, rawValue)
            }
        }
    }

    private fun parseExtraKey(raw: String): Pair<String, String> {
        val parts = raw.split(':', limit = 2)
        return if (parts.size == 2 && parts[1] in setOf("int", "long", "bool", "boolean", "float", "uri", "string")) {
            parts[0] to parts[1]
        } else if (parts.size == 2 && parts[0] in setOf("int", "long", "bool", "boolean", "float", "uri", "string")) {
            parts[1] to parts[0]
        } else {
            raw to "string"
        }
    }

    private fun parseComponent(value: String): ComponentName {
        ComponentName.unflattenFromString(value)?.let { return it }
        if (value.contains('/')) {
            val pkg = value.substringBefore('/')
            val cls = value.substringAfter('/')
            val fullClass = if (cls.startsWith('.')) "$pkg$cls" else cls
            return ComponentName(pkg, fullClass)
        }
        error("无法解析组件：$value")
    }

    private suspend fun getForegroundInfo(): Pair<String, String> {
        val res = privilegeManager.executeShellCommand(
            "/system/bin/dumpsys activity activities | /system/bin/grep -E 'topResumedActivity|mResumedActivity' | /system/bin/head -n 1"
        )
        if (!res.success || res.stdout.isBlank()) return "Unknown" to "Unknown"
        // 匹配格式形如：topResumedActivity=ActivityRecord{... u0 com.tencent.mm/.ui.LauncherUI ...}
        val match = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.]+)").find(res.stdout)
        return if (match != null) {
            val (pkg, act) = match.destructured
            pkg to act
        } else {
            "Unknown" to "Unknown"
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
