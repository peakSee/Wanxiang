package top.wanxiang.app.runtime.browser.hook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Hook 引擎数据模型（阶段 1：注入式）。
 *
 * 规则由 agent 经 `browser.hook_create` 创建，序列化为 JSON payload 推送到页内
 * [assets/hook_runtime.js]；命中与网络事件经 WanxiangBridge 回传。
 */
@Serializable
enum class HookType {
    /** target = URL glob（支持 `*` `?` 通配，如 api 下的任意路径、任意子域） */
    FETCH,
    /** target = URL glob（同 FETCH） */
    XHR,
    /** target = URL glob（同 FETCH） */
    WEBSOCKET,
    /** target = 函数路径（如 `JSON.parse`、`window.eval`） */
    FUNCTION,
    /** target = 对象.方法 路径（语义同 FUNCTION，保留别名便于 agent 表达） */
    METHOD,
    /** target = 属性路径（如 `document.cookie`、`navigator.userAgent`） */
    PROPERTY,
    /** target = `local` / `session` */
    STORAGE,
    /** target = `*` 或 cookie 名 */
    COOKIE,
    /** target = level glob（`*` / `error` / `log,warn`） */
    CONSOLE,
    /** target = `*` / `setInterval` / `setTimeout` */
    TIMER,
    /** target = `*` / `random` / `uuid` 等 */
    CRYPTO
}

/** Hook 动作。改写类动作（Redirect/Mock/ModifyHeaders）仅对 FETCH/XHR 合法。 */
@Serializable
sealed class HookAction {

    /** 记录命中（可选覆盖规则级 captureBody）。 */
    @Serializable
    @SerialName("log")
    data class Log(val captureBody: Boolean? = null) : HookAction()

    /** 阻断请求 / 调用：fetch reject、XHR abort、函数调用抛错。 */
    @Serializable
    @SerialName("block")
    class Block : HookAction()

    /** 重定向到指定 URL（仅 FETCH/XHR）。 */
    @Serializable
    @SerialName("redirect")
    data class Redirect(val url: String) : HookAction()

    /** 返回伪造响应（仅 FETCH/XHR）。 */
    @Serializable
    @SerialName("mock")
    data class Mock(
        val status: Int = 200,
        val headers: Map<String, String> = emptyMap(),
        val body: String = ""
    ) : HookAction()

    /** 改写请求/响应头；值 `"!"` 表示删除该 header（仅 FETCH/XHR）。 */
    @Serializable
    @SerialName("modify_headers")
    data class ModifyHeaders(
        val request: Map<String, String> = emptyMap(),
        val response: Map<String, String> = emptyMap()
    ) : HookAction()

    /** 用 code 替换函数实现（仅 FUNCTION/METHOD；code 将以 `(function(){...})` 求值，arguments/this 可用）。 */
    @Serializable
    @SerialName("replace")
    data class Replace(val code: String) : HookAction()

    /** 伪造属性 getter 返回值（仅 PROPERTY）。 */
    @Serializable
    @SerialName("fake_value")
    data class FakeValue(val value: String) : HookAction()
}

/**
 * 一条 hook 规则。[target] 语义随 [HookType] 变化（见 HookType 文档）。
 * [scopeTabId] 为 null 表示全局（所有 tab）。
 */
@Serializable
data class HookRule(
    val id: String,
    val type: HookType,
    val target: String,
    val name: String = "",
    val scopeTabId: String? = null,
    val method: String = "*",
    val actions: List<HookAction> = listOf(HookAction.Log()),
    val enabled: Boolean = true,
    val captureStack: Boolean = false,
    val captureBody: Boolean = false,
    val maxHits: Int = 1000,
)

/** hook 命中记录（事件总线环内）。 */
@Serializable
data class HookHitRecord(
    val tabId: String,
    val hookId: String,
    val type: HookType,
    val target: String,
    val phase: String,
    val summary: String,
    val detailJson: String = "",
    val at: Long = System.currentTimeMillis(),
)

/** hook_list 输出项：规则 + 命中计数。 */
data class HookRuleInfo(
    val rule: HookRule,
    val hitCount: Int,
)

/** 注入式 hook 引擎的持久脚本（persistent=true 的 inject_script）。 */
@Serializable
data class InjectedScript(
    val id: String,
    val name: String,
    val code: String,
    val scopeTabId: String? = null,
)

/** pushRules payload 的 envelope（发往页内 `window.__wanxiangApplyRules(<payload>)`）。 */
@Serializable
data class HookRulesPayload(
    val v: Int = 1,
    val config: PayloadConfig,
    val rules: List<HookRule>,
    val scripts: List<InjectedScript> = emptyList(),
) {
    @Serializable
    data class PayloadConfig(val maxBodyBytes: Int)
}

/** 按类型校验规则；返回错误消息（null = 合法）。错误原文透传给 agent。 */
fun HookRule.validate(): String? {
    if (id.isBlank()) return "hook id 不能为空"
    if (target.isBlank()) return "target 不能为空"
    if (target.length > 512) return "target 过长（>512 字符）"
    if (maxHits !in 1..100_000) return "maxHits 必须在 1..100000"
    val isNet = type == HookType.FETCH || type == HookType.XHR
    actions.forEach { action ->
        val bad = when (action) {
            is HookAction.Redirect -> !isNet
            is HookAction.Mock -> !isNet
            is HookAction.ModifyHeaders -> !isNet
            is HookAction.Replace -> type != HookType.FUNCTION && type != HookType.METHOD
            is HookAction.FakeValue -> type != HookType.PROPERTY
            else -> false
        }
        if (bad) return "动作 ${action::class.simpleName} 不适用于 type=$type"
    }
    if (type == HookType.FETCH || type == HookType.XHR || type == HookType.WEBSOCKET) {
        if (!isValidGlob(target)) return "target 不是合法 URL glob：$target（仅允许 * 与 ? 通配）"
    }
    if (type == HookType.FUNCTION || type == HookType.METHOD || type == HookType.PROPERTY) {
        if (!isValidPath(target)) return "target 不是合法对象路径：$target（如 JSON.parse / document.cookie）"
    }
    return null
}

/** glob：仅允许 URL 安全字符 + `*` `?`。 */
private fun isValidGlob(s: String): Boolean =
    s.matches(Regex("^[A-Za-z0-9._~:/?#@!$&'()*+,;=%\\[\\]-]{1,512}$"))

/** 对象路径：点分段，每段为合法标识符或 `*`。 */
private fun isValidPath(s: String): Boolean =
    s.split('.').all { it.matches(Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")) || it == "*" } &&
        s.length <= 512
