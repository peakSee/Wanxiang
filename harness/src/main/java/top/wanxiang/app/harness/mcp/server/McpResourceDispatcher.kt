package top.wanxiang.app.harness.mcp.server

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wanxiang.app.runtime.browser.secret.SecretRedactingInterceptor
import top.wanxiang.app.runtime.browser.tools.BrowserMcpResources

@Singleton
class McpResourceDispatcher @Inject constructor(private val browserResources: BrowserMcpResources) {

    fun listResources(): List<JsonObject> = browserResources.list().map { uri ->
        buildJsonObject { put("uri", JsonPrimitive(uri)) }
    }

    suspend fun readResource(uri: String): JsonObject? {
        val content = browserResources.read(uri) ?: return null
        // 与 McpToolDispatcher 同一脱敏出口：browser://storage 等资源内容可能夹带 cookie/token，
        // 不允许 resources/read 绕过脱敏直接回灌 LLM
        return buildJsonObject {
            put("uri", JsonPrimitive(uri))
            put("text", JsonPrimitive(SecretRedactingInterceptor.apply(content)))
        }
    }
}
