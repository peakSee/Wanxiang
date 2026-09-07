package top.wanxiang.app.harness.dual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用多模型兼容性测试：
 * 验证对所有模型（OpenAI GPT-4o/o1/o3, Anthropic Claude 3.5/3.7, Google Gemini 2.5,
 * DeepSeek-R1/V3, 阿里 Qwen 2.5, 智谱 GLM, 本地 Ollama 等）
 * 输出的格式宽容性、多语言兜底与前缀稳定性。
 */
class DualAgentCoordinatorTest {
    private val promptBuilder = PlannerPromptBuilder()

    @Test
    fun `parses standard markdown json block from any model`() {
        val responseText = """
            分析了当前需求，我们需要先定位项目入口。
            ```json
            {
              "thought": "首先检查入口文件",
              "action": "EXECUTE_STEP",
              "step": {
                "id": "step_1",
                "title": "检查入口类",
                "instruction": "读取 src/Main.kt 文件",
                "expectedOutcome": "确认包名与入口函数"
              }
            }
            ```
        """.trimIndent()

        val decision = PlannerProtocolParser.parse(responseText, emptyList())
        assertTrue(decision is PlannerDecision.ExecuteStep)
        val step = (decision as PlannerDecision.ExecuteStep).step
        assertEquals("step_1", step.id)
        assertEquals("检查入口类", step.title)
        assertEquals("读取 src/Main.kt 文件", step.instruction)
        assertEquals("确认包名与入口函数", step.expectedOutcome)
    }

    @Test
    fun `parses raw unquoted json block without markdown code fences`() {
        val responseText = """
            {
              "thought": "任务已达到要求",
              "action": "FINISH",
              "finalReport": "所有功能已成功实现并验证通过。"
            }
        """.trimIndent()

        val decision = PlannerProtocolParser.parse(responseText, emptyList())
        assertTrue(decision is PlannerDecision.Finish)
        assertEquals("所有功能已成功实现并验证通过。", (decision as PlannerDecision.Finish).finalReport)
    }

    @Test
    fun `parses replan decision with updated plan`() {
        val responseText = """
            ```json
            {
              "thought": "发现现有测试未覆盖边缘情况，需要调整方案",
              "action": "REPLAN"
            }
            ```
        """.trimIndent()

        val decision = PlannerProtocolParser.parse(responseText, emptyList())
        assertTrue(decision is PlannerDecision.Replan)
        assertEquals("发现现有测试未覆盖边缘情况，需要调整方案", (decision as PlannerDecision.Replan).reason)
    }

    @Test
    fun `falls back gracefully on multilingual natural language completion signals`() {
        // 中文模型自然语言完成
        val zhText = "经过综合评估，所有修改已经全部完成，测试通过。"
        val zhDecision = PlannerProtocolParser.parse(zhText, emptyList())
        assertTrue("中文完成信号应被正确识别", zhDecision is PlannerDecision.Finish)

        // 英文模型（Claude / GPT / Gemini）自然语言完成
        val enText = "All tasks are completed and verified successfully."
        val enDecision = PlannerProtocolParser.parse(enText, emptyList())
        assertTrue("英文完成信号应被正确识别", enDecision is PlannerDecision.Finish)
    }

    @Test
    fun `parses multi-step DAG plan array with dependencies from INIT_PLAN`() {
        val responseText = """
            ```json
            {
              "thought": "将任务拆解为3个子工序，其中前两项无依赖可并发执行",
              "action": "INIT_PLAN",
              "plan": [
                {
                  "id": "step_1",
                  "title": "检查文件结构",
                  "instruction": "ls -la",
                  "expectedOutcome": "列出文件",
                  "dependencies": []
                },
                {
                  "id": "step_2",
                  "title": "检查依赖配置",
                  "instruction": "cat build.gradle",
                  "expectedOutcome": "读取依赖",
                  "dependencies": []
                },
                {
                  "id": "step_3",
                  "title": "综合构建验证",
                  "instruction": "./gradlew build",
                  "expectedOutcome": "构建通过",
                  "dependencies": ["step_1", "step_2"]
                }
              ]
            }
            ```
        """.trimIndent()

        val decision = PlannerProtocolParser.parse(responseText, emptyList())
        assertTrue(decision is PlannerDecision.InitializePlan)
        val init = decision as PlannerDecision.InitializePlan
        assertEquals(3, init.plan.size)

        val step1 = init.plan[0]
        val step2 = init.plan[1]
        val step3 = init.plan[2]

        // 验证就绪状态判断（DAG 就绪拓扑）
        assertTrue(step1.isReady(emptySet()))
        assertTrue(step2.isReady(emptySet()))
        // step_3 依赖 step_1 与 step_2，未完成前不应就绪
        assertTrue(!step3.isReady(emptySet()))
        assertTrue(!step3.isReady(setOf("step_1")))
        // 当前置依赖全部完成时，step_3 就绪
        assertTrue(step3.isReady(setOf("step_1", "step_2")))
    }

    @Test
    fun `parses replan decision with new plan array`() {
        val responseText = """
            ```json
            {
              "thought": "前置步骤发现旧方案不可行，重新编排后续两步工序",
              "action": "REPLAN",
              "plan": [
                {
                  "id": "step_alt_1",
                  "title": "使用备用方案重构",
                  "instruction": "refactor alternative",
                  "dependencies": []
                }
              ]
            }
            ```
        """.trimIndent()

        val decision = PlannerProtocolParser.parse(responseText, emptyList())
        assertTrue(decision is PlannerDecision.Replan)
        val replan = decision as PlannerDecision.Replan
        assertEquals("前置步骤发现旧方案不可行，重新编排后续两步工序", replan.reason)
        assertEquals(1, replan.newSteps.size)
        assertEquals("step_alt_1", replan.newSteps[0].id)
    }

    @Test
    fun `planner prompt builder is completely model agnostic and cache friendly`() {
        val prompt = promptBuilder.buildSystemPrompt("/workspace/my_project", "Android Kotlin")
        // 1. 不包含任何特定厂商专有名称（保证对 OpenAI, Claude, Gemini, DeepSeek, Qwen 等完全中立）
        assertTrue(!prompt.contains("DeepSeek"))
        assertTrue(!prompt.contains("OpenAI"))
        assertTrue(!prompt.contains("Claude"))
        // 2. 包含通用工作区定义与核心角色规范
        assertTrue(prompt.contains("/workspace/my_project"))
        assertTrue(prompt.contains("Android Kotlin"))
        assertTrue(prompt.contains("Planner Agent"))
        assertTrue(prompt.contains("dependencies"))
    }
}
