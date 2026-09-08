package top.wanxiang.app.harness.workflow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.model.workflow.NodeExecutionOutput
import top.wanxiang.app.core.model.workflow.NodeRunStatus
import top.wanxiang.app.core.model.workflow.WorkflowNode
import top.wanxiang.app.core.model.workflow.WorkflowNodeType
import top.wanxiang.app.core.model.workflow.WorkflowRuntimeContext

class AgentNodeExecutorTest {
    @Test
    fun `inference interpolates workflow values and exposes structured output`() = runBlocking {
        val port = RecordingAgentPort(WorkflowAgentResult(true, "结论", 3))
        val executor = AgentNodeExecutor(port)
        val progress = mutableListOf<Pair<NodeRunStatus, String>>()
        val output = executor.execute(
            node = WorkflowNode(
                id = "agent",
                type = WorkflowNodeType.AGENT_INFERENCE,
                title = "分析",
                config = mapOf("prompt" to "目录=${'$'}{WORKSPACE_PATH}; 上游=${'$'}{build.output}; 分支=${'$'}{BRANCH}"),
            ),
            context = context(),
            onProgress = { status, message -> progress += status to message },
        )

        assertEquals("目录=/workspace/demo; 上游=构建成功; 分支=main", port.inferenceRequest?.prompt)
        assertEquals(NodeRunStatus.SUCCESS, output.status)
        assertEquals("结论", output.variables["AGENT_OUTPUT"])
        assertEquals("3", output.variables["AGENT_TOOL_CALL_COUNT"])
        assertEquals(NodeRunStatus.STREAMING, progress.single().first)
    }

    @Test
    fun `delegate maps routing and write scope config`() = runBlocking {
        val port = RecordingAgentPort(WorkflowAgentResult(true, "协作完成"))
        val output = AgentNodeExecutor(port).execute(
            node = WorkflowNode(
                id = "delegate",
                type = WorkflowNodeType.SUBAGENT_DELEGATE,
                title = "审查",
                config = mapOf(
                    "prompt" to "审查 ${'$'}{previous.output}",
                    "department" to "engineering",
                    "agentQuery" to "android security",
                    "writePaths" to "app/src, docs",
                ),
            ),
            context = context(),
            onProgress = { _, _ -> },
        )

        val request = requireNotNull(port.delegateRequest)
        assertEquals("审查 构建成功", request.prompt)
        assertEquals("engineering", request.department)
        assertEquals(listOf("app/src", "docs"), request.writePaths)
        assertEquals(NodeRunStatus.SUCCESS, output.status)
        assertEquals("协作完成", output.variables["SUBAGENT_OUTPUT"])
    }

    @Test
    fun `empty prompt fails before starting harness execution`() = runBlocking {
        val port = RecordingAgentPort(WorkflowAgentResult(true, "unused"))
        val output = AgentNodeExecutor(port).execute(
            WorkflowNode("agent", WorkflowNodeType.AGENT_INFERENCE, "空任务"),
            context().copy(nodeOutputs = emptyMap()),
        ) { _, _ -> }

        assertEquals(NodeRunStatus.FAILED, output.status)
        assertTrue(output.error.orEmpty().contains("缺少 prompt"))
        assertEquals(null, port.inferenceRequest)
    }

    private fun context() = WorkflowRuntimeContext(
        executionId = "wf-test",
        workflowId = "workflow-test",
        workspacePath = "/workspace/demo",
        globalVariables = mapOf("BRANCH" to "main"),
        nodeOutputs = mapOf("build" to NodeExecutionOutput(NodeRunStatus.SUCCESS, textOutput = "构建成功")),
    )
}

private class RecordingAgentPort(
    private val result: WorkflowAgentResult,
) : WorkflowAgentExecutionPort {
    var inferenceRequest: WorkflowAgentRequest? = null
    var delegateRequest: WorkflowAgentRequest? = null

    override suspend fun infer(request: WorkflowAgentRequest): WorkflowAgentResult {
        inferenceRequest = request
        return result
    }

    override suspend fun delegate(request: WorkflowAgentRequest): WorkflowAgentResult {
        delegateRequest = request
        return result
    }
}
