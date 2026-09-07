package top.wanxiang.app.core.database

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.wanxiang.app.core.model.AgentSubagent

@Serializable
private data class AgencyCatalogSource(
    val repository: String,
    val revision: String,
    val license: String,
    val agentCount: Int,
    val departmentCount: Int,
)

@Serializable
private data class AgencyCatalogAgent(
    val id: String,
    val name: String,
    val description: String,
    val departmentId: String,
    val promptPath: String,
    val sortOrder: Int,
)

@Serializable
private data class AgencyCatalogAsset(
    val schemaVersion: Int,
    val source: AgencyCatalogSource,
    val agents: List<AgencyCatalogAgent>,
)

internal data class AgencyAgentCatalog(
    val revision: String,
    val profiles: List<AgentSubagent>,
)

/** Loads the complete, offline Agency Agents catalog bundled with the APK. */
@Singleton
class AgencyAgentCatalogLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val loadMutex = Mutex()
    @Volatile private var cachedCatalog: AgencyAgentCatalog? = null
    @Volatile private var cachedSource: AgencyCatalogSource? = null

    internal suspend fun sourceRevision(): String = source().revision

    internal suspend fun load(): AgencyAgentCatalog = cachedCatalog ?: loadMutex.withLock {
        cachedCatalog ?: withContext(Dispatchers.IO) {
            val catalog = readJson<AgencyCatalogAsset>(CATALOG_ASSET)
            require(catalog.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
                "Unsupported Agency Agents catalog schema ${catalog.schemaVersion}"
            }
            require(catalog.agents.size == catalog.source.agentCount) {
                "Agency Agents catalog count does not match source metadata"
            }
            AgencyAgentCatalog(
                revision = catalog.source.revision,
                profiles = catalog.agents.map { agent ->
                    AgentSubagent(
                        id = agent.id,
                        name = agent.name,
                        description = agent.description,
                        systemPrompt = stripFrontmatter(readAsset(agent.promptPath)),
                        departmentId = agent.departmentId,
                        isEnabled = true,
                        isBuiltin = true,
                        sortOrder = agent.sortOrder,
                    )
                },
            )
        }.also { cachedCatalog = it }
    }

    private suspend fun source(): AgencyCatalogSource = cachedSource ?: loadMutex.withLock {
        cachedSource ?: withContext(Dispatchers.IO) {
            readJson<AgencyCatalogSource>(SOURCE_ASSET)
        }.also { cachedSource = it }
    }

    private inline fun <reified T> readJson(path: String): T = json.decodeFromString(readAsset(path))

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    companion object {
        private const val SUPPORTED_SCHEMA_VERSION = 1
        private const val SOURCE_ASSET = "agency_agents/source.json"
        private const val CATALOG_ASSET = "agency_agents/catalog.json"

        internal fun stripFrontmatter(markdown: String): String {
            val normalized = markdown.replace("\r\n", "\n")
            if (!normalized.startsWith("---\n")) return normalized.trim()
            val end = normalized.indexOf("\n---\n", startIndex = 4)
            return if (end < 0) normalized.trim() else normalized.substring(end + 5).trim()
        }
    }
}
