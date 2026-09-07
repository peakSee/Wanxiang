package top.wanxiang.app.ui.chat.floating

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.wanxiang.app.feature.chat.R
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.harness.UserMessage
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconButton
import top.wanxiang.app.ui.components.RuntimeIconName

/**
 * 智枢桌面悬浮小窗视图（支持胶囊态与面板态即时切换，去除多余遮罩与阴影瑕疵）。
 */
@Composable
fun FloatingChatView(
    sessionTitle: String?,
    messages: List<HarnessMessage>,
    running: Boolean,
    thinkingLive: Boolean,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDragBy: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onSendPrompt: (String) -> Unit,
    onStopAgent: () -> Unit,
    onRestoreApp: () -> Unit,
    onClose: () -> Unit,
) {
    if (isExpanded) {
        FloatingChatPanel(
            sessionTitle = sessionTitle,
            messages = messages,
            running = running,
            thinkingLive = thinkingLive,
            onCollapse = onToggleExpanded,
            onDragBy = onDragBy,
            onDragEnd = onDragEnd,
            onSendPrompt = onSendPrompt,
            onStopAgent = onStopAgent,
            onRestoreApp = onRestoreApp,
            onClose = onClose,
        )
    } else {
        FloatingChatCapsule(
            sessionTitle = sessionTitle,
            running = running,
            thinkingLive = thinkingLive,
            onExpand = onToggleExpanded,
            onDragBy = onDragBy,
            onDragEnd = onDragEnd,
        )
    }
}

/**
 * 胶囊态 (Mini Capsule Mode)
 * 支持滑动拖拽移动、松手自动边缘吸附与轻触点击展开，无多余阴影遮罩
 */
@Composable
private fun FloatingChatCapsule(
    sessionTitle: String?,
    running: Boolean,
    thinkingLive: Boolean,
    onExpand: () -> Unit,
    onDragBy: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val change = awaitTouchSlopOrCancellation(down.id) { c, overSlop ->
                        c.consume()
                        onDragBy(overSlop.x, overSlop.y)
                    }
                    if (change != null) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointerChange = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!pointerChange.pressed) {
                                pointerChange.consume()
                                break
                            }
                            val dragAmount = pointerChange.positionChange()
                            pointerChange.consume()
                            onDragBy(dragAmount.x, dragAmount.y)
                        }
                        onDragEnd()
                    } else {
                        onExpand()
                    }
                }
            },
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f),
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (running) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        if (running) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (running) {
                    RuntimeCircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.8.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    RuntimeIcon(
                        RuntimeIconName.Brain,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                text = when {
                    thinkingLive -> stringResource(R.string.chat_floating_thinking)
                    running -> stringResource(R.string.chat_floating_running)
                    else -> sessionTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_title)
                },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp),
            )
        }
    }
}

/**
 * 展开面板态 (Expanded Floating Chat Panel)
 */
@Composable
private fun FloatingChatPanel(
    sessionTitle: String?,
    messages: List<HarnessMessage>,
    running: Boolean,
    thinkingLive: Boolean,
    onCollapse: () -> Unit,
    onDragBy: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onSendPrompt: (String) -> Unit,
    onStopAgent: () -> Unit,
    onRestoreApp: () -> Unit,
    onClose: () -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val displayMessages = remember(messages) {
        messages.filter { it !is ToolResult }.takeLast(20)
    }

    LaunchedEffect(displayMessages.size, running) {
        if (displayMessages.isNotEmpty()) {
            listState.animateScrollToItem(displayMessages.size - 1)
        }
    }

    Surface(
        modifier = Modifier
            .width(310.dp)
            .height(410.dp)
            .clip(RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.98f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 顶栏（左侧标题区域支持按住拖拽移动，右侧操作按钮独立响应点击）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragEnd() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDragBy(dragAmount.x, dragAmount.y)
                                },
                            )
                        },
                ) {
                    RuntimeIcon(
                        RuntimeIconName.Brain,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = sessionTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_title),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (running) {
                        RuntimeIconButton(
                            onClick = onStopAgent,
                            modifier = Modifier.size(24.dp),
                            contentDescription = stringResource(R.string.chat_floating_stop),
                        ) {
                            RuntimeIcon(
                                RuntimeIconName.Stop,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    RuntimeIconButton(
                        onClick = onRestoreApp,
                        modifier = Modifier.size(24.dp),
                        contentDescription = stringResource(R.string.chat_floating_restore_app),
                    ) {
                        RuntimeIcon(
                            RuntimeIconName.OpenInNew,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    RuntimeIconButton(
                        onClick = onCollapse,
                        modifier = Modifier.size(24.dp),
                        contentDescription = "收起为胶囊",
                    ) {
                        RuntimeIcon(
                            RuntimeIconName.ChevronDown,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RuntimeIconButton(
                        onClick = onClose,
                        modifier = Modifier.size(24.dp),
                        contentDescription = stringResource(R.string.chat_floating_close),
                    ) {
                        RuntimeIcon(
                            RuntimeIconName.Close,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 消息流列表
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(10.dp),
            ) {
                if (displayMessages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_floating_idle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(displayMessages, key = { it.id }) { msg ->
                            when (msg) {
                                is UserMessage -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.widthIn(max = 240.dp),
                                        ) {
                                            Text(
                                                text = msg.text,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            )
                                        }
                                    }
                                }
                                is AssistantText -> {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        if (!msg.reasoning.isNullOrBlank()) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                shape = RoundedCornerShape(6.dp),
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    RuntimeIcon(
                                                        RuntimeIconName.Brain,
                                                        Modifier.size(11.dp),
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    Text(
                                                        text = msg.reasoning.orEmpty(),
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                        if (msg.text.isNotBlank()) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.widthIn(max = 260.dp),
                                            ) {
                                                Text(
                                                    text = msg.text,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                                is ToolCall -> {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        shape = RoundedCornerShape(6.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RuntimeIcon(
                                                RuntimeIconName.Code,
                                                Modifier.size(11.dp),
                                                MaterialTheme.colorScheme.primary,
                                            )
                                            Text(
                                                text = "${msg.rawToolName ?: msg.tool.name} ${msg.args}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            }

            // 底部快速输入栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(
                        0.8.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(18.dp),
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_floating_input_hint),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank()) {
                                    val prompt = inputText.trim()
                                    inputText = ""
                                    onSendPrompt(prompt)
                                }
                            },
                        ),
                    )
                }

                RuntimeIconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            val prompt = inputText.trim()
                            inputText = ""
                            onSendPrompt(prompt)
                        }
                    },
                    modifier = Modifier.size(24.dp),
                    contentDescription = stringResource(R.string.chat_floating_send),
                ) {
                    RuntimeIcon(
                        RuntimeIconName.ArrowUp,
                        Modifier.size(13.dp),
                        tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}
