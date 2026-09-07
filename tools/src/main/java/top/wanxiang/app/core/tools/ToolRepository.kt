package top.wanxiang.app.core.tools

import top.wanxiang.app.core.database.ToolDao
import top.wanxiang.app.core.database.ToolEntity
import top.wanxiang.app.core.model.ToolManifest
import android.net.Uri
import top.wanxiang.app.core.common.result.AppResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Tool persistence and registry boundary; UI layers do not access Room directly. */
@Singleton
class ToolRepository @Inject constructor(
    private val toolDao: ToolDao,
    private val toolRegistry: ToolRegistry,
) {
    fun observeTools(distroId: String): Flow<List<ToolEntity>> = toolDao.observeForDistro(distroId)
    suspend fun getForDistro(distroId: String): List<ToolEntity> = toolDao.getForDistro(distroId)
    suspend fun findById(distroId: String, id: String): ToolEntity? = toolDao.findById(distroId, id)
    suspend fun upsert(tool: ToolEntity) = toolDao.upsert(tool)
    suspend fun updateState(distroId: String, id: String, state: String) =
        toolDao.updateState(distroId, id, state)
    suspend fun updateStateAndInstalledVersion(distroId: String, id: String, state: String, installedVersion: String?) =
        toolDao.updateStateAndInstalledVersion(distroId, id, state, installedVersion)
    suspend fun deleteByDistro(distroId: String) = toolDao.deleteByDistro(distroId)
    fun manifests(): List<ToolManifest> = toolRegistry.load()
    fun manifest(id: String): ToolManifest? = manifests().firstOrNull { it.id == id }
    suspend fun importLocal(
        uri: Uri,
        onProgress: (LocalPluginImportProgress) -> Unit = {},
    ): AppResult<ToolManifest> = toolRegistry.importLocal(uri, onProgress)
    suspend fun inspectLocal(uri: Uri): AppResult<LocalPluginPreview> = toolRegistry.inspectLocal(uri)
}
