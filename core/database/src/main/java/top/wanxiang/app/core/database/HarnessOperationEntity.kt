package top.wanxiang.app.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable, total program counter for a run, compaction, or navigation operation. */
@Entity(
    tableName = "harness_operations",
    indices = [
        Index(value = ["sessionId", "laneName"]),
        Index(value = ["status", "updatedAt"]),
    ],
)
data class HarnessOperationEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val laneName: String,
    val kind: String,
    val status: String,
    val phase: String,
    val startedAt: Long,
    val updatedAt: Long,
    val startLeafId: String?,
    /** Complete state snapshot, never a delta. */
    val stateJson: String,
    val pendingEffectKind: String? = null,
    val pendingEffectId: String? = null,
    /** safe | never */
    val replayPolicy: String? = null,
    val attempt: Int = 0,
)
