package top.wanxiang.app.runtime.gui

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
) {
    /**
     * 感知屏幕状态：获取当前前台应用、Activity 及 UI 控件树
     */
    suspend fun observeScreen(onlyInteractive: Boolean = true): Result<ScreenObservation> = withContext(Dispatchers.IO) {
        runCatching {
            val foreground = getForegroundInfo()
            val dumpPath = "/data/local/tmp/wanxiang_gui_dump.xml"
            val fallbackDumpPath = "/sdcard/wanxiang_gui_dump.xml"

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
    }

    /**
     * 点击屏幕坐标 (x, y)
     */
    suspend fun click(x: Int, y: Int): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val res = privilegeManager.executeShellCommand("/system/bin/input tap $x $y")
            if (res.success) "已点击坐标 ($x, $y)" else error(res.stderr.ifBlank { "点击失败 exit=${res.exitCode}" })
        }
    }

    /**
     * 滑动屏幕：从 (x1, y1) 滑动至 (x2, y2)
     */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val res = privilegeManager.executeShellCommand("/system/bin/input swipe $x1 $y1 $x2 $y2 $durationMs")
            if (res.success) "已从 ($x1, $y1) 滑动至 ($x2, $y2)，耗时 ${durationMs}ms" else error(res.stderr.ifBlank { "滑动失败" })
        }
    }

    /**
     * 向当前焦点控件输入文本
     */
    suspend fun inputText(text: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // Android input text 需要对空格和特殊字符进行转义
            val sanitized = text.replace(" ", "%s").replace("'", "\\'")
            val res = privilegeManager.executeShellCommand("/system/bin/input text '$sanitized'")
            if (res.success) "已输入文本：$text" else error(res.stderr.ifBlank { "输入失败" })
        }
    }

    /**
     * 发送系统导航或功能按键
     */
    suspend fun sendKey(keyName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val keyCode = when (keyName.trim().lowercase()) {
                "back" -> 4
                "home" -> 3
                "recents", "app_switch" -> 187
                "enter" -> 66
                "delete", "backspace" -> 67
                "volume_up" -> 24
                "volume_down" -> 25
                "power" -> 26
                else -> keyName.toIntOrNull() ?: error("未知按键：$keyName（支持 back/home/recents/enter/delete/power）")
            }
            val res = privilegeManager.executeShellCommand("/system/bin/input keyevent $keyCode")
            if (res.success) "已触发按键：$keyName (KEYCODE $keyCode)" else error(res.stderr.ifBlank { "按键触发失败" })
        }
    }

    /**
     * 启动指定 Android 应用
     */
    suspend fun launchApp(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
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
