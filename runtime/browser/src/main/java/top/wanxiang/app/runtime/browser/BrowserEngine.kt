package top.wanxiang.app.runtime.browser

import top.wanxiang.app.core.browser.BrowserFamily
import top.wanxiang.app.core.browser.BrowserPreferences
import top.wanxiang.app.core.browser.PageSnapshot
import top.wanxiang.app.core.model.ToolImageRef
import top.wanxiang.app.runtime.browser.cdp.DebugBreakpoint
import top.wanxiang.app.runtime.browser.cdp.DebugStep
import top.wanxiang.app.runtime.browser.hook.HookRule
import top.wanxiang.app.runtime.browser.hook.HookRuleInfo
import top.wanxiang.app.runtime.browser.hook.InjectedScript
import android.webkit.WebView

/**
 * 浏览器引擎的统一操作接口。一类引擎（in-app WebView / External Chrome CT / Remote CDP）对应一个实现。
 *
 * 设计要点：
 * - 所有动作都是 suspend，**不**阻塞主线程；UI 只通过 StateFlow 订阅结果；
 * - `navigate / click / type / press` 等依赖 ref；模型给出 ref + 真实 selector 由 [runtime.browser.snapshot.RefResolver] 翻译；
 * - `click/type/press` 接收的 `refResolver` 回调用于回查 ref → selector；RefResolver 由宿主注入。
 */
interface BrowserEngine {
    val descriptor: top.wanxiang.app.core.browser.BrowserDescriptor
    val eventBus: BrowserEventBus
    val family: BrowserFamily get() = descriptor.family

    /** New or activate a tab; returns the resulting token. */
    suspend fun openTab(url: String? = null, activate: Boolean = true): BrowserSessionToken
    suspend fun navigate(tab: BrowserSessionToken, url: String)
    suspend fun snapshot(tab: BrowserSessionToken, maxElements: Int = 200): PageSnapshot
    suspend fun click(
        tab: BrowserSessionToken,
        ref: String,
        refSelectorLookup: suspend (tab: BrowserSessionToken, ref: String) -> String?,
    ): Boolean
    suspend fun typeInto(
        tab: BrowserSessionToken,
        ref: String,
        text: String,
        refSelectorLookup: suspend (tab: BrowserSessionToken, ref: String) -> String?,
    ): Boolean
    suspend fun press(
        tab: BrowserSessionToken,
        ref: String?,
        key: String,
        refSelectorLookup: suspend (tab: BrowserSessionToken, ref: String) -> String?,
    ): Boolean
    suspend fun scroll(
        tab: BrowserSessionToken,
        deltaY: Int,
    ): Boolean
    suspend fun screenshot(tab: BrowserSessionToken, prefs: BrowserPreferences): ToolImageRef?
    suspend fun evaluate(tab: BrowserSessionToken, script: String): String?
    suspend fun pageSource(tab: BrowserSessionToken, maxBytes: Int = 60_000): String
    suspend fun back(tab: BrowserSessionToken): Boolean
    suspend fun forward(tab: BrowserSessionToken): Boolean
    suspend fun refresh(tab: BrowserSessionToken)
    suspend fun closeTab(tab: BrowserSessionToken)
    suspend fun listTabs(): List<BrowserSessionToken>
    fun activeTab(): BrowserSessionToken?
    suspend fun setActiveTab(tab: BrowserSessionToken)

    // Storage（均按指定 tab 操作，避免多 tab 时误操作 activeTab）
    suspend fun cookiesGet(tab: BrowserSessionToken, url: String?): String
    suspend fun cookiesSet(tab: BrowserSessionToken, url: String, headerLine: String)
    suspend fun cookiesDelete(tab: BrowserSessionToken, url: String, name: String)
    suspend fun localGet(tab: BrowserSessionToken, key: String): String?
    suspend fun localSet(tab: BrowserSessionToken, key: String, value: String)
    suspend fun localDelete(tab: BrowserSessionToken, key: String)
    suspend fun localKeys(tab: BrowserSessionToken): List<String>
    suspend fun sessionGet(tab: BrowserSessionToken, key: String): String?
    suspend fun sessionSet(tab: BrowserSessionToken, key: String, value: String)
    suspend fun sessionDelete(tab: BrowserSessionToken, key: String)
    suspend fun sessionKeys(tab: BrowserSessionToken): List<String>

    // ===== 注入式 Hook 引擎（阶段 1；默认不支持，仅 in-app 引擎实现） =====

    /** 安装 hook 规则并推送到受影响的活跃 tab。 */
    suspend fun hookInstall(rule: HookRule): HookRule =
        throw UnsupportedOperationException("hooks not supported by ${family.name}")

    suspend fun hookRemove(id: String): Boolean =
        throw UnsupportedOperationException("hooks not supported by ${family.name}")

    suspend fun hookList(tabId: String?): List<HookRuleInfo> =
        throw UnsupportedOperationException("hooks not supported by ${family.name}")

    /** 清空规则 + 持久脚本 + 命中 + body（tabId=null 时全局）。 */
    suspend fun hookReset(tabId: String?): Boolean =
        throw UnsupportedOperationException("hooks not supported by ${family.name}")

    /** 注入 JS：persistent=true 注册为持久脚本（每次导航自动重放），否则立即执行一次。 */
    suspend fun injectScript(tab: BrowserSessionToken, code: String, persistent: Boolean, name: String): String =
        throw UnsupportedOperationException("hooks not supported by ${family.name}")

    /** 查询单条已捕获网络请求的元数据与请求/响应 body。 */
    suspend fun networkDetail(id: String): String? =
        throw UnsupportedOperationException("hooks not supported by ${family.name}")

    // ===== CDP 调试引擎（阶段 2：真断点 + Worker 级 Fetch 拦截；默认不支持，仅 in-app 引擎实现） =====

    /** CDP attach 会话 / 断点数 / paused 状态总览。 */
    suspend fun debugStatus(): String =
        throw UnsupportedOperationException("cdp debug not supported by ${family.name}")

    /** attach tab（devtools socket 发现 + target 匹配 + 断点重放 + Fetch 拦截）。 */
    suspend fun debugAttach(tab: BrowserSessionToken): String =
        throw UnsupportedOperationException("cdp debug not supported by ${family.name}")

    /** detach；tab 省略 = 全部。返回 detach 数量。 */
    suspend fun debugDetach(tab: BrowserSessionToken?): Int =
        throw UnsupportedOperationException("cdp debug not supported by ${family.name}")

    /** 设置断点（line/column 0-based，与 CDP 原生一致）。 */
    suspend fun debugSetBreakpoint(tab: BrowserSessionToken, url: String, line: Int, column: Int, condition: String?): DebugBreakpoint =
        throw UnsupportedOperationException("cdp debug not supported by ${family.name}")

    suspend fun debugRemoveBreakpoint(tab: BrowserSessionToken, id: String): Boolean =
        throw UnsupportedOperationException("cdp debug not supported by ${family.name}")

    suspend fun debugListBreakpoints(tab: BrowserSessionToken): List<DebugBreakpoint> =
        throw UnsupportedOperationException("cdp debug not supported by ${family.name}")

    /** 恢复执行；tab 省略 = 恢复全部 paused 的 tab。返回恢复数。 */
    suspend fun debugResume(tab: BrowserSessionToken?): Int =
        throw UnsupportedOperationException("cdp debug not supported by ${family.name}")

    suspend fun debugStep(tab: BrowserSessionToken, step: DebugStep): Boolean =
        throw UnsupportedOperationException("cdp debug not supported by ${family.name}")

    /** paused 调用栈（帧/行号/作用域类型）或 "running"。 */
    suspend fun debugState(tab: BrowserSessionToken): String =
        throw UnsupportedOperationException("cdp debug not supported by ${family.name}")

    /** 在暂停帧上求值（returnByValue；超长截断）。 */
    suspend fun debugEval(tab: BrowserSessionToken, frame: Int, expression: String): String =
        throw UnsupportedOperationException("cdp debug not supported by ${family.name}")

    /** 读作用域变量；scope=null 时输出全部作用域摘要。 */
    suspend fun debugScope(tab: BrowserSessionToken, frame: Int, scope: Int?): String =
        throw UnsupportedOperationException("cdp debug not supported by ${family.name}")

    suspend fun shutdown()
}

/** Implemented only by an engine whose live view can be embedded in the Compose browser screen. */
interface InAppBrowserViewProvider {
    fun activeWebView(): WebView?
}

