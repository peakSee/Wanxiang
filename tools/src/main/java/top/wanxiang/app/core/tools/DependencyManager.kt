package top.wanxiang.app.core.tools

import top.wanxiang.app.core.common.result.AppResult
import top.wanxiang.app.core.model.InstalledRuntime
import top.wanxiang.app.core.model.RuntimeRequirement
import top.wanxiang.app.core.model.ToolManifest
import javax.inject.Inject
import javax.inject.Singleton

/** Owns dependency resolution at the Tool boundary; adapters do not access RuntimeManager directly. */
interface DependencyManager {
    fun requirements(manifest: ToolManifest): List<RuntimeRequirement>

    suspend fun acquire(
        requirement: RuntimeRequirement,
        toolId: String,
    ): AppResult<InstalledRuntime>

    suspend fun release(runtimeId: String, toolId: String): AppResult<Unit>
}

@Singleton
class DependencyManagerImpl @Inject constructor(
    private val resolver: DependencyResolver,
    private val runtimeManager: RuntimeManager,
) : DependencyManager {
    override fun requirements(manifest: ToolManifest): List<RuntimeRequirement> =
        resolver.resolve(manifest)

    override suspend fun acquire(
        requirement: RuntimeRequirement,
        toolId: String,
    ): AppResult<InstalledRuntime> = runtimeManager.acquire(requirement, toolId)

    override suspend fun release(runtimeId: String, toolId: String): AppResult<Unit> =
        runtimeManager.release(runtimeId, toolId)
}
