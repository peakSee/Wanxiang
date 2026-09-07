package top.wanxiang.app.core.tools

import top.wanxiang.app.core.database.InstallTaskDao
import top.wanxiang.app.core.database.InstallTaskEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Persistence boundary for durable install transactions. */
@Singleton
class InstallTaskRepository @Inject constructor(
    private val dao: InstallTaskDao,
) {
    suspend fun upsert(task: InstallTaskEntity) = dao.upsert(task)
    suspend fun findByTool(distroId: String, toolId: String): InstallTaskEntity? = dao.findByTool(distroId, toolId)
    suspend fun listByState(state: String): List<InstallTaskEntity> = dao.listByState(state)
    suspend fun deleteByDistro(distroId: String) = dao.deleteByDistro(distroId)
}

