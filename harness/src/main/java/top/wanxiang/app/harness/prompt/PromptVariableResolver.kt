package top.wanxiang.app.harness.prompt

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 提示词动态宏变量解析引擎。
 * 支持在自定义系统提示词中嵌入 {{cur_datetime}}、{{device_info}} 等动态占位符。
 */
object PromptVariableResolver {

    fun resolve(
        template: String,
        context: Context,
        modelId: String = "",
        modelName: String = "",
        charName: String = "万象智枢",
        userName: String = "用户",
    ): String {
        if (!template.contains("{{")) return template

        val now = Date()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        val curDate = dateFormat.format(now)
        val curTime = timeFormat.format(now)
        val curDateTime = dateTimeFormat.format(now)

        val locale = Locale.getDefault().toLanguageTag()
        val timezone = TimeZone.getDefault().id
        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val systemVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val batteryLevel = getBatteryLevel(context)

        return template
            .replace("{{cur_date}}", curDate)
            .replace("{{cur_time}}", curTime)
            .replace("{{cur_datetime}}", curDateTime)
            .replace("{{model_id}}", modelId.ifBlank { "default-model" })
            .replace("{{model_name}}", modelName.ifBlank { modelId.ifBlank { "WanXiang Model" } })
            .replace("{{locale}}", locale)
            .replace("{{timezone}}", timezone)
            .replace("{{device_info}}", deviceInfo)
            .replace("{{system_version}}", systemVersion)
            .replace("{{battery_level}}", batteryLevel)
            .replace("{{nickname}}", userName)
            .replace("{{user}}", userName)
            .replace("{{char}}", charName)
    }

    private fun getBatteryLevel(context: Context): String {
        return runCatching {
            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                "${(level * 100 / scale.toFloat()).toInt()}%"
            } else "100%"
        }.getOrDefault("100%")
    }
}
