package top.wanxiang.app.runtime.gui

import android.content.ComponentName
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import top.wanxiang.app.core.model.ExecutionMode
import top.wanxiang.app.runtime.privilege.PrivilegeManager

/**
 * With Shizuku/Root, programmatically enable [WanxiangGuiAccessibilityService]
 * via Secure settings — no manual trip to 无障碍 settings.
 */
@Singleton
class GuiAccessibilityEnabler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val privilegeManager: PrivilegeManager,
) {
    @Volatile private var lastAttemptAt = 0L
    @Volatile private var lastOk = false

    /**
     * @return true if the gesture service is connected (already was, or just enabled).
     */
    suspend fun ensureEnabled(): Boolean {
        if (AccessibilityGestureBridge.isAvailable()) {
            lastOk = true
            return true
        }
        val info = privilegeManager.getPrivilegeInfo()
        if (info.mode == ExecutionMode.PROOT || !info.modeActive) {
            Log.d(TAG, "skip a11y auto-enable: not privileged (${info.mode})")
            return false
        }
        val now = System.currentTimeMillis()
        if (lastOk && now - lastAttemptAt < SUCCESS_COOLDOWN_MS) {
            return AccessibilityGestureBridge.isAvailable()
        }
        if (!lastOk && now - lastAttemptAt < FAIL_COOLDOWN_MS) {
            return false
        }
        lastAttemptAt = now

        val component = ComponentName(context, WanxiangGuiAccessibilityService::class.java).flattenToString()
        val read = privilegeManager.executeShellCommand("settings get secure enabled_accessibility_services")
        val currentRaw = read.stdout.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val current = if (currentRaw == "null") "" else currentRaw
        val services = current.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        if (services.none { it.equals(component, ignoreCase = true) || it.endsWith("/${WanxiangGuiAccessibilityService::class.java.name}") }) {
            services += component
        }
        val joined = services.joinToString(":")
        val putServices = privilegeManager.executeShellCommand(
            "settings put secure enabled_accessibility_services ${shellQuote(joined)}",
        )
        val putFlag = privilegeManager.executeShellCommand("settings put secure accessibility_enabled 1")
        if (!putServices.success || !putFlag.success) {
            Log.w(
                TAG,
                "a11y auto-enable shell failed: services=${putServices.stderr} flag=${putFlag.stderr}",
            )
            lastOk = false
            return false
        }
        Log.i(TAG, "a11y auto-enable requested for $component")

        repeat(20) {
            if (AccessibilityGestureBridge.isAvailable()) {
                lastOk = true
                Log.i(TAG, "a11y gesture service connected after auto-enable")
                return true
            }
            delay(100)
        }
        lastOk = AccessibilityGestureBridge.isAvailable()
        if (!lastOk) {
            Log.w(TAG, "a11y settings written but service not connected yet")
        }
        return lastOk
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val TAG = "TaiXu-GuiA11yEnable"
        const val SUCCESS_COOLDOWN_MS = 30_000L
        const val FAIL_COOLDOWN_MS = 5_000L
    }
}
