package top.wanxiang.app.harness.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.model.workflow.BuiltinWorkflows
import top.wanxiang.app.core.model.workflow.WorkflowDefinition
import top.wanxiang.app.core.model.workflow.WorkflowTrigger
import top.wanxiang.app.core.model.workflow.WorkflowValidator

class ProactiveWorkflowAdvisorTest {
    @Test
    fun `all builtins remain valid after adding proactive presets`() {
        BuiltinWorkflows.all.forEach { workflow ->
            assertTrue("${workflow.id}: ${WorkflowValidator.validate(workflow)}", WorkflowValidator.validate(workflow).isEmpty())
        }
    }

    @Test
    fun `build failure matches trigger and carries stable context`() {
        val signal = WorkflowSignal.BuildFailed("demo", "/workspace/demo", "Gradle dependency resolution failed", 42L)
        val matching = WorkflowDefinition(
            id = "doctor",
            name = "诊断",
            trigger = WorkflowTrigger.Proactive("BUILD_FAILED", "dependency", "修复构建"),
        )

        val suggestion = suggestionsFor(signal, listOf(matching)).single()

        assertEquals("doctor", suggestion.workflowId)
        assertEquals("demo", suggestion.projectName)
        assertEquals("Gradle dependency resolution failed", suggestion.initialVariables["BUILD_ERROR"])
        assertEquals("/workspace/demo", suggestion.initialVariables["TARGET_WORKSPACE"])
        assertEquals(42L, suggestion.createdAt)
    }

    @Test
    fun `invalid condition and unrelated event do not recommend workflows`() {
        val signal = WorkflowSignal.ApkGenerated("demo", "/workspace/demo", "/tmp/app.apk")
        val definitions = listOf(
            WorkflowDefinition("wrong-event", "A", trigger = WorkflowTrigger.Proactive("BUILD_FAILED", suggestionLabel = "A")),
            WorkflowDefinition("bad-regex", "B", trigger = WorkflowTrigger.Proactive("APK_GENERATED", "[", "B")),
        )

        assertTrue(suggestionsFor(signal, definitions).isEmpty())
    }
}
