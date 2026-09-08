package top.wkbin.taixu.harness.dual

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 模型无关的 Planner 决策协议解析器（Model-Agnostic Protocol Parser）。
 *
 * 核心目标：
 * 太墟支持任何 LLM（OpenAI, Anthropic Claude, Google Gemini, DeepSeek, 阿里通义千问, 智谱 GLM, 本地 Ollama 等）。
 * 本解析器专为多模型输出设计，具备高度容错：
 * 1. 优先提取 Markdown 代码块 ```json ... ```；
 * 2. 兜底提取前后带有杂质文本的裸 JSON 对象 `{ ... }`；
 * 3. 对小参数量或未严格遵循 JSON 的自然语言回复，支持中英文完成信号智能识别；
 * 4. 支持多步骤 DAG 依赖拆解（INIT_PLAN / REPLAN / EXECUTE_STEP）；
 * 5. 彻底解耦，不依赖特定厂商私有结构。
 */
object PlannerProtocolParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String, currentSteps: List<PlanStep>): PlannerDecision {
        val jsonPattern = Regex("""```(?:json)?\s*(\{.*?\})\s*```""", RegexOption.DOT_MATCHES_ALL)
        val match = jsonPattern.find(text)
        val rawJson = match?.groupValues?.get(1) ?: text.substringAfter("{", "").let {
            if (it.isNotBlank()) "{" + it.substringBeforeLast("}") + "}" else ""
        }

        val parsed = runCatching {
            json.parseToJsonElement(rawJson).jsonObject
        }.getOrNull()

        if (parsed == null) {
            val lower = text.lowercase()
            val isCompleted = listOf("已完成", "全部完成", "实现完毕", "任务完成", "completed", "all done", "finished", "all tasks are completed")
                .any { it in lower || it in text }
            return if (isCompleted) {
                PlannerDecision.Finish(finalReport = text, completedSteps = currentSteps)
            } else {
                val step = PlanStep(
                    id = "step_${currentSteps.size + 1}",
                    title = "执行下一步",
                    instruction = text.take(500),
                )
                PlannerDecision.ExecuteStep(step = step, updatedPlan = currentSteps + step)
            }
        }

        val thought = parsed["thought"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val action = parsed["action"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: run {
            if (parsed["plan"] != null || parsed["steps"] != null) "INIT_PLAN" else "EXECUTE_STEP"
        }

        return when (action) {
            "FINISH" -> {
                val report = parsed["finalReport"]?.jsonPrimitive?.contentOrNull ?: text
                PlannerDecision.Finish(finalReport = report, completedSteps = currentSteps)
            }
            "REPLAN" -> {
                val reason = if (thought.isNotBlank()) thought else "规划方案微调"
                val planList = parsePlanArray(parsed) ?: currentSteps
                PlannerDecision.Replan(reason = reason, newSteps = planList)
            }
            "INIT_PLAN" -> {
                val planList = parsePlanArray(parsed)
                if (!planList.isNullOrEmpty()) {
                    PlannerDecision.InitializePlan(thought = thought, plan = planList)
                } else {
                    val stepObj = parsed["step"] as? JsonObject
                    if (stepObj != null) {
                        val step = parseStepObject(stepObj, defaultId = "step_1", defaultInstruction = thought)
                        PlannerDecision.ExecuteStep(step = step, updatedPlan = currentSteps + step)
                    } else {
                        PlannerDecision.InitializePlan(thought = thought, plan = currentSteps)
                    }
                }
            }
            else -> {
                val planList = parsePlanArray(parsed)
                if (!planList.isNullOrEmpty() && currentSteps.isEmpty()) {
                    PlannerDecision.InitializePlan(thought = thought, plan = planList)
                } else {
                    val stepObj = parsed["step"] as? JsonObject
                    val step = if (stepObj != null) {
                        parseStepObject(stepObj, defaultId = "step_${currentSteps.size + 1}", defaultInstruction = thought.ifBlank { text })
                    } else {
                        PlanStep(
                            id = "step_${currentSteps.size + 1}",
                            title = "工序 step_${currentSteps.size + 1}",
                            instruction = thought.ifBlank { text },
                        )
                    }
                    PlannerDecision.ExecuteStep(step = step, updatedPlan = currentSteps + step)
                }
            }
        }
    }

    private fun parsePlanArray(parsed: JsonObject): List<PlanStep>? {
        val array = (parsed["plan"] as? JsonArray) ?: (parsed["steps"] as? JsonArray) ?: return null
        return array.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            parseStepObject(obj, defaultId = "step_${index + 1}", defaultInstruction = "")
        }
    }

    private fun parseStepObject(stepObj: JsonObject, defaultId: String, defaultInstruction: String): PlanStep {
        val stepId = stepObj["id"]?.jsonPrimitive?.contentOrNull ?: defaultId
        val title = stepObj["title"]?.jsonPrimitive?.contentOrNull ?: "工序 $stepId"
        val instruction = stepObj["instruction"]?.jsonPrimitive?.contentOrNull ?: defaultInstruction
        val expected = stepObj["expectedOutcome"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val dependencies = runCatching {
            stepObj["dependencies"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrNull().orEmpty()

        return PlanStep(
            id = stepId,
            title = title,
            instruction = instruction,
            expectedOutcome = expected,
            dependencies = dependencies,
        )
    }
}
