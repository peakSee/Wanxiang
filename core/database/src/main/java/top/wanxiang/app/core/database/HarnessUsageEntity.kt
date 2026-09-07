package top.wanxiang.app.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Append-only provider/tool accounting ledger. */
@Entity(
    tableName = "harness_usage",
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["sessionId", "sequence"]),
        Index(value = ["operationId"]),
        Index(value = ["entryId"]),
    ],
)
data class HarnessUsageEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val id: String,
    val sessionId: String,
    val operationId: String?,
    val entryId: String?,
    val provider: String?,
    val modelId: String?,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val estimatedCostUsd: Double? = null,
    val adjustment: Boolean = false,
    val detailsJson: String? = null,
    val createdAt: Long,
)
