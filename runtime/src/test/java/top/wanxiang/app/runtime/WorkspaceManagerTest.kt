package top.wanxiang.app.runtime

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import top.wanxiang.app.core.database.WorkspaceRepository
import top.wanxiang.app.core.database.WorkspaceEntity
import top.wanxiang.app.runtime.rootfs.RootfsValidator
import top.wanxiang.app.template.ProjectTemplateEngine
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var workspaceDir: File
    private lateinit var pathManager: RuntimePathManager
    private lateinit var workspaceDao: FakeWorkspaceDao
    private lateinit var manager: WorkspaceManager

    @Before
    fun setUp() {
        val root = temporaryFolder.newFolder("linux-runtime")
        val validator = RootfsValidator(ElfInspector())
        val context = TestContext(root)
        pathManager = RuntimePathManager(context, validator)
        copyProjectTemplates(pathManager.baseDir)
        workspaceDir = pathManager.workspaceDir.apply { mkdirs() }
        workspaceDao = FakeWorkspaceDao()
        manager = WorkspaceManager(pathManager, workspaceDao, ProjectTemplateEngine(context))
    }

    private fun runTest(block: suspend () -> Unit) = runBlocking { block() }

    @Test
    fun createProjectAndFileOperationsRoundTrip() = runTest {
        val proj = manager.createProject("demo").getOrNull()
        assertTrue(proj != null)
        assertEquals("demo", proj?.name)
        assertEquals("/workspace/demo", proj?.linuxPath)

        // 创建文件并写入
        val createRes = manager.createFile("demo", "main.py")
        assertTrue(createRes.isSuccess)

        val writeRes = manager.writeFile("demo", "main.py", "print('hello world')")
        assertTrue(writeRes.isSuccess)

        val readRes = manager.readFile("demo", "main.py")
        assertTrue(readRes.isSuccess)
        assertEquals("print('hello world')", readRes.getOrNull())

        // 创建子目录和子文件
        val dirRes = manager.createDirectory("demo", "src")
        assertTrue(dirRes.isSuccess)

        val subFileRes = manager.createFile("demo", "src/helper.py")
        assertTrue(subFileRes.isSuccess)

        // 列表排序验证：目录优先，随后按文件名排序
        val listRes = manager.listFiles("demo", "")
        assertTrue(listRes.isSuccess)
        val items = listRes.getOrNull().orEmpty()
        assertEquals(2, items.size)
        assertEquals("src", items[0].name)
        assertTrue(items[0].isDirectory)
        assertEquals("main.py", items[1].name)
        assertFalse(items[1].isDirectory)
        assertEquals("py", items[1].extension)

        // 重命名
        val renameRes = manager.renameItem("demo", "main.py", "app.py")
        assertTrue(renameRes.isSuccess)
        val readRenamed = manager.readFile("demo", "app.py")
        assertEquals("print('hello world')", readRenamed.getOrNull())

        // 删除
        val deleteFileRes = manager.deleteItem("demo", "app.py")
        assertTrue(deleteFileRes.isSuccess)
        assertFalse(manager.readFile("demo", "app.py").isSuccess)

        val deleteDirRes = manager.deleteItem("demo", "src")
        assertTrue(deleteDirRes.isSuccess)
    }

    @Test
    fun rejectsPathTraversalAndEscape() = runTest {
        manager.createProject("safe-proj")
        temporaryFolder.newFile("secret.txt").apply { writeText("top-secret") }

        // 尝试通过 .. 逃逸
        assertFalse(manager.readFile("safe-proj", "../secret.txt").isSuccess)
        assertFalse(manager.readFile("safe-proj", "/workspace/../secret.txt").isSuccess)
        assertFalse(manager.createFile("safe-proj", "../pwned.txt").isSuccess)
        assertFalse(manager.writeFile("safe-proj", "../pwned.txt", "evil").isSuccess)
    }

    @Test
    fun linksExistingInternalDirectoryWithoutDeletingItsFiles() = runTest {
        val existing = File(pathManager.workspaceDir, "existing-source").apply {
            mkdirs()
            resolve("keep.txt").writeText("keep")
        }

        val project = manager.createProject(
            name = "linked-project",
            storage = WorkspaceStorage.INTERNAL,
            directoryPath = "existing-source",
        ).getOrNull()

        assertEquals("/workspace/existing-source", project?.linuxPath)
        assertFalse(project?.ownsDirectory ?: true)
        assertEquals("keep", manager.readFile("linked-project", "keep.txt").getOrNull())

        assertTrue(manager.deleteProject("linked-project").isSuccess)
        assertTrue(existing.resolve("keep.txt").isFile)
    }

    @Test
    fun templateDoesNotOverlayNonEmptyDirectory() = runTest {
        val directory = File(workspaceDir, "existing-android").apply {
            mkdirs()
            resolve("old.txt").writeText("old")
        }

        val result = manager.createProject(
            name = "existing-android-project",
            directoryPath = "existing-android",
            templateId = "builtin.android-compose",
            packageName = "com.example.existingandroid",
        )

        assertFalse(result.isSuccess)
        assertTrue(directory.resolve("old.txt").isFile)
    }

    @Test
    fun androidTemplateGeneratesLauncherInPackageDirectory() = runTest {
        val result = manager.createProject(
            name = "android-template",
            templateId = "builtin.android-compose",
            packageName = "com.example.generated",
        )

        assertTrue(result.isSuccess)
        val project = File(workspaceDir, "android-template")
        val launcher = project.resolve("app/src/main/java/com/example/generated/MainActivity.kt")
        assertTrue(launcher.isFile)
        assertTrue(launcher.readText().startsWith("package com.example.generated"))
        assertFalse(project.walkTopDown().any { it.name.endsWith(".template") })
        assertTrue(
            project.resolve("settings.gradle.kts").readText()
                .contains("import org.gradle.api.initialization.resolve.RepositoriesMode"),
        )
    }

    @Test
    fun flutterTemplateGeneratesAndroidHostInPackageDirectory() = runTest {
        val result = manager.createProject(
            name = "flutter_template",
            templateId = "builtin.flutter",
            packageName = "com.example.fluttergenerated",
        )

        assertTrue(result.isSuccess)
        val project = File(workspaceDir, "flutter_template")
        val launcher = project.resolve(
            "android/app/src/main/kotlin/com/example/fluttergenerated/MainActivity.kt",
        )
        assertTrue(launcher.isFile)
        assertTrue(launcher.readText().startsWith("package com.example.fluttergenerated"))
        assertFalse(project.walkTopDown().any { it.name.endsWith(".template") })
    }

    @Test
    fun flutterTemplateRejectsNumericProjectName() = runTest {
        val result = manager.createProject(
            name = "444",
            templateId = "builtin.flutter",
            packageName = "com.example.numeric",
        )

        assertTrue(result.isFailure)
        assertFalse(File(workspaceDir, "444").exists())
    }

    @Test
    fun androidNoActivityTemplateHasNoLauncherOrActivitySource() = runTest {
        val result = manager.createProject(
            name = "android-no-activity",
            templateId = "builtin.android-no-activity",
            packageName = "com.example.noactivity",
        )

        assertTrue(result.isSuccess)
        val project = File(workspaceDir, "android-no-activity")
        assertTrue(project.resolve("app/build.gradle.kts").isFile)
        assertFalse(project.resolve("app/src/main/java/com/example/noactivity/MainActivity.kt").exists())
        assertFalse(project.resolve("app/src/main/AndroidManifest.xml").readText().contains("<activity"))
    }

    @Test
    fun xposedTemplateGeneratesHookInPackageDirectory() = runTest {
        val result = manager.createProject(
            name = "xposed-template",
            templateId = "builtin.android-xposed",
            packageName = "com.example.xposedmodule",
            templateVariables = mapOf(
                "targetPackage" to "com.example.target",
                "moduleDescription" to "Example module",
            ),
        )

        assertTrue(result.isSuccess)
        val project = File(workspaceDir, "xposed-template")
        val hook = project.resolve("app/src/main/java/com/example/xposedmodule/MainHook.kt")
        assertTrue(hook.isFile)
        assertTrue(hook.readText().startsWith("package com.example.xposedmodule"))
        assertEquals(
            "com.example.xposedmodule.MainHook",
            project.resolve("app/src/main/assets/xposed_init").readText().trim(),
        )
        assertFalse(project.walkTopDown().any { it.name.endsWith(".template") })
    }

    @Test
    fun importedXposedTemplateGeneratesHookWithoutBuiltinId() = runTest {
        val templates = File(pathManager.baseDir, "templates/android")
        val imported = File(templates, "user-xposed")
        File(templates, "xposed").copyRecursively(imported)
        val manifest = imported.resolve("template.json")
        manifest.writeText(
            manifest.readText()
                .replace("builtin.android-xposed", "user.android-xposed")
                .replace("\"name\": \"Xposed\"", "\"name\": \"User Xposed\""),
        )

        val result = manager.createProject(
            name = "imported-xposed-template",
            templateId = "user.android-xposed",
            packageName = "com.example.importedxposed",
            templateVariables = mapOf(
                "appName" to "Imported Xposed",
                "targetPackage" to "com.example.target",
                "moduleDescription" to "Imported module",
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals(ProjectType.ANDROID, result.getOrNull()?.projectType)
        val project = File(workspaceDir, "imported-xposed-template")
        assertTrue(project.resolve("app/src/main/java/com/example/importedxposed/MainHook.kt").isFile)
        assertTrue(
            project.resolve("app/src/main/java/com/example/importedxposed/MainHook.kt")
                .readText().contains("Imported Xposed loaded"),
        )
        assertEquals(
            "com.example.importedxposed.MainHook",
            project.resolve("app/src/main/assets/xposed_init").readText().trim(),
        )
    }

    @Test
    fun manifestProjectTypeIsPersistedWithoutStructureGuessing() = runTest {
        val template = File(pathManager.baseDir, "templates/android/user-type-only").apply { mkdirs() }
        template.resolve("template.json").writeText(
            """
            {
              "schemaVersion": 1,
              "id": "user.type-only",
              "name": "Type Only",
              "version": "1.0.0",
              "projectType": "ANDROID",
              "variables": [],
              "validation": { "requiredFiles": ["README.md"] }
            }
            """.trimIndent(),
        )
        template.resolve("README.md.template").writeText("# {{projectName}}")

        val result = manager.createProject(name = "type-only", templateId = "user.type-only")

        assertTrue(result.isSuccess)
        assertEquals(ProjectType.ANDROID, result.getOrNull()?.projectType)
        assertTrue(File(workspaceDir, "type-only/.wanxiang-project.properties").readText().contains("source=TEMPLATE"))
    }

    @Test
    fun promptedTemplateVariableIsValidatedAndExpanded() = runTest {
        val template = File(pathManager.baseDir, "templates/android/jetpack-compose")
        val manifest = template.resolve("template.json")
        manifest.writeText(
            manifest.readText().replace(
                "\"variables\": [",
                "\"variables\": [{\"name\":\"screenTitle\",\"label\":\"Screen title\",\"prompt\":true},",
            ),
        )
        val launcherTemplate = template.resolve("app/src/main/java/WANXIANG_PACKAGE_PATH/MainActivity.kt.template")
        launcherTemplate.appendText("\n// {{screenTitle}}\n")

        val missing = manager.createProject(
            name = "missing-variable",
            templateId = "builtin.android-compose",
            packageName = "com.example.missingvariable",
        )
        assertFalse(missing.isSuccess)

        val result = manager.createProject(
            name = "custom-variable",
            templateId = "builtin.android-compose",
            packageName = "com.example.customvariable",
            templateVariables = mapOf("screenTitle" to "Dashboard"),
        )
        assertTrue(result.isSuccess)
        val launcher = File(
            workspaceDir,
            "custom-variable/app/src/main/java/com/example/customvariable/MainActivity.kt",
        )
        assertTrue(launcher.readText().contains("// Dashboard"))
    }

    @Test
    fun projectArchiveImportFlattensSingleWrapperDirectory() {
        val archive = zipOf(
            "repository-main/settings.gradle.kts" to "rootProject.name = \"Imported\"",
            "repository-main/app/src/main.txt" to "hello",
        )
        val target = File(workspaceDir, "archive-target").apply { mkdirs() }

        manager.extractProjectArchive(ByteArrayInputStream(archive), "project.zip", target)

        assertEquals("rootProject.name = \"Imported\"", target.resolve("settings.gradle.kts").readText())
        assertEquals("hello", target.resolve("app/src/main.txt").readText())
        assertFalse(target.resolve("repository-main").exists())
    }

    @Test
    fun projectArchiveImportRejectsPathTraversal() {
        val archive = zipOf("../escaped.txt" to "unsafe")
        val target = File(workspaceDir, "safe-archive-target").apply { mkdirs() }

        val result = runCatching {
            manager.extractProjectArchive(ByteArrayInputStream(archive), "project.zip", target)
        }

        assertTrue(result.isFailure)
        assertFalse(File(workspaceDir, "escaped.txt").exists())
        assertTrue(target.listFiles().orEmpty().isEmpty())
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun copyProjectTemplates(baseDir: File) {
        val source = File("../project-template/src/main/assets/templates").takeIf(File::isDirectory)
            ?: File("project-template/src/main/assets/templates").takeIf(File::isDirectory)
            ?: error("Test project templates not found")
        source.copyRecursively(File(baseDir, "templates"), overwrite = true)
    }

    private class TestContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = baseDir
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo().apply {
            nativeLibraryDir = File(baseDir, "lib").apply { mkdirs() }.absolutePath
        }
    }

    private class FakeWorkspaceDao : WorkspaceRepository {
        private val list = mutableListOf<WorkspaceEntity>()
        private val flow = MutableStateFlow<List<WorkspaceEntity>>(emptyList())

        override fun observeAll(): Flow<List<WorkspaceEntity>> = flow

        override suspend fun listAll(): List<WorkspaceEntity> = list.toList()

        override suspend fun findByName(name: String): WorkspaceEntity? = list.firstOrNull { it.name == name }

        override suspend fun upsert(workspace: WorkspaceEntity) {
            list.removeAll { it.name == workspace.name }
            list.add(workspace)
            flow.value = list.toList()
        }

        override suspend fun delete(name: String) {
            list.removeAll { it.name == name }
            flow.value = list.toList()
        }
    }
}
