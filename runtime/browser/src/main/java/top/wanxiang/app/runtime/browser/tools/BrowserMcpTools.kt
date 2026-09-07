package top.wanxiang.app.runtime.browser.tools

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import top.wanxiang.app.core.browser.BrowserCapability
import top.wanxiang.app.core.browser.BrowserPreferences
import top.wanxiang.app.core.browser.BrowserRisk
import top.wanxiang.app.core.model.ToolImageRef
import top.wanxiang.app.runtime.browser.BrowserEngine
import top.wanxiang.app.runtime.browser.BrowserSessionToken
import top.wanxiang.app.runtime.browser.cdp.DebugStep
import top.wanxiang.app.runtime.browser.hook.HookAction
import top.wanxiang.app.runtime.browser.hook.HookRule
import top.wanxiang.app.runtime.browser.hook.HookType
import top.wanxiang.app.runtime.browser.hook.validate

/**
 * mcp__browser__* 工具调度器。
 *
 * `tools/list` 的描述由 [list] 提供；`tools/call` 的入参由 [invoke] 翻译成 [BrowserEngine] 方法。
 *
 * 注：所有调用都是 `suspend`，由调用方在自己的协程里 await；浏览器能力列表由 [BrowserEngine.descriptor.capabilities] 预先判定。
 */
class BrowserMcpTools(
    private val engines: List<BrowserEngine>,
    private val engineSelector: (BrowserSessionToken) -> BrowserEngine?,
    private val prefs: BrowserPreferences,
) {
    /** hook 动作数组的解码器：sealed 多态，判别字段 "type"（与 HookRuleStore 的 Json 配置一致）。 */
    private val hookJson = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    /**
     * provider 可见工具清单。与 invoke 侧门禁（allowEvalJs / allowHooks / allowCdp）同源：
     * 被关闭的能力对应工具不再注入模型——模型看不到就不会调用，省掉每轮请求重复携带的
     * schema tokens；若模型凭历史轮次调用已隐藏工具，invoke 门禁仍会拦截并给出开启指引。
     * 门禁组合：hook_* 需 allowHooks 或 allowCdp（CDP Fetch 拦截同样要建规则）；
     * inject_script 仅 allowHooks；debug_* 仅 allowCdp；evaluate 仅 allowEvalJs；
     * network_detail 无门禁（hooks 关闭时 body 天然不存在，仅元数据可见）。
     */
    fun list(): List<ToolSpec> = TOOLS.filter { spec ->
        when {
            spec.name.startsWith("browser.debug_") -> prefs.allowCdp
            spec.name.startsWith("browser.hook_") -> prefs.allowHooks || prefs.allowCdp
            spec.name == "browser.inject_script" -> prefs.allowHooks
            spec.name == "browser.evaluate" -> prefs.allowEvalJs
            else -> true
        }
    }

    suspend fun invoke(toolName: String, args: JsonObject): InvokeResult {
        val spec = TOOLS.firstOrNull { it.name == toolName }
            ?: return InvokeResult.error("未知工具：$toolName")
        val engine = pickEngine(explicitTokenOf(args)) ?: return InvokeResult.error("无浏览器引擎")
        return when (toolName) {
            "browser.open" -> {
                val url = sanitizeUrl(args["url"]?.asString()).ifEmpty { prefs.homeUrl }
                val token = engine.openTab(url)
                InvokeResult.okMessage("opened tab=${token.tabId} url=${url}")
            }
            "browser.navigate" -> {
                val url = sanitizeUrl(args["url"]?.asString())
                if (url.isBlank()) InvokeResult.error("缺少 url 参数") else {
                engine.navigate(tokenOf(args, engine), url); InvokeResult.okMessage("navigated=$url")
                }
            }
            "browser.back" -> InvokeResult.okMessage(if (engine.back(tokenOf(args, engine))) "ok" else "noop")
            "browser.forward" -> InvokeResult.okMessage(if (engine.forward(tokenOf(args, engine))) "ok" else "noop")
            "browser.refresh" -> { engine.refresh(tokenOf(args, engine)); InvokeResult.okMessage("refreshed") }
            "browser.list_tabs" -> InvokeResult.okMessage(
                engine.listTabs().joinToString("\n") { "${it.tabId}\t${it.url}\t${it.title}" }
            )
            "browser.close_tab" -> { engine.closeTab(tokenOf(args, engine)); InvokeResult.okMessage("closed") }
            "browser.snapshot" -> handleSnapshot(engine, args)
            "browser.click" -> handleWithRef(engine, args, ::click)
            "browser.type" -> handleType(engine, args)
            "browser.press" -> handlePress(engine, args)
            "browser.scroll" -> {
                val deltaY = args["deltaY"]?.asInt() ?: 300
                InvokeResult.okMessage(if (engine.scroll(tokenOf(args, engine), deltaY)) "scrolled=$deltaY" else "noop")
            }
            "browser.screenshot" -> InvokeResult.okImage("", engine.screenshot(tokenOf(args, engine), prefs))
            "browser.current_url" -> {
            val t = tokenOf(args, engine)
            InvokeResult.okMessage(engine.eventBus.urlOf(t.tabId) ?: t.url)
        }
            "browser.title" -> {
            val t = tokenOf(args, engine)
            InvokeResult.okMessage(engine.eventBus.titleOf(t.tabId) ?: t.title)
        }
            "browser.page_source" -> {
                val max = args["maxBytes"]?.asInt() ?: 60_000
                InvokeResult.okMessage(engine.pageSource(tokenOf(args, engine), max))
            }
            "browser.evaluate" -> {
                val exp = args["expression"]?.asString().orEmpty()
                when {
                    // 安全门禁：allowEvalJs 默认 false，未显式开启时不执行任何脚本
                    !prefs.allowEvalJs -> InvokeResult.error("evaluate 被禁用（allowEvalJs=false），请在浏览器设置中开启")
                    exp.isEmpty() -> InvokeResult.error("缺少 expression")
                    else -> engine.evaluate(tokenOf(args, engine), exp)?.let { InvokeResult.okMessage(it) }
                        // 引擎侧 evaluate 超时/失败返回 null：不能再伪装成成功（空串）
                        ?: InvokeResult.error("evaluation failed or timed out")
                }
            }
            "browser.console_list" -> InvokeResult.okMessage(
                engine.eventBus.console.value.joinToString("\n") { "[${it.level}] ${it.message}" }
            )
            "browser.console_clear" -> { engine.eventBus.clearConsole(); InvokeResult.okMessage("ok") }
            "browser.network_list" -> InvokeResult.okMessage(
                engine.eventBus.network.value.joinToString("\n") { r ->
                    buildString {
                        append("[${r.source}] [${r.method}] ${r.url} (${r.statusCode}, ${r.durationMs}ms")
                        if (r.requestSize > 0 || r.responseSize > 0) {
                            append(", req ${fmtBytes(r.requestSize)} res ${fmtBytes(r.responseSize)}")
                        }
                        if (r.ruleId.isNotBlank()) append(", rule=${r.ruleId}, action=${r.actionTaken}")
                        append(") id=${r.id}")
                    }
                }.ifEmpty { "no captured requests" }
            )
            "browser.cookies_get" -> {
            val t = tokenOf(args, engine)
            InvokeResult.okMessage(engine.cookiesGet(t, args["url"]?.asString()))
        }
            "browser.cookies_set" -> {
            val t = tokenOf(args, engine)
            engine.cookiesSet(t, args["url"]?.asString().orEmpty(), args["header"]?.asString().orEmpty())
            InvokeResult.okMessage("ok")
        }
            "browser.cookies_delete" -> {
            val t = tokenOf(args, engine)
            engine.cookiesDelete(t, args["url"]?.asString().orEmpty(), args["name"]?.asString().orEmpty())
            InvokeResult.okMessage("ok")
        }
            "browser.local_get", "browser.session_get" -> handleKvGet(engine, args, toolName.startsWith("browser.session"))
            "browser.local_set", "browser.session_set" -> handleKvSet(engine, args, toolName.startsWith("browser.session"))
            "browser.local_delete", "browser.session_delete" -> handleKvDelete(engine, args, toolName.startsWith("browser.session"))
            "browser.local_keys", "browser.session_keys" -> handleKvKeys(engine, args, toolName.startsWith("browser.session"))
            // ===== 注入式 Hook 引擎（阶段 1；allowHooks||allowCdp 门禁，network_detail 除外） =====
            "browser.hook_create" -> handleHookCreate(engine, args)
            "browser.hook_list" -> handleHookList(engine, args)
            "browser.hook_remove" -> handleHookRemove(engine, args)
            "browser.hook_reset" -> handleHookReset(engine, args)
            "browser.hook_hits" -> handleHookHits(engine, args)
            "browser.inject_script" -> handleInjectScript(engine, args)
            "browser.network_detail" -> handleNetworkDetail(engine, args)
            // ===== CDP 调试引擎（阶段 2；allowCdp 门禁） =====
            "browser.debug_status" -> handleDebugStatus(engine)
            "browser.debug_attach" -> handleDebugAttach(engine, args)
            "browser.debug_detach" -> handleDebugDetach(engine, args)
            "browser.debug_set_breakpoint" -> handleDebugSetBreakpoint(engine, args)
            "browser.debug_remove_breakpoint" -> handleDebugRemoveBreakpoint(engine, args)
            "browser.debug_list_breakpoints" -> handleDebugListBreakpoints(engine, args)
            "browser.debug_resume" -> handleDebugResume(engine, args)
            "browser.debug_step" -> handleDebugStep(engine, args)
            "browser.debug_state" -> handleDebugState(engine, args)
            "browser.debug_eval" -> handleDebugEval(engine, args)
            "browser.debug_scope" -> handleDebugScope(engine, args)
            else -> InvokeResult.error("工具未实现：$toolName")
        }
    }

    private fun explicitTokenOf(args: JsonObject): BrowserSessionToken {
        val tabId = args["tab"]?.asString() ?: BrowserSessionToken.DEFAULT_TAB_ID
        return BrowserSessionToken(tabId = tabId, family = prefs.resolvedFamily)
    }

    /**
     * 解析工具调用的目标 tab token。
     * 归一化容错：模型常把 list_tabs 输出的 `t:xxx` 传成 `xxx`（丢前缀），
     * 先精确匹配再后缀匹配已存在的 tab，均未命中时保留原值让引擎报 not found。
     */
    private suspend fun tokenOf(args: JsonObject, engine: BrowserEngine): BrowserSessionToken {
        val raw = args["tab"]?.asString()
        if (raw.isNullOrBlank()) {
            return engine.activeTab() ?: BrowserSessionToken.defaultTab(engine.family)
        }
        if (raw == BrowserSessionToken.DEFAULT_TAB_ID) {
            return BrowserSessionToken(raw, engine.family)
        }
        val tabs = engine.listTabs()
        val normalized = tabs.firstOrNull { it.tabId == raw }?.tabId
            ?: tabs.firstOrNull { it.tabId.endsWith(raw) }?.tabId
            ?: raw
        return BrowserSessionToken(tabId = normalized, family = engine.family)
    }

    /** URL 清洗：模型偶发把 URL 包在反引号或首尾空白里（`https://x.test`），剥离后再交给引擎。 */
    private fun sanitizeUrl(raw: String?): String =
        raw?.trim()?.trim('`')?.trim().orEmpty()

    private fun pickEngine(token: BrowserSessionToken): BrowserEngine? {
        return engineSelector(token) ?: engines.firstOrNull()
    }

    private suspend fun handleWithRef(
        engine: BrowserEngine,
        args: JsonObject,
        action: suspend (BrowserEngine, BrowserSessionToken, String) -> Boolean,
    ): InvokeResult {
        val ref = args["ref"]?.asString().orEmpty()
        if (ref.isBlank()) return InvokeResult.error("缺少 ref")
        val ok = action(engine, tokenOf(args, engine), ref)
        return if (ok) InvokeResult.okMessage("ok=$ref") else InvokeResult.error("failed=$ref")
    }

    private suspend fun click(engine: BrowserEngine, tab: BrowserSessionToken, ref: String): Boolean =
        engine.click(tab, ref) { _, r -> "[data-wanxiang-ref='$r']" }

    private suspend fun handleType(engine: BrowserEngine, args: JsonObject): InvokeResult {
        val ref = args["ref"]?.asString().orEmpty()
        val text = args["text"]?.asString().orEmpty()
        if (ref.isBlank() || text.isEmpty()) return InvokeResult.error("缺少 ref 或 text")
        val ok = engine.typeInto(tokenOf(args, engine), ref, text) { _, r -> "[data-wanxiang-ref='$r']" }
        return if (ok) InvokeResult.okMessage("typed=${text.length}") else InvokeResult.error("type-failed=$ref")
    }

    private suspend fun handlePress(engine: BrowserEngine, args: JsonObject): InvokeResult {
        val ref = args["ref"]?.asString()
        val key = args["key"]?.asString().orEmpty()
        if (key.isEmpty()) return InvokeResult.error("缺少 key")
        val ok = engine.press(tokenOf(args, engine), ref, key) { _, r -> ref?.let { "[data-wanxiang-ref='$r']" } ?: "" }
        return if (ok) InvokeResult.okMessage("pressed=$key") else InvokeResult.error("press-failed=$key")
    }

    private suspend fun handleSnapshot(engine: BrowserEngine, args: JsonObject): InvokeResult {
        val max = args["maxElements"]?.asInt() ?: 200
        val snap = engine.snapshot(tokenOf(args, engine), max)
        val refsArray = JsonArray(snap.refs.entries.map { (ref, r) ->
            buildJsonObject {
                put("ref", JsonPrimitive(ref))
                put("tag", JsonPrimitive(r.tag))
                r.type?.let { put("type", JsonPrimitive(it)) }
                r.role?.let { put("role", JsonPrimitive(it)) }
                r.name?.let { put("name", JsonPrimitive(it)) }
                r.text?.let { put("text", JsonPrimitive(it)) }
                r.placeholder?.let { put("placeholder", JsonPrimitive(it)) }
                r.ariaLabel?.let { put("ariaLabel", JsonPrimitive(it)) }
            }
        })
        return InvokeResult.okMessage(buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("tab", JsonPrimitive(snap.tabId))
            put("url", JsonPrimitive(snap.url))
            put("title", JsonPrimitive(snap.title))
            put("domFingerprint", JsonPrimitive(snap.domFingerprint))
            put("interactiveCount", JsonPrimitive(snap.interactiveRefs.size))
            put("refs", refsArray)
        }.toString())
    }

    private suspend fun handleKvGet(engine: BrowserEngine, args: JsonObject, session: Boolean): InvokeResult {
        val key = args["key"]?.asString().orEmpty()
        if (key.isEmpty()) return InvokeResult.error("缺少 key")
        val tab = tokenOf(args, engine)
        val v = if (session) engine.sessionGet(tab, key) else engine.localGet(tab, key)
        return InvokeResult.okMessage(v ?: "")
    }

    private suspend fun handleKvSet(engine: BrowserEngine, args: JsonObject, session: Boolean): InvokeResult {
        val key = args["key"]?.asString().orEmpty()
        val v = args["value"]?.asString().orEmpty()
        if (key.isEmpty()) return InvokeResult.error("缺少 key")
        val tab = tokenOf(args, engine)
        if (session) engine.sessionSet(tab, key, v) else engine.localSet(tab, key, v)
        return InvokeResult.okMessage("ok")
    }

    private suspend fun handleKvDelete(engine: BrowserEngine, args: JsonObject, session: Boolean): InvokeResult {
        val key = args["key"]?.asString().orEmpty()
        if (key.isEmpty()) return InvokeResult.error("缺少 key")
        val tab = tokenOf(args, engine)
        if (session) engine.sessionDelete(tab, key) else engine.localDelete(tab, key)
        return InvokeResult.okMessage("ok")
    }

    private suspend fun handleKvKeys(engine: BrowserEngine, args: JsonObject, session: Boolean): InvokeResult =
        InvokeResult.okMessage(
            (if (session) engine.sessionKeys(tokenOf(args, engine)) else engine.localKeys(tokenOf(args, engine))).joinToString("\n")
        )

    // ===== hook 引擎工具实现 =====

    /**
     * 安全门禁：hook 规则工具在 allowHooks 或 allowCdp 任一开启时可用
     * （只开 allowCdp 时 CDP Fetch 拦截同样需要建规则）；默认均 false 时拒绝。
     */
    private fun hookGate(): InvokeResult? =
        if (!prefs.allowHooks && !prefs.allowCdp) {
            InvokeResult.error("hook 规则工具被禁用（allowHooks 与 allowCdp 均为 false），请在浏览器设置中开启后重启")
        } else null

    /** CDP debug 工具门禁：独立 allowCdp 开关（与 allowHooks 解耦）。 */
    private fun debugGate(): InvokeResult? =
        if (!prefs.allowCdp) {
            InvokeResult.error("debug 工具被禁用（allowCdp=false），请在浏览器设置中开启后重启")
        } else null

    /** 引擎侧 hooks/cdp 未启用（如 in-app 池开关为 false）时抛 UnsupportedOperationException → 转友好错误。 */
    private suspend fun runGatedTool(block: suspend () -> InvokeResult): InvokeResult =
        runCatching { block() }.getOrElse { e ->
            InvokeResult.error(e.message ?: "操作失败")
        }

    private suspend fun handleHookCreate(engine: BrowserEngine, args: JsonObject): InvokeResult {
        hookGate()?.let { return it }
        val typeRaw = args["type"]?.asString()
            ?: return InvokeResult.error("缺少 type（fetch/xhr/websocket/function/method/property/storage/cookie/console/timer/crypto）")
        val type = HookType.entries.firstOrNull { it.name.equals(typeRaw, ignoreCase = true) }
            ?: return InvokeResult.error("未知 hook type：$typeRaw")
        val target = args["target"]?.asString() ?: return InvokeResult.error("缺少 target")
        val actions: List<HookAction> = when (val actionsEl = args["actions"]) {
            null -> listOf(HookAction.Log())
            is JsonArray -> runCatching {
                hookJson.decodeFromJsonElement(ListSerializer(HookAction.serializer()), actionsEl)
            }.getOrElse { return InvokeResult.error("actions 解析失败：${it.message}") }
            else -> return InvokeResult.error("actions 必须是数组，如 [{\"type\":\"log\"}]")
        }
        if (actions.isEmpty()) return InvokeResult.error("actions 不能为空")
        val rule = HookRule(
            id = "hr_" + java.util.UUID.randomUUID().toString().substring(0, 8),
            type = type,
            target = target,
            name = args["name"]?.asString() ?: "",
            scopeTabId = args["tab"]?.asString(),
            method = args["method"]?.asString() ?: "*",
            actions = actions,
            enabled = args["enabled"]?.asBool() ?: true,
            captureStack = args["captureStack"]?.asBool() ?: false,
            captureBody = args["captureBody"]?.asBool() ?: false,
        )
        rule.validate()?.let { return InvokeResult.error(it) }
        return runGatedTool {
            engine.hookInstall(rule)
            InvokeResult.okMessage(
                "hook installed: ${rule.id} type=${rule.type} target=${rule.target} actions=${actions.joinToString(",") { it::class.simpleName ?: "?" }}"
            )
        }
    }

    private suspend fun handleHookList(engine: BrowserEngine, args: JsonObject): InvokeResult {
        hookGate()?.let { return it }
        return runGatedTool {
            val rules = engine.hookList(args["tab"]?.asString())
            InvokeResult.okMessage(
                if (rules.isEmpty()) "no hooks"
                else rules.joinToString("\n") {
                    "${it.rule.id}\t${it.rule.type}\t${it.rule.target}\tenabled=${it.rule.enabled}\thits=${it.hitCount}"
                }
            )
        }
    }

    private suspend fun handleHookRemove(engine: BrowserEngine, args: JsonObject): InvokeResult {
        hookGate()?.let { return it }
        val id = args["id"]?.asString() ?: return InvokeResult.error("缺少 id")
        return runGatedTool {
            if (engine.hookRemove(id)) InvokeResult.okMessage("hook removed: $id")
            else InvokeResult.error("hook not found: $id")
        }
    }

    private suspend fun handleHookReset(engine: BrowserEngine, args: JsonObject): InvokeResult {
        hookGate()?.let { return it }
        val tabId = args["tab"]?.asString()
        return runGatedTool {
            engine.hookReset(tabId)
            InvokeResult.okMessage("hooks reset" + (tabId?.let { " (tab=$it)" } ?: " (all)"))
        }
    }

    private suspend fun handleHookHits(engine: BrowserEngine, args: JsonObject): InvokeResult {
        hookGate()?.let { return it }
        val tabId = args["tab"]?.asString()
        val limit = (args["limit"]?.asInt() ?: 50).coerceIn(1, 200)
        val hits = engine.eventBus.hookHits.value
            .filter { tabId == null || it.tabId == tabId }
            .takeLast(limit)
        return InvokeResult.okMessage(
            if (hits.isEmpty()) "no hook hits"
            else hits.joinToString("\n") {
                "[${it.at}] ${it.tabId} ${it.hookId} ${it.type} ${it.target} ${it.phase} — ${it.summary}"
            }
        )
    }

    private suspend fun handleInjectScript(engine: BrowserEngine, args: JsonObject): InvokeResult {
        // inject_script 是页内注入，维持 allowHooks-only（CDP 开关不包含注入能力）
        if (!prefs.allowHooks) {
            return InvokeResult.error("inject_script 被禁用（allowHooks=false），请在浏览器设置中开启后重启")
        }
        val code = args["code"]?.asString() ?: return InvokeResult.error("缺少 code")
        if (code.isBlank()) return InvokeResult.error("code 不能为空")
        val persistent = args["persistent"]?.asBool() ?: false
        val name = args["name"]?.asString() ?: ""
        return runGatedTool {
            InvokeResult.okMessage(engine.injectScript(tokenOf(args, engine), code, persistent, name))
        }
    }

    /** 不受 allowHooks 门禁：hooks 关闭时 body 天然不存在，仅元数据可见。 */
    private suspend fun handleNetworkDetail(engine: BrowserEngine, args: JsonObject): InvokeResult {
        val id = args["id"]?.asString() ?: return InvokeResult.error("缺少 id")
        return runGatedTool {
            engine.networkDetail(id)?.let { InvokeResult.okMessage(it) }
                ?: InvokeResult.error("network request not found: $id（id 来自 browser.network_list 输出末尾）")
        }
    }

    // ===== CDP debug 工具实现 =====

    private suspend fun handleDebugStatus(engine: BrowserEngine): InvokeResult {
        debugGate()?.let { return it }
        return runGatedTool { InvokeResult.okMessage(engine.debugStatus()) }
    }

    private suspend fun handleDebugAttach(engine: BrowserEngine, args: JsonObject): InvokeResult {
        debugGate()?.let { return it }
        return runGatedTool { InvokeResult.okMessage(engine.debugAttach(tokenOf(args, engine))) }
    }

    private suspend fun handleDebugDetach(engine: BrowserEngine, args: JsonObject): InvokeResult {
        debugGate()?.let { return it }
        val tab = args["tab"]?.asString()?.let { BrowserSessionToken(tabId = it, family = engine.family) }
        return runGatedTool {
            val n = engine.debugDetach(tab)
            InvokeResult.okMessage("detached $n tab(s)" + (tab?.let { " (tab=${it.tabId})" } ?: " (all)"))
        }
    }

    private suspend fun handleDebugSetBreakpoint(engine: BrowserEngine, args: JsonObject): InvokeResult {
        debugGate()?.let { return it }
        val url = sanitizeUrl(args["url"]?.asString())
        if (url.isEmpty()) return InvokeResult.error("缺少 url（脚本 URL，如 https://x.test/app.js）")
        val line = args["line"]?.asInt() ?: return InvokeResult.error("缺少 line（0-based 行号）")
        val column = args["column"]?.asInt() ?: 0
        val condition = args["condition"]?.asString()
        return runGatedTool {
            val bp = engine.debugSetBreakpoint(tokenOf(args, engine), url, line, column, condition)
            InvokeResult.okMessage(
                "breakpoint ${bp.id}: ${bp.url}:${bp.lineNumber}:${bp.columnNumber}" +
                    (if (bp.condition.isNotEmpty()) " if (${bp.condition})" else "")
            )
        }
    }

    private suspend fun handleDebugRemoveBreakpoint(engine: BrowserEngine, args: JsonObject): InvokeResult {
        debugGate()?.let { return it }
        val id = args["id"]?.asString() ?: return InvokeResult.error("缺少 id（来自 debug_list_breakpoints）")
        return runGatedTool {
            if (engine.debugRemoveBreakpoint(tokenOf(args, engine), id)) InvokeResult.okMessage("breakpoint removed: $id")
            else InvokeResult.error("breakpoint not found: $id")
        }
    }

    private suspend fun handleDebugListBreakpoints(engine: BrowserEngine, args: JsonObject): InvokeResult {
        debugGate()?.let { return it }
        return runGatedTool {
            val bps = engine.debugListBreakpoints(tokenOf(args, engine))
            InvokeResult.okMessage(
                if (bps.isEmpty()) "no breakpoints"
                else bps.joinToString("\n") {
                    "${it.id}\t${it.url}:${it.lineNumber}:${it.columnNumber}" +
                        (if (it.condition.isNotEmpty()) "\tif(${it.condition})" else "")
                }
            )
        }
    }

    private suspend fun handleDebugResume(engine: BrowserEngine, args: JsonObject): InvokeResult {
        debugGate()?.let { return it }
        val tab = args["tab"]?.asString()?.let { BrowserSessionToken(tabId = it, family = engine.family) }
        return runGatedTool {
            val n = engine.debugResume(tab)
            InvokeResult.okMessage(
                if (n > 0) "resumed $n tab(s)"
                else "no paused tabs（省略 tab 参数时恢复全部 paused 的 tab）"
            )
        }
    }

    private suspend fun handleDebugStep(engine: BrowserEngine, args: JsonObject): InvokeResult {
        debugGate()?.let { return it }
        val stepRaw = args["step"]?.asString() ?: return InvokeResult.error("缺少 step（over/into/out）")
        val step = when (stepRaw.lowercase()) {
            "over" -> DebugStep.OVER
            "into" -> DebugStep.INTO
            "out" -> DebugStep.OUT
            else -> return InvokeResult.error("未知 step：$stepRaw（可选 over/into/out）")
        }
        return runGatedTool {
            engine.debugStep(tokenOf(args, engine), step)
            InvokeResult.okMessage("stepped $stepRaw（下一暂停点见 browser.debug_state）")
        }
    }

    private suspend fun handleDebugState(engine: BrowserEngine, args: JsonObject): InvokeResult {
        debugGate()?.let { return it }
        return runGatedTool { InvokeResult.okMessage(engine.debugState(tokenOf(args, engine))) }
    }

    private suspend fun handleDebugEval(engine: BrowserEngine, args: JsonObject): InvokeResult {
        debugGate()?.let { return it }
        val expression = args["expression"]?.asString() ?: return InvokeResult.error("缺少 expression")
        if (expression.isBlank()) return InvokeResult.error("expression 不能为空")
        val frame = args["frame"]?.asInt() ?: 0
        return runGatedTool {
            InvokeResult.okMessage(engine.debugEval(tokenOf(args, engine), frame, expression))
        }
    }

    private suspend fun handleDebugScope(engine: BrowserEngine, args: JsonObject): InvokeResult {
        debugGate()?.let { return it }
        val frame = args["frame"]?.asInt() ?: 0
        val scope = args["scope"]?.asInt()
        return runGatedTool {
            InvokeResult.okMessage(engine.debugScope(tokenOf(args, engine), frame, scope))
        }
    }

    private fun fmtBytes(n: Long): String = when {
        n >= 1024 * 1024 -> "${n / (1024 * 1024)}MB"
        n >= 1024 -> "${n / 1024}KB"
        else -> "${n}B"
    }

    private fun JsonElement?.asString(): String? =
        (this as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }

    private fun JsonElement?.asInt(): Int? =
        (this as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonElement?.asBool(): Boolean? =
        (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()


    data class ToolSpec(
        val name: String,
        val risk: BrowserRisk,
        val capability: BrowserCapability,
        val description: String,
        val inputSchema: JsonObject,
    )

    data class InvokeResult(
        val success: Boolean,
        val output: String,
        val imageAttachments: List<ToolImageRef> = emptyList(),
    ) {
        companion object {
            fun okMessage(message: String) = InvokeResult(success = true, output = message)
            fun okImage(message: String, image: ToolImageRef?) = InvokeResult(
                success = true,
                output = message,
                imageAttachments = listOfNotNull(image)
            )
            fun error(message: String) = InvokeResult(success = false, output = message)
        }
    }

    companion object {
        private fun schema(
            properties: Map<String, String> = emptyMap(),
            required: List<String> = emptyList(),
        ): JsonObject = schemaEl(properties.entries.associate { (name, type) ->
            name to buildJsonObject { put("type", JsonPrimitive(type)) }
        }, required)

        /** 嵌套结构版 schema：属性值直接给 JsonElement（数组/对象等）。 */
        private fun schemaEl(
            properties: Map<String, JsonElement> = emptyMap(),
            required: List<String> = emptyList(),
        ): JsonObject = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                properties.forEach { (name, el) -> put(name, el) }
            })
            if (required.isNotEmpty()) {
                put("required", JsonArray(required.map(::JsonPrimitive)))
            }
            put("additionalProperties", JsonPrimitive(false))
        }

        private val NO_ARGS = schema(mapOf("tab" to "string"))
        private val URL = schema(mapOf("url" to "string", "tab" to "string"), listOf("url"))
        private val REF = schema(mapOf("ref" to "string", "tab" to "string"), listOf("ref"))

        // ---- hook 工具的嵌套 schema 片段 ----
        private val strT = buildJsonObject { put("type", JsonPrimitive("string")) }
        private val intT = buildJsonObject { put("type", JsonPrimitive("integer")) }
        private val boolT = buildJsonObject { put("type", JsonPrimitive("boolean")) }
        private val objT = buildJsonObject { put("type", JsonPrimitive("object")) }

        /** actions 数组：item 的 type 取值 log/block/redirect/mock/modify_headers/replace/fake_value。 */
        private val ACTIONS_SCHEMA: JsonElement = buildJsonObject {
            put("type", JsonPrimitive("array"))
            put("items", buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject {
                    put("type", strT)
                    put("captureBody", boolT)
                    put("url", strT)
                    put("status", intT)
                    put("headers", objT)
                    put("body", strT)
                    put("request", objT)
                    put("response", objT)
                    put("code", strT)
                    put("value", strT)
                })
                put("required", JsonArray(listOf(JsonPrimitive("type"))))
            })
        }

        private val HOOK_CREATE_SCHEMA: JsonObject = schemaEl(
            mapOf(
                "type" to strT,
                "target" to strT,
                "name" to strT,
                "method" to strT,
                "tab" to strT,
                "actions" to ACTIONS_SCHEMA,
                "captureStack" to boolT,
                "captureBody" to boolT,
                "enabled" to boolT,
            ),
            listOf("type", "target"),
        )
        private val INJECT_SCHEMA: JsonObject = schemaEl(
            mapOf(
                "code" to strT,
                "tab" to strT,
                "persistent" to boolT,
                "name" to strT,
            ),
            listOf("code"),
        )

        // ---- debug 工具 schema ----
        private val DEBUG_SET_BP_SCHEMA: JsonObject = schemaEl(
            mapOf(
                "url" to strT,
                "line" to intT,
                "column" to intT,
                "condition" to strT,
                "tab" to strT,
            ),
            listOf("url", "line"),
        )
        private val DEBUG_STEP_SCHEMA: JsonObject = schemaEl(
            mapOf("step" to strT, "tab" to strT),
            listOf("step"),
        )
        private val DEBUG_EVAL_SCHEMA: JsonObject = schemaEl(
            mapOf("expression" to strT, "frame" to intT, "tab" to strT),
            listOf("expression"),
        )
        private val DEBUG_SCOPE_SCHEMA: JsonObject = schemaEl(
            mapOf("frame" to intT, "scope" to intT, "tab" to strT),
        )
        private val TOOLS: List<ToolSpec> = listOf(
            ToolSpec("browser.open", BrowserRisk.MEDIUM, BrowserCapability.OPEN, "打开新 tab；返回 tab ID", URL),
            ToolSpec("browser.navigate", BrowserRisk.MEDIUM, BrowserCapability.NAVIGATE, "当前或指定 tab 跳转到 URL", URL),
            ToolSpec("browser.back", BrowserRisk.LOW, BrowserCapability.NAVIGATE, "后退", NO_ARGS),
            ToolSpec("browser.forward", BrowserRisk.LOW, BrowserCapability.NAVIGATE, "前进", NO_ARGS),
            ToolSpec("browser.refresh", BrowserRisk.LOW, BrowserCapability.NAVIGATE, "刷新", NO_ARGS),
            ToolSpec("browser.list_tabs", BrowserRisk.LOW, BrowserCapability.LIST_TABS, "列出 tab", schema()),
            ToolSpec("browser.close_tab", BrowserRisk.LOW, BrowserCapability.CLOSE_TAB, "关闭当前或指定 tab", NO_ARGS),
            ToolSpec("browser.snapshot", BrowserRisk.LOW, BrowserCapability.SNAPSHOT, "提取 ref + 页面元信息", schema(mapOf("tab" to "string", "maxElements" to "integer"))),
            ToolSpec("browser.click", BrowserRisk.HIGH, BrowserCapability.CLICK, "按 snapshot ref 点击元素", REF),
            ToolSpec("browser.type", BrowserRisk.HIGH, BrowserCapability.TYPE, "按 snapshot ref 键入文本", schema(mapOf("ref" to "string", "text" to "string", "tab" to "string"), listOf("ref", "text"))),
            ToolSpec("browser.press", BrowserRisk.HIGH, BrowserCapability.PRESS, "向当前焦点或指定 ref 发送按键", schema(mapOf("ref" to "string", "key" to "string", "tab" to "string"), listOf("key"))),
            ToolSpec("browser.scroll", BrowserRisk.LOW, BrowserCapability.SCROLL, "滚动当前页", schema(mapOf("deltaY" to "integer", "tab" to "string"))),
            ToolSpec("browser.screenshot", BrowserRisk.LOW, BrowserCapability.SCREENSHOT, "截图当前页", NO_ARGS),
            ToolSpec("browser.current_url", BrowserRisk.LOW, BrowserCapability.PAGE_SOURCE, "取当前 URL", NO_ARGS),
            ToolSpec("browser.title", BrowserRisk.LOW, BrowserCapability.PAGE_SOURCE, "取当前 title", NO_ARGS),
            ToolSpec("browser.page_source", BrowserRisk.MEDIUM, BrowserCapability.PAGE_SOURCE, "页面 HTML", schema(mapOf("maxBytes" to "integer", "tab" to "string"))),
            ToolSpec("browser.evaluate", BrowserRisk.CRITICAL, BrowserCapability.EVALUATE_JS, "执行 JS", schema(mapOf("expression" to "string", "tab" to "string"), listOf("expression"))),
            ToolSpec("browser.console_list", BrowserRisk.LOW, BrowserCapability.CONSOLE_READ, "最近 console 日志", NO_ARGS),
            ToolSpec("browser.console_clear", BrowserRisk.MEDIUM, BrowserCapability.CONSOLE_READ, "清空 console", NO_ARGS),
            ToolSpec("browser.network_list", BrowserRisk.LOW, BrowserCapability.NETWORK_INTERCEPT, "已捕获网络请求", NO_ARGS),
            ToolSpec("browser.cookies_get", BrowserRisk.CRITICAL, BrowserCapability.COOKIES_RW, "读 Cookie", schema(mapOf("url" to "string", "tab" to "string"))),
            ToolSpec("browser.cookies_set", BrowserRisk.HIGH, BrowserCapability.COOKIES_RW, "写 Cookie", schema(mapOf("url" to "string", "header" to "string", "tab" to "string"), listOf("url", "header"))),
            ToolSpec("browser.cookies_delete", BrowserRisk.HIGH, BrowserCapability.COOKIES_RW, "删 Cookie", schema(mapOf("url" to "string", "name" to "string", "tab" to "string"), listOf("url", "name"))),
            ToolSpec("browser.local_get", BrowserRisk.HIGH, BrowserCapability.LOCAL_RW, "读 localStorage", schema(mapOf("key" to "string", "tab" to "string"), listOf("key"))),
            ToolSpec("browser.local_set", BrowserRisk.HIGH, BrowserCapability.LOCAL_RW, "写 localStorage", schema(mapOf("key" to "string", "value" to "string", "tab" to "string"), listOf("key", "value"))),
            ToolSpec("browser.local_delete", BrowserRisk.HIGH, BrowserCapability.LOCAL_RW, "删 localStorage", schema(mapOf("key" to "string", "tab" to "string"), listOf("key"))),
            ToolSpec("browser.local_keys", BrowserRisk.MEDIUM, BrowserCapability.LOCAL_RW, "列 localStorage keys", NO_ARGS),
            ToolSpec("browser.session_get", BrowserRisk.HIGH, BrowserCapability.SESSION_RW, "读 sessionStorage", schema(mapOf("key" to "string", "tab" to "string"), listOf("key"))),
            ToolSpec("browser.session_set", BrowserRisk.HIGH, BrowserCapability.SESSION_RW, "写 sessionStorage", schema(mapOf("key" to "string", "value" to "string", "tab" to "string"), listOf("key", "value"))),
            ToolSpec("browser.session_delete", BrowserRisk.HIGH, BrowserCapability.SESSION_RW, "删 sessionStorage", schema(mapOf("key" to "string", "tab" to "string"), listOf("key"))),
            ToolSpec("browser.session_keys", BrowserRisk.MEDIUM, BrowserCapability.SESSION_RW, "列 sessionStorage keys", NO_ARGS),
            // ===== 注入式 Hook 引擎（阶段 1）=====
            ToolSpec(
                "browser.hook_create", BrowserRisk.CRITICAL, BrowserCapability.INSTALL_HOOK,
                "安装 hook 规则（网页逆向核心工具）。target 依 type：fetch/xhr/websocket→URL glob（* ? 通配）；function/method→对象路径；property→属性路径；storage→local/session；cookie→cookie 名或 *；console→逗号分隔 level；timer→setInterval/setTimeout；crypto→random/uuid。actions 为动作数组，item.type 可选 log/block/redirect(仅 fetch/xhr)/mock(仅 fetch/xhr)/modify_headers(仅 fetch/xhr)/replace(仅 function/method)/fake_value(仅 property)，各动作可用字段见 schema；多个动作按优先级 block>mock>redirect>modify_headers>log 判定。建议先 log 观察再改写",
                HOOK_CREATE_SCHEMA,
            ),
            ToolSpec("browser.hook_list", BrowserRisk.LOW, BrowserCapability.INSTALL_HOOK, "列出已安装 hook 规则与命中计数", schema(mapOf("tab" to "string"))),
            ToolSpec("browser.hook_remove", BrowserRisk.MEDIUM, BrowserCapability.INSTALL_HOOK, "按 id 移除 hook 规则", schema(mapOf("id" to "string"), listOf("id"))),
            ToolSpec("browser.hook_reset", BrowserRisk.HIGH, BrowserCapability.INSTALL_HOOK, "清空 hook 规则/持久脚本/命中记录/body（tab 省略时全局）", schema(mapOf("tab" to "string"))),
            ToolSpec("browser.hook_hits", BrowserRisk.MEDIUM, BrowserCapability.INSTALL_HOOK, "最近 hook 命中记录（函数参数摘要/属性读值等）", schema(mapOf("tab" to "string", "limit" to "integer"))),
            ToolSpec("browser.inject_script", BrowserRisk.CRITICAL, BrowserCapability.INSTALL_HOOK, "注入 JS 到页面。persistent=false 立即执行一次；persistent=true 注册为持久脚本，每次导航自动重放（适合重定义函数/改原型）。需 allowEvalJs 思路同 evaluate：代码直接作用于真实页面，请谨慎", INJECT_SCHEMA),
            ToolSpec("browser.network_detail", BrowserRisk.MEDIUM, BrowserCapability.NETWORK_INTERCEPT, "查询单条网络请求的元数据与请求/响应 body（id 来自 network_list；body 需规则 captureBody=true 捕获）", schema(mapOf("id" to "string"), listOf("id"))),
            // ===== CDP 调试引擎（阶段 2）=====
            ToolSpec("browser.debug_status", BrowserRisk.LOW, BrowserCapability.CDP_DEBUG, "CDP attach 会话总览：各 tab 的 target/paused/断点数/worker 数", schema()),
            ToolSpec("browser.debug_attach", BrowserRisk.MEDIUM, BrowserCapability.CDP_DEBUG, "attach tab 开启 CDP 调试（真断点 + Worker 级 Fetch 拦截）。重复 attach 幂等；断点在 detach 后重 attach 自动重放", NO_ARGS),
            ToolSpec("browser.debug_detach", BrowserRisk.LOW, BrowserCapability.CDP_DEBUG, "分离 CDP 连接（tab 省略=全部）。detach 前自动 resume 防页面冻结", NO_ARGS),
            ToolSpec("browser.debug_set_breakpoint", BrowserRisk.MEDIUM, BrowserCapability.CDP_DEBUG, "在脚本 URL 的指定行设断点（line/column 均 0-based）。触发后页面暂停，用 debug_state 看调用栈、debug_eval 看变量；**调试完必须 debug_resume**。condition 为可选条件表达式", DEBUG_SET_BP_SCHEMA),
            ToolSpec("browser.debug_remove_breakpoint", BrowserRisk.LOW, BrowserCapability.CDP_DEBUG, "按 id 移除断点（id 来自 debug_list_breakpoints）", schema(mapOf("id" to "string", "tab" to "string"), listOf("id"))),
            ToolSpec("browser.debug_list_breakpoints", BrowserRisk.LOW, BrowserCapability.CDP_DEBUG, "列出当前 tab 的断点", schema(mapOf("tab" to "string"))),
            ToolSpec("browser.debug_resume", BrowserRisk.MEDIUM, BrowserCapability.CDP_DEBUG, "恢复执行（tab 省略=恢复全部 paused 的 tab）。暂停期间页内工具（evaluate/snapshot/click 等）会被冻结拒绝", NO_ARGS),
            ToolSpec("browser.debug_step", BrowserRisk.MEDIUM, BrowserCapability.CDP_DEBUG, "单步执行：over（跳过函数）/into（进入函数）/out（跳出函数）；多 tab 同时 paused 时必须指定 tab", DEBUG_STEP_SCHEMA),
            ToolSpec("browser.debug_state", BrowserRisk.LOW, BrowserCapability.CDP_DEBUG, "查看暂停状态：调用栈（帧/函数名/位置/作用域类型）或 running", schema(mapOf("tab" to "string"))),
            ToolSpec("browser.debug_eval", BrowserRisk.HIGH, BrowserCapability.CDP_DEBUG, "在暂停帧上求值表达式（可读写局部变量；returnByValue）。frame 默认 0（栈顶）；结果超长截断 64KB", DEBUG_EVAL_SCHEMA),
            ToolSpec("browser.debug_scope", BrowserRisk.MEDIUM, BrowserCapability.CDP_DEBUG, "读取暂停帧的作用域变量：scope 省略时输出全部作用域摘要（类型+变量名列表），指定时输出该作用域的变量名=值明细", DEBUG_SCOPE_SCHEMA),
        )
    }
}



