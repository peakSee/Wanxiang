package top.wanxiang.app.harness.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.model.workflow.BuiltinWorkflows
import top.wanxiang.app.core.model.workflow.NodeExecutionOutput
import top.wanxiang.app.core.model.workflow.NodeRunStatus
import top.wanxiang.app.core.model.workflow.WorkflowConditionEvaluator
import top.wanxiang.app.core.model.workflow.WorkflowRuntimeContext
import top.wanxiang.app.core.model.workflow.WorkflowValidationIssue
import top.wanxiang.app.core.model.workflow.WorkflowValidator

class WorkflowConditionEvaluatorTest {
    @Test
    fun evaluatesExitCodeAndVariables() {
        val upstream = NodeExecutionOutput(NodeRunStatus.SUCCESS, exitCode = 0, textOutput = "hello world")
        val context = WorkflowRuntimeContext(
            executionId = "e",
            workflowId = "w",
            workspacePath = "/ws",
            globalVariables = mapOf("HOST_PRIVILEGED" to "true", "COUNT" to "3"),
            nodeOutputs = mapOf("up" to upstream),
            upstreamNodeIds = listOf("up"),
        )
        assertTrue(WorkflowConditionEvaluator.evaluate("exitCode == 0", context, upstream).matched)
        assertFalse(WorkflowConditionEvaluator.evaluate("exitCode != 0", context, upstream).matched)
        assertTrue(WorkflowConditionEvaluator.evaluate("output contains hello", context, upstream).matched)
        assertTrue(WorkflowConditionEvaluator.evaluate("\${HOST_PRIVILEGED} == true", context, upstream).matched)
        assertTrue(WorkflowConditionEvaluator.evaluate("\${COUNT} >= 2 && output contains world", context, upstream).matched)
        assertTrue(WorkflowConditionEvaluator.evaluate("\${MISSING} == x || exitCode == 0", context, upstream).matched)
        assertFalse(WorkflowConditionEvaluator.evaluate("empty HOST_PRIVILEGED", context, upstream).matched)
        assertTrue(WorkflowConditionEvaluator.evaluate("notEmpty HOST_PRIVILEGED", context, upstream).matched)
    }

    @Test
    fun defaultExpressionUsesUpstreamExitCode() {
        val failed = NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 7)
        val context = WorkflowRuntimeContext("e", "w", "/ws")
        assertFalse(WorkflowConditionEvaluator.evaluate(null, context, failed).matched)
        assertTrue(WorkflowConditionEvaluator.evaluate("", context, NodeExecutionOutput(NodeRunStatus.SUCCESS)).matched)
    }

    @Test
    fun hostAutomationBuiltinsValidate() {
        listOf("host_automation_lab", "host_broadcast_and_settings").forEach { id ->
            val workflow = BuiltinWorkflows.find(id) ?: error("missing $id")
            assertEquals(emptyList<WorkflowValidationIssue>(), WorkflowValidator.validate(workflow))
        }
    }
}
