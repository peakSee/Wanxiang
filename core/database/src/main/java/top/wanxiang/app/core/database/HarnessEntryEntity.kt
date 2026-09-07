package top.wanxiang.app.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Immutable node in a session conversation tree. */
@Entity(
    tableName = "harness_entries",
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["sessionId", "sequence"]),
        Index(value = ["sessionId", "parentId"]),
    ],
)
data class HarnessEntryEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val id: String,
    val sessionId: String,
    val parentId: String?,
    val createdAt: Long,
    /** message | compaction | branch_summary | custom */
    val entryType: String,
    /** user | assistant | tool_call | tool_result, or an application custom type. */
    val customType: String? = null,
    val payloadJson: String,
)
