package top.wanxiang.app.runtime.browser.cdp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.wanxiang.app.runtime.browser.BrowserEvent
import top.wanxiang.app.runtime.browser.BrowserEventBus
import java.util.concurrent.ConcurrentHashMap

/** CdpDebugController 的命令通道（CdpSession 实现；测试注入 fake）。 */
interface CdpCommandApi {
    suspend fun send(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        sessionId: String? = null,
        timeoutMs: Long = 10_000,
    ): JsonObject
}

/**
 * Debugger 域状态机：断点表 + paused/resumed 状态迁移 + 调用栈/作用域/求值。
 *
 * - 每 tab 连接独立实例（断点与暂停态天然多 tab 隔离）；
 * - 重 attach 时由宿主把既有断点经 [setup] 重放（Debugger.setBreakpointByUrl 按 url 全局生效）；
 * - paused 期间页内工具（evaluate/snapshot/click 等）会被引擎侧前置拦截——
 *   本类只维护状态，页内守卫在 AndroidInAppBrowserEngine。
 */
class CdpDebugController(
    private val tabId: String,
    private val session: CdpCommandApi,
    private val eventBus: BrowserEventBus,
) {
    private val _paused = MutableStateFlow<DebugPausedState?>(null)
    val paused: StateFlow<DebugPausedState?> = _paused.asStateFlow()

    private val breakpoints = ConcurrentHashMap<String, DebugBreakpoint>()

    fun breakpoints(): List<DebugBreakpoint> = breakpoints.values.sortedBy { it.id }

    /** attach 时启用域并重放既有断点。 */
    suspend fun setup(existing: List<DebugBreakpoint>) {
        session.send("Runtime.enable")
        session.send("Debugger.enable")
        session.send("Debugger.setSkipAllPauses", buildJsonObject { put("skip", false) })
        for (bp in existing) {
            // setBreakpoint 内部已按 CDP 返回的新 id 登记本地表；失败仅跳过该断点
            runCatching { setBreakpoint(bp.url, bp.lineNumber, bp.columnNumber, bp.condition.takeIf { it.isNotEmpty() }) }
        }
    }

    /** 设置断点；返回本地记录（含 CDP breakpointId）。 */
    suspend fun setBreakpoint(url: String, line: Int, column: Int, condition: String?): DebugBreakpoint {
        val params = buildJsonObject {
            put("url", url)
            put("lineNumber", line)
            put("columnNumber", column)
            if (!condition.isNullOrEmpty()) put("condition", condition)
        }
        val result = session.send("Debugger.setBreakpointByUrl", params)["breakpointId"]
            ?.jsonPrimitive?.contentOrNull
            ?: throw CdpCommandException(-3, "setBreakpointByUrl returned no breakpointId")
        val bp = DebugBreakpoint(
            id = result,
            tabId = tabId,
            url = url,
            lineNumber = line,
            columnNumber = column,
            condition = condition ?: "",
        )
        breakpoints[bp.id] = bp
        return bp
    }

    suspend fun removeBreakpoint(id: String): Boolean {
        if (breakpoints.remove(id) == null) return false
        session.send("Debugger.removeBreakpoint", buildJsonObject { put("breakpointId", id) })
        return true
    }

    suspend fun resume(): Boolean {
        requirePaused("resume")
        session.send("Debugger.resume")
        // 状态清除由 Debugger.resumed 事件驱动；此处乐观置空避免事件竞态窗口
        clearPaused()
        return true
    }

    suspend fun step(step: DebugStep): Boolean {
        requirePaused("step")
        session.send(
            when (step) {
                DebugStep.OVER -> "Debugger.stepOver"
                DebugStep.INTO -> "Debugger.stepInto"
                DebugStep.OUT -> "Debugger.stepOut"
            }
        )
        // step 会立即再 pause（步进到下一行）；先清当前态，等下一个 paused 事件
        clearPaused()
        return true
    }

    /**
     * 在暂停帧上求值（Runtime.evaluateOnCallFrame，returnByValue）。
     * 返回格式化结果或异常详情；超长截断 64KB。
     */
    suspend fun evaluateOnCallFrame(callFrameId: String, expression: String): String {
        val result = session.send(
            "Runtime.evaluateOnCallFrame",
            buildJsonObject {
                put("callFrameId", callFrameId)
                put("expression", expression)
                put("returnByValue", true)
                put("silent", true)
            },
            timeoutMs = 30_000,
        )
        val exception = result["exceptionDetails"] as? JsonObject
        if (exception != null) {
            val text = exception["text"]?.jsonPrimitive?.contentOrNull ?: "unknown exception"
            val detail = (exception["exception"] as? JsonObject)?.get("description")
                ?.jsonPrimitive?.contentOrNull
            return "Exception: ${detail ?: text}"
        }
        val remote = result["result"] as? JsonObject
            ?: return "undefined"
        val type = remote["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val value = when (type) {
            "string" -> "\"${remote["value"]?.jsonPrimitive?.contentOrNull ?: ""}\""
            "undefined" -> "undefined"
            "object" -> (remote["value"]?.jsonPrimitive?.toString() ?: remote["description"]?.jsonPrimitive?.contentOrNull) ?: "[object]"
            else -> remote["value"]?.jsonPrimitive?.toString() ?: "undefined"
        }
        return truncate(value)
    }

    /**
     * 读指定帧的作用域变量（Runtime.getProperties）。
     * scopeIndex 为 null 时输出全部作用域的摘要（type + 变量名列表）。
     */
    suspend fun scopeProperties(callFrameId: String, scopeIndex: Int?): String {
        val state = _paused.value ?: throw IllegalStateException("tab $tabId not paused")
        val frame = state.callFrames.firstOrNull { it.callFrameId == callFrameId }
            ?: throw IllegalArgumentException("callFrameId not found: $callFrameId")
        return if (scopeIndex == null) {
            frame.scopes.mapIndexed { i, s ->
                val names = runCatching { propertyNames(s.objectId) }.getOrDefault(emptyList())
                "[$i] ${s.type}${if (s.name.isNotEmpty()) " (${s.name})" else ""}: ${names.joinToString(", ").ifEmpty { "<empty>" }}"
            }.joinToString("\n").ifEmpty { "no scopes" }
        } else {
            val scope = frame.scopes.getOrNull(scopeIndex)
                ?: throw IllegalArgumentException("scope index out of range: $scopeIndex (0..${frame.scopes.size - 1})")
            formatProperties(scope.objectId)
        }
    }

    // ===== 事件入口（CdpTabConnection 转发） =====

    suspend fun onPaused(params: JsonObject) {
        val frames = (params["callFrames"] as? kotlinx.serialization.json.JsonArray ?: return).map { el ->
            val f = el.jsonObject
            DebugCallFrame(
                callFrameId = f["callFrameId"]?.jsonPrimitive?.contentOrNull ?: "",
                functionName = f["functionName"]?.jsonPrimitive?.contentOrNull ?: "",
                url = f["url"]?.jsonPrimitive?.contentOrNull ?: "",
                lineNumber = f["lineNumber"]?.jsonPrimitive?.int ?: 0,
                columnNumber = f["columnNumber"]?.jsonPrimitive?.int ?: 0,
                scopes = (f["scopeChain"] as? kotlinx.serialization.json.JsonArray)?.map { sc ->
                    val s = sc.jsonObject
                    DebugScope(
                        type = s["type"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                        name = s["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        objectId = (s["object"] as? JsonObject)?.get("objectId")?.jsonPrimitive?.contentOrNull ?: "",
                    )
                } ?: emptyList(),
            )
        }
        val state = DebugPausedState(
            tabId = tabId,
            reason = params["reason"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            hitBreakpoints = (params["hitBreakpoints"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            callFrames = frames,
        )
        _paused.value = state
        eventBus.publish(BrowserEvent.DebugPaused(tabId, state))
    }

    suspend fun onResumed() {
        clearPaused()
        eventBus.publish(BrowserEvent.DebugResumed(tabId))
    }

    /** detach 前清理：清状态 + 移除全部断点（CDP 侧随 session 关闭自动失效）。 */
    suspend fun cleanup() {
        breakpoints.clear()
        clearPaused()
    }

    private fun clearPaused() {
        _paused.value = null
    }

    private fun requirePaused(what: String) {
        check(_paused.value != null) { "tab $tabId not paused (cannot $what)；请先 browser.debug_set_breakpoint 触发暂停" }
    }

    private suspend fun propertyNames(objectId: String): List<String> {
        val result = session.send(
            "Runtime.getProperties",
            buildJsonObject {
                put("objectId", objectId)
                put("ownProperties", true)
            },
        )
        return (result["result"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            ?: emptyList()
    }

    private suspend fun formatProperties(objectId: String): String {
        val result = session.send(
            "Runtime.getProperties",
            buildJsonObject {
                put("objectId", objectId)
                put("ownProperties", true)
            },
        )
        val props = (result["result"] as? kotlinx.serialization.json.JsonArray) ?: return "<empty>"
        return props.joinToString("\n") { el ->
            val p = el.jsonObject
            val name = p["name"]?.jsonPrimitive?.contentOrNull ?: "?"
            val value = p["value"] as? JsonObject
            val type = value?.get("type")?.jsonPrimitive?.contentOrNull ?: "unknown"
            val rendered = when (type) {
                "string" -> "\"${value?.get("value")?.jsonPrimitive?.contentOrNull ?: ""}\""
                "undefined", "null" -> type
                "object", "function" -> value?.get("description")?.jsonPrimitive?.contentOrNull ?: "[${type}]"
                else -> value?.get("value")?.jsonPrimitive?.toString() ?: "?"
            }
            truncate("$name = $rendered")
        }.ifEmpty { "<empty>" }
    }

    private fun truncate(s: String, max: Int = 64 * 1024): String =
        if (s.length <= max) s else s.take(max) + "\n...[TRUNCATED ${s.length - max} chars]"
}
