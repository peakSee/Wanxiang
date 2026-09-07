package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Harness 可用的模型配置。密钥只存引用（Android Keystore 别名），
 * 明文 Key 永不落库。
 */
@Entity(tableName = "harness_models")
data class AiModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val provider: String,
    val model: String,
    val baseUrl: String = "",
    val secretRef: String = "",
    val isActive: Boolean = false,
    val createdAt: Long,
    /** 推理参数（null = 使用服务端默认）：温度，0.0 ~ 2.0。 */
    val temperature: Float? = null,
    /** 推理参数（null = 使用服务端默认）：单次回复最大 token 数。 */
    val maxTokens: Int? = null,
    /** 推理参数（null = 使用服务端默认）：核采样阈值，0.0 ~ 1.0。 */
    val topP: Float? = null,
    /** 推理开关：null = auto（跟随模型默认）；"disabled" / "enabled"。 */
    val reasoningMode: String? = null,
    /** 推理强度：null = 默认；"low" / "medium" / "high"。 */
    val reasoningEffort: String? = null,
    /**
     * 工具调用模式：null = native（OpenAI 标准函数调用）；
     * "json" = JSON 文本格式（工具列表写入系统提示词，模型用文本输出工具调用）；
     * "disabled" = 禁用工具（纯聊天）。
     */
    val toolCallMode: String? = null,
    /** 上下文 Token 容量上限（如 128000，超出时自动滑动窗口压缩，null = 默认）。 */
    val contextTokens: Int? = null,
    /** 自定义请求头（多行 Key: Value 格式，请求时追加注入）。 */
    val customHeaders: String = "",
    /** 纯净排查模式：关闭万象系统提示词与工具定义注入，仅发送纯用户消息。 */
    val pureChatMode: Boolean = false,
    /** 是否支持视觉多模态直接传图（true = 直接以 image_url 发送；false = 提示工具读取）。 */
    val visionEnabled: Boolean = true,
    /** 是否支持生成图片。仅用于明确的模型能力与生图交互，默认关闭以避免误判文本模型。 */
    val imageGenerationEnabled: Boolean = false,
    /** 是否使用 Responses API（true = /v1/responses；false = /v1/chat/completions）。 */
    val responseApiEnabled: Boolean = false,
    /** 已配置的 API Key 数量（仅元数据；Key 明文始终位于加密存储）。 */
    val apiKeyCount: Int = 0,
    /** 单个 Key 每分钟最多发起的请求数；0 表示不做客户端限制。 */
    val requestsPerMinutePerKey: Int = 0,
)

@Dao
interface AiModelDao {
    @Query("SELECT * FROM harness_models ORDER BY isActive DESC, createdAt ASC")
    fun observeAll(): Flow<List<AiModelEntity>>

    @Query("SELECT * FROM harness_models WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AiModelEntity?

    @Query("SELECT * FROM harness_models WHERE isActive = 1 LIMIT 1")
    suspend fun activeModel(): AiModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: AiModelEntity)

    @Query("UPDATE harness_models SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE harness_models SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: String)

    @Query("UPDATE harness_models SET reasoningMode = :mode, reasoningEffort = :effort WHERE id = :id")
    suspend fun updateReasoning(id: String, mode: String?, effort: String?)

    @Query("DELETE FROM harness_models WHERE id = :id")
    suspend fun delete(id: String)
}
