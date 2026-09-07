package top.wanxiang.app.harness.mcp.server

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wanxiang.app.runtime.browser.secret.SecretRedactingInterceptor
import top.wanxiang.app.runtime.browser.tools.BrowserMcpTools

/**
 * 把 `mcp__browser__<tool>` 转发到 [BrowserMcpTools] 实例。
 */
@Singleton
class McpToolDispatcher @Inject constructor(
    private val browserTools: BrowserMcpTools,
) {
    fun listTools(): List<JsonObject> = browserTools.list().map { spec ->
        buildJsonObject {
            put("name", JsonPrimitive(spec.name))
            put("risk", JsonPrimitive(spec.risk.name))
            put("capability", JsonPrimitive(spec.capability.name))
            put("description", JsonPrimitive(spec.description))
            put("inputSchema", spec.inputSchema)
        }
    }

    suspend fun dispatch(toolName: String, args: JsonObject): JsonObject {
        val res = browserTools.invoke(toolName, args)
        // 统一脱敏出口：cookie/页面源码/console 等可能夹带敏感值，防止直接回灌 LLM
        val text = SecretRedactingInterceptor.apply(
            if (res.imageAttachments.isEmpty()) res.output else buildJsonObject {
                put("output", JsonPrimitive(res.output))
                put("imageAttachments", kotlinx.serialization.json.JsonArray(res.imageAttachments.map { ir ->
                    buildJsonObject {
                        put("id", JsonPrimitive(ir.id))
                        put("uri", JsonPrimitive(ir.uri))
                        put("mime", JsonPrimitive(ir.mime))
                        put("width", JsonPrimitive(ir.width))
                        put("height", JsonPrimitive(ir.height))
                    }
                }))
            }.toString()
        )
        return buildJsonObject {
            put("content", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(text))
            })))
            put("isError", JsonPrimitive(!res.success))
        }
    }
}
