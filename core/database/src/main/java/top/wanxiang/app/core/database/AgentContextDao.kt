package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentContextDao {

    // ========== 长期记忆 (Memory) ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMemory(memory: AgentMemoryEntity)

    @Query("SELECT * FROM agent_memories WHERE id = :id LIMIT 1")
    suspend fun getMemoryById(id: String): AgentMemoryEntity?

    @Query("SELECT * FROM agent_memories WHERE `key` = :key AND scope = :scope AND ownerId = :ownerId LIMIT 1")
    suspend fun getMemoryByKey(key: String, scope: String, ownerId: String): AgentMemoryEntity?

    /** 按主题键（subjectKey）定位同主题记忆，用于冲突去重与 revision 判定。 */
    @Query("SELECT * FROM agent_memories WHERE subjectKey = :subjectKey AND scope = :scope AND ownerId = :ownerId LIMIT 1")
    suspend fun getMemoryBySubjectKey(subjectKey: String, scope: String, ownerId: String): AgentMemoryEntity?

    /** 钉选记忆：总是注入 system prompt 稳定前缀，绕过检索与新鲜度过滤。 */
    @Query("""
        SELECT * FROM agent_memories
        WHERE pinned = 1
          AND ((scope = 'global' AND ownerId = '')
            OR (:projectOwnerId != '' AND scope = 'project' AND ownerId = :projectOwnerId)
            OR (:sessionId != '' AND scope = 'session' AND ownerId = :sessionId))
        ORDER BY updatedAt DESC
    """)
    suspend fun getPinnedMemories(projectOwnerId: String, sessionId: String): List<AgentMemoryEntity>

    /** 未过期记忆（新鲜度查询基础）：expiresAt 为 null 或晚于 now 视为新鲜。 */
    @Query("""
        SELECT * FROM agent_memories
        WHERE ((scope = 'global' AND ownerId = '')
            OR (:projectOwnerId != '' AND scope = 'project' AND ownerId = :projectOwnerId)
            OR (:sessionId != '' AND scope = 'session' AND ownerId = :sessionId))
          AND pinned = :pinned
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY updatedAt DESC
        LIMIT :limit
    """)
    suspend fun getFreshMemories(projectOwnerId: String, sessionId: String, pinned: Boolean, now: Long, limit: Int): List<AgentMemoryEntity>

    /** 续期：确认记忆仍然有效，刷新 lastVerifiedAt（新鲜度信号，不删除）。 */
    @Query("UPDATE agent_memories SET lastVerifiedAt = :now WHERE id = :id AND (expiresAt IS NULL OR expiresAt > :now)")
    suspend fun touchMemory(id: String, now: Long)

    @Query("""
        SELECT * FROM agent_memories
        WHERE (scope = 'global' AND ownerId = '')
           OR (:projectOwnerId != '' AND scope = 'project' AND ownerId = :projectOwnerId)
           OR (:sessionId != '' AND scope = 'session' AND ownerId = :sessionId)
        ORDER BY updatedAt DESC
        LIMIT :limit
    """)
    suspend fun getMemoriesForContext(projectOwnerId: String, sessionId: String, limit: Int): List<AgentMemoryEntity>

    @Query("SELECT COUNT(*) FROM agent_memories WHERE scope = :scope AND ownerId = :ownerId")
    suspend fun countMemories(scope: String, ownerId: String): Int

    @Query("SELECT * FROM agent_memories ORDER BY updatedAt DESC")
    fun observeAllMemories(): Flow<List<AgentMemoryEntity>>

    @Query("""
        SELECT * FROM agent_memories
        WHERE ((scope = 'global' AND ownerId = '')
            OR (:projectOwnerId != '' AND scope = 'project' AND ownerId = :projectOwnerId)
            OR (:sessionId != '' AND scope = 'session' AND ownerId = :sessionId))
          AND (`key` LIKE '%' || :query || '%' OR `value` LIKE '%' || :query || '%')
        ORDER BY updatedAt DESC
        LIMIT :limit
    """)
    suspend fun searchMemories(query: String, projectOwnerId: String, sessionId: String, limit: Int): List<AgentMemoryEntity>

    @Query("DELETE FROM agent_memories WHERE id = :id")
    suspend fun deleteMemoryById(id: String)

    @Query("DELETE FROM agent_memories WHERE `key` = :key AND scope = :scope AND ownerId = :ownerId")
    suspend fun deleteMemoryByKey(key: String, scope: String, ownerId: String)

    // ========== 任务规划 (Plan) ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlan(plan: AgentPlanEntity)

    @Query("SELECT * FROM agent_plans WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getPlanBySession(sessionId: String): AgentPlanEntity?

    @Query("SELECT * FROM agent_plans WHERE sessionId = :sessionId AND status = 'active' LIMIT 1")
    suspend fun getActivePlan(sessionId: String): AgentPlanEntity?

    @Query("DELETE FROM agent_plans WHERE sessionId = :sessionId")
    suspend fun deletePlanBySession(sessionId: String)

    // ========== 工作草稿 (Scratchpad) ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveScratchpad(scratchpad: AgentScratchpadEntity)

    @Query("SELECT * FROM agent_scratchpads WHERE sessionId = :sessionId AND `key` = :key LIMIT 1")
    suspend fun getScratchpad(sessionId: String, key: String): AgentScratchpadEntity?

    @Query("SELECT * FROM agent_scratchpads WHERE sessionId = :sessionId ORDER BY updatedAt DESC")
    suspend fun listScratchpads(sessionId: String): List<AgentScratchpadEntity>

    @Query("DELETE FROM agent_scratchpads WHERE sessionId = :sessionId AND `key` = :key")
    suspend fun deleteScratchpad(sessionId: String, key: String)

    @Query("DELETE FROM agent_scratchpads WHERE sessionId = :sessionId")
    suspend fun clearScratchpads(sessionId: String)
}
