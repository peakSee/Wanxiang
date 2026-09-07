package top.wanxiang.app.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable steer/follow-up/next-run item, placed into the tree only when consumed. */
@Entity(
    tableName = "harness_queue_items",
    indices = [
        Index(value = ["sessionId", "laneName", "queueType", "createdAt"]),
        Index(value = ["operationId"]),
    ],
)
data class HarnessQueueItemEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val laneName: String,
    val operationId: String?,
    /** steer | follow_up | next_run */
    val queueType: String,
    val createdAt: Long,
    val payloadJson: String,
)
