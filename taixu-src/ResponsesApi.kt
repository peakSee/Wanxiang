package top.wkbin.taixu.harness

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Responses API（POST /responses）适配层：把内部统一的 OpenAI 风格 [ApiMessage] 列表
 * 翻译成 Responses API 的 `input` items 结构，并把响应（含 SSE 流式）映射回 [ChatResult]。
 *
 * 与 [ChatApi]（chat/completions）的主要协议差异：
 * - 系统提示词放在顶层 `instructions`，不在 messages/input 里；
 * - 历史消息用 `input` items 表示：`{role:user|assistant, content:[...]}`、
 *   `{type:"function_call", call_id, name, arguments}`、
 *   `{type:"function_call_output", call_id, output}`；
 * - 图片内容 part 类型为 `input_image`，文本为 `input_text` / `output_text`；
 * - 工具定义为扁平结构 `{type:"function", name, description, parameters}`；
 * - 最大输出 token 字段名为 `max_output_tokens`；
 * - 推理参数为顶层 `reasoning: {effort: low|medium|high}`；
 * - 流式事件为 response.* 事件族：`response.output_text.delta`（正文增量）、
 *   `response.reasoning_text.delta` / `response.reasoning_summary_text.delta`（推理增量）、
 *   `response.output_item.added` / `response.function_call_arguments.delta`（工具调用分片）、
 *   `response.completed`（最终 usage 与完整输出）。
 */
internal class ResponsesApi(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun chat(model: ModelConfig, messages: List<ApiMessage>): ChatResult =
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(buildRequest(model, messages, stream = false)).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    if (response.code == 429) {
                        throw ProviderClient.rateLimitException(response.code, body, response.header("Retry-After"))
                    }
                    throw IllegalStateException("Responses 请求失败 HTTP ${response.code}：${extractError(body)}")
                }
                parseFinalResponse(body)
            }
        }

    @OptIn(InternalCoroutinesApi::class)
    suspend fun chatStream(
        model: ModelConfig,
        messages: List<ApiMessage>,
        onReasoning: (String) -> Unit = {},
        onToolProgress: (ToolCallStreamProgress) -> Unit = {},
        onDelta: (String) -> Unit,
    ): ChatResult = withContext(Dispatchers.IO) {
        val call = okHttpClient.newCall(buildRequest(model, messages, stream = true))
        // 与 ChatApi 一致：取消时立即关闭 socket，保证"停止"秒级生效
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion(onCancelling = true) { call.cancel() }
        val firstEventTimeoutMs = ProviderClient.resolveFirstEventTimeoutMs(
            ProviderClient.estimateApiMessageTokens(messages),
        )
        val firstEventState = AtomicInteger(ProviderClient.FIRST_EVENT_WAITING)
        val firstEventWatchdog = launch {
            delay(firstEventTimeoutMs)
            if (firstEventState.compareAndSet(ProviderClient.FIRST_EVENT_WAITING, ProviderClient.FIRST_EVENT_TIMED_OUT)) {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body.string().take(512)
                    if (response.code == 429) {
                        throw ProviderClient.rateLimitException(response.code, rawBody, response.header("Retry-After"))
                    }
                    throw IllegalStateException("Responses 请求失败 HTTP ${response.code}：${extractError(rawBody)}")
                }
                val source = response.body.source()
                val demuxer = ThinkTagStreamDemuxer(onReasoning, onDelta)
                // item_id -> 工具调用累积器（Responses 以 item_id 区分同一轮多个 function_call）
                val toolCalls = mutableMapOf<String, ToolCallAccumulator>()
                var usage = ChatUsage()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    val event = runCatching {
                        json.parseToJsonElement(data) as? JsonObject
                    }.getOrNull() ?: continue
                    if (firstEventState.compareAndSet(
                            ProviderClient.FIRST_EVENT_WAITING,
                            ProviderClient.FIRST_EVENT_RECEIVED,
                        )
                    ) {
                        firstEventWatchdog.cancel()
                    }
                    when (event["type"]?.jsonPrimitive?.contentOrNull) {
                        // 正文增量
                        "response.output_text.delta" -> {
                            event["delta"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                                ?.let { demuxer.onContentChunk(it) }
                        }
                        // 推理增量：detail 推理与 summary 摘要都作为推理内容回传
                        "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> {
                            event["delta"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                                ?.let { demuxer.onExplicitReasoningChunk(it) }
                        }
                        // function_call item 开始：携带 call_id 与 name
                        "response.output_item.added" -> {
                            val item = event["item"] as? JsonObject ?: continue
                            if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
                                val itemId = item["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val accum = toolCalls.getOrPut(itemId) { ToolCallAccumulator() }
                                accum.id = item["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                                    ?: itemId
                                accum.name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                accum.publishProgress(onToolProgress)
                            }
                        }
                        // function_call 参数增量分片
                        "response.function_call_arguments.delta" -> {
                            val itemId = event["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val chunk = event["delta"]?.jsonPrimitive?.contentOrNull
                            if (!chunk.isNullOrEmpty()) {
                                val accum = toolCalls.getOrPut(itemId) { ToolCallAccumulator() }
                                accum.arguments.append(chunk)
                                accum.publishProgress(onToolProgress)
                            }
                        }
                        // 兼容部分网关不下发 arguments.delta、只在 item.done 里带完整参数的情况
                        "response.output_item.done" -> {
                            val item = event["item"] as? JsonObject ?: continue
                            if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
                                val itemId = item["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val accum = toolCalls.getOrPut(itemId) { ToolCallAccumulator() }
                                if (accum.id.isBlank()) {
                                    accum.id = item["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                                        ?: itemId
                                }
                                if (accum.name.isBlank()) accum.name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val args = item["arguments"]?.jsonPrimitive?.contentOrNull
                                if (args != null && accum.arguments.isEmpty() && args.isNotBlank()) {
                                    accum.arguments.append(args)
                                }
                                accum.publishProgress(onToolProgress)
                            }
                        }
                        // 结束：携带最终 usage（与完整 output）
                        "response.completed" -> {
                            ((event["response"] as? JsonObject)?.get("usage") as? JsonObject)
                                ?.let { usage = parseUsage(it) }
                            break
                        }
                        "response.failed" -> {
                            val error = (event["response"] as? JsonObject)?.get("error") as? JsonObject
                            val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "响应处理失败"
                            throw IllegalStateException("Responses 请求失败：$message")
                        }
                        "error" -> {
                            val message = event["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
                            val code = event["code"]?.jsonPrimitive?.contentOrNull
                            throw IllegalStateException(
                                "Responses 请求失败：$message" + (code?.let { " (code=$it)" } ?: ""),
                            )
                        }
                        else -> Unit // response.created / in_progress / content_part.* 等无需处理
                    }
                }
                demuxer.flush()
                toolCalls.values.forEach { it.publishProgress(onToolProgress, force = true) }
                // 无参数函数可能不下发 arguments 分片，空串兜底为 "{}"
                val calls = toolCalls.values.map {
                    ApiToolCallSpec(it.id, it.name, it.arguments.toString().ifBlank { "{}" })
                }
                ChatResult(
                    content = demuxer.fullText.toString().ifEmpty { null },
                    toolCalls = calls,
                    reasoningContent = demuxer.fullReasoning.toString().ifEmpty { null },
                    usage = usage,
                )
            }
        } catch (io: IOException) {
            if (firstEventState.get() == ProviderClient.FIRST_EVENT_TIMED_OUT) {
                throw SocketTimeoutException(
                    "等待模型首个响应超过 ${firstEventTimeoutMs / 1000}s",
                ).apply { initCause(io) }
            }
            throw io
        } finally {
            firstEventWatchdog.cancel()
            cancelHandle?.dispose()
        }
    }

    private fun buildRequest(model: ModelConfig, messages: List<ApiMessage>, stream: Boolean): Request {
        val systemPrompt = StringBuilder()
        val inputItems = buildJsonArray {
            var index = 0
            while (index < messages.size) {
                val message = messages[index]
                when (message.role) {
                    "system" -> {
                        // 系统提示词聚合到顶层 instructions
                        if (!message.content.isNullOrBlank()) {
                            if (systemPrompt.isNotEmpty()) systemPrompt.append("\n\n")
                            systemPrompt.append(message.content)
                        }
                        index++
                    }
                    "user" -> {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    buildJsonArray {
                                        if (!message.content.isNullOrBlank()) {
                                            add(
                                                buildJsonObject {
                                                    put("type", "input_text")
                                                    put("text", message.content)
                                                },
                                            )
                                        }
                                        message.imageUrls.forEach { url ->
                                            add(
                                                buildJsonObject {
                                                    put("type", "input_image")
                                                    put("image_url", url)
                                                },
                                            )
                                        }
                                        if (message.content.isNullOrBlank() && message.imageUrls.isEmpty()) {
                                            // 空 user 消息补一个空文本，避免 content 空数组被 400
                                            add(
                                                buildJsonObject {
                                                    put("type", "input_text")
                                                    put("text", "")
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                        index++
                    }
                    "assistant" -> {
                        add(
                            buildJsonObject {
                                put("role", "assistant")
                                put(
                                    "content",
                                    buildJsonArray {
                                        if (!message.content.isNullOrBlank()) {
                                            add(
                                                buildJsonObject {
                                                    put("type", "output_text")
                                                    put("text", message.content)
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                        // 推理内容回传为 reasoning item（可选；保持多轮上下文一致）
                        if (!message.reasoning_content.isNullOrBlank()) {
                            add(
                                buildJsonObject {
                                    put("type", "reasoning")
                                    put(
                                        "summary",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("type", "summary_text")
                                                    put("text", message.reasoning_content)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                        // 历史工具调用：以 function_call item 逐条回传
                        message.tool_calls.orEmpty().forEach { call ->
                            add(
                                buildJsonObject {
                                    put("type", "function_call")
                                    put("call_id", call.id)
                                    put("name", call.function.name)
                                    put("arguments", call.function.arguments.ifBlank { "{}" })
                                },
                            )
                        }
                        index++
                    }
                    "tool" -> {
                        add(
                            buildJsonObject {
                                put("type", "function_call_output")
                                put("call_id", message.tool_call_id.orEmpty())
                                put("output", message.content.orEmpty())
                            },
                        )
                        index++
                    }
                    else -> index++
                }
            }
        }

        val dynamicTools = if (model.pureChatMode) emptyList() else ProviderClient.buildDynamicTools(model.dynamicMcpTools)
        // JSON_TEXT 模式：工具定义写进 instructions，模型用文本输出工具调用
        if (!model.pureChatMode && model.toolCallMode == ToolCallMode.JSON_TEXT && dynamicTools.isNotEmpty()) {
            systemPrompt.append("\n\n## 可用工具 JSON 定义（必须严格按此 name 与参数输出）\n")
                .append(ProviderClient.buildToolsTextDescription(dynamicTools))
        }

        val requestBody = buildJsonObject {
            put("model", model.model)
            put("stream", stream)
            model.temperature?.let { put("temperature", it) }
            model.topP?.let { put("top_p", it) }
            // Responses API 的输出上限字段名与 chat/completions 不同
            model.maxTokens?.let { put("max_output_tokens", it) }
            // 推理开关/强度：Responses 专用 reasoning.effort 格式
            ReasoningAdapter.responsesFields(model).forEach { (key, value) -> put(key, value) }
            if (systemPrompt.isNotBlank()) put("instructions", systemPrompt.toString())
            put("input", inputItems)
            // 仅 NATIVE 模式注入标准 tools；纯净模式与 JSON_TEXT / DISABLED 均不注入
            if (!model.pureChatMode && model.toolCallMode == ToolCallMode.NATIVE && dynamicTools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        dynamicTools.forEach { definition ->
                            add(
                                buildJsonObject {
                                    put("type", "function")
                                    put("name", definition.function.name)
                                    put("description", definition.function.description)
                                    put("parameters", definition.function.parameters)
                                },
                            )
                        }
                    },
                )
                put("tool_choice", "auto")
            }
        }

        return Request.Builder()
            .url("${model.baseUrl.trimEnd('/')}/responses")
            .header("Content-Type", "application/json")
            .apply {
                model.apiKey?.let { header("Authorization", "Bearer $it") }
                ProviderClient.parseCustomHeaders(model.customHeaders).forEach { (name, value) ->
                    header(name, value)
                }
            }
            .post(requestBody.toString().encodeToByteArray().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /** 非流式响应：解析 output 数组（message / function_call / reasoning）与顶层 usage。 */
    private fun parseFinalResponse(body: String): ChatResult {
        if (!ProviderClient.looksLikeJsonResponse(body)) {
            throw IllegalStateException(ProviderClient.formatHttpErrorMessage(200, body))
        }
        val root = json.parseToJsonElement(body).jsonObject
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ApiToolCallSpec>()
        (root["output"]?.jsonArray.orEmpty()).forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            when (item["type"]?.jsonPrimitive?.contentOrNull) {
                "message" -> {
                    (item["content"]?.jsonArray.orEmpty()).forEach { part ->
                        val partObj = part as? JsonObject ?: return@forEach
                        if (partObj["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                            text.append(partObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        }
                    }
                }
                "function_call" -> calls += ApiToolCallSpec(
                    id = item["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: item["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: ToolCallIdNormalizer.normalize(null),
                    name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    argumentsJson = item["arguments"]?.jsonPrimitive?.contentOrNull?.ifBlank { "{}" } ?: "{}",
                )
                "reasoning" -> {
                    (item["summary"]?.jsonArray.orEmpty()).forEach { part ->
                        val partObj = part as? JsonObject ?: return@forEach
                        if (partObj["type"]?.jsonPrimitive?.contentOrNull == "summary_text") {
                            reasoning.append(partObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        }
                    }
                }
            }
        }
        return ChatResult(
            content = text.toString().ifEmpty { null },
            toolCalls = calls,
            reasoningContent = reasoning.toString().ifEmpty { null },
            usage = (root["usage"] as? JsonObject)?.let { parseUsage(it) } ?: ChatUsage(),
        )
    }

    /** Responses API usage：input/output_tokens + cached/reasoning details。 */
    private fun parseUsage(usage: JsonObject): ChatUsage = ChatUsage(
        inputTokens = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
        outputTokens = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
        reasoningTokens = (usage["output_tokens_details"] as? JsonObject)
            ?.get("reasoning_tokens")?.jsonPrimitive?.longOrNull ?: 0,
        cacheReadTokens = (usage["input_tokens_details"] as? JsonObject)
            ?.get("cached_tokens")?.jsonPrimitive?.longOrNull ?: 0,
    )

    private fun extractError(body: String): String {
        val message = runCatching {
            val obj = json.parseToJsonElement(body) as? JsonObject
            val error = (obj?.get("error") as? JsonObject)
                ?: (obj?.get("response") as? JsonObject)?.get("error") as? JsonObject
            error?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return message ?: body.take(512)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
