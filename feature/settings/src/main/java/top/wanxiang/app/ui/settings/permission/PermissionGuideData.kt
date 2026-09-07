package top.wanxiang.app.ui.settings.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import top.wanxiang.app.ui.components.RuntimeIconName

/**
 * 厂商保活引导步骤模型
 */
data class PermissionStep(
    val title: String,
    val description: String,
    val tip: String? = null,
)

/**
 * 品牌定义与识别模型
 */
enum class OemBrand(val id: String, val label: String, val osName: String) {
    XIAOMI("xiaomi", "小米 / Redmi", "HyperOS / MIUI"),
    HUAWEI("huawei", "华为 / 荣耀", "HarmonyOS / MagicOS"),
    VIVO("vivo", "vivo / iQOO", "OriginOS / Funtouch"),
    OPPO("oppo", "OPPO / 一加 / realme", "ColorOS / RealmeUI"),
    MEIZU("meizu", "魅族", "Flyme"),
    SAMSUNG("samsung", "三星", "One UI"),
    GENERIC("generic", "原生 / 其他品牌", "AOSP / Android");

    companion object {
        fun detect(): OemBrand {
            val manufacturer = Build.MANUFACTURER?.lowercase().orEmpty()
            val brand = Build.BRAND?.lowercase().orEmpty()
            val fingerprint = Build.FINGERPRINT?.lowercase().orEmpty()
            val hardware = Build.HARDWARE?.lowercase().orEmpty()
            val combined = "$manufacturer $brand $fingerprint $hardware"

            return when {
                combined.contains("xiaomi") || combined.contains("redmi") ||
                    combined.contains("poco") || combined.contains("miui") -> XIAOMI
                combined.contains("huawei") || combined.contains("honor") ||
                    combined.contains("emui") || combined.contains("magic") -> HUAWEI
                combined.contains("vivo") || combined.contains("iqoo") ||
                    combined.contains("funtouch") || combined.contains("origin") -> VIVO
                combined.contains("oppo") || combined.contains("realme") ||
                    combined.contains("oneplus") || combined.contains("coloros") -> OPPO
                combined.contains("meizu") || combined.contains("flyme") -> MEIZU
                combined.contains("samsung") -> SAMSUNG
                else -> GENERIC
            }
        }
    }
}

/**
 * 保活与权限专题枚举
 */
enum class KeepaliveTopic(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: RuntimeIconName,
) {
    AUTOSTART(
        id = "autostart",
        title = "自启动与后台唤醒",
        subtitle = "允许系统在开机或后台需要时自动拉起沙箱与 Agent 服务",
        icon = RuntimeIconName.Cpu,
    ),
    BATTERY_UNRESTRICTED(
        id = "battery_unrestricted",
        title = "电池优化无限制",
        subtitle = "豁免系统省电策略与 Doze 模式，防止息屏后 PRoot 进程被冻结",
        icon = RuntimeIconName.Battery,
    ),
    LOCK_RECENTS(
        id = "lock_recents",
        title = "多任务卡片加锁",
        subtitle = "在最近任务列表中锁定万象，防止一键清理时杀死前台服务",
        icon = RuntimeIconName.Shield,
    ),
    FLOATING_OVERLAY(
        id = "floating_overlay",
        title = "悬浮窗与后台弹出",
        subtitle = "允许在后台运行时弹出构建完成或 Agent 交互通知与窗口",
        icon = RuntimeIconName.Terminal,
    ),
    NOTIFICATION(
        id = "notification",
        title = "通知与前台常驻",
        subtitle = "保持前台保活服务通知栏显示，确保 Linux 运行时最高优先级存活",
        icon = RuntimeIconName.Chat,
    ),
}

/**
 * 厂商保活与权限引导知识库仓储
 */
object PermissionGuideRepository {

    fun getSteps(brand: OemBrand, topic: KeepaliveTopic): List<PermissionStep> {
        return when (topic) {
            KeepaliveTopic.AUTOSTART -> when (brand) {
                OemBrand.XIAOMI -> listOf(
                    PermissionStep("进入「应用设置」", "打开系统【设置】 ➔ 【应用设置】 ➔ 【授权管理】 ➔ 【自启动管理】。"),
                    PermissionStep("开启「万象」自启动", "在应用列表中找到【万象 (WanXiang)】，将自启动开关切换为【开启】。"),
                    PermissionStep("允许应用间互相唤醒", "点击进入万象详情，开启【允许关联启动 / 被其他应用唤醒】（HyperOS 推荐）。"),
                )
                OemBrand.HUAWEI -> listOf(
                    PermissionStep("打开「应用启动管理」", "进入【手机管家】 ➔ 【应用启动管理】。"),
                    PermissionStep("切换为手动管理", "找到【万象】，关闭「自动管理」开关，在弹出的窗口中开启全部三项：【允许自启动】、【允许关联启动】和【允许后台活动】。"),
                )
                OemBrand.VIVO -> listOf(
                    PermissionStep("打开「自启动权限」", "进入系统【设置】 ➔ 【应用与权限】 ➔ 【权限管理】 ➔ 【权限】标签页 ➔ 【自启动】。"),
                    PermissionStep("开启万象自启动", "找到【万象】并开启自启动允许。"),
                    PermissionStep("开启高耗电后台运行", "在【设置】 ➔ 【电池】 ➔ 【后台高耗电】中，勾选允许万象继续在后台运行。"),
                )
                OemBrand.OPPO -> listOf(
                    PermissionStep("进入「自启动管理」", "进入系统【设置】 ➔ 【应用】 ➔ 【自启动管理】。"),
                    PermissionStep("允许自启动与关联启动", "找到【万象】，打开【允许自启动】与【允许关联启动】开关。"),
                )
                OemBrand.MEIZU -> listOf(
                    PermissionStep("打开「后台管理」", "进入【手机管家】 ➔ 【权限管理】 ➔ 【后台管理】。"),
                    PermissionStep("保持后台运行", "将【万象】设置为【允许后台运行】或【保持后台唤醒】。"),
                )
                OemBrand.SAMSUNG -> listOf(
                    PermissionStep("检查「自动运行应用程序」", "进入【设置】 ➔ 【电池】 ➔ 【后台使用限制】。"),
                    PermissionStep("移出休眠列表", "确保万象没有在「深度休眠应用程序」中，并加入【从不休眠的应用程序】列表。"),
                )
                OemBrand.GENERIC -> listOf(
                    PermissionStep("检查系统启动项", "在系统设置应用详情中，检查是否有「自启动」或「开机启动」权限并予以放行。"),
                )
            }
            KeepaliveTopic.BATTERY_UNRESTRICTED -> when (brand) {
                OemBrand.XIAOMI -> listOf(
                    PermissionStep("应用详情省电策略", "长按万象桌面图标 ➔ 【应用信息】 ➔ 下滑找到【省电策略】。"),
                    PermissionStep("设为「无限制」", "选择【无限制】（不限制后台活动，最利于 PRoot 编译与 Agent 长思考）。"),
                )
                OemBrand.HUAWEI -> listOf(
                    PermissionStep("忽略电池优化", "进入【设置】 ➔ 【应用】 ➔ 【权限管理】 ➔ 右上角菜单【特殊访问权限】 ➔ 【电池优化】。"),
                    PermissionStep("设为「不允许优化」", "下拉筛选「所有应用」，找到【万象】，设为【不允许】。"),
                )
                OemBrand.VIVO -> listOf(
                    PermissionStep("后台高耗电允许", "进入【设置】 ➔ 【电池】 ➔ 【后台耗电管理】。"),
                    PermissionStep("开启高耗电运行", "找到【万象】，选择【允许高耗电后台运行】。"),
                )
                OemBrand.OPPO -> listOf(
                    PermissionStep("电池耗电管理", "长按万象图标 ➔ 【应用详情】 ➔ 【耗电管理】。"),
                    PermissionStep("开启所有后台选项", "勾选【允许完全后台行为】、【允许唤醒前台】并关闭「深度省电优化」。"),
                )
                OemBrand.SAMSUNG -> listOf(
                    PermissionStep("电池使用模式", "进入系统【设置】 ➔ 【应用程序】 ➔ 【万象】 ➔ 【电池】。"),
                    PermissionStep("设为「不受限制」", "由默认的「已优化」切换为【不受限制】（Unrestricted）。"),
                )
                else -> listOf(
                    PermissionStep("忽略电池优化", "进入应用信息 ➔ 【电池】 ➔ 设为【无限制】或申请忽略系统电池优化。"),
                )
            }
            KeepaliveTopic.LOCK_RECENTS -> listOf(
                PermissionStep("进入多任务后台", "从屏幕底部上滑悬停（或点击多任务导航键），进入多任务卡片视图。"),
                PermissionStep("锁定万象卡片", "长按万象任务卡片（或向下拉动卡片），点击出现的小锁 🔒 图标进行锁定，防止一键清理后台时被系统杀死。"),
            )
            KeepaliveTopic.FLOATING_OVERLAY -> listOf(
                PermissionStep("开启悬浮窗 / 显示在其他应用上层", "进入【应用信息】 ➔ 【高级】 ➔ 【显示在其他应用上层 / 悬浮窗】，切换为【允许】。"),
                PermissionStep("后台弹出界面权限", "对于小米/vivo等系统，在【权限管理】中开启【后台弹出界面】或【桌面快捷方式】权限。"),
            )
            KeepaliveTopic.NOTIFICATION -> listOf(
                PermissionStep("允许通知与前台服务常驻", "确保万象拥有通知权限，万象会在启动 PRoot 沙箱与 Agent 循环时启动前台常驻服务，保障系统进程优先级。"),
            )
        }
    }

    /**
     * 检查某项权限在当前系统中的状态
     */
    fun checkStatus(context: Context, topic: KeepaliveTopic): Boolean {
        return when (topic) {
            KeepaliveTopic.BATTERY_UNRESTRICTED -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            }
            KeepaliveTopic.NOTIFICATION -> {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
            KeepaliveTopic.FLOATING_OVERLAY -> {
                Settings.canDrawOverlays(context)
            }
            KeepaliveTopic.AUTOSTART,
            KeepaliveTopic.LOCK_RECENTS -> {
                // Android 无公开 API 直接读取 OEM 私有自启动/最近任务加锁状态
                false
            }
        }
    }

    /**
     * 构建跳转至该专题设置页的 Intent 列表（按优先级降级尝试）
     */
    fun buildLaunchIntents(context: Context, brand: OemBrand, topic: KeepaliveTopic): List<Intent> {
        val pkg = context.packageName
        return when (topic) {
            KeepaliveTopic.AUTOSTART -> buildAutostartIntents(pkg, brand)
            KeepaliveTopic.BATTERY_UNRESTRICTED -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                appDetailsIntent(pkg),
            )
            KeepaliveTopic.FLOATING_OVERLAY -> listOf(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg")),
                appDetailsIntent(pkg),
            )
            KeepaliveTopic.NOTIFICATION -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                },
                appDetailsIntent(pkg),
            )
            KeepaliveTopic.LOCK_RECENTS -> listOf(
                // 最近任务加锁不需要跳转，用户直接呼出多任务即可
                appDetailsIntent(pkg),
            )
        }
    }

    private fun buildAutostartIntents(pkg: String, brand: OemBrand): List<Intent> {
        return when (brand) {
            OemBrand.XIAOMI -> listOf(
                intentFor("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                intentFor("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"),
                intentFor("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
                appDetailsIntent(pkg),
            )
            OemBrand.HUAWEI -> listOf(
                intentFor("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                intentFor("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                intentFor("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                appDetailsIntent(pkg),
            )
            OemBrand.VIVO -> listOf(
                intentFor("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                intentFor("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                intentFor("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                appDetailsIntent(pkg),
            )
            OemBrand.OPPO -> listOf(
                intentFor("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                intentFor("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                intentFor("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                appDetailsIntent(pkg),
            )
            OemBrand.MEIZU -> listOf(
                intentFor("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
                intentFor("com.meizu.safe", "com.meizu.safe.security.AppSecActivity"),
                appDetailsIntent(pkg),
            )
            OemBrand.SAMSUNG -> listOf(
                appDetailsIntent(pkg),
            )
            OemBrand.GENERIC -> listOf(
                appDetailsIntent(pkg),
            )
        }
    }

    private fun intentFor(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun appDetailsIntent(pkg: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
