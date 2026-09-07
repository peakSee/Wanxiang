package top.wanxiang.app.core.database

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.wanxiang.app.core.model.QuickPhrase

/** Stable persistence ports consumed by feature, harness, and runtime layers. */
interface AiModelRepository {
    fun observeAll(): Flow<List<AiModelEntity>>
    suspend fun findById(id: String): AiModelEntity?
    suspend fun activeModel(): AiModelEntity?
    suspend fun upsert(model: AiModelEntity)
    suspend fun clearActive()
    suspend fun setActive(id: String)
    suspend fun updateReasoning(id: String, mode: String?, effort: String?)
    suspend fun delete(id: String)
}

interface HarnessSessionRepository {
    fun observeAll(): Flow<List<HarnessSessionEntity>>
    suspend fun findById(id: String): HarnessSessionEntity?
    suspend fun upsert(session: HarnessSessionEntity)
    suspend fun touch(id: String, updatedAt: Long)
    suspend fun rename(id: String, title: String, updatedAt: Long)
    suspend fun setApprovalMode(id: String, approvalMode: String, updatedAt: Long)
    suspend fun setApprovalModeForAll(approvalMode: String, updatedAt: Long)
    suspend fun setModelSelection(id: String, modelId: String?, modelVariant: String?, updatedAt: Long)
    suspend fun deleteSession(id: String)
    suspend fun countInRange(start: Long?, end: Long?): Int
    suspend fun listAll(): List<HarnessSessionEntity>
}

interface WorkspaceRepository {
    fun observeAll(): Flow<List<WorkspaceEntity>>
    suspend fun listAll(): List<WorkspaceEntity>
    suspend fun findByName(name: String): WorkspaceEntity?
    suspend fun upsert(workspace: WorkspaceEntity)
    suspend fun delete(name: String)
}

interface TerminalSessionRepository {
    fun observeAll(): Flow<List<TerminalSessionEntity>>
    suspend fun listAll(): List<TerminalSessionEntity>
    suspend fun nextOrder(): Int
    suspend fun upsert(session: TerminalSessionEntity)
    suspend fun delete(id: String)
    suspend fun deleteAll()
}

/** Persistence boundary for the privileged Android application inventory. */
interface AndroidAppRepository {
    fun observeAll(): Flow<List<AndroidAppEntity>>
    suspend fun findByPackageName(packageName: String): AndroidAppEntity?
    suspend fun search(query: String, limit: Int = 100): List<AndroidAppEntity>
    suspend fun count(): Int
    suspend fun reconcile(apps: List<AndroidAppEntity>)
}

interface BuildScriptRepository {
    fun observeScripts(): Flow<List<BuildScriptEntity>>
    fun observeBindings(): Flow<List<ProjectBuildScriptBindingEntity>>
    suspend fun listScripts(): List<BuildScriptEntity>
    suspend fun findScript(id: String): BuildScriptEntity?
    suspend fun findBinding(projectName: String): ProjectBuildScriptBindingEntity?
    suspend fun resolvedScript(projectName: String): BuildScriptEntity?
    suspend fun upsertScript(script: BuildScriptEntity)
    suspend fun deleteScript(id: String): Boolean
    suspend fun bind(projectName: String, scriptId: String)
    suspend fun unbind(projectName: String)
    suspend fun ensureBuiltinScripts(androidScript: String = "", flutterScript: String = "")
}

@Singleton
class RoomBuildScriptRepository @Inject constructor(
    private val dao: BuildScriptDao,
) : BuildScriptRepository {
    override fun observeScripts() = dao.observeScripts()
    override fun observeBindings() = dao.observeBindings()
    override suspend fun listScripts(): List<BuildScriptEntity> {
        val scripts = dao.listScripts()
        if (scripts.isEmpty()) {
            ensureBuiltinScripts()
            return dao.listScripts()
        }
        return scripts
    }
    override suspend fun findScript(id: String): BuildScriptEntity? {
        val script = dao.findScript(id)
        if (script == null && (id == "builtin-android" || id == "builtin-flutter")) {
            ensureBuiltinScripts()
            return dao.findScript(id)
        }
        return script
    }
    override suspend fun findBinding(projectName: String) = dao.findBinding(projectName)
    override suspend fun resolvedScript(projectName: String): BuildScriptEntity? =
        dao.findBinding(projectName)?.let { findScript(it.scriptId) }
    override suspend fun upsertScript(script: BuildScriptEntity) = dao.upsertScript(script)
    override suspend fun deleteScript(id: String) = dao.deleteScriptAndBindings(id)
    override suspend fun bind(projectName: String, scriptId: String) {
        requireNotNull(findScript(scriptId)) { "构建脚本不存在：$scriptId" }
        dao.upsertBinding(ProjectBuildScriptBindingEntity(projectName, scriptId, System.currentTimeMillis()))
    }
    override suspend fun unbind(projectName: String) = dao.deleteBinding(projectName)

    override suspend fun ensureBuiltinScripts(androidScript: String, flutterScript: String) {
        val now = System.currentTimeMillis()
        val existing = dao.listScripts().associateBy { it.id }
        val builtins = listOf(
            BuildScriptEntity(
                id = "builtin-android",
                name = "标准 Android",
                description = "万象内置 Android 构建脚本，可复制后适配旧版或新版依赖。",
                projectType = "ANDROID",
                content = androidScript.ifBlank {
                    "#!/bin/sh\nset -eu\nPROJECT_DIR=\"\${1:-.}\"\nTASK=\"\${2:-assembleDebug}\"\ncd \"\$PROJECT_DIR\"\nif [ -f ./gradlew ]; then\n    chmod +x ./gradlew\n    ./gradlew \"\$TASK\" --no-daemon --max-workers=2\nelif command -v gradle >/dev/null 2>&1; then\n    gradle \"\$TASK\" --no-daemon --max-workers=2\nelif [ -x /opt/wanxiang/bin/gradle ]; then\n    /opt/wanxiang/bin/gradle \"\$TASK\" --no-daemon --max-workers=2\nelse\n    echo '未找到可用的 Gradle 环境，请检查是否已安装 Android 基础套件' >&2\n    exit 127\nfi\n"
                },
                isBuiltin = true,
                createdAt = now,
                updatedAt = now,
            ),
            BuildScriptEntity(
                id = "builtin-flutter",
                name = "标准 Flutter",
                description = "万象内置 Flutter APK 构建脚本，可复制后定制。",
                projectType = "FLUTTER",
                content = flutterScript.ifBlank {
                    "#!/bin/sh\nset -eu\nPROJECT_DIR=\"\${1:-.}\"\nTARGET=\"\${2:-apk --debug}\"\ncd \"\$PROJECT_DIR\"\nflutter pub get\nflutter build \$TARGET\n"
                },
                isBuiltin = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        builtins.filter { it.id !in existing }.forEach { dao.upsertScript(it) }
    }
}

@Singleton
class RoomAndroidAppRepository @Inject constructor(private val dao: AndroidAppDao) : AndroidAppRepository {
    override fun observeAll() = dao.observeAll()
    override suspend fun findByPackageName(packageName: String) = dao.findByPackageName(packageName)
    override suspend fun search(query: String, limit: Int) = dao.search(query, limit)
    override suspend fun count() = dao.count()
    override suspend fun reconcile(apps: List<AndroidAppEntity>) = dao.reconcile(apps)
}

interface AgentContextRepository {
    suspend fun saveMemory(memory: AgentMemoryEntity)
    suspend fun getMemoryById(id: String): AgentMemoryEntity?
    suspend fun getMemoryByKey(key: String, scope: String, ownerId: String): AgentMemoryEntity?
    suspend fun getMemoryBySubjectKey(subjectKey: String, scope: String, ownerId: String): AgentMemoryEntity?
    suspend fun getMemoriesForContext(projectOwnerId: String, sessionId: String, limit: Int = 100): List<AgentMemoryEntity>
    suspend fun countMemories(scope: String, ownerId: String): Int
    fun observeAllMemories(): Flow<List<AgentMemoryEntity>>
    suspend fun searchMemories(query: String, projectOwnerId: String, sessionId: String, limit: Int = 50): List<AgentMemoryEntity>
    suspend fun getPinnedMemories(projectOwnerId: String, sessionId: String): List<AgentMemoryEntity>
    suspend fun getFreshMemories(projectOwnerId: String, sessionId: String, pinned: Boolean, now: Long, limit: Int = 100): List<AgentMemoryEntity>
    suspend fun touchMemory(id: String, now: Long)
    suspend fun deleteMemoryById(id: String)
    suspend fun deleteMemoryByKey(key: String, scope: String, ownerId: String)
    suspend fun savePlan(plan: AgentPlanEntity)
    suspend fun getPlanBySession(sessionId: String): AgentPlanEntity?
    suspend fun getActivePlan(sessionId: String): AgentPlanEntity?
    suspend fun deletePlanBySession(sessionId: String)
    suspend fun saveScratchpad(scratchpad: AgentScratchpadEntity)
    suspend fun getScratchpad(sessionId: String, key: String): AgentScratchpadEntity?
    suspend fun listScratchpads(sessionId: String): List<AgentScratchpadEntity>
    suspend fun deleteScratchpad(sessionId: String, key: String)
    suspend fun clearScratchpads(sessionId: String)
}

@Singleton
class RoomAiModelRepository @Inject constructor(private val dao: AiModelDao) : AiModelRepository {
    override fun observeAll() = dao.observeAll()
    override suspend fun findById(id: String) = dao.findById(id)
    override suspend fun activeModel() = dao.activeModel()
    override suspend fun upsert(model: AiModelEntity) = dao.upsert(model)
    override suspend fun clearActive() = dao.clearActive()
    override suspend fun setActive(id: String) = dao.setActive(id)
    override suspend fun updateReasoning(id: String, mode: String?, effort: String?) = dao.updateReasoning(id, mode, effort)
    override suspend fun delete(id: String) = dao.delete(id)
}

@Singleton
class RoomHarnessSessionRepository @Inject constructor(private val dao: HarnessSessionDao) : HarnessSessionRepository {
    override fun observeAll() = dao.observeAll()
    override suspend fun findById(id: String) = dao.findById(id)
    override suspend fun upsert(session: HarnessSessionEntity) = dao.upsert(session)
    override suspend fun touch(id: String, updatedAt: Long) = dao.touch(id, updatedAt)
    override suspend fun rename(id: String, title: String, updatedAt: Long) = dao.rename(id, title, updatedAt)
    override suspend fun setApprovalMode(id: String, approvalMode: String, updatedAt: Long) = dao.setApprovalMode(id, approvalMode, updatedAt)
    override suspend fun setApprovalModeForAll(approvalMode: String, updatedAt: Long) = dao.setApprovalModeForAll(approvalMode, updatedAt)
    override suspend fun setModelSelection(id: String, modelId: String?, modelVariant: String?, updatedAt: Long) =
        dao.setModelSelection(id, modelId, modelVariant, updatedAt)
    override suspend fun deleteSession(id: String) = dao.deleteSession(id)
    override suspend fun countInRange(start: Long?, end: Long?) = dao.countInRange(start, end)
    override suspend fun listAll() = dao.listAll()
}

@Singleton
class RoomWorkspaceRepository @Inject constructor(private val dao: WorkspaceDao) : WorkspaceRepository {
    override fun observeAll() = dao.observeAll()
    override suspend fun listAll() = dao.listAll()
    override suspend fun findByName(name: String) = dao.findByName(name)
    override suspend fun upsert(workspace: WorkspaceEntity) = dao.upsert(workspace)
    override suspend fun delete(name: String) = dao.delete(name)
}

@Singleton
class RoomTerminalSessionRepository @Inject constructor(private val dao: TerminalSessionDao) : TerminalSessionRepository {
    override fun observeAll() = dao.observeAll()
    override suspend fun listAll() = dao.listAll()
    override suspend fun nextOrder() = dao.nextOrder()
    override suspend fun upsert(session: TerminalSessionEntity) = dao.upsert(session)
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun deleteAll() = dao.deleteAll()
}

@Singleton
class RoomAgentContextRepository @Inject constructor(private val dao: AgentContextDao) : AgentContextRepository {
    override suspend fun saveMemory(memory: AgentMemoryEntity) = dao.saveMemory(memory)
    override suspend fun getMemoryById(id: String) = dao.getMemoryById(id)
    override suspend fun getMemoryByKey(key: String, scope: String, ownerId: String) = dao.getMemoryByKey(key, scope, ownerId)
    override suspend fun getMemoryBySubjectKey(subjectKey: String, scope: String, ownerId: String) =
        dao.getMemoryBySubjectKey(subjectKey, scope, ownerId)
    override suspend fun getMemoriesForContext(projectOwnerId: String, sessionId: String, limit: Int) =
        dao.getMemoriesForContext(projectOwnerId, sessionId, limit)
    override suspend fun countMemories(scope: String, ownerId: String) = dao.countMemories(scope, ownerId)
    override fun observeAllMemories() = dao.observeAllMemories()
    override suspend fun searchMemories(query: String, projectOwnerId: String, sessionId: String, limit: Int) =
        dao.searchMemories(query, projectOwnerId, sessionId, limit)
    override suspend fun getPinnedMemories(projectOwnerId: String, sessionId: String) =
        dao.getPinnedMemories(projectOwnerId, sessionId)
    override suspend fun getFreshMemories(projectOwnerId: String, sessionId: String, pinned: Boolean, now: Long, limit: Int) =
        dao.getFreshMemories(projectOwnerId, sessionId, pinned, now, limit)
    override suspend fun touchMemory(id: String, now: Long) = dao.touchMemory(id, now)
    override suspend fun deleteMemoryById(id: String) = dao.deleteMemoryById(id)
    override suspend fun deleteMemoryByKey(key: String, scope: String, ownerId: String) = dao.deleteMemoryByKey(key, scope, ownerId)
    override suspend fun savePlan(plan: AgentPlanEntity) = dao.savePlan(plan)
    override suspend fun getPlanBySession(sessionId: String) = dao.getPlanBySession(sessionId)
    override suspend fun getActivePlan(sessionId: String) = dao.getActivePlan(sessionId)
    override suspend fun deletePlanBySession(sessionId: String) = dao.deletePlanBySession(sessionId)
    override suspend fun saveScratchpad(scratchpad: AgentScratchpadEntity) = dao.saveScratchpad(scratchpad)
    override suspend fun getScratchpad(sessionId: String, key: String) = dao.getScratchpad(sessionId, key)
    override suspend fun listScratchpads(sessionId: String) = dao.listScratchpads(sessionId)
    override suspend fun deleteScratchpad(sessionId: String, key: String) = dao.deleteScratchpad(sessionId, key)
    override suspend fun clearScratchpads(sessionId: String) = dao.clearScratchpads(sessionId)
}

interface QuickPhraseRepository {
    fun observeAll(): Flow<List<QuickPhrase>>
    suspend fun getAll(): List<QuickPhrase>
    suspend fun findById(id: String): QuickPhrase?
    suspend fun upsert(phrase: QuickPhrase)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun delete(id: String)
    suspend fun resetToDefault()
    suspend fun ensureInitialized()
}

@Singleton
class RoomQuickPhraseRepository @Inject constructor(
    private val dao: QuickPhraseDao,
) : QuickPhraseRepository {
    override fun observeAll(): Flow<List<QuickPhrase>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getAll(): List<QuickPhrase> =
        dao.getAll().map { it.toDomain() }

    override suspend fun findById(id: String): QuickPhrase? =
        dao.findById(id)?.toDomain()

    override suspend fun upsert(phrase: QuickPhrase) =
        dao.upsert(QuickPhraseEntity.fromDomain(phrase))

    override suspend fun setEnabled(id: String, enabled: Boolean) =
        dao.setEnabled(id, enabled)

    override suspend fun delete(id: String) =
        dao.delete(id)

    override suspend fun resetToDefault() {
        dao.clearAll()
        dao.upsertAll(defaultQuickPhrases.map { QuickPhraseEntity.fromDomain(it) })
    }

    override suspend fun ensureInitialized() {
        if (dao.count() == 0) {
            dao.upsertAll(defaultQuickPhrases.map { QuickPhraseEntity.fromDomain(it) })
        }
    }

    companion object {
        val defaultQuickPhrases = listOf(
            QuickPhrase(
                id = "builtin_run",
                title = "运行代码",
                content = "/run ",
                description = "执行当前工作区的入口代码（如 python main.py / npm start）",
                iconName = "Play",
                targetProjectType = null,
                isEnabled = true,
                sortOrder = 1,
                isBuiltin = true,
            ),
            QuickPhrase(
                id = "builtin_install",
                title = "安装依赖",
                content = "/install ",
                description = "在 Linux 沙箱中安装系统或语言依赖（apt / pip / npm）",
                iconName = "Package",
                targetProjectType = null,
                isEnabled = true,
                sortOrder = 2,
                isBuiltin = true,
            ),
            QuickPhrase(
                id = "builtin_init",
                title = "初始化项目",
                content = "/init ",
                description = "创建新的项目骨架模板（Python, Node.js, C/C++, HTML）",
                iconName = "Plus",
                targetProjectType = null,
                isEnabled = true,
                sortOrder = 3,
                isBuiltin = true,
            ),
            QuickPhrase(
                id = "builtin_git",
                title = "Git 状态",
                content = "/git status",
                description = "查看状态、提交或拉取版本控制仓库",
                iconName = "Code",
                targetProjectType = null,
                isEnabled = true,
                sortOrder = 4,
                isBuiltin = true,
            ),
            QuickPhrase(
                id = "builtin_test",
                title = "运行测试",
                content = "/test ",
                description = "执行单元测试与代码验证",
                iconName = "Check",
                targetProjectType = null,
                isEnabled = true,
                sortOrder = 5,
                isBuiltin = true,
            ),
            QuickPhrase(
                id = "builtin_help",
                title = "环境与帮助",
                content = "/help",
                description = "查看当前 Linux PRoot 沙箱环境与 Agent 工具说明",
                iconName = "Alert",
                targetProjectType = null,
                isEnabled = true,
                sortOrder = 6,
                isBuiltin = true,
            ),
            QuickPhrase(
                id = "builtin_android_check",
                title = "检查 Android 工程",
                content = "请检查当前 Android 工程结构、Gradle 配置、Manifest、包名和构建环境；发现问题直接编辑文件修复并验证。",
                description = "检查 Gradle、Manifest、包名和当前构建环境",
                iconName = "Check",
                targetProjectType = "ANDROID",
                isEnabled = true,
                sortOrder = 10,
                isBuiltin = true,
            ),
            QuickPhrase(
                id = "builtin_android_build",
                title = "编译并安装到手机",
                content = "请构建当前 Android 工程，成功后将 APK 导出到手机并调起安装器；优先使用当前工作区的构建脚本和 wanxiang-host install-apk。",
                description = "构建 Debug APK，导出并调起手机安装器",
                iconName = "Play",
                targetProjectType = "ANDROID",
                isEnabled = true,
                sortOrder = 11,
                isBuiltin = true,
            ),
            QuickPhrase(
                id = "builtin_android_debug",
                title = "排查 Android 构建",
                content = "请读取最近一次 Android 构建日志，定位真实错误并直接编辑脚本或工程文件修复，然后重新验证。",
                description = "定位 Gradle、Kotlin、AAPT2 或安装问题",
                iconName = "Alert",
                targetProjectType = "ANDROID",
                isEnabled = true,
                sortOrder = 12,
                isBuiltin = true,
            ),
            QuickPhrase(
                id = "builtin_flutter_check",
                title = "检查 Flutter 工程",
                content = "请检查当前 Flutter 工程的 pubspec.yaml、Dart 入口和 Android Gradle 配置，发现问题直接修复并验证。",
                description = "检查 pubspec、Dart 入口和 Android 宿主配置",
                iconName = "Check",
                targetProjectType = "FLUTTER",
                isEnabled = true,
                sortOrder = 20,
                isBuiltin = true,
            ),
            QuickPhrase(
                id = "builtin_flutter_build",
                title = "编译并安装 Flutter",
                content = "请执行 Flutter 依赖检查和 Debug APK 构建，成功后将 APK 导出到手机并调起 wanxiang-host install-apk。",
                description = "拉取依赖、构建 APK 并调起安装器",
                iconName = "Play",
                targetProjectType = "FLUTTER",
                isEnabled = true,
                sortOrder = 21,
                isBuiltin = true,
            ),
            QuickPhrase(
                id = "builtin_flutter_debug",
                title = "排查 Flutter 构建",
                content = "请读取 Flutter 最近一次构建错误，定位依赖、Gradle 或 AAPT2 根因，直接修改工程并重新验证。",
                description = "定位依赖、Gradle 或 AAPT2 错误",
                iconName = "Alert",
                targetProjectType = "FLUTTER",
                isEnabled = true,
                sortOrder = 22,
                isBuiltin = true,
            ),
            QuickPhrase(
                id = "builtin_reverse_analyze",
                title = "分析 APK 工程",
                content = "请读取当前逆向工程的 apk-info.properties 和 REVERSE.md，使用 jadx/apktool 分析 APK 并汇报关键发现。",
                description = "读取清单、DEX、资源和加固特征",
                iconName = "Search",
                targetProjectType = "REVERSE",
                isEnabled = true,
                sortOrder = 30,
                isBuiltin = true,
            ),
            QuickPhrase(
                id = "builtin_reverse_decode",
                title = "解包并反编译",
                content = "请对当前工程内的原始 APK 执行安全解包和反编译，保留原始文件并把产物写入新的输出目录。",
                description = "执行 JADX 或 apktool 解包流程",
                iconName = "Code",
                targetProjectType = "REVERSE",
                isEnabled = true,
                sortOrder = 31,
                isBuiltin = true,
            ),
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PersistenceRepositoryModule {
    @Binds abstract fun bindAiModelRepository(impl: RoomAiModelRepository): AiModelRepository
    @Binds abstract fun bindHarnessSessionRepository(impl: RoomHarnessSessionRepository): HarnessSessionRepository
    @Binds abstract fun bindWorkspaceRepository(impl: RoomWorkspaceRepository): WorkspaceRepository
    @Binds abstract fun bindTerminalSessionRepository(impl: RoomTerminalSessionRepository): TerminalSessionRepository
    @Binds abstract fun bindAgentContextRepository(impl: RoomAgentContextRepository): AgentContextRepository
    @Binds abstract fun bindAndroidAppRepository(impl: RoomAndroidAppRepository): AndroidAppRepository
    @Binds abstract fun bindQuickPhraseRepository(impl: RoomQuickPhraseRepository): QuickPhraseRepository
    @Binds abstract fun bindHarnessRuntimeRepository(impl: RoomHarnessRuntimeRepository): HarnessRuntimeRepository
    @Binds abstract fun bindBuildScriptRepository(impl: RoomBuildScriptRepository): BuildScriptRepository
}
