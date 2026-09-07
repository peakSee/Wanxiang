package top.wanxiang.app.runtime.browser.hook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 页面上报事件（envelope `{v, tab, seq, ts, kind, data}`）解析为强类型。
 *
 * 纯函数、可单测；tabId 强制取桥绑定值（[parse] 入参），忽略 envelope 内的 tab
 * 字段，防止 iframe / 被改写的页面伪造跨 tab 事件。
 */
object HookEventParser {

    private const val MAX_EVENT_BYTES = 512 * 1024
    private const val MAX_STRING_FIELD = 64 * 1024
    private const val MAX_HEADERS = 64
    private val KINDS = setOf("ready", "net_req", "net_res", "hit", "ws", "hook_error")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    sealed class HookBridgeEvent {
        abstract val tabId: String
        abstract val ts: Long

        data class Ready(override val tabId: String, override val ts: Long, val href: String) : HookBridgeEvent()

        data class NetReq(
            override val tabId: String,
            override val ts: Long,
            val id: String,
            val initiator: String,
            val url: String,
            val method: String,
            val headers: Map<String, String>,
            val body: String?,
        ) : HookBridgeEvent()

        data class NetRes(
            override val tabId: String,
            override val ts: Long,
            val id: String,
            val url: String,
            val status: Int,
            val statusText: String,
            val headers: Map<String, String>,
            val body: String?,
            val durationMs: Long,
            val ruleId: String?,
            val actionTaken: String?,
        ) : HookBridgeEvent()

        data class Hit(
            override val tabId: String,
            override val ts: Long,
            val hookId: String,
            val type: HookType?,
            val target: String,
            val phase: String,
            val summary: String,
            val detailJson: String,
        ) : HookBridgeEvent()

        data class Ws(
            override val tabId: String,
            override val ts: Long,
            val url: String,
            val event: String,
            val summary: String,
            val detailJson: String,
        ) : HookBridgeEvent()

        data class HookError(override val tabId: String, override val ts: Long, val stage: String, val message: String) : HookBridgeEvent()
    }

    /** 解析失败返回 null（调用方静默丢弃并计数，不抛异常打爆日志）。 */
    fun parse(boundTabId: String, raw: String): HookBridgeEvent? {
        if (raw.length > MAX_EVENT_BYTES) return null
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val v = obj.int("v") ?: return null
        if (v != 1) return null
        val kind = obj.str("kind") ?: return null
        if (kind !in KINDS) return null
        val ts = obj.long("ts") ?: System.currentTimeMillis()
        val data = (obj["data"] as? JsonObject) ?: JsonObject(emptyMap())
        return when (kind) {
            "ready" -> HookBridgeEvent.Ready(boundTabId, ts, data.clampStr("href"))
            "net_req" -> HookBridgeEvent.NetReq(
                tabId = boundTabId,
                ts = ts,
                id = data.clampStr("id"),
                initiator = data.clampStr("initiator", "fetch"),
                url = data.clampStr("url"),
                method = data.clampStr("method", "GET").uppercase(),
                headers = data.headers("headers"),
                body = data.strOrNull("body"),
            )
            "net_res" -> HookBridgeEvent.NetRes(
                tabId = boundTabId,
                ts = ts,
                id = data.clampStr("id"),
                url = data.clampStr("url"),
                status = data.int("status") ?: 0,
                statusText = data.clampStr("statusText"),
                headers = data.headers("headers"),
                body = data.strOrNull("body"),
                durationMs = data.long("durationMs") ?: 0L,
                ruleId = data.strOrNull("ruleId"),
                actionTaken = data.strOrNull("actionTaken"),
            )
            "hit" -> HookBridgeEvent.Hit(
                tabId = boundTabId,
                ts = ts,
                hookId = data.clampStr("hookId"),
                type = data.strOrNull("type")?.let { t -> HookType.entries.firstOrNull { it.name.equals(t, true) } },
                target = data.clampStr("target"),
                phase = data.clampStr("phase", "call"),
                summary = data.clampStr("summary"),
                detailJson = data.strOrNull("detail") ?: "",
            )
            "ws" -> HookBridgeEvent.Ws(
                tabId = boundTabId,
                ts = ts,
                url = data.clampStr("url"),
                event = data.clampStr("event", "message"),
                summary = data.clampStr("summary"),
                detailJson = data.strOrNull("detail") ?: "",
            )
            "hook_error" -> HookBridgeEvent.HookError(boundTabId, ts, data.clampStr("stage"), data.clampStr("message"))
            else -> null
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.clampStr(key: String, default: String = ""): String =
        strOrNull(key)?.take(MAX_STRING_FIELD) ?: default

    private fun JsonObject.strOrNull(key: String): String? =
        str(key)?.takeIf { it.isNotEmpty() }?.take(MAX_STRING_FIELD)

    private fun JsonObject.headers(key: String): Map<String, String> {
        val obj = this[key] as? JsonObject ?: return emptyMap()
        return obj.entries
            .take(MAX_HEADERS)
            .mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it.take(4096) } }
            .toMap()
    }
}
