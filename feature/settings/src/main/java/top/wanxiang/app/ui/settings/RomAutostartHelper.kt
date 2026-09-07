package top.wanxiang.app.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 国产 ROM 自启动 / 后台运行深度引导。
 *
 * 小米、华为、OPPO、vivo 等厂商系统对后台进程限制严格，仅靠前台 Service 和电池优化豁免不够，
 * 还需要用户在厂商专属设置中开启「自启动」「后台运行」「锁屏不清理」等开关。
 * 本工具根据 Build.MANUFACTURER 匹配厂商，尝试跳转到对应的自启动管理页面；
 * 全部失败时降级到通用应用详情页。
 */
object RomAutostartHelper {

    private const val TAG = "RomAutostartHelper"

    /** 当前设备是否为已知需要额外自启动引导的国产 ROM。 */
    fun isKnownRestrictiveRom(): Boolean = detectRom() != RomBrand.OTHER

    /** 厂商品牌的人类可读名称，用于设置页展示。 */
    fun romLabel(): String = detectRom().label

    /**
     * 尝试跳转到厂商自启动设置页。
     *
     * @return true 表示成功跳转到某个设置页（包括降级的通用应用详情页），false 表示全部失败
     */
    fun openAutostartSettings(context: Context): Boolean {
        val brand = detectRom()
        val intents = buildIntents(context, brand)
        for (intent in intents) {
            if (tryStart(context, intent)) {
                Log.i(TAG, "成功跳转到 ${brand.label} 自启动设置页: ${intent.component}")
                return true
            }
        }
        // 降级：通用应用详情页
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(context, fallback)
    }

    private fun detectRom(): RomBrand {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        val brand = Build.BRAND?.lowercase() ?: ""
        val fingerprint = Build.HARDWARE?.lowercase() ?: ""
        val combined = "$manufacturer $brand $fingerprint"

        return when {
            combined.contains("xiaomi") || combined.contains("redmi") ||
                combined.contains("poco") || combined.contains("miui") -> RomBrand.XIAOMI
            combined.contains("huawei") || combined.contains("honor") ||
                combined.contains("emui") || combined.contains("magic") -> RomBrand.HUAWEI
            combined.contains("oppo") || combined.contains("realme") ||
                combined.contains("oneplus") || combined.contains("coloros") -> RomBrand.OPPO
            combined.contains("vivo") || combined.contains("iqoo") ||
                combined.contains("funtouch") || combined.contains("origin") -> RomBrand.VIVO
            combined.contains("meizu") || combined.contains("flyme") -> RomBrand.MEIZU
            combined.contains("samsung") -> RomBrand.SAMSUNG
            else -> RomBrand.OTHER
        }
    }

    private fun buildIntents(context: Context, brand: RomBrand): List<Intent> = when (brand) {
        RomBrand.XIAOMI -> listOf(
            intentFor("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            intentFor("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"),
            intentFor("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
        )
        RomBrand.HUAWEI -> listOf(
            intentFor("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            intentFor("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            intentFor("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        RomBrand.OPPO -> listOf(
            intentFor("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            intentFor("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            intentFor("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        )
        RomBrand.VIVO -> listOf(
            intentFor("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            intentFor("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            intentFor("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        )
        RomBrand.MEIZU -> listOf(
            intentFor("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
            intentFor("com.meizu.safe", "com.meizu.safe.security.AppSecActivity"),
        )
        RomBrand.SAMSUNG -> listOf(
            // 三星设备维护 -> 电池 -> 后台使用限制，无直接自启动页，降级到应用详情
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
        RomBrand.OTHER -> emptyList()
    }

    private fun intentFor(pkg: String, cls: String): Intent =
        Intent().apply {
            component = ComponentName(pkg, cls)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun tryStart(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.getOrElse {
        Log.w(TAG, "跳转失败: ${intent.component}", it)
        false
    }

    private enum class RomBrand(val label: String) {
        XIAOMI("小米 / Redmi / POCO"),
        HUAWEI("华为 / 荣耀"),
        OPPO("OPPO / realme / 一加"),
        VIVO("vivo / iQOO"),
        MEIZU("魅族"),
        SAMSUNG("三星"),
        OTHER("其他"),
    }
}
