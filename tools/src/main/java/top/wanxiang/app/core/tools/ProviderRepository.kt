package top.wanxiang.app.core.tools

import top.wanxiang.app.core.datastore.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Provider persistence boundary used by Settings UI and tool adapters. */
@Singleton
class ProviderRepository @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) {
    val provider: Flow<String> = settingsDataStore.provider
    val baseUrl: Flow<String> = settingsDataStore.providerBaseUrl
    val model: Flow<String> = settingsDataStore.providerModel
    val apiKeyConfigured: Flow<Boolean> = settingsDataStore.apiKeyConfigured

    suspend fun setProvider(value: String) = settingsDataStore.setProvider(value)
    suspend fun setBaseUrl(value: String) = settingsDataStore.setProviderBaseUrl(value)
    suspend fun setModel(value: String) = settingsDataStore.setProviderModel(value)
    suspend fun setApiKey(value: String) = settingsDataStore.setApiKey(value)
    suspend fun readApiKey(): String? = settingsDataStore.readApiKey()
    suspend fun setModelApiKey(secretRef: String, value: String) = settingsDataStore.setModelApiKey(secretRef, value)
    suspend fun readModelApiKey(secretRef: String): String? = settingsDataStore.readModelApiKey(secretRef)
    suspend fun setModelApiKeys(secretRef: String, values: List<String>) = settingsDataStore.setModelApiKeys(secretRef, values)
    suspend fun readModelApiKeys(secretRef: String): List<String> = settingsDataStore.readModelApiKeys(secretRef)
    suspend fun removeModelApiKey(secretRef: String) = settingsDataStore.removeModelApiKey(secretRef)
}
