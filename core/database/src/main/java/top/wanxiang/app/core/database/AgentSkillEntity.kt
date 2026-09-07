package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.wanxiang.app.core.model.AgentSkill
import top.wanxiang.app.core.model.BuiltinSkills
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "agent_skills")
data class AgentSkillEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val triggerCommand: String?,
    val iconName: String,
    val isEnabled: Boolean,
    val isBuiltin: Boolean,
    val isImmutable: Boolean,
    val category: String,
    val resourcePath: String?,
)

@Dao
interface AgentSkillDao {
    @Query("SELECT * FROM agent_skills ORDER BY isBuiltin DESC, name ASC")
    fun observeAll(): Flow<List<AgentSkillEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(skills: List<AgentSkillEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: AgentSkillEntity)

    @Query("UPDATE agent_skills SET isEnabled = :enabled WHERE id = :id AND isImmutable = 0")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM agent_skills WHERE id = :id AND isBuiltin = 0")
    suspend fun deleteCustom(id: String)
}

/** 可自动发现 Skill 的宿主目录与其在 PRoot 沙箱内的挂载前缀（如 /attachments/skills）。 */
data class SkillScanRoot(
    val hostDir: File,
    val guestPrefix: String,
)

@Singleton
class AgentSkillRepository @Inject constructor(
    private val dao: AgentSkillDao,
) {
    val allSkills: Flow<List<AgentSkill>> = dao.observeAll().map { rows -> rows.map(AgentSkillEntity::toModel) }
    val activeSkills: Flow<List<AgentSkill>> = allSkills.map { skills -> skills.filter { it.isEnabled } }

    private val directorySyncMutex = Mutex()

    suspend fun ensureInitialized() {
        dao.insertAll(BuiltinSkills.presets.map(AgentSkill::toEntity))
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        ensureInitialized()
        dao.setEnabled(id, enabled)
    }

    suspend fun addCustom(skill: AgentSkill) {
        ensureInitialized()
        dao.upsert(skill.toEntity())
    }

    suspend fun deleteCustom(id: String) {
        ensureInitialized()
        dao.deleteCustom(id)
    }

    /**
     * 递归扫描各根目录（含任意深度嵌套），把直属包含 SKILL.md / prompt.md 且尚未入库的
     * 目录自动注册为自定义 Skill。用户手动复制 Skill 文件夹（或把其他工具的整个
     * skills 目录整体复制）到 attachments/skills 或工作区 skills 目录即可被发现，
     * 无需通过 ZIP 逐个导入。按 resourcePath 去重，重复调用安全。
     *
     * 嵌套语义：目录树中任何直属含 SKILL.md 的目录都是一个 Skill——既支持
     * “单个 Skill 目录”，也支持“集合目录（如 rikkahub / aicode 的整个 skills 目录）”，
     * 以及 Skill 目录内再嵌套 Skill 子目录的多级结构。
     *
     * @return 本次新注册的 Skill 列表
     */
    suspend fun syncFromDirectories(roots: List<SkillScanRoot>): List<AgentSkill> = directorySyncMutex.withLock {
        ensureInitialized()
        val knownPaths = dao.observeAll().first()
            .mapNotNull { it.resourcePath }
            .mapTo(mutableSetOf()) { stored ->
                runCatching { File(stored).canonicalPath }.getOrDefault(stored)
            }
        val imported = mutableListOf<AgentSkill>()
        roots.forEach { root ->
            root.hostDir.walkTopDown()
                .maxDepth(MAX_SCAN_DEPTH)
                .filter { it.isDirectory && it != root.hostDir }
                .forEach { dir ->
                    val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return@forEach
                    if (canonical in knownPaths) return@forEach
                    val promptFile = directPromptFile(dir) ?: return@forEach
                    val relative = dir.relativeTo(root.hostDir).invariantSeparatorsPath
                    registerDir(dir, promptFile, root.guestPrefix.trimEnd('/') + "/" + relative, knownPaths, imported)
                }
        }
        imported
    }

    private suspend fun registerDir(dir: File, promptFile: File, guestPath: String, knownPaths: MutableSet<String>, imported: MutableList<AgentSkill>) {
        val markdown = runCatching { promptFile.readText().trim() }.getOrNull()
        if (markdown.isNullOrBlank()) return
        val skill = AgentSkill(
            id = "custom_" + UUID.randomUUID().toString().take(8),
            name = extractSkillMetadata(markdown, "name")
                ?: markdown.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
                ?: dir.name,
            description = extractSkillMetadata(markdown, "description") ?: "从目录自动发现的 Skill",
            systemPrompt = markdown + "\n\n【Skill 资源目录】$guestPath\n如需执行该 Skill 附带的脚本，请先检查脚本内容与参数，再从此目录调用。",
            isBuiltin = false,
            category = "自定义",
            resourcePath = dir.absolutePath,
        )
        dao.upsert(skill.toEntity())
        knownPaths += runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        imported += skill
    }

    /** 目录直属的提示词文件（不含嵌套子目录），优先 SKILL.md。 */
    private fun directPromptFile(dir: File): File? =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.lowercase() in SKILL_PROMPT_FILE_NAMES }
            .let { files -> files.firstOrNull { it.name.lowercase() == "skill.md" } ?: files.firstOrNull() }

    companion object {
        /** Skill 目录的提示词文件名（小写），供导入与扫描逻辑统一判定。 */
        val SKILL_PROMPT_FILE_NAMES = setOf("skill.md", "prompt.md")
        private const val MAX_SCAN_DEPTH = 6

        /** 从 SKILL.md 的 YAML frontmatter 中提取 name / description 等元数据。 */
        fun extractSkillMetadata(markdown: String, key: String): String? {
            if (!markdown.startsWith("---")) return null
            return markdown.lineSequence().drop(1).takeWhile { it.trim() != "---" }
                .firstOrNull { it.substringBefore(':').trim().equals(key, ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.trim('"', '\'')?.takeIf { it.isNotBlank() }
        }
    }
}

private fun AgentSkillEntity.toModel() = AgentSkill(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    triggerCommand = triggerCommand,
    iconName = iconName,
    isEnabled = isEnabled,
    isBuiltin = isBuiltin,
    isImmutable = isImmutable,
    category = category,
    resourcePath = resourcePath,
)

private fun AgentSkill.toEntity() = AgentSkillEntity(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    triggerCommand = triggerCommand,
    iconName = iconName,
    isEnabled = isEnabled,
    isBuiltin = isBuiltin,
    isImmutable = isImmutable,
    category = category,
    resourcePath = resourcePath,
)
