package top.wanxiang.app.runtime.browser.di

/**
 * Browser 引擎注册入口的兼容占位类。
 *
 * 实际初始化已迁移到 harness 层 [top.wanxiang.app.harness.browser.BrowserMcpBootstrap]，
 * AppScope 协程里调用其 bootstrap() 即可。这里仅保留以避免破坏既有插件脚本引用。
 */
class BrowserInitializer {
    /** 由调用方调用，启动浏览器引擎。 */
    fun noop() = Unit
}
