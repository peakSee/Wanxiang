package top.wkbin.taixu.core.database

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Persistence port used by the harness runtime; feature modules never depend on its DAO. */
interface HarnessRuntimeRepository {
    suspend fun ensureLane(sessionId: String, laneName: String, atEntryId: String? = null): HarnessLaneEntity
    suspend fun findLane(sessionId: String, laneName: String): HarnessLaneEntity?
    fun observeLanes(sessionId: String): Flow<List<HarnessLaneEntity>>
    suspend fun listEntries(sessionId: String): List<HarnessEntryEntity>
    suspend fun listEntriesInRange(start: Long?, end: Long?): List<HarnessEntryEntity>
    suspend fun countEntriesInRange(start: Long?, end: Long?): Int
    suspend fun branch(sessionId: String, leafId: String?): List<HarnessEntryEntity>
    suspend fun branchTail(sessionId: String, leafId: String?, limit: Int): List<HarnessEntryEntity> {
        require(limit > 0) { "Branch tail limit must be positive" }
        return branch(sessionId, leafId).takeLast(limit)
    }
    suspend fun latestBranchEntryOfType(
        sessionId: String,
        leafId: String?,
        entryType: String,
    ): HarnessEntryEntity? = branch(sessionId, leafId).lastOrNull { it.entryType == entryType }
    suspend fun searchBranch(sessionId: String, leafId: String?, query: String, limit: Int): List<HarnessEntryEntity> =
        branch(sessionId, leafId).asReversed().filter { it.entryType == "message" && it.payloadJson.contains(query, ignoreCase = true) }.take(limit)
    suspend fun branchEntryAt(sessionId: String, leafId: String?, index: Int): HarnessEntryEntity? =
        branch(sessionId, leafId).filter { it.entryType == "message" }.getOrNull(index)
    suspend fun findEntry(sessionId: String, entryId: String): HarnessEntryEntity? =
        listEntries(sessionId).firstOrNull { it.id == entryId }
    suspend fun appendToLane(sessionId: String, laneName: String, entry: HarnessEntryEntity)
    suspend fun moveLane(sessionId: String, laneName: String, leafId: String?)
    suspend fun clearLaneOperation(sessionId: String, laneName: String)
    suspend fun findOperation(operationId: String): HarnessOperationEntity?
    suspend fun listActiveOperations(sessionId: String): List<HarnessOperationEntity>
    suspend fun acceptOperation(entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity)
    suspend fun acceptQueuedOperation(queueItemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity)
    suspend fun beginOperation(lane: HarnessLaneEntity, operation: HarnessOperationEntity)
    suspend fun saveOperation(operation: HarnessOperationEntity)
    suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?, operation: HarnessOperationEntity, lane: HarnessLaneEntity)
    suspend fun finishOperation(result: HarnessLaneResultEntity, lane: HarnessLaneEntity)
    suspend fun enqueue(item: HarnessQueueItemEntity)
    suspend fun listQueue(sessionId: String, laneName: String, queueType: String): List<HarnessQueueItemEntity>
    suspend fun listAllQueues(sessionId: String, laneName: String): List<HarnessQueueItemEntity>
    suspend fun cancelQueued(itemId: String)
    suspend fun clearQueue(sessionId: String, laneName: String, queueType: String)
    suspend fun consumeQueued(itemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity)
    suspend fun recordUsage(usage: HarnessUsageEntity)
    suspend fun listUsage(sessionId: String): List<HarnessUsageEntity>
    suspend fun deleteSessionData(sessionId: String)
}

@Singleton
class RoomHarnessRuntimeRepository @Inject constructor(
    private val dao: HarnessRuntimeDao,
    private val blobStore: HarnessBlobStore? = null,
) : HarnessRuntimeRepository {

    private fun sanitizeForStorage(entry: HarnessEntryEntity): HarnessEntryEntity {
        val store = blobStore ?: return entry
        val processed = store.storeIfLarge(entry.sessionId, entry.id, entry.payloadJson)
        return if (processed === entry.payloadJson) entry else entry.copy(payloadJson = processed)
    }

    private fun restoreFromStorage(entry: HarnessEntryEntity): HarnessEntryEntity {
        val store = blobStore ?: return entry
        val restored = store.read(entry.payloadJson)
        return if (restored === entry.payloadJson) entry else entry.copy(payloadJson = restored)
    }

    override suspend fun ensureLane(sessionId: String, laneName: String, atEntryId: String?): HarnessLaneEntity {
        dao.findLane(sessionId, laneName)?.let { return it }
        if (atEntryId != null) {
            require(dao.findEntry(atEntryId)?.sessionId == sessionId) { "Lane anchor does not belong to session $sessionId" }
        }
        val lane = HarnessLaneEntity(
            sessionId = sessionId,
            name = laneName,
            leafId = atEntryId,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsertLane(lane)
        return lane
    }

    override suspend fun findLane(sessionId: String, laneName: String) = dao.findLane(sessionId, laneName)
    override fun observeLanes(sessionId: String) = dao.observeLanes(sessionId)
    override suspend fun listEntries(sessionId: String): List<HarnessEntryEntity> =
        dao.listEntries(sessionId).map(::restoreFromStorage)
    override suspend fun listEntriesInRange(start: Long?, end: Long?): List<HarnessEntryEntity> =
        dao.listEntriesInRange(start, end).map(::restoreFromStorage)
    override suspend fun countEntriesInRange(start: Long?, end: Long?) = dao.countEntriesInRange(start, end)

    override suspend fun branch(sessionId: String, leafId: String?): List<HarnessEntryEntity> {
        return leafId?.let { dao.branch(sessionId, it).map(::restoreFromStorage) }.orEmpty()
    }
    override suspend fun branchTail(sessionId: String, leafId: String?, limit: Int): List<HarnessEntryEntity> {
        require(limit > 0) { "Branch tail limit must be positive" }
        return leafId?.let { dao.branchTail(sessionId, it, limit).map(::restoreFromStorage) }.orEmpty()
    }
    override suspend fun latestBranchEntryOfType(
        sessionId: String,
        leafId: String?,
        entryType: String,
    ): HarnessEntryEntity? = leafId?.let {
        dao.latestBranchEntryOfType(sessionId, it, entryType)?.let(::restoreFromStorage)
    }
    override suspend fun searchBranch(sessionId: String, leafId: String?, query: String, limit: Int): List<HarnessEntryEntity> =
        leafId?.let {
            val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            dao.searchBranch(sessionId, it, escaped, limit).map(::restoreFromStorage)
        }.orEmpty()
    override suspend fun branchEntryAt(sessionId: String, leafId: String?, index: Int): HarnessEntryEntity? =
        leafId?.let { dao.branchEntryAt(sessionId, it, index)?.let(::restoreFromStorage) }
    private suspend fun ensureUniqueStorageEntry(entry: HarnessEntryEntity): HarnessEntryEntity {
        var candidate = entry
        repeat(8) {
            val existing = dao.findEntry(candidate.id) ?: return candidate
            if (existing.sessionId == entry.sessionId && existing.payloadJson == entry.payloadJson) {
                // Idempotent retry of the same logical entry.
                return candidate
            }
            val randomSuffix = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            candidate = entry.copy(id = "${entry.id}_$randomSuffix")
        }
        val fallback = java.util.UUID.randomUUID().toString().replace("-", "")
        val resolved = entry.copy(id = "${entry.id}_$fallback")
        check(dao.findEntry(resolved.id) == null) {
            "Unable to allocate a unique harness entry id for ${entry.id}"
        }
        return resolved
    }

    override suspend fun findEntry(sessionId: String, entryId: String): HarnessEntryEntity? =
        dao.findEntry(entryId)?.takeIf { it.sessionId == sessionId }?.let(::restoreFromStorage)

    override suspend fun appendToLane(sessionId: String, laneName: String, entry: HarnessEntryEntity) {
        val lane = ensureLane(sessionId, laneName)
        if (lane.leafId == entry.id) {
            val existing = dao.findEntry(entry.id)
            if (existing != null && existing.sessionId == sessionId && existing.payloadJson == entry.payloadJson) {
                return
            }
        }
        require(entry.sessionId == sessionId && entry.parentId == lane.leafId) { "Entry parent does not match lane leaf" }
        val uniqueEntry = ensureUniqueStorageEntry(entry)
        val sanitized = sanitizeForStorage(uniqueEntry)
        dao.appendEntry(sanitized, lane.copy(leafId = uniqueEntry.id, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun moveLane(sessionId: String, laneName: String, leafId: String?) {
        val lane = ensureLane(sessionId, laneName)
        if (leafId != null) require(dao.findEntry(leafId)?.sessionId == sessionId) { "Unknown lane target $leafId" }
        dao.upsertLane(lane.copy(leafId = leafId, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun clearLaneOperation(sessionId: String, laneName: String) {
        val lane = dao.findLane(sessionId, laneName) ?: return
        dao.upsertLane(lane.copy(currentOperationId = null, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun findOperation(operationId: String) = dao.findOperation(operationId)
    override suspend fun listActiveOperations(sessionId: String) = dao.listActiveOperations(sessionId)
    override suspend fun acceptOperation(entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity) {
        val uniqueEntry = ensureUniqueStorageEntry(entry)
        val sanitized = sanitizeForStorage(uniqueEntry)
        val updatedLane = if (lane.leafId == entry.id && uniqueEntry.id != entry.id) {
            lane.copy(leafId = uniqueEntry.id)
        } else {
            lane
        }
        dao.acceptOperation(sanitized, updatedLane, operation)
    }

    override suspend fun acceptQueuedOperation(queueItemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity) {
        val uniqueEntry = ensureUniqueStorageEntry(entry)
        val sanitized = sanitizeForStorage(uniqueEntry)
        val updatedLane = if (lane.leafId == entry.id && uniqueEntry.id != entry.id) {
            lane.copy(leafId = uniqueEntry.id)
        } else {
            lane
        }
        dao.acceptQueuedOperation(queueItemId, sanitized, updatedLane, operation)
    }

    override suspend fun beginOperation(lane: HarnessLaneEntity, operation: HarnessOperationEntity) =
        dao.beginOperation(lane, operation)
    override suspend fun saveOperation(operation: HarnessOperationEntity) = dao.upsertOperation(operation)
    override suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?, operation: HarnessOperationEntity, lane: HarnessLaneEntity) {
        val uniqueEntry = entry?.let { ensureUniqueStorageEntry(it) }
        val sanitized = uniqueEntry?.let(::sanitizeForStorage)
        val updatedLane = if (entry != null && lane.leafId == entry.id && uniqueEntry != null && uniqueEntry.id != entry.id) {
            lane.copy(leafId = uniqueEntry.id)
        } else {
            lane
        }
        dao.settleEffect(sanitized, usage, operation, updatedLane)
    }

    override suspend fun finishOperation(result: HarnessLaneResultEntity, lane: HarnessLaneEntity) =
        dao.finishOperation(result, lane)
    override suspend fun enqueue(item: HarnessQueueItemEntity) = dao.insertQueueItem(item)
    override suspend fun listQueue(sessionId: String, laneName: String, queueType: String) =
        dao.listQueue(sessionId, laneName, queueType)
    override suspend fun listAllQueues(sessionId: String, laneName: String) =
        dao.listAllQueues(sessionId, laneName)
    override suspend fun cancelQueued(itemId: String) = dao.deleteQueueItem(itemId)
    override suspend fun clearQueue(sessionId: String, laneName: String, queueType: String) =
        dao.clearQueue(sessionId, laneName, queueType)
    override suspend fun consumeQueued(itemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity) {
        val uniqueEntry = ensureUniqueStorageEntry(entry)
        val sanitized = sanitizeForStorage(uniqueEntry)
        val updatedLane = if (lane.leafId == entry.id && uniqueEntry.id != entry.id) {
            lane.copy(leafId = uniqueEntry.id)
        } else {
            lane
        }
        dao.consumeQueueItem(itemId, sanitized, updatedLane)
    }
    override suspend fun recordUsage(usage: HarnessUsageEntity) {
        dao.insertUsage(usage)
    }
    override suspend fun listUsage(sessionId: String) = dao.listUsage(sessionId)
    override suspend fun deleteSessionData(sessionId: String) {
        dao.deleteSessionData(sessionId)
        blobStore?.deleteSessionBlobs(sessionId)
    }
}
