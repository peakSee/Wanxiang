package top.wanxiang.app.runtime.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuntimeServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun start() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, RuntimeForegroundService::class.java),
        )
    }

    fun stop() {
        context.startService(
            Intent(context, RuntimeForegroundService::class.java)
                .setAction(RuntimeForegroundService.ACTION_STOP),
        )
    }
}
