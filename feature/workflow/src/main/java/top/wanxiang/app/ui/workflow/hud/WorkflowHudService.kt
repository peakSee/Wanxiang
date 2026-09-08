package top.wanxiang.app.ui.workflow.hud

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.wanxiang.app.runtime.gui.WorkflowGuiHudBridge
import top.wanxiang.app.ui.theme.WanXiangTheme

/**
 * Workflow run HUD: current step + stop. Detaches from WindowManager while
 * [WorkflowGuiHudBridge.Session.overlayVisible] is false so screen dumps skip the overlay.
 */
@AndroidEntryPoint
class WorkflowHudService : Service() {

    @Inject
    lateinit var hud: WorkflowGuiHudBridge

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: WorkflowHudLifecycleOwner? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var attached = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
            stopSelf()
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * resources.displayMetrics.density).toInt()
        }
        windowParams = params

        val owner = WorkflowHudLifecycleOwner()
        lifecycleOwner = owner
        owner.onCreate()

        val view = ComposeView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            owner.attach(this)
            setContent {
                WanXiangTheme {
                    val session by hud.session.collectAsState()
                    val current = session
                    LaunchedEffect(current?.active, current?.phase) {
                        if (current != null && !current.active) {
                            delay(12_000)
                            if (hud.session.value?.executionId == current.executionId &&
                                hud.session.value?.active == false
                            ) {
                                hud.dismiss()
                                stopSelf()
                            }
                        }
                    }
                    if (current != null) {
                        WorkflowHudOverlay(
                            session = current,
                            onStop = { hud.requestStop() },
                            onDismiss = {
                                hud.dismiss()
                                stopSelf()
                            },
                        )
                    }
                }
            }
        }
        composeView = view

        serviceScope.launch {
            hud.session.collect { session ->
                if (session == null) {
                    stopSelf()
                    return@collect
                }
                val shouldShow = when {
                    !session.active -> true
                    else -> session.overlayVisible
                }
                if (shouldShow) attachOverlay() else detachOverlay()
            }
        }
    }

    private fun attachOverlay() {
        val wm = windowManager ?: return
        val view = composeView ?: return
        val params = windowParams ?: return
        if (attached) return
        runCatching {
            wm.addView(view, params)
            lifecycleOwner?.onStart()
            attached = true
        }
    }

    private fun detachOverlay() {
        val wm = windowManager ?: return
        val view = composeView ?: return
        if (!attached) return
        runCatching {
            lifecycleOwner?.onStop()
            wm.removeView(view)
        }
        attached = false
    }

    override fun onDestroy() {
        super.onDestroy()
        detachOverlay()
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        composeView = null
        windowManager = null
        serviceScope.cancel()
    }

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, WorkflowHudService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WorkflowHudService::class.java))
        }
    }
}
