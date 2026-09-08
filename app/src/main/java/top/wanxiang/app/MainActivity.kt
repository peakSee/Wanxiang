package top.wanxiang.app

import top.wanxiang.app.ui.components.RuntimeAlertDialog
import top.wanxiang.app.ui.onboarding.OnboardingScreen
import top.wanxiang.app.ui.onboarding.OnboardingViewModel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.wanxiang.app.ui.components.RuntimeButton as Button
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import top.wanxiang.app.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.wanxiang.app.core.datastore.AppearancePreferences
import top.wanxiang.app.core.model.AppUpdateInfo
import top.wanxiang.app.core.network.AppUpdateManager
import top.wanxiang.app.runtime.service.RuntimeServiceController
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.navigation.WanXiangNavHost
import top.wanxiang.app.ui.theme.WanXiangTheme
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

import javax.inject.Inject
import top.wanxiang.app.core.common.navigation.AppNavigationTarget
import top.wanxiang.app.core.common.navigation.GlobalNavigationBus
import top.wanxiang.app.service.adb.AdbNotificationManager

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var settingsDataStore: AppearancePreferences

    @Inject
    lateinit var updatePreferences: top.wanxiang.app.core.datastore.UpdatePreferences

    @Inject
    lateinit var appUpdateManager: AppUpdateManager

    @Inject
    lateinit var runtimeServiceController: RuntimeServiceController

    @Inject
    lateinit var globalNavigationBus: GlobalNavigationBus

    @Inject
    lateinit var adbNotificationManager: AdbNotificationManager

    @Inject
    lateinit var gitCredentialIpcBridge: top.wanxiang.app.runtime.credentials.GitCredentialIpcBridge

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) runtimeServiceController.start()
    }

    private var notificationPermissionCheckScheduled = false

    /** 控制 SplashScreen 持续显示，直到 onboarding 偏好从 DataStore 加载完成，避免白屏空窗。 */
    private val keepSplashOnScreen: MutableState<Boolean> = mutableStateOf(true)

    private var createdUptimeMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        createdUptimeMs = android.os.SystemClock.uptimeMillis()
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSplashOnScreen.value }
        super.onCreate(savedInstanceState)
        handleNavigationIntent(intent)
        enableEdgeToEdge()
        setContent {
            val themeMode by settingsDataStore.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            val themeStyle by settingsDataStore.themeStyle.collectAsStateWithLifecycle(initialValue = "xuantong")
            val chengmingBackgroundUri by settingsDataStore.chengmingBackgroundUri.collectAsStateWithLifecycle(initialValue = null)
            val pageScale by settingsDataStore.appFontScale.collectAsStateWithLifecycle(initialValue = 1f)
            val systemDark = isSystemInDarkTheme()
            val systemDensity = LocalDensity.current
            val isDark = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }

            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = systemDensity.density * pageScale.coerceIn(0.8f, 1.3f),
                    fontScale = systemDensity.fontScale,
                ),
            ) {
                WanXiangTheme(
                    style = top.wanxiang.app.ui.theme.ThemeStyle.fromId(themeStyle),
                    darkTheme = isDark,
                    backgroundUri = chengmingBackgroundUri,
                ) {
                val onboardingViewModel: OnboardingViewModel = hiltViewModel()
                val onboarding by onboardingViewModel.status.collectAsStateWithLifecycle()

                // onboarding 偏好读盘完成后让 SplashScreen 退场；Runtime 恢复已在后台继续，不阻塞首帧。
                // Linux 环境恢复（restoreInstalledState）由 OnboardingViewModel.init 自行发起，不再在此重复触发。
                LaunchedEffect(onboarding.loaded) {
                    if (onboarding.loaded && keepSplashOnScreen.value) {
                        keepSplashOnScreen.value = false
                        android.util.Log.i(
                            "WanXiangStartup",
                            "splash dismissed in ${android.os.SystemClock.uptimeMillis() - createdUptimeMs}ms",
                        )
                    }
                }

                // 启动时静默检查更新
                var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
                var downloadProgress by remember { mutableStateOf<Float?>(null) }
                var isDownloading by remember { mutableStateOf(false) }
                var downloadFailed by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val mainContext = LocalContext.current
                val currentVersionName = remember {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            mainContext.packageManager.getPackageInfo(
                                mainContext.packageName,
                                PackageManager.PackageInfoFlags.of(0),
                            ).versionName
                        } else {
                            @Suppress("DEPRECATION")
                            mainContext.packageManager.getPackageInfo(mainContext.packageName, 0).versionName
                        }
                    } catch (_: Exception) {
                        null
                    } ?: "0.0.0"
                }

                LaunchedEffect(onboarding.completed) {
                    if (!onboarding.completed) return@LaunchedEffect
                    val autoCheck = updatePreferences.autoCheckUpdates.first()
                    if (!autoCheck) return@LaunchedEffect
                    // P0-1 冷却：同 versionCode 用户已「稍后再说」→ 本次不弹；上次自动检查 <6h → 本次跳过
                    val dismissedCode = updatePreferences.dismissedUpdateVersionCode.first()
                    val lastCheckMs = updatePreferences.lastUpdateCheckTimeMs.first()
                    val nowMs = System.currentTimeMillis()
                    val coolingDown = nowMs - lastCheckMs < top.wanxiang.app.core.datastore.UpdatePreferences.UPDATE_AUTO_CHECK_COOLDOWN_MS
                    if (coolingDown) return@LaunchedEffect
                    updatePreferences.setLastUpdateCheckTime(nowMs)
                    val res = appUpdateManager.checkUpdateMerged(currentVersionName)
                    res.onSuccess { info ->
                        if (info.hasUpdate && info.versionCode != dismissedCode) {
                            // P0-2 忙碌延后：延迟 3 秒，避开用户冷启后立刻输入或操作的窗口
                            kotlinx.coroutines.delay(3_000L)
                            updateInfo = info
                        }
                    }
                }

                updateInfo?.let { info ->
                    RuntimeAlertDialog(
                        onDismissRequest = {
                            if (!isDownloading && !info.forceUpdate) {
                                updateInfo = null
                                scope.launch { updatePreferences.setDismissedUpdateVersionCode(info.versionCode) }
                            }
                        },
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                RuntimeIcon(
                                    name = RuntimeIconName.Refresh,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(stringResource(R.string.wanxiang_update_available, info.latestVersion), fontWeight = FontWeight.Bold)
                            }
                        },
                        text = {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.wanxiang_update_versions, info.currentVersion, info.latestVersion),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                                if (info.releaseNotes.isNotBlank()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            text = info.releaseNotes,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(12.dp),
                                        )
                                    }
                                }
                                if (isDownloading) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(stringResource(R.string.wanxiang_update_downloading_package), style = MaterialTheme.typography.labelMedium)
                                        if (downloadProgress != null) {
                                            LinearProgressIndicator(
                                                progress = { downloadProgress ?: 0f },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        } else {
                                            LinearProgressIndicator(
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }
                                }
                                if (downloadFailed) {
                                    // 下载失败必须可见：不再静默停止
                                    Text(
                                        text = stringResource(R.string.wanxiang_update_download_failed),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            val apkUrl = info.apkDownloadUrl
                            if (apkUrl != null) {
                                Button(
                                    onClick = {
                                        isDownloading = true
                                        downloadProgress = 0f
                                        downloadFailed = false
                                        scope.launch {
                                            val res = appUpdateManager.downloadApk(apkUrl) { dl, tot ->
                                                if (tot != null && tot > 0) downloadProgress = dl.toFloat() / tot.toFloat()
                                            }
                                            isDownloading = false
                                            res.onSuccess { apkFile ->
                                                downloadProgress = 1f
                                                appUpdateManager.installApk(apkFile)
                                                updateInfo = null
                                            }.onFailure {
                                                // 下载失败：在更新对话框内给出可见的错误文案（跟随现有 UI 模式）
                                                downloadFailed = true
                                            }
                                        }
                                    },
                                    enabled = !isDownloading,
                                ) {
                                    Text(stringResource(if (isDownloading) R.string.wanxiang_downloading else R.string.wanxiang_update_now))
                                }
                            } else {
                                Button(
                                    onClick = {
                                        runCatching {
                                            startActivity(Intent(Intent.ACTION_VIEW,
                                                info.releaseUrl.toUri()))
                                        }
                                        updateInfo = null
                                    },
                                ) {
                                    Text(stringResource(R.string.wanxiang_open_github))
                                }
                            }
                        },
                        dismissButton = {
                            // 强制更新时不显示「稍后再说」，用户只能去下载
                            if (!info.forceUpdate) {
                                TextButton(
                                    onClick = {
                                        updateInfo = null
                                        // P0-1：记下这个 versionCode 已被用户 dismiss，下次同版本不再弹
                                        scope.launch { updatePreferences.setDismissedUpdateVersionCode(info.versionCode) }
                                    },
                                    enabled = !isDownloading,
                                ) {
                                    Text(stringResource(R.string.wanxiang_later))
                                }
                            }
                        },
                    )
                }

                when {
                    !onboarding.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    onboarding.completed -> WanXiangNavHost(globalNavigationBus = globalNavigationBus)
                    else -> OnboardingScreen(onboardingViewModel)
                }
                // 全局 Git/下载 进度横幅（跨页面可见，用户切 tab 也不丢进度）
                val apkDownloadProgress by appUpdateManager.downloadProgress.collectAsStateWithLifecycle()
                apkDownloadProgress?.let { p ->
                    top.wanxiang.app.ui.common.DownloadProgressBanner(progress = p)
                }
                // 全局 git 凭据弹窗宿主：容器 helper 走文件 IPC 请求凭据时，无论在哪个页面都能立即弹出。
                top.wanxiang.app.ui.chat.GlobalCredentialDialogHost(gitCredentialIpcBridge)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 主应用回到前台时，自动关闭智枢桌面悬浮小窗，避免主界面与悬浮窗重叠
        runCatching {
            top.wanxiang.app.ui.chat.floating.FloatingChatService.stop(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(targetIntent: Intent?) {
        val intentToHandle = targetIntent ?: intent ?: return
        val action = intentToHandle.action
        val navigateTo = intentToHandle.getStringExtra("navigate_to")
        val isAdbLogcat = action == "top.wanxiang.app.action.OPEN_ADB_LOGCAT" || navigateTo == "adb_logcat"
        if (isAdbLogcat) {
            globalNavigationBus.navigateTo(top.wanxiang.app.core.common.navigation.AppNavigationTarget.AdbLogcat)
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (notificationPermissionCheckScheduled) return
        notificationPermissionCheckScheduled = true

        // Runtime permission dialogs are most reliable after the first page is resumed and drawn.
        // First launch also restores onboarding/theme state, so requesting from onCreate can be
        // swallowed by some Android builds before the Activity becomes fully interactive.
        window.decorView.post {
            if (!isFinishing && !isDestroyed) {
                // 首帧之后再拉起 Runtime 保活前台服务，避免 onStartCommand 抢在首帧前占用主线程。
                runtimeServiceController.start()
                requestNotificationPermissionIfNeeded()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
