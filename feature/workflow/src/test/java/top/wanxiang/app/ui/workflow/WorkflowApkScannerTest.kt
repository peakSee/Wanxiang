package top.wanxiang.app.ui.workflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkflowApkScannerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var workspaceDir: File

    @Before
    fun setUp() {
        workspaceDir = temporaryFolder.newFolder("workspace")
    }

    @Test
    fun scanApksFindsAndSortsProjectApksDescending() {
        val projectDir = File(workspaceDir, "RelayGo").apply { mkdirs() }
        val flutterApkDir = File(projectDir, "build/app/outputs/flutter-apk").apply { mkdirs() }

        val olderApk = File(flutterApkDir, "app-debug.apk").apply {
            writeBytes(ByteArray(1024))
            setLastModified(1_000_000L)
        }

        val newerApk = File(flutterApkDir, "app-release.apk").apply {
            writeBytes(ByteArray(2048))
            setLastModified(2_000_000L)
        }

        // Non-apk file that must be ignored
        File(flutterApkDir, "output-metadata.json").writeText("{}")

        val apks = scanApksInWorkspace(workspaceDir, "RelayGo")
        assertEquals(2, apks.size)

        // Newest must be first
        assertEquals("app-release.apk", apks[0].name)
        assertEquals("build/app/outputs/flutter-apk/app-release.apk", apks[0].relativePath)
        assertEquals("/workspace/RelayGo/build/app/outputs/flutter-apk/app-release.apk", apks[0].sandboxPath)
        assertEquals(2048L, apks[0].sizeBytes)

        assertEquals("app-debug.apk", apks[1].name)
        assertEquals("build/app/outputs/flutter-apk/app-debug.apk", apks[1].relativePath)
    }

    @Test
    fun scanApksFallsBackToWorkspaceRootWhenProjectHasNone() {
        val projectDir = File(workspaceDir, "EmptyProject").apply { mkdirs() }
        val otherProjectDir = File(workspaceDir, "AndroidProject/app/build/outputs/apk/release").apply { mkdirs() }

        val rootApk = File(otherProjectDir, "app-release.apk").apply {
            writeBytes(ByteArray(4096))
            setLastModified(5_000_000L)
        }

        val apks = scanApksInWorkspace(workspaceDir, "EmptyProject")
        assertEquals(1, apks.size)
        assertEquals("app-release.apk", apks[0].name)
        assertTrue(apks[0].sandboxPath.contains("AndroidProject"))
    }

    @Test
    fun scanApksReturnsEmptyWhenNoApksExist() {
        File(workspaceDir, "EmptyProject").mkdirs()
        val apks = scanApksInWorkspace(workspaceDir, "EmptyProject")
        assertTrue(apks.isEmpty())
    }
}
