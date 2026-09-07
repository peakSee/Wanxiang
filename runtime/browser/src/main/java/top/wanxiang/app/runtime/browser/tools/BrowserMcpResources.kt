package top.wanxiang.app.runtime.browser.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wanxiang.app.runtime.browser.BrowserEngine
import top.wanxiang.app.runtime.browser.BrowserSessionToken

/**
 * MCP resources 提供者（`browser://current-page`, `browser://dom`, ...）。
 *
 * 每个 resource 渲染为一段 JSON 文本，外部 IDE 可以 subscribe。
 */
class BrowserMcpResources(private val engineProvider: () -> BrowserEngine?) {

    fun list(): List<String> = RESOURCE_URIS

    suspend fun read(uri: String): String? {
        if (uri !in RESOURCE_URIS) return null
        val engine = engineProvider() ?: return null
        return when (uri) {
            "browser://current-page" -> buildJsonObject {
                put("url", kotlinx.serialization.json.JsonPrimitive(engine.eventBus.url.value))
                put("title", kotlinx.serialization.json.JsonPrimitive(engine.eventBus.title.value))
            }.toString()
            "browser://dom" -> engine.eventBus.snapshot.value?.snapshot?.let {
                buildJsonObject {
                    put("url", kotlinx.serialization.json.JsonPrimitive(it.url))
                    put("title", kotlinx.serialization.json.JsonPrimitive(it.title))
                    put("interactiveCount", kotlinx.serialization.json.JsonPrimitive(it.interactiveRefs.size))
                }.toString()
            }.orEmpty()
            "browser://console" -> engine.eventBus.console.value.joinToString("\n") { "[${it.level}] ${it.message}" }
            "browser://network" -> engine.eventBus.network.value.joinToString("\n") { "${it.method} ${it.url} (${it.statusCode})" }
            "browser://tabs" -> engine.listTabs().joinToString("\n") { "${it.tabId}\t${it.url}\t${it.title}" }
            "browser://storage" -> {
                val tab = engine.activeTab() ?: BrowserSessionToken.defaultTab(engine.family)
                buildJsonObject {
                    put("cookies", kotlinx.serialization.json.JsonPrimitive(engine.cookiesGet(tab, null)))
                    put("localKeys", kotlinx.serialization.json.JsonPrimitive(engine.localKeys(tab).joinToString(",")))
                }.toString()
            }
            else -> null
        }
    }

    companion object {
        val RESOURCE_URIS: List<String> = listOf(
            "browser://current-page",
            "browser://dom",
            "browser://console",
            "browser://network",
            "browser://tabs",
            "browser://storage",
        )
    }
}
