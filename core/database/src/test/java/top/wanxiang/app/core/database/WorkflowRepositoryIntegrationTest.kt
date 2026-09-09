package top.wanxiang.app.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wanxiang.app.core.model.workflow.BuiltinWorkflows
import top.wanxiang.app.core.model.workflow.WorkflowRunStatus
import top.wanxiang.app.core.model.workflow.WorkflowRuntimeState
import top.wanxiang.app.core.model.workflow.WorkflowDefinition
import top.wanxiang.app.core.model.workflow.WorkflowNode
import top.wanxiang.app.core.model.workflow.WorkflowNodeType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkflowRepositoryIntegrationTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: WorkflowRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomWorkflowRepository(
            database.workflowDao(),
            Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false },
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `seeds builtins and resolves slash command`() = runBlocking {
        repository.ensureBuiltins()
        val definitions = repository.observeDefinitions().first()
        assertEquals(BuiltinWorkflows.all.size, definitions.size)
        assertEquals("release_apk_direct_install", repository.findBySlashCommand("/wf release_apk_direct_install")?.id)
    }

    @Test
    fun `execution persistence ensures workflow foreign key parent`() = runBlocking {
        val definition = BuiltinWorkflows.buildDoctor
        val initial = WorkflowRuntimeState.initial("execution-1", definition).copy(
            status = WorkflowRunStatus.SUCCESS,
            startedAt = 10L,
            finishedAt = 20L,
        )
        repository.saveExecution(initial)
        assertNotNull(repository.findById(definition.id))
        val logs = repository.observeRecentExecutions().first()
        assertTrue(logs.any { it.executionId == "execution-1" && it.status == "SUCCESS" })
    }

    @Test
    fun `editing and reseeding preserve execution history`() = runBlocking {
        val definition = BuiltinWorkflows.buildDoctor
        repository.saveExecution(WorkflowRuntimeState.initial("preserved", definition).copy(status = WorkflowRunStatus.SUCCESS))
        repository.ensureBuiltins()
        repository.upsert(definition.copy(name = "new name"))
        assertEquals("preserved", repository.observeHistory().first().single().executionId)
    }

    @Test
    fun `saving old execution never overwrites edited definition`() = runBlocking {
        val definition = BuiltinWorkflows.buildDoctor.copy(id = "custom", isBuiltin = false)
        repository.upsert(definition.copy(name = "new name"))
        repository.saveExecution(WorkflowRuntimeState.initial("old-run", definition).copy(status = WorkflowRunStatus.SUCCESS))
        assertEquals("new name", repository.findById(definition.id)?.name)
        assertEquals(definition.name, repository.observeHistory().first().single().definition.name)
    }

    @Test
    fun `custom workflow can be saved updated and deleted`() = runBlocking {
        val original = WorkflowDefinition(
            id = "custom-editor-test",
            name = "编辑器测试",
            nodes = listOf(WorkflowNode("start", WorkflowNodeType.TRIGGER, "开始")),
        )
        repository.upsert(original)
        repository.upsert(original.copy(name = "编辑器测试 2"))

        assertEquals("编辑器测试 2", repository.findById(original.id)?.name)
        assertTrue(repository.deleteCustom(original.id))
        assertEquals(null, repository.findById(original.id))
    }
}
