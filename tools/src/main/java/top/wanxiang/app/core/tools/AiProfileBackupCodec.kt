package top.wanxiang.app.core.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import top.wanxiang.app.core.database.AiModelRepository
import top.wanxiang.app.core.model.AiModelProfileBundle
import top.wanxiang.app.core.model.AiModelProfileExport
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 模型档案的导入/导出编解码：实体 ↔ 导出 JSON 的唯一映射实现。
 * Settings 与 Onboarding 共用，保证两处容错规则一致。
 */
@Singleton
class AiProfileBackupCodec @Inject constructor(
    private val aiModelDao: AiModelRepository,
    private val providerRepository: ProviderRepository,
    private val profileWriter: AiProfileWriter,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun exportAll(includeApiKeys: Boolean): String {
        val models = aiModelDao.observeAll().first()
        val bundle = AiModelProfileBundle(
            schemaVersion = 1,
            exportedAt = System.currentTimeMillis(),
            source = "WanXiang",
            profiles = models.map { entityToExport(it, readKeys(it, includeApiKeys)) },
        )
        return json.encodeToString(bundle)
    }

    suspend fun exportSingle(modelId: String, includeApiKeys: Boolean): String? {
        val entity = aiModelDao.findById(modelId) ?: return null
        return json.encodeToString(entityToExport(entity, readKeys(entity, includeApiKeys)))
    }

    /** 兼容 Bundle / 单档案 / 档案数组三种 JSON 形态 */
    fun parseProfiles(rawJson: String): Result<List<AiModelProfileExport>> {
        val trimmed = rawJson.trim()
        if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("导入内容为空"))
        return runCatching {
            when {
                trimmed.startsWith("{") -> {
                    val bundleResult = runCatching { json.decodeFromString<AiModelProfileBundle>(trimmed) }
                    if (bundleResult.isSuccess && bundleResult.getOrThrow().profiles.isNotEmpty()) {
                        bundleResult.getOrThrow().profiles
                    } else {
                        val single = json.decodeFromString<AiModelProfileExport>(trimmed)
                        listOf(single)
                    }
                }
                trimmed.startsWith("[") -> json.decodeFromString<List<AiModelProfileExport>>(trimmed)
                else -> throw IllegalArgumentException("JSON 格式无效，内容必须以 { 或 [ 开头")
            }
        }
    }

    /** 逐条入库并返回成功导入的数量 */
    suspend fun importProfiles(rawJson: String): Result<Int> {
        val parseResult = parseProfiles(rawJson)
        if (parseResult.isFailure) return Result.failure(parseResult.exceptionOrNull() ?: RuntimeException("JSON 解析失败"))
        val profiles = parseResult.getOrThrow()
        if (profiles.isEmpty()) return Result.failure(IllegalArgumentException("未检测到有效的模型档案配置"))

        val existing = aiModelDao.observeAll().first()
        val importedIds = mutableListOf<String>()

        for (profile in profiles) {
            val modelStr = profile.model.trim()
            val providerStr = profile.provider.trim().ifBlank { "Custom" }
            if (modelStr.isBlank() && profile.name.isBlank()) continue

            val modelId = profile.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            val keys = profileWriter.parseApiKeys(
                (profile.apiKeys + listOfNotNull(profile.apiKey)).joinToString("\n"),
            )
            val nameStr = profile.name.trim().ifBlank {
                val firstModel = modelStr.split(",").firstOrNull()?.trim().orEmpty()
                firstModel.ifBlank { providerStr }
            }

            profileWriter.upsertProfile(
                AiProfileWriter.UpsertRequest(
                    id = modelId,
                    name = nameStr,
                    provider = providerStr,
                    model = modelStr.ifBlank { nameStr },
                    baseUrl = profile.baseUrl,
                    apiKey = keys.joinToString("\n"),
                    requestsPerMinutePerKey = profile.requestsPerMinutePerKey,
                    temperature = profile.temperature,
                    maxTokens = profile.maxTokens,
                    topP = profile.topP,
                    reasoningMode = profile.reasoningMode,
                    reasoningEffort = profile.reasoningEffort,
                    toolCallMode = profile.toolCallMode,
                    contextTokens = profile.contextTokens,
                    customHeaders = profile.customHeaders,
                    pureChatMode = profile.pureChatMode,
                    visionEnabled = profile.visionEnabled,
                    imageGenerationEnabled = profile.imageGenerationEnabled,
                    responseApiEnabled = profile.responseApiEnabled,
                ),
            )
            importedIds.add(modelId)
        }

        // 与既有行为一致：仅当原本没有任何活跃档案时，才把第一条导入设为活跃
        if (existing.none { it.isActive } && importedIds.isNotEmpty()) {
            aiModelDao.clearActive()
            aiModelDao.setActive(importedIds.first())
        }

        return if (importedIds.isNotEmpty()) Result.success(importedIds.size)
        else Result.failure(IllegalArgumentException("未解析到任何有效模型"))
    }

    private suspend fun readKeys(entity: top.wanxiang.app.core.database.AiModelEntity, includeApiKeys: Boolean): List<String> {
        return if (includeApiKeys && entity.secretRef.isNotBlank()) {
            providerRepository.readModelApiKeys(entity.secretRef)
        } else {
            emptyList()
        }
    }

    private fun entityToExport(
        entity: top.wanxiang.app.core.database.AiModelEntity,
        keys: List<String>,
    ) = AiModelProfileExport(
        id = entity.id,
        name = entity.name,
        provider = entity.provider,
        model = entity.model,
        baseUrl = entity.baseUrl,
        apiKeys = keys,
        apiKey = keys.firstOrNull(),
        requestsPerMinutePerKey = entity.requestsPerMinutePerKey,
        temperature = entity.temperature,
        maxTokens = entity.maxTokens,
        topP = entity.topP,
        reasoningMode = entity.reasoningMode,
        reasoningEffort = entity.reasoningEffort,
        toolCallMode = entity.toolCallMode,
        contextTokens = entity.contextTokens,
        customHeaders = entity.customHeaders,
        pureChatMode = entity.pureChatMode,
        visionEnabled = entity.visionEnabled,
        imageGenerationEnabled = entity.imageGenerationEnabled,
        responseApiEnabled = entity.responseApiEnabled,
    )
}
