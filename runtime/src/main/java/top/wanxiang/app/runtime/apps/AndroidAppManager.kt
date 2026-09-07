package top.wanxiang.app.runtime.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.wanxiang.app.core.database.AndroidAppEntity
import top.wanxiang.app.core.database.AndroidAppRepository
import top.wanxiang.app.core.model.ExecutionMode
import top.wanxiang.app.runtime.privilege.PrivilegeManager

data class AppInventorySyncResult(val total: Int, val systemApps: Int, val userApps: Int)

/**
 * Always builds the basic inventory through PackageManager. When Shizuku/Root is active, it
 * supplements it with shell-only state (suspended and netpolicy) before reconciling Room.
 */
@Singleton
class AndroidAppManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val privilegeManager: PrivilegeManager,
    private val repository: AndroidAppRepository,
) {
    suspend fun synchronize(): Result<AppInventorySyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            val localApps = installedApplications()
            check(localApps.isNotEmpty()) { "未读取到应用清单，已保留现有数据库数据。" }
            val privilege = privilegeManager.getPrivilegeInfo()
            val privilegedSections = if (privilege.mode != ExecutionMode.PROOT && privilege.modeActive) {
                privilegeManager.executeShellCommand(INVENTORY_COMMAND, "android-app-inventory")
                    .takeIf { it.success }
                    ?.stdout
                    ?.let(::parseSections)
                    .orEmpty()
            } else {
                emptyMap()
            }
            val disabled = packageNames(privilegedSections[DISABLED].orEmpty())
            val suspended = packageNames(privilegedSections[SUSPENDED].orEmpty())
            val restrictedUids = Regex("\\b\\d{4,10}\\b").findAll(privilegedSections[NETWORK].orEmpty())
                .map { it.value.toInt() }.toSet()
            val now = System.currentTimeMillis()
            val apps = localApps.map { info ->
                val packageName = info.packageName
                val flags = info.flags
                // 非特权模式下 ApplicationInfo.enabled 只反映 manifest 默认值（几乎恒为 true），
                // 无法识别用户通过 pm disable-user 冻结的应用；getApplicationEnabledSetting
                // 无需任何权限即可读取组件的实际启用状态。
                val enabled = if (privilegedSections.isEmpty()) {
                    isEffectivelyEnabled(packageName, info.enabled)
                } else {
                    packageName !in disabled
                }
                AndroidAppEntity(
                    packageName = packageName,
                    label = info.loadLabel(context.packageManager).toString().ifBlank { packageName },
                    uid = info.uid,
                    apkPath = info.sourceDir.orEmpty(),
                    isSystemApp = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    isEnabled = enabled,
                    isSuspended = packageName in suspended,
                    isNetworkRestricted = info.uid in restrictedUids,
                    lastSyncedAt = now,
                )
            }
            repository.reconcile(apps)
            AppInventorySyncResult(apps.size, apps.count { it.isSystemApp }, apps.count { !it.isSystemApp })
        }
    }

    suspend fun requireInitialized(packageName: String): AndroidAppEntity {
        check(repository.count() > 0) { INITIALIZATION_REQUIRED_MESSAGE }
        return requireNotNull(repository.findByPackageName(packageName)) {
            "应用 $packageName 不在已同步的应用数据库中；请到设置 → 应用管理执行同步后重试。"
        }
    }

    suspend fun isInitialized(): Boolean = repository.count() > 0

    @Suppress("DEPRECATION")
    private fun installedApplications(): List<ApplicationInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            context.packageManager.getInstalledApplications(0)
        }
    }.getOrDefault(emptyList())

    /**
     * 非特权模式下判断应用是否实际启用：getApplicationEnabledSetting 无需特殊权限即可读取
     * 用户通过 pm disable-user / 设置页"停用"造成的禁用状态，弥补 ApplicationInfo.enabled
     * 只反映 manifest 默认值的不足。
     */
    private fun isEffectivelyEnabled(packageName: String, manifestDefault: Boolean): Boolean = runCatching {
        when (context.packageManager.getApplicationEnabledSetting(packageName)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> false
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> manifestDefault
        }
    }.getOrDefault(manifestDefault)

    private fun parseSections(output: String): Map<String, String> {
        val marker = Regex("^__(WANXIANG_[A-Z_]+)__$", RegexOption.MULTILINE)
        val matches = marker.findAll(output).toList()
        return matches.mapIndexed { index, match ->
            val end = matches.getOrNull(index + 1)?.range?.first ?: output.length
            match.groupValues[1].removePrefix("WANXIANG_") to output.substring(match.range.last + 1, end)
        }.toMap()
    }

    private fun packageNames(output: String): Set<String> = output.lineSequence()
        .map { it.trim().removePrefix("package:") }
        .filter { PACKAGE_NAME.matches(it) }
        .toSet()

    private companion object {
        const val INITIALIZATION_REQUIRED_MESSAGE = "应用数据库尚未初始化；请先到设置 → 应用管理完成初始化和同步。"
        const val DISABLED = "DISABLED"
        const val SUSPENDED = "SUSPENDED"
        const val NETWORK = "NETWORK"
        val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        const val INVENTORY_COMMAND = """
            printf '__WANXIANG_DISABLED__\\n'; /system/bin/pm list packages -d;
            printf '__WANXIANG_SUSPENDED__\\n'; /system/bin/pm list packages --suspended 2>/dev/null || true;
            printf '__WANXIANG_NETWORK__\\n'; /system/bin/cmd netpolicy list restrict-background-blacklist 2>/dev/null || true
        """
    }
}
