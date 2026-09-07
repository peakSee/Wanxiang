package top.wanxiang.app.harness

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.database.BuildScriptEntity
import top.wanxiang.app.core.database.BuildScriptRepository
import top.wanxiang.app.core.database.ProjectBuildScriptBindingEntity

class FakeBuildScriptRepository : BuildScriptRepository {
    private val scripts = mutableMapOf<String, BuildScriptEntity>()
    private val bindings = mutableMapOf<String, ProjectBuildScriptBindingEntity>()

    override fun observeScripts(): Flow<List<BuildScriptEntity>> = MutableStateFlow(scripts.values.toList())
    override fun observeBindings(): Flow<List<ProjectBuildScriptBindingEntity>> = MutableStateFlow(bindings.values.toList())
    override suspend fun listScripts(): List<BuildScriptEntity> = scripts.values.toList()
    override suspend fun findScript(id: String): BuildScriptEntity? = scripts[id]
    override suspend fun findBinding(projectName: String): ProjectBuildScriptBindingEntity? = bindings[projectName]
    override suspend fun resolvedScript(projectName: String): BuildScriptEntity? =
        bindings[projectName]?.let { scripts[it.scriptId] }

    override suspend fun upsertScript(script: BuildScriptEntity) {
        scripts[script.id] = script
    }

    override suspend fun deleteScript(id: String): Boolean {
        val s = scripts[id] ?: return false
        if (s.isBuiltin) return false
        scripts.remove(id)
        bindings.entries.removeAll { it.value.scriptId == id }
        return true
    }

    override suspend fun bind(projectName: String, scriptId: String) {
        requireNotNull(scripts[scriptId]) { "构建脚本不存在：$scriptId" }
        bindings[projectName] = ProjectBuildScriptBindingEntity(projectName, scriptId, System.currentTimeMillis())
    }

    override suspend fun unbind(projectName: String) {
        bindings.remove(projectName)
    }

    override suspend fun ensureBuiltinScripts(androidScript: String, flutterScript: String) {
        if ("builtin-android" !in scripts) {
            scripts["builtin-android"] = BuildScriptEntity("builtin-android", "标准 Android", "", "ANDROID", "#!/bin/sh\n", true, 0L, 0L)
        }
        if ("builtin-flutter" !in scripts) {
            scripts["builtin-flutter"] = BuildScriptEntity("builtin-flutter", "标准 Flutter", "", "FLUTTER", "#!/bin/sh\n", true, 0L, 0L)
        }
    }
}

class BuildScriptToolExecutorTest {
    private val repository = FakeBuildScriptRepository()
    private val executor = BuildScriptToolExecutor(repository)

    @Test
    fun `create, get, list, bind and unbind workflow`() = runBlocking {
        val createArgs = buildJsonObject {
            put("action", "create")
            put("name", "Custom Android")
            put("project_type", "android")
            put("content", "#!/bin/sh\n./gradlew assembleDebug\n")
        }
        val (createOk, createMsg) = executor.execute(createArgs, "/workspace/demo-app")
        assertTrue(createOk)
        assertTrue(createMsg.contains("已创建构建脚本"))

        val scripts = repository.listScripts()
        val custom = scripts.single { it.name == "Custom Android" }
        assertEquals("ANDROID", custom.projectType)

        // bind to workspace
        val bindArgs = buildJsonObject {
            put("action", "bind")
            put("id", custom.id)
        }
        val (bindOk, bindMsg) = executor.execute(bindArgs, "/workspace/demo-app/")
        assertTrue(bindOk)
        assertEquals(custom.id, repository.findBinding("demo-app")?.scriptId)

        // unbind
        val unbindArgs = buildJsonObject {
            put("action", "unbind")
        }
        val (unbindOk, _) = executor.execute(unbindArgs, "/workspace/demo-app")
        assertTrue(unbindOk)
        assertEquals(null, repository.findBinding("demo-app"))
    }

    @Test
    fun `validation rejects invalid script without shell header or empty name`() = runBlocking {
        val badContentArgs = buildJsonObject {
            put("action", "create")
            put("name", "Bad")
            put("project_type", "android")
            put("content", "echo hello")
        }
        val (ok1, msg1) = executor.execute(badContentArgs, "/workspace/demo")
        assertFalse(ok1)
        assertTrue(msg1.contains("首行必须声明 shell"))

        val emptyNameArgs = buildJsonObject {
            put("action", "create")
            put("name", "")
            put("project_type", "android")
            put("content", "#!/bin/sh\necho hello")
        }
        val (ok2, msg2) = executor.execute(emptyNameArgs, "/workspace/demo")
        assertFalse(ok2)
        assertTrue(msg2.contains("name 不能为空"))
    }
}
