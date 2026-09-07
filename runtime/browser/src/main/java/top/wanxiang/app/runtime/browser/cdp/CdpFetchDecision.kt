package top.wanxiang.app.runtime.browser.cdp

import top.wanxiang.app.runtime.browser.hook.HookAction
import top.wanxiang.app.runtime.browser.hook.HookRule
import top.wanxiang.app.runtime.browser.hook.HookType

/**
 * Fetch 域拦截决策：纯函数（规则 + 请求 → CDP 动作）。
 *
 * 镜像 hook_runtime.js 的 netDecide 语义：
 * - 遍历**所有**命中规则（type ∈ 网络类 + method 匹配 + glob 匹配），动作**合并**；
 * - 同类动作后写入的覆盖先写入的；执行优先级 block > mock > redirect > modify_headers > log；
 * - captureBody 起始取规则级值，log action 显式 true/false 覆盖；
 * - 无命中 → [FetchDecision.Pass]（立即放行，绝不挂起请求）。
 *
 * tab 作用域（scopeTabId）由调用方 rulesFor(tabId) 预先过滤，本函数不管。
 */
sealed interface FetchDecision {
    data object Pass : FetchDecision
    data class Log(val ruleId: String, val captureBody: Boolean) : FetchDecision
    data class Block(val ruleId: String) : FetchDecision
    data class Redirect(val ruleId: String, val url: String) : FetchDecision
    data class Mock(
        val ruleId: String,
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
    ) : FetchDecision

    /** CDP 层只支持请求头改写（响应头改写需原始 body，仍由注入层承担）。 */
    data class ModifyRequestHeaders(
        val ruleId: String,
        val headers: Map<String, String>,
        val captureBody: Boolean,
    ) : FetchDecision

    /** 决策附带的执行语义（供 actionTaken 记录）。 */
    fun actionTaken(): String = when (this) {
        is Block -> "block"
        is Redirect -> "redirect"
        is Mock -> "mock"
        is ModifyRequestHeaders -> "modify_headers"
        is Log -> "log"
        FetchDecision.Pass -> "pass"
    }
}

object CdpFetchDecision {

    private val NETWORK_TYPES = setOf(HookType.FETCH, HookType.XHR, HookType.WEBSOCKET)

    fun decide(rules: List<HookRule>, url: String, method: String): FetchDecision {
        var block: HookAction.Block? = null
        var mock: HookAction.Mock? = null
        var redirect: HookAction.Redirect? = null
        var modify: HookAction.ModifyHeaders? = null
        var log = false
        var captureBody = false
        var firstRuleId = ""

        for (rule in rules) {
            if (!rule.enabled || rule.type !in NETWORK_TYPES) continue
            if (!methodOk(rule.method, method)) continue
            if (!CdpGlob.matches(rule.target, url)) continue
            if (firstRuleId.isEmpty()) {
                firstRuleId = rule.id
                captureBody = rule.captureBody
            }
            for (action in rule.actions) {
                when (action) {
                    is HookAction.Block -> block = action
                    is HookAction.Mock -> mock = action
                    is HookAction.Redirect -> redirect = action
                    is HookAction.ModifyHeaders -> modify = action
                    is HookAction.Log -> {
                        log = true
                        action.captureBody?.let { captureBody = it }
                    }
                    else -> Unit // replace/fake_value 不适用于网络层
                }
            }
        }
        if (firstRuleId.isEmpty()) return FetchDecision.Pass
        // 优先级：block > mock > redirect > modify_headers > log
        return when {
            block != null -> FetchDecision.Block(firstRuleId)
            mock != null -> FetchDecision.Mock(firstRuleId, mock.status, mock.headers, mock.body)
            redirect != null -> FetchDecision.Redirect(firstRuleId, redirect.url)
            modify != null -> FetchDecision.ModifyRequestHeaders(
                firstRuleId,
                modify.request,
                captureBody,
            )
            log -> FetchDecision.Log(firstRuleId, captureBody)
            else -> FetchDecision.Pass.also { /* 有命中但无网络类动作（如仅 replace） */ }
        }
    }

    /** Fetch.enable 的 patterns：网络类规则 target 去重透传（CDP urlPattern 同为 * ? 语义）。 */
    fun fetchPatterns(rules: List<HookRule>): List<String> =
        rules.asSequence()
            .filter { it.enabled && it.type in NETWORK_TYPES }
            .map { it.target }
            .distinct()
            .toList()

    private fun methodOk(ruleMethod: String, method: String): Boolean =
        ruleMethod == "*" || ruleMethod.equals(method, ignoreCase = true)
}
