package top.wanxiang.app.runtime.browser.cdp

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * tab ↔ CDP page target 匹配：
 *
 * 1. 仅 1 个 page target → 直用（最常见：单 tab attach）；
 * 2. 多个 page target → 与 tab 当前 URL 唯一相等者；
 * 3. 仍撞车（同 URL 多 tab / about:blank）→ probe：临时 WS 连接候选 target，
 *    `Runtime.evaluate` 读 `window.__wanxiangTabId` 精确比对
 *    （marker 由 [top.wanxiang.app.runtime.browser.engine.WebViewTabPool] 在 document-start 注入）；
 * 4. 全部失败 → [TargetMatch.NotFound]（列出候选，错误原文直达 agent）。
 */
class CdpTargetMatcher(
    private val transport: CdpTransport,
    private val scope: CoroutineScope,
) {

    sealed interface TargetMatch {
        data class Matched(val target: CdpTargetInfo) : TargetMatch
        data class NotFound(val message: String, val candidates: List<CdpTargetInfo>) : TargetMatch
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** GET /json 解析 target 列表（page / service_worker / shared_worker 等）。 */
    fun listTargets(): List<CdpTargetInfo> {
        val conn = transport.open()
        val body = try {
            HttpOverSocket.get(conn, "/json")
        } finally {
            conn.close()
        }
        return json.parseToJsonElement(body).jsonArray.map { el ->
            val o = el.jsonObject
            CdpTargetInfo(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                type = o["type"]?.jsonPrimitive?.contentOrNull ?: "",
                title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
                url = o["url"]?.jsonPrimitive?.contentOrNull ?: "",
                webSocketDebuggerUrl = o["webSocketDebuggerUrl"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }

    /** 为 tab 匹配 page target（匹配策略见类 KDoc）。 */
    suspend fun matchPageTarget(tabId: String, tabUrl: String?): TargetMatch {
        val pages = listTargets().filter { it.type == "page" && it.webSocketDebuggerUrl.isNotEmpty() }
        return when {
            pages.isEmpty() -> TargetMatch.NotFound(
                "devtools /json 中没有 page target（页面可能尚未加载完成）",
                emptyList(),
            )
            pages.size == 1 -> TargetMatch.Matched(pages[0])
            else -> matchAmongMany(tabId, tabUrl, pages)
        }
    }

    private suspend fun matchAmongMany(tabId: String, tabUrl: String?, pages: List<CdpTargetInfo>): TargetMatch {
        // 2. URL 唯一匹配
        if (tabUrl != null && tabUrl.isNotBlank() && tabUrl != "about:blank") {
            val normalized = normalizeUrl(tabUrl)
            val byUrl = pages.filter { normalizeUrl(it.url) == normalized }
            if (byUrl.size == 1) return TargetMatch.Matched(byUrl[0])
        }
        // 3. marker probe：读 window.__wanxiangTabId 精确比对
        val probed = pages.firstOrNull { probeMarker(it) == tabId }
        if (probed != null) return TargetMatch.Matched(probed)
        return TargetMatch.NotFound(
            "多个 page target 且无法定位 tab $tabId（URL/marker 均未命中；" +
                "tabUrl=${tabUrl ?: "unknown"}）",
            pages,
        )
    }

    /** 临时 WS 会话读页内 marker；失败（未注入/连接失败）返回 null。 */
    private suspend fun probeMarker(target: CdpTargetInfo): String? = runCatching {
        val path = target.webSocketDebuggerUrl.substringAfter("devtools", "").ifEmpty { return null }
        val conn = transport.open()
        val ws = try {
            WsHandshake.open(conn, path)
        } catch (e: Exception) {
            conn.close()
            return null
        }
        val session = CdpSession(ws, scope)
        try {
            val resp = session.send(
                "Runtime.evaluate",
                buildJsonObject {
                    put("expression", "window.__wanxiangTabId || ''")
                    put("returnByValue", true)
                },
                timeoutMs = 3_000,
            )
            (resp["result"] as? kotlinx.serialization.json.JsonObject)
                ?.get("result")?.jsonObject
                ?.get("value")?.jsonPrimitive?.contentOrNull
        } finally {
            session.close()
        }
    }.getOrNull()

    /** 去掉尾部 `/` 与 fragment，忽略大小写的 host 部分。 */
    private fun normalizeUrl(url: String): String =
        url.substringBefore('#').trimEnd('/').lowercase()
}
