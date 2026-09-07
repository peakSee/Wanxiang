package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Atomic storage primitives for the durable harness interpreter. */
@Dao
interface HarnessRuntimeDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntry(entry: HarnessEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUsage(usage: HarnessUsageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLane(lane: HarnessLaneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOperation(operation: HarnessOperationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertQueueItem(item: HarnessQueueItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLaneResult(result: HarnessLaneResultEntity)

    @Query("SELECT * FROM harness_lanes WHERE sessionId = :sessionId AND name = :laneName LIMIT 1")
    suspend fun findLane(sessionId: String, laneName: String): HarnessLaneEntity?

    @Query("SELECT * FROM harness_lanes WHERE sessionId = :sessionId ORDER BY name")
    fun observeLanes(sessionId: String): Flow<List<HarnessLaneEntity>>

    @Query("SELECT * FROM harness_entries WHERE sessionId = :sessionId ORDER BY sequence")
    suspend fun listEntries(sessionId: String): List<HarnessEntryEntity>

    @Query("SELECT * FROM harness_entries WHERE (:start IS NULL OR createdAt >= :start) AND (:end IS NULL OR createdAt < :end) ORDER BY sequence")
    suspend fun listEntriesInRange(start: Long?, end: Long?): List<HarnessEntryEntity>

    @Query("SELECT COUNT(*) FROM harness_entries WHERE (:start IS NULL OR createdAt >= :start) AND (:end IS NULL OR createdAt < :end)")
    suspend fun countEntriesInRange(start: Long?, end: Long?): Int

    @Query("SELECT * FROM harness_entries WHERE id = :entryId LIMIT 1")
    suspend fun findEntry(entryId: String): HarnessEntryEntity?

    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM branch ORDER BY sequence
    """)
    suspend fun branch(sessionId: String, leafId: String): List<HarnessEntryEntity>

    /**
     * Return only the newest branch window to the Android process. The recursive walk still
     * happens inside SQLite, but payloadJson for older ancestors never enters the managed heap.
     */
    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM (
            SELECT * FROM branch ORDER BY sequence DESC LIMIT :limit
        ) ORDER BY sequence
    """)
    suspend fun branchTail(sessionId: String, leafId: String, limit: Int): List<HarnessEntryEntity>

    /** Latest entry of one type on the selected immutable-tree branch. */
    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM branch
        WHERE entryType = :entryType
        ORDER BY sequence DESC LIMIT 1
    """)
    suspend fun latestBranchEntryOfType(
        sessionId: String,
        leafId: String,
        entryType: String,
    ): HarnessEntryEntity?

    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM branch
        WHERE entryType = 'message' AND payloadJson LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY sequence DESC LIMIT :limit
    """)
    suspend fun searchBranch(sessionId: String, leafId: String, query: String, limit: Int): List<HarnessEntryEntity>

    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM branch WHERE entryType = 'message' ORDER BY sequence LIMIT 1 OFFSET :index
    """)
    suspend fun branchEntryAt(sessionId: String, leafId: String, index: Int): HarnessEntryEntity?

    @Query("SELECT * FROM harness_operations WHERE id = :operationId LIMIT 1")
    suspend fun findOperation(operationId: String): HarnessOperationEntity?

    @Query("SELECT * FROM harness_operations WHERE sessionId = :sessionId AND status IN ('running', 'waiting_approval', 'suspended', 'aborting') ORDER BY startedAt")
    suspend fun listActiveOperations(sessionId: String): List<HarnessOperationEntity>

    @Query("SELECT * FROM harness_queue_items WHERE sessionId = :sessionId AND laneName = :laneName AND queueType = :queueType ORDER BY createdAt, id")
    suspend fun listQueue(sessionId: String, laneName: String, queueType: String): List<HarnessQueueItemEntity>

    @Query("SELECT * FROM harness_queue_items WHERE sessionId = :sessionId AND laneName = :laneName ORDER BY createdAt, id")
    suspend fun listAllQueues(sessionId: String, laneName: String): List<HarnessQueueItemEntity>

    @Query("SELECT * FROM harness_usage WHERE sessionId = :sessionId ORDER BY sequence")
    suspend fun listUsage(sessionId: String): List<HarnessUsageEntity>

    @Query("DELETE FROM harness_queue_items WHERE id = :itemId")
    suspend fun deleteQueueItem(itemId: String)

    @Query("DELETE FROM harness_queue_items WHERE sessionId = :sessionId AND laneName = :laneName AND queueType = :queueType")
    suspend fun clearQueue(sessionId: String, laneName: String, queueType: String)

    @Query("DELETE FROM harness_operations WHERE id = :operationId")
    suspend fun deleteOperation(operationId: String)

    @Query("DELETE FROM harness_queue_items WHERE operationId = :operationId")
    suspend fun deleteOperationQueue(operationId: String)

    @Query("DELETE FROM harness_entries WHERE sessionId = :sessionId")
    suspend fun deleteSessionEntries(sessionId: String)

    @Query("DELETE FROM harness_lanes WHERE sessionId = :sessionId")
    suspend fun deleteSessionLanes(sessionId: String)

    @Query("DELETE FROM harness_operations WHERE sessionId = :sessionId")
    suspend fun deleteSessionOperations(sessionId: String)

    @Query("DELETE FROM harness_queue_items WHERE sessionId = :sessionId")
    suspend fun deleteSessionQueue(sessionId: String)

    @Query("DELETE FROM harness_usage WHERE sessionId = :sessionId")
    suspend fun deleteSessionUsage(sessionId: String)

    @Query("DELETE FROM harness_lane_results WHERE sessionId = :sessionId")
    suspend fun deleteSessionResults(sessionId: String)

    @Transaction
    suspend fun acceptOperation(entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity) {
        insertEntry(entry)
        upsertOperation(operation)
        upsertLane(lane)
    }

    @Transaction
    suspend fun acceptQueuedOperation(
        queueItemId: String,
        entry: HarnessEntryEntity,
        lane: HarnessLaneEntity,
        operation: HarnessOperationEntity,
    ) {
        insertEntry(entry)
        deleteQueueItem(queueItemId)
        upsertOperation(operation)
        upsertLane(lane)
    }

    @Transaction
    suspend fun beginOperation(lane: HarnessLaneEntity, operation: HarnessOperationEntity) {
        upsertOperation(operation)
        upsertLane(lane)
    }

    @Transaction
    suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?, operation: HarnessOperationEntity, lane: HarnessLaneEntity) {
        if (entry != null) insertEntry(entry)
        if (usage != null) insertUsage(usage)
        upsertOperation(operation)
        upsertLane(lane)
    }

    @Transaction
    suspend fun finishOperation(result: HarnessLaneResultEntity, lane: HarnessLaneEntity) {
        deleteOperationQueue(result.operationId)
        deleteOperation(result.operationId)
        upsertLaneResult(result)
        upsertLane(lane)
    }

    @Transaction
    suspend fun consumeQueueItem(itemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity) {
        insertEntry(entry)
        deleteQueueItem(itemId)
        upsertLane(lane)
    }

    @Transaction
    suspend fun appendEntry(entry: HarnessEntryEntity, lane: HarnessLaneEntity) {
        val current = findLane(lane.sessionId, lane.name)
        check(current?.leafId == entry.parentId) { "Lane ${lane.name} moved while appending ${entry.id}" }
        insertEntry(entry)
        upsertLane(lane)
    }

    @Transaction
    suspend fun deleteSessionData(sessionId: String) {
        deleteSessionQueue(sessionId)
        deleteSessionOperations(sessionId)
        deleteSessionResults(sessionId)
        deleteSessionUsage(sessionId)
        deleteSessionLanes(sessionId)
        deleteSessionEntries(sessionId)
    }
}
