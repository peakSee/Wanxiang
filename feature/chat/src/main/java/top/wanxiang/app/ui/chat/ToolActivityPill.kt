package top.wanxiang.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wanxiang.app.feature.chat.R
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.HarnessTool
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.harness.UserMessage
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeTextButton
import top.wanxiang.app.ui.components.StatusBadge

/**
 * 🌟 底部悬浮工具活动胶囊 (Floating Tool Activity Strip / Live Pill)
 * 实时捕获后台 Shell 命令/工具调用/构建进度，提供运行时长、命令摘要、一键中止与展开日志抽屉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolActivityPill(
    running: Boolean,
    status: String?,
    messages: List<HarnessMessage>,
    toolResults: Map<String, ToolResult>,
    workspace: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDetailSheet by remember { mutableStateOf(false) }

    val activeToolCall = remember(messages, running, toolResults) {
        if (!running) null
        else messages.filterIsInstance<ToolCall>().lastOrNull { call ->
            !toolResults.containsKey(call.id)
        }
    }

    val roundStart = remember(messages.size, running) {
        if (!running) 0L
        else messages.lastOrNull { it is UserMessage }?.createdAt ?: System.currentTimeMillis()
    }

    // 运行耗时秒数计算（若有活跃工具则计算该工具耗时，否则计算本轮耗时）
    val timingStart = remember(activeToolCall?.id, roundStart, running) {
        activeToolCall?.createdAt ?: roundStart
    }
    var elapsedMillis by remember { mutableLongStateOf(0L) }

    // 仅在用户手动点击胶囊时弹窗；工具切换或执行完成时重置状态，绝不自动弹窗
    LaunchedEffect(activeToolCall?.id) {
        showDetailSheet = false
    }
    LaunchedEffect(running) {
        if (!running) {
            showDetailSheet = false
        }
    }

    LaunchedEffect(running, timingStart) {
        if (running && timingStart > 0L) {
            while (true) {
                elapsedMillis = (System.currentTimeMillis() - timingStart).coerceAtLeast(0L)
                delay(500)
            }
        } else {
            elapsedMillis = 0L
        }
    }

    // 脉冲与呼吸光效
    val transition = rememberInfiniteTransition(label = "toolPillPulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pillPulseAlpha",
    )

    AnimatedVisibility(
        visible = running,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val toolKind = activeToolCall?.tool
        val toolIcon = when (toolKind) {
            HarnessTool.BASE, HarnessTool.PROCESS -> RuntimeIconName.Terminal
            HarnessTool.READ, HarnessTool.WRITE, HarnessTool.EDIT -> RuntimeIconName.Document
            HarnessTool.SUBAGENT -> RuntimeIconName.Brain
            else -> RuntimeIconName.Speed
        }

        val pillAccent = when (toolKind) {
            HarnessTool.BASE, HarnessTool.PROCESS -> Color(0xFF00E5FF)
            HarnessTool.WRITE, HarnessTool.EDIT -> Color(0xFF10B981)
            HarnessTool.SUBAGENT -> Color(0xFF8B5CF6)
            else -> MaterialTheme.colorScheme.primary
        }

        val actionSummary = activeToolCall?.let { formatToolSummary(it) } ?: status ?: stringResource(R.string.chat_thinking)

        Surface(
            onClick = { showDetailSheet = true },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
            border = androidx.compose.foundation.BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 1.5.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    // 运行状态微光指示点（纯颜色补文本语义，TalkBack 可读）
                    val runningDotLabel = stringResource(R.string.chat_tool_state_running)
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(pillAccent.copy(alpha = pulseAlpha))
                            .semantics { contentDescription = runningDotLabel },
                    )

                    // 图标
                    RuntimeIcon(
                        name = toolIcon,
                        modifier = Modifier.size(12.dp),
                        tint = pillAccent,
                    )

                    // 工具名称与摘要
                    Text(
                        text = actionSummary,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    // 耗时
                    if (elapsedMillis > 0) {
                        Text(
                            text = formatDuration(elapsedMillis),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                // 一键终止极简微型按钮
                val stopLabel = stringResource(R.string.chat_stop_generation)
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f))
                        .clickable(onClick = onStop)
                        .semantics { contentDescription = stopLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    RuntimeIcon(
                        name = RuntimeIconName.Close,
                        modifier = Modifier.size(9.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }

    if (showDetailSheet && activeToolCall != null) {
        // 参数默认折叠截断，避免原始 JSON 直出刷屏；点击"查看原始参数"展开全文
        var showRawArgs by remember(activeToolCall.id) { mutableStateOf(false) }
        val runningStatusText = stringResource(R.string.chat_tool_detail_running, formatDuration(elapsedMillis))
        val readyStatusText = stringResource(R.string.chat_tool_detail_ready)
        val typeText = stringResource(R.string.chat_tool_detail_type, activeToolCall.tool.name)
        val argsText = remember(activeToolCall.id) { prettyArgs(activeToolCall.args) }
        ModalBottomSheet(
            onDismissRequest = { showDetailSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.chat_tool_detail_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    StatusBadge(
                        text = if (running) runningStatusText else readyStatusText,
                        color = if (running) Color(0xFF00E5FF) else MaterialTheme.colorScheme.primary,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = typeText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.chat_tool_detail_args),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (showRawArgs) argsText else argsText.take(ARG_PREVIEW_LENGTH) +
                                if (argsText.length > ARG_PREVIEW_LENGTH) stringResource(R.string.chat_tool_detail_args_truncated) else "",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (argsText.length > ARG_PREVIEW_LENGTH) {
                            RuntimeTextButton(
                                onClick = { showRawArgs = !showRawArgs },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text(
                                    stringResource(
                                        if (showRawArgs) R.string.chat_tool_detail_hide_raw else R.string.chat_tool_detail_view_raw,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private const val ARG_PREVIEW_LENGTH = 400
private val PRETTY_JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }

private fun prettyArgs(args: JsonObject): String =
    runCatching {
        PRETTY_JSON.encodeToString(JsonObject.serializer(), args)
    }.getOrDefault(args.toString())

private fun formatDuration(ms: Long): String {
    return String.format(java.util.Locale.getDefault(), "%.1fs", ms / 1000f)
}

private fun formatToolSummary(call: ToolCall): String {
    return when (call.tool) {
        HarnessTool.BASE -> {
            val cmd = call.args["command"]?.jsonPrimitive?.content ?: call.args.toString()
            "bash: ${cmd.take(40)}"
        }
        HarnessTool.PROCESS -> {
            val action = call.args["action"]?.jsonPrimitive?.content ?: "process"
            val id = call.args["id"]?.jsonPrimitive?.content ?: ""
            "process[$action]: $id"
        }
        HarnessTool.READ -> "read: ${call.args["path"]?.jsonPrimitive?.content ?: "file"}"
        HarnessTool.WRITE -> "write: ${call.args["path"]?.jsonPrimitive?.content ?: "file"}"
        HarnessTool.EDIT -> "edit: ${call.args["path"]?.jsonPrimitive?.content ?: "file"}"
        HarnessTool.SUBAGENT -> "subagent: ${call.args["prompt"]?.jsonPrimitive?.content?.take(30) ?: "task"}"
        else -> "${call.tool.name.lowercase()}: active"
    }
}
