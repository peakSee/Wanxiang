package top.wanxiang.app.harness.workflow

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wanxiang.app.core.model.ExecutionMode
import top.wanxiang.app.core.model.workflow.NodeExecutionOutput
import top.wanxiang.app.core.model.workflow.NodeRunStatus
import top.wanxiang.app.harness.ApiMessage
import top.wanxiang.app.harness.ProviderClient
import top.wanxiang.app.runtime.gui.HostGuiController
import top.wanxiang.app.runtime.gui.ScreenObservation
import top.wanxiang.app.runtime.gui.ScrollDirection
import top.wanxiang.app.runtime.gui.WorkflowGuiHudBridge
import top.wanxiang.app.runtime.privilege.PrivilegeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Workflow-native GUI loop: observe → LLM returns one JSON action → execute.
 * Avoids SubagentLaneRunner (MCP discovery + approval dead-ends) so progress shows in the workflow UI.
 */
@Singleton
class WorkflowGuiPilot @Inject constructor(
    private val gui: HostGuiController,
    private val privilegeManager: PrivilegeManager,
    private val providerClient: ProviderClient,
    private val hud: WorkflowGuiHudBridge,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun run(
        goal: String,
        targetPackage: String?,
        modelId: String?,
        modelVariant: String?,
        maxSteps: Int = 18,
        onProgress: suspend (String) -> Unit,
    ): NodeExecutionOutput {
        val info = privilegeManager.getPrivilegeInfo()
        if (info.mode == ExecutionMode.PROOT || !info.modeActive) {
            return NodeExecutionOutput(
                status = NodeRunStatus.FAILED,
                exitCode = 13,
                error = "GUI 试飞需要 Shizuku 或 Root 已授权",
            )
        }
        val trimmedGoal = goal.trim()
        if (trimmedGoal.isBlank()) {
            return NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 2, error = "GUI_GOAL 为空")
        }

        val log = StringBuilder()
        fun appendLog(line: String) {
            log.appendLine(line)
            Log.i(TAG, line)
        }

        appendLog("目标：$trimmedGoal")
        if (!targetPackage.isNullOrBlank()) {
            onProgress("打开 $targetPackage")
            gui.launchApp(targetPackage).onFailure {
                appendLog("启动失败：${it.message}")
            }.onSuccess {
                appendLog(it)
            }
            gui.waitForForeground(targetPackage, 15_000).onSuccess { appendLog(it) }
            delay(1_200)
        }

        val model = providerClient.resolveConfigured(modelId, modelVariant)
            .copy(pureChatMode = true, dynamicMcpTools = emptyList())
        appendLog("模型：${model.name} / ${model.model}（纯决策 JSON，不走工具协议）")

        var doneReason: String? = null
        var failedReason: String? = null
        var steps = 0

        for (step in 1..maxSteps.coerceIn(1, 40)) {
            coroutineContext.ensureActive()
            if (hud.isStopRequested()) {
                throw CancellationException("用户停止 GUI 试飞")
            }
            steps = step
            onProgress("第 $step 步：感知屏幕…")
            hud.thinking("第 $step 步：感知屏幕…")
            val observation = gui.observeScreen(onlyInteractive = true).getOrElse { err ->
                appendLog("感知失败：${err.message}")
                failedReason = "screen_observe 失败：${err.message}"
                break
            }
            appendLog("前台=${observation.packageName} 节点=${observation.nodes.size}")
            onProgress("第 $step 步：请模型决策…")
            hud.thinking("第 $step 步：模型决策中…", "节点 ${observation.nodes.size} 个")

            val decisionText = runCatching {
                providerClient.chat(
                    model,
                    listOf(
                        ApiMessage(role = "system", content = SYSTEM_PROMPT),
                        ApiMessage(
                            role = "user",
                            content = buildUserPrompt(trimmedGoal, targetPackage, observation, step, maxSteps),
                        ),
                    ),
                ).content.orEmpty()
            }.getOrElse { err ->
                appendLog("模型调用失败：${err.message}")
                failedReason = "模型调用失败：${err.message}"
                break
            }

            if (hud.isStopRequested()) {
                throw CancellationException("用户停止 GUI 试飞")
            }

            val action = parseAction(decisionText)
            if (action == null) {
                appendLog("无法解析决策：${decisionText.take(400)}")
                onProgress("第 $step 步：决策解析失败，重试")
                hud.thinking("第 $step 步：决策解析失败，重试")
                delay(600)
                continue
            }

            val kind = action.string("action")?.lowercase().orEmpty()
            val reason = action.string("reason").orEmpty()
            appendLog("决策=$kind reason=$reason")
            onProgress("第 $step 步：执行 $kind")
            hud.thinking("第 $step 步：执行 $kind", reason)

            when (kind) {
                "done" -> {
                    doneReason = reason.ifBlank { "模型宣布完成" }
                    break
                }
                "fail" -> {
                    failedReason = reason.ifBlank { "模型宣布失败" }
                    break
                }
                "click_text" -> {
                    val needle = action.string("text").orEmpty()
                    if (needle.isBlank()) {
                        appendLog("click_text 缺 text")
                        continue
                    }
                    // 禁止把「待发送文案」当成屏幕按钮去点
                    if (needle.length >= 4 && trimmedGoal.contains(needle) &&
                        observation.nodes.none { it.text.contains(needle) || it.contentDesc.contains(needle) }
                    ) {
                        appendLog("「$needle」不像屏幕控件（更像待发送文案），请改用 screen_input_text")
                        continue
                    }
                    val hit = observation.nodes
                        .filter { node ->
                            listOf(node.text, node.contentDesc, node.resourceId)
                                .any { it.contains(needle, ignoreCase = true) }
                        }
                        .sortedWith(
                            compareByDescending<top.wanxiang.app.runtime.gui.GuiNode> { it.text.equals(needle, true) }
                                .thenByDescending { it.clickable }
                                .thenByDescending { it.text.length.coerceAtMost(needle.length * 2) },
                        )
                        .firstOrNull()
                    if (hit == null) {
                        appendLog("未找到文案「$needle」")
                        continue
                    }
                    // 聊天场景：标题栏「返回」容易把流程抖回去，除非目标就是返回
                    if ((hit.text == "返回" || hit.contentDesc.contains("返回")) &&
                        !trimmedGoal.contains("返回") &&
                        observation.nodes.any { it.editable || it.className.contains("EditText", true) }
                    ) {
                        appendLog("忽略「返回」（当前已有输入框，应留在聊天页）")
                        continue
                    }
                    gui.click(hit.centerX, hit.centerY).fold(
                        onSuccess = { appendLog("$it ← 「$needle」") },
                        onFailure = { appendLog("点击失败：${it.message}") },
                    )
                }
                "screen_click" -> {
                    val x = action.int("x")
                    val y = action.int("y")
                    if (x == null || y == null) {
                        appendLog("screen_click 缺坐标")
                        continue
                    }
                    gui.click(x, y).fold(
                        onSuccess = { appendLog(it) },
                        onFailure = { appendLog("点击失败：${it.message}") },
                    )
                }
                "screen_swipe" -> {
                    val x1 = action.int("x1")
                    val y1 = action.int("y1")
                    val x2 = action.int("x2")
                    val y2 = action.int("y2")
                    if (listOf(x1, y1, x2, y2).any { it == null }) {
                        appendLog("screen_swipe 缺坐标")
                        continue
                    }
                    gui.swipe(x1!!, y1!!, x2!!, y2!!, action.int("duration_ms")?.toLong() ?: 300L).fold(
                        onSuccess = { appendLog(it) },
                        onFailure = { appendLog("滑动失败：${it.message}") },
                    )
                }
                "screen_input_text" -> {
                    val text = action.string("text").orEmpty()
                    if (text.isBlank()) {
                        appendLog("screen_input_text 缺 text")
                        continue
                    }
                    // 先点到可编辑框，再剪贴板粘贴（中文不能走 input text）
                    val editable = observation.nodes.firstOrNull { it.editable }
                        ?: observation.nodes.firstOrNull {
                            it.className.contains("EditText", ignoreCase = true) ||
                                it.contentDesc.contains("输入", ignoreCase = true) ||
                                it.text.contains("输入", ignoreCase = true)
                        }
                    if (editable != null) {
                        gui.click(editable.centerX, editable.centerY).onSuccess {
                            appendLog("先聚焦输入框 center=(${editable.centerX},${editable.centerY})")
                        }
                        delay(250)
                    }
                    gui.inputText(text).fold(
                        onSuccess = { appendLog(it) },
                        onFailure = { appendLog("输入失败：${it.message}") },
                    )
                }
                "screen_scroll" -> {
                    val dir = when (action.string("direction")?.lowercase()) {
                        "up" -> ScrollDirection.UP
                        "down" -> ScrollDirection.DOWN
                        "left" -> ScrollDirection.LEFT
                        "right" -> ScrollDirection.RIGHT
                        else -> {
                            appendLog("screen_scroll 缺合法 direction")
                            continue
                        }
                    }
                    gui.scroll(dir).fold(
                        onSuccess = { appendLog(it) },
                        onFailure = { appendLog("滚动失败：${it.message}") },
                    )
                }
                "screen_double_click" -> {
                    val x = action.int("x")
                    val y = action.int("y")
                    if (x == null || y == null) {
                        appendLog("screen_double_click 缺坐标")
                        continue
                    }
                    gui.doubleClick(x, y).fold(
                        onSuccess = { appendLog(it) },
                        onFailure = { appendLog("双击失败：${it.message}") },
                    )
                }
                "screen_long_press" -> {
                    val x = action.int("x")
                    val y = action.int("y")
                    if (x == null || y == null) {
                        appendLog("screen_long_press 缺坐标")
                        continue
                    }
                    gui.longPress(x, y, action.int("duration_ms")?.toLong() ?: 800L).fold(
                        onSuccess = { appendLog(it) },
                        onFailure = { appendLog("长按失败：${it.message}") },
                    )
                }
                "screen_key" -> {
                    val key = action.string("key").orEmpty()
                    if (key.isBlank()) {
                        appendLog("screen_key 缺 key")
                        continue
                    }
                    gui.sendKey(key).fold(
                        onSuccess = { appendLog(it) },
                        onFailure = { appendLog("按键失败：${it.message}") },
                    )
                }
                "app_launch" -> {
                    val pkg = action.string("package").orEmpty().ifBlank { targetPackage.orEmpty() }
                    if (pkg.isBlank()) {
                        appendLog("app_launch 缺 package")
                        continue
                    }
                    gui.launchApp(pkg).fold(
                        onSuccess = { appendLog(it) },
                        onFailure = { appendLog("启动失败：${it.message}") },
                    )
                }
                else -> appendLog("未知动作：$kind")
            }
            delay(900)
        }

        val success = failedReason == null && (doneReason != null || steps >= maxSteps)
        val summary = buildString {
            appendLine(if (doneReason != null) "✔ $doneReason" else if (failedReason != null) "✘ $failedReason" else "达到最大步数 $maxSteps")
            appendLine()
            append(log.toString().trimEnd())
        }
        onProgress(if (success && failedReason == null) "GUI 试飞结束" else "GUI 试飞未完成")
        return NodeExecutionOutput(
            status = if (failedReason == null) NodeRunStatus.SUCCESS else NodeRunStatus.FAILED,
            exitCode = if (failedReason == null) 0 else 1,
            textOutput = summary,
            error = failedReason,
            variables = mapOf(
                "GUI_PILOT_STEPS" to steps.toString(),
                "GUI_PILOT_DONE" to (doneReason != null).toString(),
            ),
        )
    }

    private fun parseAction(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull
            ?: this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun buildUserPrompt(
        goal: String,
        targetPackage: String?,
        observation: ScreenObservation,
        step: Int,
        maxSteps: Int,
    ): String = buildString {
        appendLine("## 目标")
        appendLine(goal)
        if (!targetPackage.isNullOrBlank()) appendLine("目标包名：$targetPackage")
        appendLine("进度：第 $step / $maxSteps 步")
        appendLine()
        appendLine("## 当前屏幕")
        append(observation.toAgentSummary(maxNodes = 60))
        appendLine()
        appendLine("只输出一个 JSON 对象。发中文消息：先聚焦输入框，再 screen_input_text（系统会自动剪贴板粘贴）；然后 click_text「发送」。不要点「返回」来回抖。")
    }

    private companion object {
        const val TAG = "TaiXu-GuiPilot"
        val SYSTEM_PROMPT = """
你是 Android GUI 驾驶员。根据屏幕控件树推进用户目标。
只输出一个 JSON 对象，字段：
- action: click_text | screen_click | screen_double_click | screen_long_press | screen_swipe | screen_scroll | screen_input_text | screen_key | app_launch | done | fail
- text: click_text / screen_input_text 用
- x,y / x1,y1,x2,y2,duration_ms: 坐标动作
- direction: up|down|left|right（screen_scroll）
- key: back|home|recents|enter|delete|paste
- package: app_launch
- reason: 简短中文说明

规则：
1. 优先 click_text，text 必须来自当前屏幕已有节点（群名/发送/搜索等），绝不要用待发送正文当 click_text。
2. 输入任意中文或长文本：用 screen_input_text（底层剪贴板粘贴）。列表不够时用 screen_scroll。
3. 进入聊天且已有输入框后：禁止点「返回」；应 screen_input_text → click_text「发送」。
4. 禁止付款/红包/转账；若出现此类界面输出 {"action":"fail","reason":"..."}。
5. 目标完成后 {"action":"done","reason":"..."}。
6. 不要输出 Markdown 或其它文字。
        """.trimIndent()
    }
}
