package top.wanxiang.app.harness.mcp

import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.core.model.BuiltinMcpPresets
import top.wanxiang.app.core.model.McpServerConfig
import top.wanxiang.app.core.model.McpToolInfo
import top.wanxiang.app.harness.mcp.server.BuiltinBrowserMcpAccess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 远程 MCP 传输：同时支持 Streamable HTTP（2025-03-26+）与 legacy HTTP+SSE（2024-11-05）。
 *
 * 连接策略：
 * - 会话按服务 id 复用，一次握手（initialize + notifications/initialized）后所有请求直接复用；
 * - 自动协商：URL 以 /sse 结尾优先尝试 legacy SSE，否则先试 Streamable HTTP，失败回落另一种；
 * - initialize/tools-list 等只读发现请求遇到传输故障时允许重建并重试一次；
 * - tools/call 永不自动重试，响应丢失时返回不确定结果，避免重复执行副作用。
 */
@Singleton
class McpHttpTransport @Inject constructor(
    client: OkHttpClient,
    private val json: Json,
    private val logger: AppLogger,
) : McpTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, HttpSession>()
    private val sessionMutexes = ConcurrentHashMap<String, Mutex>()

    /** 连接失败冷却：服务 id → 冷却截止时间戳。冷却期内直接快速失败，不再空耗连接超时。 */
    private val downUntil = ConcurrentHashMap<String, Long>()

    // 握手/列表用短超时；工具调用可能耗时较长；SSE 长连接读流不设超时。
    // connectTimeout 单独收紧：本地端口无服务时（ECONNREFUSED 被系统延迟上报）也要快速失败。
    private val fastClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(FAST_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(FAST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    /** POSTs that may already have taken effect must not be transparently replayed by OkHttp. */
    private val nonRetryingFastClient = fastClient.newBuilder()
        .retryOnConnectionFailure(false)
        .build()
    private val callClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(CALL_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val sseClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(CALL_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun check(server: McpServerConfig) = withContext(Dispatchers.IO) {
        // 设置页手动测试连接不受冷却限制
        runCatching { ensureSession(server, bypassCooldown = true); true }.getOrDefault(false)
    }

    override suspend fun discover(server: McpServerConfig): List<McpToolInfo> = withContext(Dispatchers.IO) {
        withRetryableSession(server) { session ->
            val response = exchange(session, "tools/list", JsonObject(emptyMap()))
            val result = response.result?.let { json.decodeFromJsonElement(McpToolsListResponse.serializer(), it) }
                ?: error("MCP tools/list did not return a result")
            result.tools.map { it.toInfo(server, json.encodeToString(JsonObject.serializer(), it.inputSchema)) }
        }
    }

    override suspend fun execute(server: McpServerConfig, toolName: String, arguments: JsonObject): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val session = ensureSession(server)
            try {
                val params = json.encodeToJsonElement(
                    McpCallToolParams.serializer(),
                    McpCallToolParams(toolName, arguments),
                )
                val response = exchange(session, "tools/call", params)
                val result = response.result?.let { json.decodeFromJsonElement(McpCallToolResult.serializer(), it) }
                    ?: error("MCP tools/call did not return a result")
                !result.isError to result.content.joinToString("\n") { it.text.orEmpty() }
                    .ifBlank { if (result.isError) "执行失败" else "执行成功" }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (isTransportFailure(t)) {
                    dropSession(server.id, session)
                    throw IOException("MCP 工具响应丢失；为避免副作用重复，未自动重试 tools/call", t)
                }
                throw t
            }
        }

    // ---------- 会话管理 ----------

    private suspend fun <T> withRetryableSession(server: McpServerConfig, block: suspend (HttpSession) -> T): T {
        val session = ensureSession(server)
        return try {
            block(session)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (!isTransportFailure(t)) throw t
            logger.w("MCP[${server.name}] 会话失效（${t.message}），重建后重试一次")
            dropSession(server.id, session)
            block(ensureSession(server))
        }
    }

    private fun isTransportFailure(t: Throwable): Boolean =
        t is IOException || (t is IllegalStateException && t.message?.startsWith("MCP HTTP ") == true)

    private suspend fun ensureSession(server: McpServerConfig, bypassCooldown: Boolean = false): HttpSession =
        sessionMutexes.getOrPut(server.id) { Mutex() }.withLock {
            ensureSessionLocked(server, bypassCooldown)
        }

    private suspend fun ensureSessionLocked(server: McpServerConfig, bypassCooldown: Boolean): HttpSession {
        val url = effectiveUrlOf(server)
        sessions[server.id]?.let { existing ->
            if (existing.serverUrl == url && existing.isOpen) return existing
            dropSession(server.id, existing)
        }
        // 冷却期内直接快速失败：上一轮刚整体握手失败，短时间内大概率仍是同一个故障
        // （进程未启动 / 端口不对），不值得每轮对话都空耗连接超时。
        val now = System.currentTimeMillis()
        if (!bypassCooldown) {
            val until = downUntil[server.id]
            if (until != null && now < until) {
                throw IllegalStateException(
                    "MCP[${server.name}] 连接冷却中（剩余 ${(until - now) / 1000}s），跳过本次连接；" +
                        "请确认服务端已启动后再在设置页手动测试",
                )
            }
        }
        val preferLegacy = runCatching {
            validatedMcpHttpEndpoint(url).encodedPath.trimEnd('/').endsWith("sse")
        }.getOrDefault(false)
        val attempts = if (preferLegacy) {
            listOf(TransportMode.LEGACY_SSE, TransportMode.STREAMABLE_HTTP)
        } else {
            listOf(TransportMode.STREAMABLE_HTTP, TransportMode.LEGACY_SSE)
        }
        val failures = mutableListOf<String>()
        for (mode in attempts) {
            val label = modeLabel(mode)
            try {
                val session = if (mode == TransportMode.STREAMABLE_HTTP) {
                    createStreamableSession(server)
                } else {
                    createLegacySession(server)
                }
                sessions[server.id] = session
                downUntil.remove(server.id)
                logger.i("MCP[${server.name}] 连接成功（$label）endpoint=${session.endpoint}")
                return session
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                failures += "$label: ${t.message ?: t::class.simpleName}"
                logger.w("MCP[${server.name}] $label 握手失败: ${t.message}", t)
            }
        }
        downUntil[server.id] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
        throw IllegalStateException("MCP HTTP 连接失败（${failures.joinToString("；")}）")
    }

    private fun dropSession(serverId: String, session: HttpSession) {
        sessions.remove(serverId, session)
        session.legacy?.close()
    }

    /**
     * 解析目标 server 的 Bearer token：config 显式携带优先；
     * 内置 browser server（自环）回退到 bootstrap 生成的运行时 token，
     * 否则认证恒开启的进程内 server 会拒绝自环请求。
     */
    private fun bearerOf(server: McpServerConfig): String? {
        server.authToken.takeIf { it.isNotBlank() }?.let { return it }
        if (server.id == BuiltinMcpPresets.BROWSER_BUILTIN_ID) return BuiltinBrowserMcpAccess.token
        return null
    }

    /**
     * 解析目标 server 的实际 URL。内置 browser server 的首选端口被占用时，
     * server 会顺延绑定到相邻端口（见 [McpServerRuntime]），此时静态预设 URL 已过期，
     * 以 [BuiltinBrowserMcpAccess.port] 为准替换 URL 中的端口。
     */
    private fun effectiveUrlOf(server: McpServerConfig): String =
        resolveBrowserEffectiveUrl(server.serverUrl.trim(), server.id, BuiltinBrowserMcpAccess.port)

    private suspend fun createStreamableSession(server: McpServerConfig): HttpSession {
        val url = effectiveUrlOf(server)
        val endpoint = validatedMcpHttpEndpoint(url)
        val bearer = bearerOf(server)
        val params = json.encodeToJsonElement(McpInitializeParams.serializer(), McpInitializeParams())
        val init = postStreamable(endpoint, sessionId = null, method = "initialize", params = params, bearer = bearer)
        val result = init.response.result?.let { json.decodeFromJsonElement(McpInitializeResult.serializer(), it) }
            ?: error("MCP initialize did not return a result")
        val session = HttpSession(
            mode = TransportMode.STREAMABLE_HTTP,
            endpoint = endpoint,
            sessionId = init.sessionId,
            protocolVersion = result.protocolVersion,
            serverUrl = url,
            bearer = bearer,
        )
        postNotification(session, "notifications/initialized")
        return session
    }

    private suspend fun createLegacySession(server: McpServerConfig): HttpSession {
        val url = effectiveUrlOf(server)
        val sseUrl = validatedMcpHttpEndpoint(url)
        val bearer = bearerOf(server)
        val channel = LegacySseChannel(sseUrl, sseClient, json, scope)
        try {
            val messageEndpoint = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { channel.awaitEndpoint() }
                ?: error("Legacy SSE 握手超时：${HANDSHAKE_TIMEOUT_MS / 1000}s 内未收到 endpoint 事件")
            val params = json.encodeToJsonElement(McpInitializeParams.serializer(), McpInitializeParams())
            val init = postViaLegacy(channel, "initialize", params, bearer)
            val result = init.result?.let { json.decodeFromJsonElement(McpInitializeResult.serializer(), it) }
                ?: error("MCP initialize did not return a result")
            postViaLegacyNotification(channel, "notifications/initialized", bearer)
            return HttpSession(
                mode = TransportMode.LEGACY_SSE,
                endpoint = messageEndpoint,
                sessionId = null,
                protocolVersion = result.protocolVersion,
                serverUrl = url,
                legacy = channel,
                bearer = bearer,
            )
        } catch (t: Throwable) {
            channel.close()
            throw t
        }
    }

    // ---------- 请求执行 ----------

    private suspend fun exchange(session: HttpSession, method: String, params: JsonElement): JsonRpcResponse =
        when (session.mode) {
            TransportMode.STREAMABLE_HTTP -> postStreamable(
                endpoint = session.endpoint,
                sessionId = session.sessionId,
                method = method,
                params = params,
                longRunning = method == "tools/call",
                bearer = session.bearer,
            ).response
            TransportMode.LEGACY_SSE -> postViaLegacy(
                channel = session.legacy ?: error("Legacy SSE 会话已关闭"),
                method = method,
                params = params,
                bearer = session.bearer,
            )
        }

    private suspend fun postStreamable(
        endpoint: HttpUrl,
        sessionId: String?,
        method: String,
        params: JsonElement,
        longRunning: Boolean = false,
        bearer: String? = null,
    ): StreamableExchange {
        val id = UUID.randomUUID().toString()
        val payload = json.encodeToString(JsonRpcRequest.serializer(), JsonRpcRequest(id = id, method = method, params = params))
        val request = requestBuilder(endpoint, sessionId, bearer)
            .header("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
            .post(payload.toRequestBody(JSON))
            .build()
        val client = if (longRunning) callClient else fastClient
        return client.newCall(request).executeCancellable().use { response ->
            check(response.isSuccessful) { "MCP HTTP ${response.code}" }
            val rpc = readResponse(response, id)
            rpc.error?.let { error("MCP JSON-RPC ${it.code}: ${it.message}") }
            StreamableExchange(rpc, response.header("Mcp-Session-Id"))
        }
    }

    private suspend fun postNotification(session: HttpSession, method: String) {
        val payload = json.encodeToString(JsonRpcNotification.serializer(), JsonRpcNotification(method = method))
        val request = requestBuilder(session.endpoint, session.sessionId, session.bearer)
            .header("MCP-Protocol-Version", session.protocolVersion)
            .post(payload.toRequestBody(JSON))
            .build()
        fastClient.newCall(request).executeCancellable().use { check(it.isSuccessful) { "MCP HTTP ${it.code}" } }
    }

    private suspend fun postViaLegacy(
        channel: LegacySseChannel,
        method: String,
        params: JsonElement,
        bearer: String? = null,
    ): JsonRpcResponse {
        val id = UUID.randomUUID().toString()
        val payload = json.encodeToString(JsonRpcRequest.serializer(), JsonRpcRequest(id = id, method = method, params = params))
        val deferred = channel.register(id)
        return try {
            postLegacyPayload(channel, payload, retryable = method != "tools/call", bearer = bearer)
            withTimeoutOrNull(CALL_TIMEOUT_MS.milliseconds) { channel.await(id, deferred) }
                ?: error("MCP 响应超时（${CALL_TIMEOUT_MS / 1000}s）")
        } catch (t: Throwable) {
            channel.unregister(id)
            throw t
        }
    }

    private suspend fun postViaLegacyNotification(channel: LegacySseChannel, method: String, bearer: String? = null) {
        val payload = json.encodeToString(JsonRpcNotification.serializer(), JsonRpcNotification(method = method))
        postLegacyPayload(channel, payload, retryable = true, bearer = bearer)
    }

    private suspend fun postLegacyPayload(channel: LegacySseChannel, payload: String, retryable: Boolean, bearer: String? = null) {
        val endpoint = channel.messageEndpoint ?: error("Legacy SSE 消息端点未就绪")
        val request = Request.Builder().url(endpoint).header("Accept", ACCEPT)
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .post(payload.toRequestBody(JSON)).build()
        val client = if (retryable) fastClient else nonRetryingFastClient
        client.newCall(request).executeCancellable().use { check(it.isSuccessful) { "MCP HTTP ${it.code}" } }
    }

    private suspend fun Call.executeCancellable(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private fun requestBuilder(endpoint: HttpUrl, sessionId: String?, bearer: String? = null) = Request.Builder()
        .url(endpoint).header("Accept", ACCEPT)
        .apply {
            sessionId?.let { header("Mcp-Session-Id", it) }
            bearer?.let { header("Authorization", "Bearer $it") }
        }

    // ---------- 响应解析 ----------

    private fun readResponse(response: Response, requestId: String): JsonRpcResponse =
        if (response.header("Content-Type").orEmpty().lowercase().startsWith("text/event-stream")) {
            readSse(response, requestId)
        } else {
            json.decodeFromString(JsonRpcResponse.serializer(), readLimited(response))
        }

    private fun readSse(response: Response, requestId: String): JsonRpcResponse {
        val source = response.body.source()
        val data = mutableListOf<String>()
        var bytes = 0
        while (!source.exhausted()) {
            val line = source.readUtf8LineStrict(MAX_SSE_LINE_BYTES.toLong())
            bytes += line.toByteArray().size + 1
            require(bytes <= MAX_BYTES) { "MCP response is too large" }
            if (line.isBlank()) {
                decodeSse(data, requestId)?.let { return it }
                data.clear()
            } else if (line.startsWith("data:")) data += line.removePrefix("data:").trimStart()
        }
        return decodeSse(data, requestId) ?: error("MCP SSE response did not contain request $requestId")
    }

    private fun decodeSse(lines: List<String>, id: String) =
        runCatching { json.decodeFromString(JsonRpcResponse.serializer(), lines.joinToString("\n")) }
            .getOrNull()?.takeIf { it.id == id }

    private fun readLimited(response: Response): String {
        val length = response.body.contentLength()
        require(length < 0 || length <= MAX_BYTES) { "MCP response is too large" }
        val output = java.io.ByteArrayOutputStream()
        response.body.byteStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= MAX_BYTES) { "MCP response is too large" }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun modeLabel(mode: TransportMode) = when (mode) {
        TransportMode.STREAMABLE_HTTP -> "Streamable HTTP"
        TransportMode.LEGACY_SSE -> "Legacy SSE"
    }

    private enum class TransportMode { STREAMABLE_HTTP, LEGACY_SSE }

    private class StreamableExchange(val response: JsonRpcResponse, val sessionId: String?)

    private class HttpSession(
        val mode: TransportMode,
        val endpoint: HttpUrl,
        val sessionId: String?,
        val protocolVersion: String,
        val serverUrl: String,
        val legacy: LegacySseChannel? = null,
        val bearer: String? = null,
    ) {
        /** Streamable 会话视为一直可用（失效由传输层错误触发重建）；Legacy 会话看读流是否存活 */
        val isOpen: Boolean get() = legacy == null || legacy.isAlive
    }

    /**
     * legacy HTTP+SSE 通道：GET 打开长连接接收服务端事件，
     * 请求 POST 到握手时下发的消息端点，响应经长连接按 id 回流。
     */
    private class LegacySseChannel(
        private val url: HttpUrl,
        sseClient: OkHttpClient,
        private val json: Json,
        scope: CoroutineScope,
    ) {
        private val call = sseClient.newCall(
            Request.Builder().url(url).header("Accept", "text/event-stream").build(),
        )
        private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>()
        private val endpointDeferred = CompletableDeferred<HttpUrl>()

        @Volatile private var closed = false

        @Volatile var messageEndpoint: HttpUrl? = null
            private set

        val isAlive: Boolean get() = !closed && endpointDeferred.isCompleted

        private val reader = scope.launch { readLoop() }

        private suspend fun readLoop() {
            runCatching {
                call.execute().use { response ->
                    check(response.isSuccessful) { "MCP HTTP ${response.code}" }
                    var event = ""
                    val data = mutableListOf<String>()
                    var eventBytes = 0
                    val source = response.body.source()
                    while (!source.exhausted()) {
                        val line = source.readUtf8LineStrict(MAX_SSE_LINE_BYTES.toLong())
                        eventBytes += line.toByteArray(Charsets.UTF_8).size + 1
                        require(eventBytes <= MAX_BYTES) { "MCP SSE event is too large" }
                        when {
                            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> data += line.removePrefix("data:").trimStart()
                            line.isBlank() -> {
                                onEvent(event, data)
                                event = ""
                                data.clear()
                                eventBytes = 0
                            }
                        }
                    }
                    error("SSE 连接已关闭")
                }
            }.onFailure { fail(it) }
        }

        private fun onEvent(event: String, data: List<String>) {
            if (data.isEmpty()) return
            if (event == "endpoint") {
                val resolved = url.resolve(data.first())
                if (resolved == null) {
                    endpointDeferred.completeExceptionally(
                        IllegalStateException("无法解析 endpoint 地址：${data.first()}"),
                    )
                } else {
                    runCatching { validatedDerivedMcpEndpoint(url, resolved) }
                        .onSuccess {
                            messageEndpoint = it
                            endpointDeferred.complete(it)
                        }
                        .onFailure { endpointDeferred.completeExceptionally(it) }
                }
                return
            }
            val rpc = runCatching {
                json.decodeFromString(JsonRpcResponse.serializer(), data.joinToString("\n"))
            }.getOrNull() ?: return
            rpc.id?.let { key -> pending.remove(key)?.complete(rpc) }
        }

        fun register(id: String): CompletableDeferred<JsonRpcResponse> {
            val deferred = CompletableDeferred<JsonRpcResponse>()
            pending[id] = deferred
            return deferred
        }

        fun unregister(id: String) {
            pending.remove(id)
        }

        suspend fun awaitEndpoint(): HttpUrl = endpointDeferred.await()

        suspend fun await(id: String, deferred: CompletableDeferred<JsonRpcResponse>): JsonRpcResponse =
            try {
                deferred.await()
            } finally {
                pending.remove(id)
            }

        fun close() {
            if (closed) return
            closed = true
            call.cancel()
            reader.cancel()
        }

        private fun fail(t: Throwable) {
            closed = true
            endpointDeferred.completeExceptionally(t)
            pending.values.forEach { it.completeExceptionally(t) }
            pending.clear()
        }
    }

    companion object {
        private const val ACCEPT = "application/json, text/event-stream"
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val MAX_SSE_LINE_BYTES = 1 * 1024 * 1024
        private const val FAST_TIMEOUT_MS = 5_000L
        private const val CALL_TIMEOUT_MS = 120_000L
        private const val HANDSHAKE_TIMEOUT_MS = 4_000L

        /** TCP 连接超时：本地地址端口无服务时快速失败，避免 30s 级别的空等。 */
        private const val FAST_CONNECT_TIMEOUT_MS = 3_000L
        private const val CALL_CONNECT_TIMEOUT_MS = 10_000L

        /** 整体握手失败后的冷却时长：期间该服务的工具发现直接快速失败。 */
        private const val FAILURE_COOLDOWN_MS = 5 * 60_000L
        private val JSON = "application/json".toMediaType()
    }
}

/**
 * 解析内置 browser server 的"实际"URL：仅当日标服务是内置 browser 且已绑定运行时端口时，
 * 用运行时端口替换静态预设 URL 里的首选端口；否则原样返回。纯函数，便于对端口顺延做回归测试。
 *
 * 这是"发现与调用分离"的分界：provider 可见工具 schema（[top.wanxiang.app.harness.mcp.server.McpToolDispatcher.listTools]）
 * 不含任何端口，真实端口只在 tools/call 连接阶段经此解析注入，因此绑定端口变化不改变 provider 可见表面。
 */
internal fun resolveBrowserEffectiveUrl(serverUrl: String, serverId: String, runtimePort: Int?): String {
    if (runtimePort == null) return serverUrl
    if (serverId != BuiltinMcpPresets.BROWSER_BUILTIN_ID) return serverUrl
    return serverUrl.replace(Regex("^(https?://[^/:]+):\\d+"), "$1:$runtimePort")
}

internal fun isAllowedCleartextMcpHost(host: String): Boolean {
    if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
    val octets = host.split('.')
    if (octets.size != 4 || octets[0] != "192" || octets[1] != "168") return false
    return octets.all { it.toIntOrNull()?.let { value -> value in 0..255 } == true }
}

internal fun validatedMcpHttpEndpoint(baseUrl: String): HttpUrl {
    val url = baseUrl.trim().toHttpUrl()
    require(url.isHttps || isAllowedCleartextMcpHost(url.host)) {
        "明文 MCP 仅允许 localhost、回环地址或 192.168.* 局域网地址"
    }
    return url
}

internal fun validatedDerivedMcpEndpoint(base: HttpUrl, resolved: HttpUrl): HttpUrl {
    require(base.scheme == resolved.scheme && base.host == resolved.host && base.port == resolved.port) {
        "MCP SSE endpoint 必须与初始服务同源"
    }
    return validatedMcpHttpEndpoint(resolved.toString())
}
