package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.wanxiang.app.core.model.BuiltinMcpPresets
import top.wanxiang.app.core.model.McpServerConfig
import top.wanxiang.app.core.model.McpTransportType
import top.wanxiang.app.core.security.SecretManager
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val description: String,
    val transportType: String,
    val command: String,
    val argsCiphertext: String,
    val envCiphertext: String,
    val serverUrl: String,
    val isEnabled: Boolean,
    val isBuiltin: Boolean,
    /** 用户是否手动切换过启停：false = 跟随内置预设默认值，true = 尊重用户当前选择。 */
    val userToggled: Boolean = false,
)

@Dao
interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY name ASC")
    fun observeAll(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): McpServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: McpServerEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(servers: List<McpServerEntity>)

    @Query("UPDATE mcp_servers SET name = :name, description = :description, transportType = :transportType, command = :command, argsCiphertext = :argsCiphertext, envCiphertext = :envCiphertext, serverUrl = :serverUrl, isBuiltin = 1 WHERE id = :id AND isBuiltin = 1")
    suspend fun updateBuiltinDefinition(
        id: String,
        name: String,
        description: String,
        transportType: String,
        command: String,
        argsCiphertext: String,
        envCiphertext: String,
        serverUrl: String,
    )

    @Query("UPDATE mcp_servers SET isEnabled = :enabled, userToggled = 1 WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    /** 同步内置服务的默认启停：仅作用于用户从未手动切换过的行，尊重用户显式选择。 */
    @Query("UPDATE mcp_servers SET isEnabled = :enabled WHERE id = :id AND isBuiltin = 1 AND userToggled = 0")
    suspend fun syncBuiltinDefault(id: String, enabled: Boolean)

    @Query("SELECT id FROM mcp_servers WHERE isBuiltin = 1")
    suspend fun findAllBuiltinIds(): List<String>

    @Query("DELETE FROM mcp_servers WHERE id = :id AND isBuiltin = 1")
    suspend fun deleteBuiltin(id: String)

    @Query("DELETE FROM mcp_servers WHERE id = :id AND isBuiltin = 0")
    suspend fun deleteCustom(id: String)
}

@Singleton
class McpServerRepository @Inject constructor(
    private val dao: McpServerDao,
    private val secretManager: SecretManager,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val servers: Flow<List<McpServerConfig>> = dao.observeAll().map { rows -> rows.map { it.toModel(json, secretManager) } }

    suspend fun ensureInitialized() {
        BuiltinMcpPresets.presets.forEach { preset ->
            val entity = preset.toEntity(json, secretManager)
            if (dao.findById(preset.id) == null) {
                dao.upsert(entity)
            } else {
                // 更新内置服务的启动定义（例如从失效 npx 包迁移到 APK 自带脚本）。
                dao.updateBuiltinDefinition(
                    id = entity.id,
                    name = entity.name,
                    description = entity.description,
                    transportType = entity.transportType,
                    command = entity.command,
                    argsCiphertext = entity.argsCiphertext,
                    envCiphertext = entity.envCiphertext,
                    serverUrl = entity.serverUrl,
                )
                // 用户从未手动切换过启停的行跟随当前预设默认值
                // （默认值从关闭改为开启后，存量安装也能自动启用，无需手动开启）。
                dao.syncBuiltinDefault(entity.id, entity.isEnabled)
            }
        }
        // 清理已被移除的内置预设：代码不再提供的系统核心 MCP 从库中移除，
        // 避免升级后残留幽灵服务（内置定义以当前代码为准）。
        val activeBuiltinIds = BuiltinMcpPresets.presets.map { it.id }.toSet()
        dao.findAllBuiltinIds().filterNot { it in activeBuiltinIds }.forEach { dao.deleteBuiltin(it) }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        ensureInitialized()
        dao.setEnabled(id, enabled)
    }

    suspend fun save(server: McpServerConfig) {
        ensureInitialized()
        dao.upsert(server.toEntity(json, secretManager))
    }

    suspend fun delete(id: String) {
        ensureInitialized()
        dao.deleteCustom(id)
    }
}

private fun McpServerEntity.toModel(json: Json, secretManager: SecretManager): McpServerConfig = McpServerConfig(
    id = id,
    name = name,
    description = description,
    transportType = runCatching { McpTransportType.valueOf(transportType) }.getOrDefault(McpTransportType.STDIO),
    command = command,
    args = secretManager.decrypt(argsCiphertext)
        ?.let { plaintext -> runCatching { json.decodeFromString<List<String>>(plaintext) }.getOrDefault(emptyList()) }
        .orEmpty(),
    env = secretManager.decrypt(envCiphertext)
        ?.let { plaintext -> runCatching { json.decodeFromString<Map<String, String>>(plaintext) }.getOrDefault(emptyMap()) }
        .orEmpty(),
    serverUrl = serverUrl,
    isEnabled = isEnabled,
    isBuiltin = isBuiltin,
)

private fun McpServerConfig.toEntity(json: Json, secretManager: SecretManager): McpServerEntity = McpServerEntity(
    id = id,
    name = name,
    description = description,
    transportType = transportType.name,
    command = command,
    argsCiphertext = secretManager.encrypt(json.encodeToString(args)),
    envCiphertext = secretManager.encrypt(json.encodeToString(env)),
    serverUrl = serverUrl,
    isEnabled = isEnabled,
    isBuiltin = isBuiltin,
)
