package top.wanxiang.app.harness.mcp

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 工作区感知的 MCP 预设推荐器（Harness 2.0 Phase 3.1 的第一块切片）。
 *
 * 扫描工作区顶层目录的文件特征，匹配内置 MCP 预设的启用信号：
 * - `.git` 目录 → Git 协同中心
 * - `*.db / *.sqlite*` → SQLite 探索器
 * - `*.apk` → Android 逆向与 APK 审计
 * - 代码文件达到密度阈值 → CodeGraph 代码知识图谱
 *
 * 只做轻量顶层扫描（不递归、限制条目数），结果供 UI 提示用户一键启用；
 * 不自动启用任何服务，启用决定权始终在用户。
 */
@Singleton
class McpWorkspaceRecommender @Inject constructor() {

    data class Recommendation(
        val presetId: String,
        val presetName: String,
        val reason: String,
    )

    suspend fun recommend(workspaceDir: File?): List<Recommendation> = withContext(Dispatchers.IO) {
        val root = workspaceDir?.takeIf { it.isDirectory } ?: return@withContext emptyList()
        val entries = runCatching { root.listFiles()?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .take(MAX_SCAN_ENTRIES)
        if (entries.isEmpty()) return@withContext emptyList()

        val recommendations = mutableListOf<Recommendation>()
        if (entries.any { it.isDirectory && it.name == ".git" }) {
            recommendations += Recommendation(
                presetId = "mcp_git",
                presetName = "Git 仓库协同中心",
                reason = "检测到 Git 仓库（.git），启用后 Agent 可直接分析提交历史、分支与 Diff",
            )
        }
        if (entries.any { it.isFile && it.extension.lowercase() in SQLITE_EXTENSIONS }) {
            recommendations += Recommendation(
                presetId = "mcp_sqlite",
                presetName = "SQLite 数据库探索器",
                reason = "检测到 SQLite 数据库文件，启用后 Agent 可直接查询与分析数据",
            )
        }
        if (entries.any { it.isFile && it.extension.lowercase() == "apk" }) {
            recommendations += Recommendation(
                presetId = "mcp_apktool",
                presetName = "Android 逆向与 APK 审计",
                reason = "检测到 APK 文件，启用后 Agent 可反编译、分析清单权限与 Smali 代码",
            )
        }
        val codeFileCount = entries.count { it.isFile && it.extension.lowercase() in CODE_EXTENSIONS }
        if (codeFileCount >= CODE_FILE_THRESHOLD) {
            recommendations += Recommendation(
                presetId = "mcp_codegraph",
                presetName = "CodeGraph 代码知识图谱",
                reason = "检测到 $codeFileCount 个代码文件，启用后可建立符号索引，减少代码探索的调用轮次",
            )
        }
        recommendations
    }

    companion object {
        private val SQLITE_EXTENSIONS = setOf("db", "sqlite", "sqlite3")
        private val CODE_EXTENSIONS = setOf(
            "kt", "java", "py", "js", "ts", "jsx", "tsx",
            "c", "cc", "cpp", "h", "hpp", "rs", "go", "smali",
        )
        private const val CODE_FILE_THRESHOLD = 3
        private const val MAX_SCAN_ENTRIES = 500
    }
}
