package top.wanxiang.app.core.database

import androidx.room.Entity
import androidx.room.Index

/** Named cursor over a shared session entry tree. */
@Entity(
    tableName = "harness_lanes",
    primaryKeys = ["sessionId", "name"],
    indices = [Index(value = ["sessionId", "currentOperationId"])],
)
data class HarnessLaneEntity(
    val sessionId: String,
    val name: String,
    val leafId: String?,
    val currentOperationId: String? = null,
    val modelId: String? = null,
    val thinkingLevel: String = "off",
    val faulted: Boolean = false,
    val updatedAt: Long,
)

/** Last terminal result; completed operations leave no live program-state row behind. */
@Entity(tableName = "harness_lane_results", primaryKeys = ["sessionId", "laneName"])
data class HarnessLaneResultEntity(
    val sessionId: String,
    val laneName: String,
    val operationId: String,
    val outcome: String,
    val finalEntryId: String?,
    val detailsJson: String? = null,
    val completedAt: Long,
)
