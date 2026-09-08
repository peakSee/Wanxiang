package top.wkbin.taixu.runtime.webchat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.database.HarnessSessionEntity
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.QuickPhraseRepository
import top.wkbin.taixu.core.database.WorkspaceRepository
import top.wkbin.taixu.runtime.WorkspaceFileService
import top.wkbin.taixu.runtime.WorkspaceManager

const val DEFAULT_WEBCHAT_PORT = 8899

data class WebChatServerStatus(
    val isRunning: Boolean = false,
    val port: Int = DEFAULT_WEBCHAT_PORT,
    val localIp: String = "127.0.0.1",
    val pinCode: String = "",
    val activeConnections: Int = 0,
) {
    val accessUrl: String get() = "http://$localIp:$port"
}

/** LAN bridge for TaiXu's own Harness sessions and registered Linux workspaces. */
@Singleton
class WebChatBridgeServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: HarnessSessionRepository,
    private val models: AiModelRepository,
    private val quickPhrases: QuickPhraseRepository,
    private val workspaces: WorkspaceRepository,
    private val workspaceManager: WorkspaceManager,
    private val workspaceFiles: WorkspaceFileService,
    private val agentGateway: WebChatAgentGateway,
    private val logger: AppLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var httpServer: AndroidHttpServer? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val _status = MutableStateFlow(WebChatServerStatus())
    val status: StateFlow<WebChatServerStatus> = _status.asStateFlow()

    private val sseEmitters = ConcurrentHashMap.newKeySet<AndroidHttpExchange>()
    private val taskSessions = ConcurrentHashMap<String, String>()
    private val sessionObservers = ConcurrentHashMap<String, Job>()
    private var heartbeatJob: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    fun start(port: Int = DEFAULT_WEBCHAT_PORT, pin: String? = null): Boolean {
        if (_status.value.isRunning) return true
        return try {
            val generatedPin = pin ?: generatePin()
            // 工作线程池由 AndroidHttpServer 自己持有：stop() 时随服务一起回收，
            // 不再让本类持有一个永不 shutdown 的 fixedThreadPool。
            val server = AndroidHttpServer.create(InetSocketAddress(port), 0)
            server.createContext("/webchat/api/session/bootstrap", SessionBootstrapHandler())
            server.createContext("/webchat/api/bootstrap", BootstrapHandler())
            server.createContext("/webchat/api/conversations", ConversationsHandler())
            server.createContext("/webchat/api/tasks", TasksHandler())
            server.createContext("/webchat/api/events", SseEventsHandler())
            server.createContext("/webchat/api/workspaces", WorkspacesHandler())
            server.createContext("/", StaticAssetHandler())
            server.start()
            httpServer = server

            val localIp = resolveLocalIp()
            _status.value = WebChatServerStatus(true, port, localIp, generatedPin)
            acquireLocks()
            showNotification("http://$localIp:$port", generatedPin)
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch {
                while (isActive) {
                    delay(25_000)
                    if (sseEmitters.isNotEmpty()) {
                        broadcastEvent("ping", "{}")
                    }
                }
            }
            logger.i("太墟智枢 Web 协作服务启动成功：http://$localIp:$port")
            true
        } catch (exception: Exception) {
            logger.e("太墟智枢 Web 协作服务启动失败", exception)
            false
        }
    }

    fun stop() {
        runCatching {
            heartbeatJob?.cancel()
            heartbeatJob = null
            releaseLocks()
            hideNotification()
            sessionObservers.values.forEach(Job::cancel)
            sessionObservers.clear()
            taskSessions.clear()
            sseEmitters.forEach { runCatching { it.close() } }
            sseEmitters.clear()
            httpServer?.stop(0)
            httpServer = null
            _status.value = _status.value.copy(isRunning = false, activeConnections = 0)
        }.onFailure { logger.e("太墟智枢 Web 协作服务停止异常", it) }
    }

    fun broadcastEvent(eventName: String, dataJson: String) {
        val payload = "event: $eventName\ndata: $dataJson\n\n".toByteArray(Charsets.UTF_8)
        val iterator = sseEmitters.iterator()
        while (iterator.hasNext()) {
            val exchange = iterator.next()
            try {
                exchange.responseBody.write(payload)
                exchange.responseBody.flush()
            } catch (_: Exception) {
                runCatching { exchange.close() }
                iterator.remove()
            }
        }
        _status.value = _status.value.copy(activeConnections = sseEmitters.size)
    }

    /**
     * 请求协程的统一兜底。handler 在 [scope] 里异步执行，抛出的异常不会回到
     * [AndroidHttpServer] 的 accept 循环，socket 会连同工作线程一起挂住：
     * 这里保证异常路径也回一个 500 并最终关闭连接（[AndroidHttpExchange.close] 幂等）。
     *
     * 返回 Unit 而不是 Job：调用点是 `override fun handle(...) = launchRequest(...) { }`，
     * 返回 Job 会迫使每个 handler 末尾补一个 `.let { }` 才能满足 Unit 覆写。
     */
    private fun launchRequest(
        exchange: AndroidHttpExchange,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        scope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                logger.e("太墟智枢 Web 请求处理失败：${exchange.requestURI.path}", throwable)
                if (!exchange.isResponseStarted) {
                    runCatching { sendJson(exchange, 500, errorJson(throwable.message ?: "请求处理失败")) }
                }
            } finally {
                runCatching { exchange.close() }
            }
        }
    }

    private inner class StaticAssetHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) {
            val path = exchange.requestURI.path.removePrefix("/").trimStart('/')
            val assetPath = if (path.isBlank() || !hasFileExtension(path)) "webchat/index.html" else "webchat/$path"
            val stream: InputStream = runCatching { context.assets.open(assetPath) }.getOrElse {
                if (hasFileExtension(path)) {
                    sendText(exchange, 404, "资源不存在")
                    return
                }
                context.assets.open("webchat/index.html")
            }
            stream.use { sendResponse(exchange, 200, getMimeType(assetPath), it.readBytes()) }
        }
    }

    private inner class SessionBootstrapHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) = launchRequest(exchange) {
            if (handlePreflight(exchange)) return@launchRequest
            val token = requestJson(exchange)["token"]?.jsonPrimitive?.content.orEmpty()
            if (token != _status.value.pinCode) sendJson(exchange, 401, errorJson("配对码不正确"))
            else sendJson(exchange, 200, buildJsonObject { put("authenticated", true) })
        }
    }

    private inner class BootstrapHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) = launchRequest(exchange) {
            if (handlePreflight(exchange)) return@launchRequest
            if (!requireAuthenticated(exchange)) return@launchRequest
            val configuredModels = models.observeAll().firstValue()
            // 与 ChatViewModel 一致先播种内置短语，避免聊天页从未打开时列表为空
            runCatching { quickPhrases.ensureInitialized() }
            val enabledPhrases = runCatching {
                quickPhrases.getAll()
                    .filter { it.isEnabled }
                    .sortedBy { it.sortOrder }
            }.getOrDefault(emptyList())
            sendJson(exchange, 200, buildJsonObject {
                put("authenticated", true)
                put("appName", "太墟智枢")
                put("version", "1.0")
                putJsonArray("models") {
                    configuredModels.forEach { model ->
                        add(buildJsonObject {
                            put("id", model.id)
                            put("name", model.name)
                            put("model", model.model)
                            put("active", model.isActive)
                        })
                    }
                }
                putJsonArray("quickPhrases") {
                    enabledPhrases.forEach { phrase ->
                        add(buildJsonObject {
                            put("id", phrase.id)
                            put("title", phrase.title)
                            put("content", phrase.content)
                            if (phrase.description.isNotBlank()) put("description", phrase.description)
                        })
                    }
                }
                put("workspace", buildJsonObject {
                    put("workspace", buildJsonObject { put("rootPath", "/workspace") })
                    put("root", buildJsonObject { put("path", "/workspace") })
                })
            })
        }
    }

    private inner class ConversationsHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) = launchRequest(exchange) {
            if (handlePreflight(exchange)) return@launchRequest
            if (!requireAuthenticated(exchange)) return@launchRequest
            try {
                val suffix = exchange.requestURI.path.substringAfter("/conversations", "").trim('/')
                val parts = suffix.split('/').filter(String::isNotBlank)
                when {
                    parts.isEmpty() && exchange.requestMethod == "GET" -> {
                        sendJson(exchange, 200, buildJsonArray {
                            sessions.listAll().sortedByDescending(HarnessSessionEntity::updatedAt).forEach { add(conversationJson(it)) }
                        })
                    }
                    parts.isEmpty() && exchange.requestMethod == "POST" -> {
                        val body = requestJson(exchange)
                        val workspace = body["workspace"]?.jsonPrimitive?.content.orEmpty()
                        val id = agentGateway.createSession(
                            body["title"]?.jsonPrimitive?.content.orEmpty(),
                            workspace.takeIf { isRegisteredWorkspacePath(it) }.orEmpty(),
                        )
                        val session = requireNotNull(sessions.findById(id))
                        sendJson(exchange, 200, conversationJson(session))
                        broadcastEvent("conversation_created", buildJsonObject { put("conversationId", id) }.toString())
                    }
                    parts.size == 1 && exchange.requestMethod == "DELETE" -> {
                        val id = parts[0]
                        requireNotNull(sessions.findById(id)) { "会话不存在" }
                        agentGateway.deleteSession(id)
                        sendJson(exchange, 200, buildJsonObject { put("deleted", true) })
                        broadcastEvent("conversation_deleted", buildJsonObject { put("conversationId", id) }.toString())
                    }
                    parts.size == 2 && parts[1] == "messages" && exchange.requestMethod == "GET" -> {
                        val id = parts[0]
                        requireNotNull(sessions.findById(id)) { "会话不存在" }
                        sendJson(exchange, 200, messageArray(agentGateway.messages(id)))
                    }
                    parts.size == 2 && parts[1] == "approvals" && exchange.requestMethod == "GET" -> {
                        val id = parts[0]
                        requireNotNull(sessions.findById(id)) { "会话不存在" }
                        sendJson(exchange, 200, approvalArray(agentGateway.pendingApprovals(id)))
                    }
                    parts.size == 3 && parts[1] == "approvals" && exchange.requestMethod == "POST" -> {
                        val sessionId = parts[0]
                        requireNotNull(sessions.findById(sessionId)) { "会话不存在" }
                        val body = requestJson(exchange)
                        val approved = body["approved"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                            ?: throw IllegalArgumentException("缺少审批决定")
                        val accepted = agentGateway.resolveApproval(sessionId, parts[2], approved)
                        require(accepted) { "审批请求不存在、已处理或不属于当前会话" }
                        val taskId = taskSessions.entries.firstOrNull { it.value == sessionId }?.key
                        sendJson(exchange, 200, buildJsonObject {
                            put("accepted", true)
                            taskId?.let { put("taskId", it) }
                        })
                    }
                    parts.size == 2 && parts[1] == "runs" && exchange.requestMethod == "POST" -> {
                        startRun(exchange, parts[0])
                    }
                    else -> sendText(exchange, 404, "接口不存在")
                }
            } catch (throwable: Throwable) {
                sendJson(exchange, 400, errorJson(throwable.message ?: "会话操作失败"))
            }
        }
    }

    private suspend fun startRun(exchange: AndroidHttpExchange, sessionId: String) {
        requireNotNull(sessions.findById(sessionId)) { "会话不存在" }
        val body = requestJson(exchange)
        val text = body["userMessage"]?.jsonPrimitive?.content.orEmpty()
        val imageUrls = body["attachments"]?.jsonArray.orEmpty().mapNotNull { item ->
            item.jsonObject["dataUrl"]?.jsonPrimitive?.content?.takeIf { it.startsWith("data:image/") }
        }
        require(text.isNotBlank() || imageUrls.isNotEmpty()) { "消息不能为空" }
        val taskId = body["taskId"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: "web-${System.currentTimeMillis()}"
        taskSessions[taskId] = sessionId
        agentGateway.send(sessionId, text, imageUrls)
        observeRun(sessionId, taskId)
        sendJson(exchange, 200, buildJsonObject {
            put("taskId", taskId)
            put("conversationMode", "normal")
            put("conversation", conversationJson(requireNotNull(sessions.findById(sessionId))))
        })
    }

    private fun observeRun(sessionId: String, taskId: String) {
        sessionObservers.remove(sessionId)?.cancel()
        sessionObservers[sessionId] = scope.launch {
            var observedRunning = false
            var waitingEventSent = false
            agentGateway.observeSession(sessionId).collect { snapshot ->
                if (snapshot.running) {
                    observedRunning = true
                    waitingEventSent = false
                }
                broadcastEvent("messages_replaced", buildJsonObject {
                    put("conversationId", sessionId)
                    put("conversationMode", "normal")
                    put("messages", messageArray(snapshot.messages))
                }.toString())
                if (snapshot.waitingApproval) {
                    if (!waitingEventSent) {
                        broadcastEvent("chat_task_event", taskEvent(taskId, "waiting_approval", sessionId, snapshot.approvals))
                        waitingEventSent = true
                    }
                } else if (observedRunning && !snapshot.running) {
                    broadcastEvent("chat_task_event", taskEvent(taskId, if (snapshot.error == null) "completed" else "error", sessionId))
                    taskSessions.remove(taskId)
                    cancel()
                }
            }
        }
    }

    private inner class TasksHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) = launchRequest(exchange) {
            if (handlePreflight(exchange)) return@launchRequest
            if (!requireAuthenticated(exchange)) return@launchRequest
            val suffix = exchange.requestURI.path.substringAfter("/tasks", "").trim('/')
            val parts = suffix.split('/').filter(String::isNotBlank)
            if (parts.size == 2 && parts[1] == "cancel" && exchange.requestMethod == "POST") {
                val taskId = parts[0]
                val sessionId = taskSessions.remove(taskId)
                if (sessionId != null) agentGateway.cancel(sessionId)
                sendJson(exchange, 200, buildJsonObject { put("cancelled", sessionId != null) })
            } else {
                sendText(exchange, 404, "任务接口不存在")
            }
        }
    }

    private inner class SseEventsHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) {
            if (handlePreflight(exchange)) return
            if (!isAuthenticated(exchange)) {
                sendJson(exchange, 401, errorJson("请先配对"))
                return
            }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.responseHeaders.add("Connection", "keep-alive")
            exchange.sendResponseHeaders(200, 0)
            sseEmitters.add(exchange)
            _status.value = _status.value.copy(activeConnections = sseEmitters.size)
            exchange.responseBody.write("event: ping\ndata: {}\n\n".toByteArray())
            exchange.responseBody.flush()
        }
    }

    private inner class WorkspacesHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) = launchRequest(exchange) {
            if (handlePreflight(exchange)) return@launchRequest
            if (!requireAuthenticated(exchange)) return@launchRequest
            try {
                val suffix = exchange.requestURI.path.substringAfter("/workspaces", "").trim('/')
                when {
                    suffix.isEmpty() && exchange.requestMethod == "GET" -> listWorkspace(exchange)
                    suffix == "file" && exchange.requestMethod == "GET" -> readWorkspaceFile(exchange)
                    suffix == "file" && exchange.requestMethod == "PUT" -> writeWorkspaceFile(exchange)
                    suffix == "download" && exchange.requestMethod == "GET" -> downloadWorkspaceFile(exchange)
                    else -> sendText(exchange, 404, "工作区接口不存在")
                }
            } catch (throwable: Throwable) {
                sendJson(exchange, 400, errorJson(throwable.message ?: "工作区操作失败"))
            }
        }
    }

    private suspend fun listWorkspace(exchange: AndroidHttpExchange) {
        val path = getQueryParam(exchange, "path").orEmpty().ifBlank { "/workspace" }
        if (path.trimEnd('/') == "/workspace") {
            val projects = workspaceManager.listProjects()
            sendJson(exchange, 200, buildJsonObject {
                put("path", "/workspace")
                putJsonArray("items") {
                    projects.forEach { project ->
                        add(buildJsonObject {
                            put("name", project.name)
                            put("path", project.linuxPath)
                            put("isDirectory", true)
                            put("size", project.sizeBytes)
                        })
                    }
                }
            })
            return
        }
        val (project, relative) = parseWorkspacePath(path)
        val items = workspaceFiles.listFiles(project, relative).orThrow()
        sendJson(exchange, 200, buildJsonObject {
            put("path", workspacePath(project, relative))
            putJsonArray("items") {
                items.forEach { item ->
                    add(buildJsonObject {
                        put("name", item.name)
                        put("path", workspacePath(project, item.relativePath))
                        put("isDirectory", item.isDirectory)
                        put("size", item.sizeBytes)
                    })
                }
            }
        })
    }

    private suspend fun readWorkspaceFile(exchange: AndroidHttpExchange) {
        val (project, relative) = parseWorkspacePath(requireNotNull(getQueryParam(exchange, "path")))
        sendJson(exchange, 200, buildJsonObject {
            put("content", workspaceFiles.readFile(project, relative).orThrow())
        })
    }

    private suspend fun writeWorkspaceFile(exchange: AndroidHttpExchange) {
        val body = requestJson(exchange)
        val (project, relative) = parseWorkspacePath(body["path"]?.jsonPrimitive?.content.orEmpty())
        workspaceFiles.writeFile(project, relative, body["content"]?.jsonPrimitive?.content.orEmpty()).orThrow()
        sendJson(exchange, 200, buildJsonObject { put("saved", true) })
        broadcastEvent("workspace_changed", buildJsonObject { put("path", workspacePath(project, relative)) }.toString())
    }

    private suspend fun downloadWorkspaceFile(exchange: AndroidHttpExchange) {
        val (project, relative) = parseWorkspacePath(requireNotNull(getQueryParam(exchange, "path")))
        val entity = requireNotNull(workspaces.findByName(project)) { "工作区不存在" }
        val root = File(entity.path).canonicalFile
        val file = File(root, relative).canonicalFile
        require(file.isFile && (file == root || file.path.startsWith(root.path + File.separator))) { "文件路径无效" }
        exchange.responseHeaders.add("Content-Disposition", "attachment; filename=\"${file.name.replace("\"", "")}\"")
        sendResponse(exchange, 200, "application/octet-stream", file.readBytes())
    }

    private fun conversationJson(session: HarnessSessionEntity) = buildJsonObject {
        put("id", session.id)
        put("title", session.title)
        put("mode", "normal")
        put("createdAt", session.createdAt)
        put("updatedAt", session.updatedAt)
        put("workspace", session.workspace)
    }

    private fun messageArray(messages: List<WebChatMessage>) = buildJsonArray {
        messages.forEach { add(json.encodeToJsonElement(WebChatMessage.serializer(), it)) }
    }

    private fun approvalArray(approvals: List<WebChatApproval>) = buildJsonArray {
        approvals.forEach { add(json.encodeToJsonElement(WebChatApproval.serializer(), it)) }
    }

    private fun taskEvent(
        taskId: String,
        kind: String,
        sessionId: String,
        approvals: List<WebChatApproval> = emptyList(),
    ) = buildJsonObject {
        put("taskId", taskId)
        put("kind", kind)
        put("conversationId", sessionId)
        if (approvals.isNotEmpty()) put("approvals", approvalArray(approvals))
    }.toString()

    private fun requestJson(exchange: AndroidHttpExchange): JsonObject {
        val raw = exchange.requestBody.bufferedReader().readText()
        return if (raw.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(raw).jsonObject
    }

    private fun handlePreflight(exchange: AndroidHttpExchange): Boolean {
        if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
            sendResponse(exchange, 204, "text/plain", ByteArray(0))
            return true
        }
        return false
    }

    private fun isAuthenticated(exchange: AndroidHttpExchange): Boolean {
        val token = getQueryParam(exchange, "token")
            ?: exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        return token != null && token == _status.value.pinCode
    }

    private fun requireAuthenticated(exchange: AndroidHttpExchange): Boolean {
        if (isAuthenticated(exchange)) return true
        sendJson(exchange, 401, errorJson("请先使用配对码连接"))
        return false
    }

    private fun parseWorkspacePath(path: String): Pair<String, String> {
        val normalized = path.replace('\\', '/').trim().removeSuffix("/")
        require(normalized.startsWith("/workspace/")) { "仅允许访问已注册的 /workspace 工程" }
        val tail = normalized.removePrefix("/workspace/")
        val project = tail.substringBefore('/')
        val relative = tail.substringAfter('/', "")
        require(project.isNotBlank() && relative.split('/').none { it == ".." }) { "工作区路径无效" }
        return project to relative
    }

    private suspend fun isRegisteredWorkspacePath(path: String): Boolean = runCatching {
        val (project, _) = parseWorkspacePath(path)
        workspaces.findByName(project) != null
    }.getOrDefault(false)

    private fun workspacePath(project: String, relative: String): String =
        "/workspace/$project" + relative.trim('/').takeIf(String::isNotEmpty)?.let { "/$it" }.orEmpty()

    private fun <T> AppResult<T>.orThrow(): T = when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> throw IllegalArgumentException(error.message)
    }

    private fun errorJson(message: String) = buildJsonObject { put("error", message) }

    private fun sendJson(exchange: AndroidHttpExchange, code: Int, payload: kotlinx.serialization.json.JsonElement) =
        sendResponse(exchange, code, "application/json; charset=utf-8", payload.toString().toByteArray())

    private fun sendText(exchange: AndroidHttpExchange, code: Int, text: String) =
        sendResponse(exchange, code, "text/plain; charset=utf-8", text.toByteArray())

    private fun sendResponse(exchange: AndroidHttpExchange, code: Int, contentType: String, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization")
        exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
        exchange.close()
    }

    private fun getQueryParam(exchange: AndroidHttpExchange, key: String): String? {
        val raw = exchange.requestURI.query.orEmpty().split('&').firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "") ?: return null
        return URLDecoder.decode(raw, Charsets.UTF_8.name())
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()

    private fun acquireLocks() {
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        wakeLock = power?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "taixu:webchat_bridge_wake")?.apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        wifiLock = wifi?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "taixu:webchat_bridge_wifi")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun showNotification(url: String, pin: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "太墟智枢 Web 协作台",
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "太墟智枢局域网协作服务"; setShowBadge(false) },
            )
        }
        manager.notify(
            NOTIFICATION_ID,
            androidx.core.app.NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("太墟智枢 Web 协作台运行中")
                .setContentText("$url（配对码：$pin）")
                .setOngoing(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .build(),
        )
    }

    private fun hideNotification() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)?.cancel(NOTIFICATION_ID)
    }

    private fun getMimeType(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        else -> "application/octet-stream"
    }

    private fun hasFileExtension(path: String): Boolean = path.substringAfterLast('/', "").contains('.')
    private fun generatePin(): String = (100000..999999).random().toString()

    private fun resolveLocalIp(): String = runCatching {
        var fallback = "127.0.0.1"
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (network.isLoopback || !network.isUp) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    val host = address.hostAddress.orEmpty()
                    if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) return@runCatching host
                    fallback = host
                }
            }
        }
        fallback
    }.getOrDefault("127.0.0.1")

    companion object {
        const val DEFAULT_PORT = DEFAULT_WEBCHAT_PORT
        const val NOTIFICATION_CHANNEL_ID = "taixu_webchat_bridge"
        const val NOTIFICATION_ID = 8899
    }
}
