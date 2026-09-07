package top.wanxiang.app.runtime.rootfs

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import top.wanxiang.app.runtime.DistributionCatalog
import top.wanxiang.app.runtime.ElfInspector
import top.wanxiang.app.runtime.RuntimePathManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootfsResetTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var pathManager: RuntimePathManager
    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = temporaryFolder.newFolder("linux-runtime")
        val validator = RootfsValidator(ElfInspector())
        val context = TestContext(baseDir)
        pathManager = RuntimePathManager(context, validator)
        pathManager.ensureDirectories()
    }

    @Test
    fun distroLayersFileReturnsCorrectMetadataPath() {
        val layersFile = pathManager.distroLayersFile("debian")
        assertEquals(
            File(pathManager.metadataDir("debian"), "layers.txt").absolutePath,
            layersFile.absolutePath,
        )
    }

    @Test
    fun distributionCatalogResolvesCorrectSpecs() {
        val debian = DistributionCatalog.require("debian")
        assertEquals("Debian", debian.displayName)
        assertEquals("debian", debian.id)

        val alpine = DistributionCatalog.require("alpine")
        assertEquals("Alpine Linux", alpine.displayName)
        assertEquals("alpine", alpine.id)

        // Fallback for unknown id
        val fallback = DistributionCatalog.require("unknown-distro-xyz")
        assertEquals("Ubuntu", fallback.displayName)
    }

    @Test
    fun layersFileReadAndParseCorrectly() {
        val layersFile = pathManager.distroLayersFile("alpine")
        layersFile.parentFile?.mkdirs()
        layersFile.writeText(
            "oci:sha256-111111:application/vnd.oci.image.layer.v1.tar+gzip\n" +
            "oci:sha256-222222:application/vnd.oci.image.layer.v1.tar+gzip\n",
        )

        val lines = layersFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
        assertEquals("oci", lines[0].split(":")[0])
        assertEquals("sha256-111111", lines[0].split(":")[1])
        assertEquals("application/vnd.oci.image.layer.v1.tar+gzip", lines[0].split(":")[2])
    }

    @Test
    fun workspaceDirIsCompletelySeparateFromDistroRootfs() {
        val wsFile = File(pathManager.workspaceDir, "my_project/main.py")
        wsFile.parentFile?.mkdirs()
        wsFile.writeText("print('hello')")

        val distroRootfs = pathManager.rootfsDir("debian")
        distroRootfs.mkdirs()
        File(distroRootfs, "broken_file.txt").writeText("broken")

        // Emulate resetting distroRootfs
        distroRootfs.deleteRecursively()
        distroRootfs.mkdirs()

        // Workspace file is still preserved and untouched
        assertTrue(wsFile.exists())
        assertEquals("print('hello')", wsFile.readText())
        assertFalse(File(distroRootfs, "broken_file.txt").exists())
    }

    private class TestContext(private val rootDir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = rootDir
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo().apply {
            nativeLibraryDir = File(rootDir, "lib").apply { mkdirs() }.absolutePath
        }
    }
}
