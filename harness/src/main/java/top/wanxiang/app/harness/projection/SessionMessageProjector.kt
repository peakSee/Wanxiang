package top.wanxiang.app.harness.projection

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.session.SessionTreeStore

/**
 * 会话消息投影的窄端口：写入与读取实时消息，无需感知前台镜像的实现细节。
 * 供 HarnessLoop 与 CapabilityEventWriter 等协作者使用。
 */
interface LiveMessagePort {
    suspend fun append(sessionId: String, message: HarnessMessage)
    suspend fun publishPersisted(sessionId: String, message: HarnessMessage)
    fun snapshot(sessionId: String): List<HarnessMessage>
}

/**
 * 实时消息投影器：维护每个会话的内存态消息流（含异步历史合并），
 * 并把前台聚焦会话的列表镜像到全局 [foregroundMessages] 流。
 *
 * 持久化本身由 [SessionTreeStore] 负责；本类只做"已提交内容的发布 + 流式上屏"。
 */
@Singleton
class SessionMessageProjector @Inject constructor(
    private val store: SessionTreeStore,
    private val tracker: CurrentSessionTracker,
) : LiveMessagePort {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val liveFlows = ConcurrentHashMap<String, MutableStateFlow<List<HarnessMessage>>>()
    private val lastAccess = ConcurrentHashMap<String, Long>()
    private val accessCounter = AtomicLong()
    private val streamingSessions = ConcurrentHashMap.newKeySet<String>()

    private val _foregroundMessages = MutableStateFlow<List<HarnessMessage>>(emptyList())
    /** 当前前台聚焦会话的消息列表（供聊天界面观察）。 */
    val foregroundMessages: StateFlow<List<HarnessMessage>> = _foregroundMessages.asStateFlow()

    private fun mirrorIfForeground(sessionId: String, value: List<HarnessMessage>) {
        if (tracker.isForeground(sessionId)) {
            _foregroundMessages.value = value
        }
    }

    /**
     * 获取或创建会话的实时消息流。首次创建时异步合并持久化历史：
     * 历史读取期间新追加的消息不会被丢弃，而是按时间归并。
     */
    fun messagesFlow(sessionId: String): MutableStateFlow<List<HarnessMessage>> {
        touch(sessionId)
        liveFlows[sessionId]?.let {
            evictLeastRecentlyUsed(sessionId)
            return it
        }
        val created = MutableStateFlow<List<HarnessMessage>>(emptyList())
        val flow = liveFlows.putIfAbsent(sessionId, created) ?: created.also {
            scope.launch(Dispatchers.IO) {
                val history = history(sessionId)
                created.update { current ->
                    if (current.isEmpty()) {
                        boundLiveWindow(history)
                    } else {
                        // Current contains newer stream/persisted projections and wins on duplicate ids.
                        boundLiveWindow(
                            (history + current).associateBy { it.id }.values.sortedBy { it.createdAt },
                        )
                    }
                }
                mirrorIfForeground(sessionId, created.value)
            }
        }
        evictLeastRecentlyUsed(sessionId)
        return flow
    }

    private suspend fun history(sessionId: String): List<HarnessMessage> =
        withContext(Dispatchers.IO) { store.load(sessionId) }

    /** 会话历史（活动分支），供分支切换 / 重生成等操作重建实时流。 */
    suspend fun loadHistory(sessionId: String): List<HarnessMessage> = history(sessionId)

    /**
     * loadSession 的预置路径：已有流直接复用；否则先读历史再创建，
     * 避免异步合并窗口期把刚切换的会话闪成空列表。
     */
    suspend fun preparedForLoad(sessionId: String): MutableStateFlow<List<HarnessMessage>> {
        touch(sessionId)
        liveFlows[sessionId]?.let { return it }
        val created = MutableStateFlow(boundLiveWindow(loadHistory(sessionId)))
        val selected = liveFlows.putIfAbsent(sessionId, created) ?: created
        evictLeastRecentlyUsed(sessionId)
        return selected
    }

    /** 新建会话：无条件以空列表开局。 */
    fun seedEmpty(sessionId: String) {
        touch(sessionId)
        liveFlows[sessionId] = MutableStateFlow(emptyList())
        evictLeastRecentlyUsed(sessionId)
    }

    /** 新建/加载会话后复位前台镜像为该会话当前值。 */
    fun resetForegroundProjection(value: List<HarnessMessage>) {
        _foregroundMessages.value = boundLiveWindow(value)
    }

    /** 整体替换某会话的实时消息（重生成 / 回退 / 分支切换等场景）。 */
    fun replaceAll(sessionId: String, messages: List<HarnessMessage>) {
        val bounded = boundLiveWindow(messages)
        messagesFlow(sessionId).value = bounded
        mirrorIfForeground(sessionId, bounded)
    }

    fun removeSession(sessionId: String) {
        liveFlows.remove(sessionId)
        lastAccess.remove(sessionId)
        streamingSessions.remove(sessionId)
    }

    override suspend fun append(sessionId: String, message: HarnessMessage) {
        store.append(sessionId, message)
        publishPersisted(sessionId, message)
    }

    override suspend fun publishPersisted(sessionId: String, message: HarnessMessage) {
        val flow = messagesFlow(sessionId)
        flow.update { current ->
            val idx = current.indexOfFirst { it.id == message.id }
            val updated = if (idx >= 0) {
                // in-place 替换：避免整列 map() copy 产生的额外 List 对象（工具密集轮次频繁触发）
                current.toMutableList().apply { this[idx] = message }
            } else {
                current + message
            }
            boundLiveWindow(updated)
        }
        mirrorIfForeground(sessionId, flow.value)
        if (message is AssistantText) streamingSessions.remove(sessionId)
    }

    override fun snapshot(sessionId: String): List<HarnessMessage> = messagesFlow(sessionId).value

    /** 移除一条仅存在于实时流中的消息（如出错后的空气泡）。 */
    fun remove(sessionId: String, messageId: String) {
        val flow = messagesFlow(sessionId)
        flow.update { current -> current.filterNot { it.id == messageId } }
        mirrorIfForeground(sessionId, flow.value)
        streamingSessions.remove(sessionId)
    }

    /** Mark a provider stream complete even when it yielded only tool calls/reasoning. */
    fun endStreaming(sessionId: String) {
        streamingSessions.remove(sessionId)
    }

    /** 流式助手文本增量刷新：保留已有 reasoning。 */
    fun streamText(sessionId: String, id: String, createdAt: Long, text: String) {
        streamingSessions += sessionId
        val flow = messagesFlow(sessionId)
        flow.update { current ->
            val idx = current.indexOfFirst { it.id == id }
            val existing = current.getOrNull(idx)
            val message = AssistantText(
                id = id,
                createdAt = createdAt,
                text = text,
                reasoning = (existing as? AssistantText)?.reasoning,
            )
            val updated = if (idx >= 0) {
                current.toMutableList().apply { this[idx] = message }
            } else {
                current + message
            }
            boundLiveWindow(updated)
        }
        mirrorIfForeground(sessionId, flow.value)
    }

    /** 流式思考过程增量刷新：文本未就绪时先行生成占位气泡。 */
    fun streamReasoning(sessionId: String, id: String, createdAt: Long, reasoning: String) {
        streamingSessions += sessionId
        val flow = messagesFlow(sessionId)
        flow.update { current ->
            val idx = current.indexOfFirst { it.id == id }
            val updated = if (idx >= 0) {
                val existing = current[idx]
                (existing as? AssistantText)?.let {
                    current.toMutableList().apply { this[idx] = it.copy(reasoning = reasoning) }
                } ?: (current + AssistantText(id = id, createdAt = createdAt, text = "", reasoning = reasoning))
            } else {
                current + AssistantText(id = id, createdAt = createdAt, text = "", reasoning = reasoning)
            }
            boundLiveWindow(updated)
        }
        mirrorIfForeground(sessionId, flow.value)
    }

    private fun touch(sessionId: String) {
        lastAccess[sessionId] = accessCounter.incrementAndGet()
    }

    private fun boundLiveWindow(messages: List<HarnessMessage>): List<HarnessMessage> =
        if (messages.size > SessionTreeStore.MAX_LIVE_ENTRIES) {
            messages.takeLast(SessionTreeStore.MAX_LIVE_ENTRIES)
        } else {
            messages
        }

    private fun evictLeastRecentlyUsed(protectedSessionId: String) {
        var excess = liveFlows.size - MAX_CACHED_SESSIONS
        if (excess <= 0) return
        lastAccess.entries.asSequence()
            .filter { (id, _) ->
                id != protectedSessionId && !tracker.isForeground(id) && id !in streamingSessions
            }
            .sortedBy { it.value }
            .map { it.key }
            .forEach { id ->
                if (excess <= 0) return@forEach
                if (liveFlows.remove(id) != null) {
                    lastAccess.remove(id)
                    excess--
                }
            }
    }

    private companion object {
        /**
         * 内存中最多缓存的会话实时消息流数量。
         * 降至 4：每个缓存会话在长对话场景下驻留的 List<HarnessMessage> 可达数 MB，
         * 256MB 堆上 8 个并发会话容易触发 OOM；4 可覆盖常见多会话工作流且安全余量更充足。
         */
        const val MAX_CACHED_SESSIONS = 4
    }
}
