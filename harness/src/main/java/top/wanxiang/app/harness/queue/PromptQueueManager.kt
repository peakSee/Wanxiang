package top.wanxiang.app.harness.queue

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import top.wanxiang.app.core.database.HarnessEntryEntity
import top.wanxiang.app.core.database.HarnessQueueItemEntity
import top.wanxiang.app.core.database.HarnessRuntimeRepository
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.PendingMessage
import top.wanxiang.app.harness.QueuedPrompt
import top.wanxiang.app.harness.UserMessage
import top.wanxiang.app.harness.session.SessionTreeStore

enum class PromptQueue(val id: String) {
    STEER("steer"), FOLLOW_UP("follow_up"), NEXT_RUN("next_run")
}

/**
 * Durable prompt queues with explicit consumption timing.
 *
 * 所有操作以 [laneName] 定位队列（默认主 lane）；子智能体等独立 lane
 * 可通过显式传参获得同等的持久化队列能力。
 */
@Singleton
class PromptQueueManager @Inject constructor(
    private val repository: HarnessRuntimeRepository,
    private val json: Json,
) {
    suspend fun enqueue(
        sessionId: String,
        queue: PromptQueue,
        prompt: PendingMessage,
        laneName: String = SessionTreeStore.MAIN_LANE,
    ): String {
        val operationId = repository.ensureLane(sessionId, laneName).currentOperationId
        val id = UUID.randomUUID().toString()
        repository.enqueue(
            HarnessQueueItemEntity(
                id = id,
                sessionId = sessionId,
                laneName = laneName,
                operationId = operationId,
                queueType = queue.id,
                createdAt = prompt.createdAt,
                payloadJson = json.encodeToString(PendingMessage.serializer(), prompt),
            ),
        )
        return id
    }

    suspend fun list(
        sessionId: String,
        queue: PromptQueue,
        laneName: String = SessionTreeStore.MAIN_LANE,
    ): List<Pair<String, PendingMessage>> =
        repository.listQueue(sessionId, laneName, queue.id).mapNotNull { item ->
            runCatching { item.id to json.decodeFromString(PendingMessage.serializer(), item.payloadJson) }.getOrNull()
        }

    suspend fun first(
        sessionId: String,
        queue: PromptQueue,
        laneName: String = SessionTreeStore.MAIN_LANE,
    ): Pair<String, PendingMessage>? = list(sessionId, queue, laneName).firstOrNull()

    suspend fun listAll(
        sessionId: String,
        laneName: String = SessionTreeStore.MAIN_LANE,
    ): List<QueuedPrompt> = repository.listAllQueues(sessionId, laneName).mapNotNull { item ->
        val queue = PromptQueue.entries.firstOrNull { it.id == item.queueType } ?: return@mapNotNull null
        runCatching {
            QueuedPrompt(item.id, queue, json.decodeFromString(PendingMessage.serializer(), item.payloadJson))
        }.getOrNull()
    }

    suspend fun cancel(sessionId: String, queue: PromptQueue, index: Int, laneName: String = SessionTreeStore.MAIN_LANE) {
        list(sessionId, queue, laneName).getOrNull(index)?.first?.let { repository.cancelQueued(it) }
    }

    suspend fun clear(sessionId: String, queue: PromptQueue, laneName: String = SessionTreeStore.MAIN_LANE) {
        repository.clearQueue(sessionId, laneName, queue.id)
    }

    /** Atomically turns queued prompts into immutable entries on the given lane. */
    suspend fun consume(
        sessionId: String,
        queue: PromptQueue,
        limit: Int = Int.MAX_VALUE,
        laneName: String = SessionTreeStore.MAIN_LANE,
    ): List<UserMessage> {
        val items = repository.listQueue(sessionId, laneName, queue.id).take(limit)
        val consumed = ArrayList<UserMessage>(items.size)
        for (item in items) {
            val prompt = runCatching { json.decodeFromString(PendingMessage.serializer(), item.payloadJson) }.getOrNull()
                ?: continue
            val lane = repository.ensureLane(sessionId, laneName)
            val message = UserMessage(
                id = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                text = prompt.text,
                imageUrls = prompt.imageUrls,
            )
            val entry = HarnessEntryEntity(
                id = message.id,
                sessionId = sessionId,
                parentId = lane.leafId,
                createdAt = message.createdAt,
                entryType = "message",
                customType = "user",
                payloadJson = json.encodeToString(HarnessMessage.serializer(), message),
            )
            repository.consumeQueued(
                itemId = item.id,
                entry = entry,
                lane = lane.copy(leafId = entry.id, updatedAt = System.currentTimeMillis()),
            )
            consumed += message
        }
        return consumed
    }
}
