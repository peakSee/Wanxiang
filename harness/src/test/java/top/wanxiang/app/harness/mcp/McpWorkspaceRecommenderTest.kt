package top.wanxiang.app.harness.mcp

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpWorkspaceRecommenderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val recommender = McpWorkspaceRecommender()

    @Test
    fun `git repository recommends git preset`() = runBlocking {
        val root = temporaryFolder.newFolder()
        File(root, ".git").mkdirs()

        val recommendations = recommender.recommend(root)

        assertEquals(listOf("mcp_git"), recommendations.map { it.presetId })
    }

    @Test
    fun `sqlite files recommend sqlite preset`() = runBlocking {
        val root = temporaryFolder.newFolder()
        File(root, "app.db").writeText("")
        File(root, "cache.sqlite3").writeText("")

        val recommendations = recommender.recommend(root)

        assertEquals(listOf("mcp_sqlite"), recommendations.map { it.presetId })
    }

    @Test
    fun `apk file recommends apktool preset`() = runBlocking {
        val root = temporaryFolder.newFolder()
        File(root, "sample.apk").writeText("")

        val recommendations = recommender.recommend(root)

        assertEquals(listOf("mcp_apktool"), recommendations.map { it.presetId })
    }

    @Test
    fun `code file density recommends codegraph`() = runBlocking {
        val root = temporaryFolder.newFolder()
        listOf("Main.kt", "Util.java", "core.py").forEach { File(root, it).writeText("") }

        val recommendations = recommender.recommend(root)

        assertEquals(listOf("mcp_codegraph"), recommendations.map { it.presetId })
    }

    @Test
    fun `mixed workspace recommends all matched presets`() = runBlocking {
        val root = temporaryFolder.newFolder()
        File(root, ".git").mkdirs()
        File(root, "app.db").writeText("")
        File(root, "sample.apk").writeText("")
        listOf("Main.kt", "Util.java", "core.py").forEach { File(root, it).writeText("") }

        val recommendations = recommender.recommend(root)

        assertEquals(
            setOf("mcp_git", "mcp_sqlite", "mcp_apktool", "mcp_codegraph"),
            recommendations.map { it.presetId }.toSet(),
        )
    }

    @Test
    fun `below code threshold does not recommend codegraph`() = runBlocking {
        val root = temporaryFolder.newFolder()
        listOf("Main.kt", "Util.java").forEach { File(root, it).writeText("") }

        val recommendations = recommender.recommend(root)

        assertTrue(recommendations.isEmpty())
    }

    @Test
    fun `empty or missing directory yields no recommendations`() = runBlocking {
        assertTrue(recommender.recommend(null).isEmpty())
        val empty = temporaryFolder.newFolder()
        assertTrue(recommender.recommend(empty).isEmpty())
        assertTrue(recommender.recommend(File(empty, "missing")).isEmpty())
    }

    @Test
    fun `non code extensions do not count toward codegraph`() = runBlocking {
        val root = temporaryFolder.newFolder()
        listOf("a.txt", "b.md", "c.json", "d.yml").forEach { File(root, it).writeText("") }

        val recommendations = recommender.recommend(root)

        assertTrue(recommendations.isEmpty())
    }
}
