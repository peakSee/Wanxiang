package top.wanxiang.app.core.tools

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import java.io.File
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import top.wanxiang.app.tools.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件与工具安装系统通知栏控制器
 * 实时同步并发安装进度、完成与失败状态到 Android 系统通知栏。
 */
@SuppressLint("MissingPermission")
@Singleton
class ToolNotificationNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val notificationManager = NotificationManagerCompat.from(context)
    private val channelId = "wanxiang_tool_install"
    private val appLogo: Bitmap? by lazy {
        context.applicationInfo.icon.takeIf { it != 0 }?.let { iconRes ->
            runCatching { BitmapFactory.decodeResource(context.resources, iconRes) }.getOrNull()
        }
    }

    init {
        createChannel()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            channelId,
            "插件与工具安装进度",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "展示万象 PRoot 沙箱内 AI 工具与插件的安装与更新进度"
            setShowBadge(false)
        }
        val systemManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        systemManager?.createNotificationChannel(channel)
    }

    fun showProgress(toolId: String, toolName: String, message: String, progress: Float?) {
        val notificationId = toolNotificationId(toolId)
        val pendingIntent = getLaunchIntent()

        val progressPercent = ((progress ?: 0f) * 100).toInt().coerceIn(0, 100)
        val isIndeterminate = progress == null

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("正在安装 $toolName")
            .setContentText(message)
            .setLargeIcon(appLogo)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent, isIndeterminate)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        try {
            notificationManager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // Android 13+ 通知权限未授权时忽略
        }
    }

    fun showSuccess(toolId: String, toolName: String, version: String?) {
        val notificationId = toolNotificationId(toolId)
        val pendingIntent = getLaunchIntent()

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("$toolName 安装完成")
            .setContentText("版本 ${version ?: "已就绪"}，可在控制台或工具中心直接使用")
            .setLargeIcon(appLogo)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        try {
            notificationManager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun showFailed(toolId: String, toolName: String, error: String) {
        val notificationId = toolNotificationId(toolId)
        val pendingIntent = getLaunchIntent()

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("$toolName 安装失败")
            .setContentText(error.take(120))
            .setLargeIcon(appLogo)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        try {
            notificationManager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun cancel(toolId: String) {
        try {
            notificationManager.cancel(toolNotificationId(toolId))
        } catch (_: Exception) {
        }
    }

    // ==================== 工坊工程构建通知 ====================
    private val buildChannelId = "wanxiang_build_result"

    init {
        createBuildChannel()
    }

    private fun createBuildChannel() {
        val channel = NotificationChannel(
            buildChannelId,
            "工坊工程构建结果",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "展示万象工坊中 Android / Flutter 工程的编译完成或失败结果"
            setShowBadge(true)
        }
        val systemManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        systemManager?.createNotificationChannel(channel)
    }

    fun showBuildProgress(projectName: String, step: String) {
        val notificationId = buildNotificationId(projectName)
        val pendingIntent = getLaunchIntent()
        val builder = NotificationCompat.Builder(context, buildChannelId)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("🔨 正在编译 $projectName")
            .setContentText(step)
            .setLargeIcon(appLogo)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        try {
            notificationManager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun showBuildSuccess(projectName: String, apkPath: String?) {
        val notificationId = buildNotificationId(projectName)
        val pendingIntent = apkPath?.let { getApkInstallIntent(File(it)) } ?: getLaunchIntent()
        val builder = NotificationCompat.Builder(context, buildChannelId)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("✅ $projectName 编译成功")
            .setContentText(if (apkPath != null) "APK 已生成，点击安装到手机" else "构建已完成")
            .setLargeIcon(appLogo)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        try {
            // Some Android/OEM notification managers keep the previous ongoing
            // notification row when it is changed in-place to a non-ongoing one.
            // Remove the progress row first so a completed build cannot remain
            // stuck on the last "copying to Download" message.
            notificationManager.cancel(notificationId)
            notificationManager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun showBuildFailed(projectName: String, error: String) {
        val notificationId = buildNotificationId(projectName)
        val pendingIntent = getLaunchIntent()
        val builder = NotificationCompat.Builder(context, buildChannelId)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("❌ $projectName 编译失败")
            .setContentText(error.take(120))
            .setLargeIcon(appLogo)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
        try {
            notificationManager.cancel(notificationId)
            notificationManager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun cancelBuildNotification(projectName: String) {
        try {
            notificationManager.cancel(buildNotificationId(projectName))
        } catch (_: Exception) {
        }
    }

    private fun buildNotificationId(projectName: String): Int {
        return 30000 + (projectName.hashCode() and 0x7FFF)
    }

    private fun toolNotificationId(toolId: String): Int {
        return 20000 + (toolId.hashCode() and 0x7FFF)
    }

    private fun getLaunchIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun getApkInstallIntent(apk: File): PendingIntent? {
        if (!apk.isFile || apk.length() <= 0L) return null
        val stagedApk = runCatching { stageApkForInstall(apk) }.getOrNull() ?: return getLaunchIntent()
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", stagedApk)
        }.getOrNull() ?: return getLaunchIntent()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("APK", uri)
        }
        return PendingIntent.getActivity(
            context,
            (stagedApk.absolutePath.hashCode() and 0x7FFF) + 40000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stageApkForInstall(apk: File): File {
        val dir = File(context.cacheDir, "notification-apk-installs").apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()
            .orEmpty()
            .filter { it.isFile && now - it.lastModified() > 24 * 60 * 60 * 1000L }
            .forEach { it.delete() }
        val staged = File(dir, "${apk.nameWithoutExtension}-$now-${apk.length()}.apk")
        apk.inputStream().use { input -> staged.outputStream().use { output -> input.copyTo(output) } }
        check(staged.isFile && staged.length() == apk.length()) { "APK 临时副本不完整" }
        return staged
    }
}
