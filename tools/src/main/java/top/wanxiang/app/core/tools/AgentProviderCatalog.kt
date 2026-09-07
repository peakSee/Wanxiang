package top.wanxiang.app.core.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ProviderGroup { OFFICIAL, CHINA, AGGREGATOR, LOCAL, CUSTOM }

/** 接入协议：OPENAI = chat/completions 兼容；ANTHROPIC = Messages API 需协议适配。 */
@Serializable
enum class ProviderProtocol { OPENAI, ANTHROPIC }

@Serializable
data class AgentProviderDefinition(
    val id: String,
    val name: String,
    val baseUrl: String,
    val modelsUrl: String = "",
    val group: ProviderGroup,
    val protocol: ProviderProtocol = ProviderProtocol.OPENAI,
    val recommendedModels: List<String> = emptyList(),
    val apiKeyOptional: Boolean = false,
)

@Serializable
private data class AgentProviderDocument(
    val schemaVersion: Int,
    val providers: List<AgentProviderDefinition>,
)

@Singleton
class AgentProviderCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val providers: List<AgentProviderDefinition> by lazy {
        val document = context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
            json.decodeFromString<AgentProviderDocument>(reader.readText())
        }
        require(document.schemaVersion == SUPPORTED_SCHEMA) {
            "不支持的 Agent provider 配置版本：${document.schemaVersion}"
        }
        require(document.providers.isNotEmpty()) { "Agent provider 配置为空" }
        require(document.providers.map { it.id }.distinct().size == document.providers.size) {
            "Agent provider 配置包含重复 id"
        }
        document.providers.onEach { provider ->
            require(provider.id.isNotBlank() && provider.name.isNotBlank()) { "Provider id/name 不能为空" }
            if (provider.group != ProviderGroup.CUSTOM) {
                require(ProviderEndpointPolicy.isSafeBaseUrl(provider.baseUrl)) {
                    "Provider ${provider.id} 的 Base URL 不安全"
                }
            }
        }
    }

    fun find(id: String): AgentProviderDefinition = providers.firstOrNull { it.id == id }
        ?: providers.first { it.group == ProviderGroup.CUSTOM }

    private companion object {
        const val ASSET_PATH = "agent_providers.json"
        const val SUPPORTED_SCHEMA = 1
    }
}
