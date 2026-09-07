package top.wanxiang.app.core.tools

import top.wanxiang.app.core.database.InstallLogDao
import top.wanxiang.app.core.database.InstallLogEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Persistence boundary for redacted installation and verification logs. */
@Singleton
class InstallLogRepository @Inject constructor(
    private val dao: InstallLogDao,
) {
    suspend fun insert(log: InstallLogEntity) = dao.insert(log)
    suspend fun deleteForTool(distroId: String, toolId: String) = dao.deleteForTool(distroId, toolId)
    fun observeForTool(distroId: String, toolId: String): Flow<List<InstallLogEntity>> = dao.observeForTool(distroId, toolId)
    suspend fun deleteByDistro(distroId: String) = dao.deleteByDistro(distroId)
}

