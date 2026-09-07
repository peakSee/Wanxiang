package top.wanxiang.app.core.tools

import kotlinx.coroutines.flow.first
import top.wanxiang.app.core.database.AiModelEntity
import top.wanxiang.app.core.database.AiModelRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 模型档案的统一写入入口：负责 secretRef 生成、Key 持久化、活跃档案维护。
 * Settings / Chat / Onboarding 的模型保存与删除都应经由本类，避免各处自行拼装实体。
 */
@Singleton
class AiProfileWriter @Inject constructor(
    private val aiModelDao: AiModelRepository,
    private val providerRepository: ProviderRepository,
) {

    /** 解析多行 Key 文本为去重的 Key 列表 */
    fun parseApiKeys(raw: String): List<String> = raw
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

    data class UpsertRequest(
        val id: String? = null,
        val name: String,
        val provider: String,
        /** 单模型或逗号分隔的多模型字符串 */
        val model: String,
        val baseUrl: String,
        /** 多行 Key 文本；为空时保留该档案已有的 Key */
        val apiKey: String = "",
        val requestsPerMinutePerKey: Int = 0,
        val temperature: Float? = null,
        val maxTokens: Int? = null,
        val topP: Float? = null,
        val reasoningMode: String? = null,
        val reasoningEffort: String? = null,
        val toolCallMode: String? = null,
        val contextTokens: Int? = null,
        val customHeaders: String = "",
        val pureChatMode: Boolean = false,
        val visionEnabled: Boolean = true,
        val imageGenerationEnabled: Boolean = false,
        val responseApiEnabled: Boolean = false,
    )

    suspend fun upsertProfile(request: UpsertRequest) {
        val existing = aiModelDao.observeAll().first()
        val old = request.id?.let { aiModelDao.findById(it) }
        val modelId = request.id ?: UUID.randomUUID().toString()
        val secretRef = old?.secretRef?.takeIf { it.isNotBlank() } ?: "model_${modelId.replace("-", "")}"
        val submittedKeys = parseApiKeys(request.apiKey)
        val existingKeys = old?.let { providerRepository.readModelApiKeys(secretRef) }.orEmpty()
        // 没有任何活跃档案，或正在编辑当前活跃档案时，先清空活跃标记再写入
        if (existing.none { it.isActive } || old?.isActive == true) aiModelDao.clearActive()
        aiModelDao.upsert(
            AiModelEntity(
                id = modelId,
                name = request.name.trim(),
                provider = request.provider.trim(),
                model = request.model.trim(),
                baseUrl = request.baseUrl.trim(),
                secretRef = secretRef,
                isActive = old?.isActive ?: existing.none { it.isActive },
                createdAt = old?.createdAt ?: System.currentTimeMillis(),
                temperature = request.temperature,
                maxTokens = request.maxTokens,
                topP = request.topP,
                reasoningMode = request.reasoningMode?.ifBlank { null },
                reasoningEffort = request.reasoningEffort?.ifBlank { null },
                toolCallMode = request.toolCallMode?.ifBlank { null },
                contextTokens = request.contextTokens,
                customHeaders = request.customHeaders.trim(),
                pureChatMode = request.pureChatMode,
                visionEnabled = request.visionEnabled,
                imageGenerationEnabled = request.imageGenerationEnabled,
                responseApiEnabled = request.responseApiEnabled,
                apiKeyCount = submittedKeys.ifEmpty { existingKeys }.size,
                requestsPerMinutePerKey = request.requestsPerMinutePerKey.coerceAtLeast(0),
            ),
        )
        if (submittedKeys.isNotEmpty()) providerRepository.setModelApiKeys(secretRef, submittedKeys)
    }

    suspend fun deleteProfile(id: String) {
        aiModelDao.findById(id)?.secretRef?.takeIf { it.isNotBlank() }?.let { providerRepository.removeModelApiKey(it) }
        aiModelDao.delete(id)
    }
}
