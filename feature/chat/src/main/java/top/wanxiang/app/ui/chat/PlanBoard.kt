package top.wanxiang.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import top.wanxiang.app.feature.chat.R
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName

/** 结构化任务规划的单条步骤展示模型（与文本正则提取的 TaskStepItem 解耦）。 */
data class PlanStepUi(
    val index: Int,
    val title: String,
    val isCompleted: Boolean,
)

/**
 * plan 工具写入的 stepsJson 防御性解析器。
 *
 * 模型生成的步骤结构不固定：可能是字符串数组、对象数组（title/step/name/description 等
 * 字段名不定，完成状态可能是布尔或 pending/in_progress/completed 枚举字符串）、
 * 也可能被包裹在 {"steps": [...]} 对象里。解析失败一律降级为空列表，绝不让看板崩溃。
 */
object PlanStepParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val TITLE_KEYS = listOf("title", "step", "name", "description", "desc", "task", "content", "action")
    private val STATUS_KEYS = setOf("status", "state", "completed", "isCompleted", "is_completed", "done", "finished")
    private val COMPLETED_STATUSES = setOf("completed", "complete", "done", "finished")

    fun parse(stepsJson: String?): List<PlanStepUi> {
        if (stepsJson.isNullOrBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(stepsJson) }.getOrNull() ?: return emptyList()
        return parseElement(root)
    }

    private fun parseElement(element: JsonElement): List<PlanStepUi> = when (element) {
        is JsonArray -> parseArray(element)
        is JsonObject -> element["steps"]?.let(::parseElement) ?: emptyList()
        else -> emptyList()
    }

    private fun parseArray(array: JsonArray): List<PlanStepUi> {
        val steps = mutableListOf<PlanStepUi>()
        var index = 1
        for (item in array) {
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }?.let { title ->
                    steps.add(PlanStepUi(index++, title.trim(), isCompleted = false))
                }
                is JsonObject -> parseStepObject(item, index)?.let { parsed ->
                    steps.add(parsed)
                    index++
                }
                else -> Unit
            }
        }
        return steps
    }

    private fun parseStepObject(obj: JsonObject, index: Int): PlanStepUi? {
        var title: String? = null
        for (key in TITLE_KEYS) {
            val candidate = obj[key]
            if (candidate is JsonPrimitive) {
                val text = candidate.contentOrNull?.trim()
                if (!text.isNullOrBlank()) {
                    title = text
                    break
                }
            }
        }
        // 字段名完全未知时，取第一个非状态类字符串值兜底。
        if (title == null) {
            for ((key, value) in obj) {
                if (key in STATUS_KEYS) continue
                if (value is JsonPrimitive) {
                    val text = value.contentOrNull?.trim()
                    if (!text.isNullOrBlank()) {
                        title = text
                        break
                    }
                }
            }
        }
        title ?: return null

        var completed = false
        for (key in STATUS_KEYS) {
            val statusValue = obj[key] ?: continue
            if (statusValue !is JsonPrimitive) continue
            completed = statusValue.booleanOrNull ?: (statusValue.contentOrNull?.lowercase() in COMPLETED_STATUSES)
            if (completed) break
        }
        return PlanStepUi(index = index, title = title, isCompleted = completed)
    }

    fun progress(steps: List<PlanStepUi>): Float =
        if (steps.isEmpty()) 0f else steps.count { it.isCompleted }.toFloat() / steps.size
}

/**
 * 会话级结构化任务规划看板：数据来自模型通过 plan 工具写入的 AgentPlanEntity，
 * 与叙述文本中 `- [ ]` 正则卡片互补——模型不输出 markdown 时进度依然可见、可核验。
 */
@Composable
internal fun SessionPlanBoardCard(
    goal: String,
    steps: List<PlanStepUi>,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) return
    val haptic = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(true) }
    val completedCount = steps.count { it.isCompleted }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                expanded = !expanded
            },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeIcon(RuntimeIconName.List, Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                Text(
                    goal.ifBlank { stringResource(R.string.chat_plan_board_header) },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.chat_plan_board_progress, completedCount, steps.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { PlanStepParser.progress(steps) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
            )
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.forEach { step ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (step.isCompleted) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    RuntimeIcon(
                                        RuntimeIconName.Check,
                                        Modifier.size(9.dp),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
                                )
                            }
                            Text(
                                step.title,
                                style = MaterialTheme.typography.bodySmall,
                                textDecoration = if (step.isCompleted) TextDecoration.LineThrough else null,
                                color = when {
                                    step.isCompleted -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> Color.Unspecified
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 常驻置底计划条：固定在消息列表与输入框之间，消息滚动时始终可见。
 *
 * 折叠态：单行显示「当前步骤（首个未完成项）」+ 进度计数 + 细进度条。
 * 展开态：inline 展示完整步骤列表（与 [SessionPlanBoardCard] 同款步骤渲染）。
 * 全部完成时显示「✓ 所有步骤已完成」。
 */
@Composable
internal fun StickyPlanBar(
    goal: String,
    steps: List<PlanStepUi>,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) return
    val haptic = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }
    val completedCount = steps.count { it.isCompleted }
    val allDone = completedCount == steps.size
    val activeStep = steps.firstOrNull { !it.isCompleted }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                expanded = !expanded
            },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
        border = BorderStroke(
            1.dp,
            if (allDone) MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ── 标题行 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeIcon(
                    if (allDone) RuntimeIconName.Check else RuntimeIconName.List,
                    Modifier.size(14.dp),
                    if (allDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = when {
                        allDone -> stringResource(R.string.chat_plan_bar_all_done)
                        activeStep != null -> activeStep.title
                        else -> goal.ifBlank { stringResource(R.string.chat_plan_board_header) }
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    color = if (allDone) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                )
                // 进度计数 chip
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        stringResource(R.string.chat_plan_board_progress, completedCount, steps.size),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                RuntimeIcon(
                    if (expanded) RuntimeIconName.ChevronDown else RuntimeIconName.ChevronRight,
                    Modifier.size(12.dp),
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
            }
            // 细进度条（始终显示）
            LinearProgressIndicator(
                progress = { PlanStepParser.progress(steps) },
                modifier = Modifier.fillMaxWidth().height(2.dp).clip(CircleShape),
                color = if (allDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            // 展开：完整步骤列表（带最大高度保护与垂直滚动）
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    steps.forEach { step ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (step.isCompleted) {
                                Box(
                                    modifier = Modifier
                                        .size(13.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    RuntimeIcon(RuntimeIconName.Check, Modifier.size(8.dp), MaterialTheme.colorScheme.onPrimary)
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(13.dp)
                                        .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
                                )
                            }
                            Text(
                                step.title,
                                style = MaterialTheme.typography.bodySmall,
                                textDecoration = if (step.isCompleted) TextDecoration.LineThrough else null,
                                color = when {
                                    step.isCompleted -> MaterialTheme.colorScheme.onSurfaceVariant
                                    step == activeStep -> Color.Unspecified
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                },
                                fontWeight = if (step == activeStep) FontWeight.SemiBold else null,
                            )
                        }
                    }
                }
            }
        }
    }
}
