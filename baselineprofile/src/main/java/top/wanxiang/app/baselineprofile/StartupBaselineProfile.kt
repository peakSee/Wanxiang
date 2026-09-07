package top.wanxiang.app.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

/**
 * Baseline Profile / Startup Profile 采集器。
 *
 * 目标：把冷启动路径（Application.onCreate → Activity → 首屏 Compose 组合）涉及的
 * 类与方法纳入安装时预编译范围，消除首次启动的 JIT 编译开销。
 *
 * 采集场景保持保守稳健——只做 launch + 短暂交互，避免 UI 抖动导致生成失败：
 * 1. 冷启动主 Activity（覆盖 Application/Hilt/Compose/首页组合的全部热点）
 * 2. 主界面轻量滑动一屏，覆盖首帧滚动路径
 *
 * 运行方式见 docs/BASELINE_PROFILE.md；产物 app/src/main/baseline-prof.txt 随库提交。
 */
class StartupBaselineProfile {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateStartupProfile() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()

        // 轻量滑动一次首页列表，让 LazyColumn 的首帧测量/布局类进入 profile。
        // 不做深导航：底部 Tab 文案与入口可能随版本调整，点击失败会让采集中断。
        swipeAcrossCenter()
        device.waitForIdle()
    }

    /** 以屏幕中央为基准做一次垂直 swipe，兼容不同分辨率。 */
    private fun MacrobenchmarkScope.swipeAcrossCenter() {
        val startX = device.displayWidth / 2
        device.swipe(
            startX, (device.displayHeight * 0.7).toInt(),
            startX, (device.displayHeight * 0.3).toInt(),
            /* steps = */ 20,
        )
    }

    private companion object {
        /**
         * 生成流程使用 nonMinified 变体（继承 release 签名，applicationId 无 .debug 后缀）；
         * 此处固定填写正式包名即可同时覆盖 release 与 debug 构建。
         */
        const val PACKAGE_NAME = "top.wanxiang.app"
    }
}
