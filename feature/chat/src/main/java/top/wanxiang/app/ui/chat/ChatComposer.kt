package top.wanxiang.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import top.wanxiang.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import top.wanxiang.app.feature.chat.R
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.wanxiang.app.core.database.AiModelEntity
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicTextField
import top.wanxiang.app.harness.QueuedPrompt

/**
 * 输入区：斜杠/@ 弹窗、排队指令、附件预览、推理强度滑块、工具状态胶囊与一体化输入胶囊。
 */
@Composable
internal fun ChatComposer(
    listState: LazyListState,
    running: Boolean,
    initializing: Boolean,
    status: String?,
    messages: List<HarnessMessage>,
    toolResults: Map<String, ToolResult>,
    workspace: String,
    input: String,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    sendMode: ComposerSendMode,
    matchingCommands: List<SlashCommandItem>,
    onApplyCommand: (SlashCommandItem) -> Unit,
    matchingMentions: List<MentionItem>,
    onApplyMention: (MentionItem) -> Unit,
    attachments: List<ChatAttachment>,
    attachmentStatuses: Map<String, AttachmentStatus> = emptyMap(),
    attachmentsProcessing: Boolean,
    onAttachmentsPicked: (List<Uri>, Boolean) -> Unit,
    onRemoveAttachment: (ChatAttachment) -> Unit,
    queuedPrompts: List<QueuedPrompt>,
    onEditQueuedPrompt: (QueuedPrompt) -> Unit,
    onRemoveQueuedPrompt: (QueuedPrompt) -> Unit,
    onConvertToSteer: (QueuedPrompt) -> Unit,
    knownMentionNames: List<String>,
    attachedMentions: List<MentionItem>,
    onRemoveMention: (MentionItem) -> Unit,
    pinnedCapabilities: List<MentionItem>,
    onUnpinMention: (String) -> Unit,
    onOpenSkillsMcp: () -> Unit,
    activeModel: AiModelEntity?,
    onUpdateReasoning: (String?, String?) -> Unit,
    contextUsage: ContextUsage,
) {
    var showReasoningSlider by rememberSaveable { mutableStateOf(false) }

    // 🌟 任务执行中的极光流光边框动效 (Aurora Glow Border Animation)
    // 仅在任务运行中时才动画，空闲时保持静态，避免聊天页每帧重组。
    val glowOffset: Float
    val glowPulse: Float
    if (running) {
        val transition = rememberInfiniteTransition(label = "capsuleGlowTransition")
        glowOffset = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1000f,
            animationSpec = infiniteRepeatable(
                animation = tween(2800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "glowOffset",
        ).value
        glowPulse = transition.animateFloat(
            initialValue = 0.65f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glowPulse",
        ).value
    } else {
        glowOffset = 0f
        glowPulse = 1f
    }

    val focusRequester = remember { FocusRequester() }
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(input, TextRange(input.length)))
    }

    LaunchedEffect(input) {
        if (textFieldValue.text != input) {
            textFieldValue = TextFieldValue(
                text = input,
                selection = TextRange(input.length),
            )
            if (input.isNotEmpty()) {
                runCatching { focusRequester.requestFocus() }
            }
        }
    }

    val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        onAttachmentsPicked(uris.toList(), true)
    }

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        onAttachmentsPicked(uris.toList(), false)
    }

    val coroutineScope = rememberCoroutineScope()

    val doSend = {
        // 初始化（会话尚未恢复）期间发送目标未定，禁止误发
        if (!initializing && (input.isNotBlank() || attachments.isNotEmpty())) {
            onSend()
            coroutineScope.launch {
                delay(60)
                val count = listState.layoutInfo.totalItemsCount
                if (count > 0) {
                    listState.animateScrollToItem(count - 1)
                }
            }
        }
    }

    // 斜杠指令快捷提示弹窗
    if (matchingCommands.isNotEmpty()) {
        SlashCommandPopup(
            commands = matchingCommands,
            onSelect = onApplyCommand,
        )
    }

    // @ 艾特精准挂载快捷弹窗 (Skills & MCP)
    if (matchingMentions.isNotEmpty()) {
        MentionPopup(
            mentions = matchingMentions,
            onSelect = onApplyMention,
        )
    }

    QueuedPromptStack(
        prompts = queuedPrompts,
        onEdit = onEditQueuedPrompt,
        onRemove = onRemoveQueuedPrompt,
        onConvertToSteer = onConvertToSteer,
    )

    // 待发送附件预览栏
    AttachmentPreviewRow(
        attachments = attachments,
        onRemove = onRemoveAttachment,
        statuses = attachmentStatuses,
    )

    // 附件处理中（复制/压缩/编码）加载指示
    if (attachmentsProcessing) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Text(
                stringResource(R.string.chat_attachment_processing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 🌟 思考/推理强度浮动调节胶囊 (ChatGPT 同款滑块面板)
    androidx.compose.animation.AnimatedVisibility(
        visible = showReasoningSlider,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
    ) {
        ReasoningEffortSlider(
            currentMode = activeModel?.reasoningMode,
            currentEffort = activeModel?.reasoningEffort,
            onSelect = { mode, effort ->
                onUpdateReasoning(mode, effort)
            },
            onClose = { showReasoningSlider = false },
        )
    }

    // 🌟 底部工具执行状态与中止胶囊
    ToolActivityPill(
        running = running,
        status = status,
        messages = messages,
        toolResults = toolResults,
        workspace = workspace,
        onStop = onStop,
    )


    // 现代化一体化输入胶囊 (Unified Chat Input Capsule with Aurora Glow)
    val auroraBrush = if (running) {
        androidx.compose.ui.graphics.Brush.linearGradient(
            colors = listOf(
                Color(0xFF00E5FF),
                Color(0xFF7C4DFF),
                Color(0xFFFF4081),
                Color(0xFF00E5FF),
            ),
            start = androidx.compose.ui.geometry.Offset(glowOffset, 0f),
            end = androidx.compose.ui.geometry.Offset(glowOffset + 600f, 600f),
            tileMode = androidx.compose.ui.graphics.TileMode.Repeated,
        )
    } else null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 6.dp)
            .then(
                if (running && auroraBrush != null) {
                    Modifier.border(
                        androidx.compose.foundation.BorderStroke(
                            (1.2f + 0.3f * glowPulse).dp,
                            auroraBrush,
                        ),
                        RoundedCornerShape(20.dp),
                    )
                } else Modifier
            ),
        shape = RoundedCornerShape(20.dp),
        color = if (running) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (!running) androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ) else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 🌟 常驻/已钉选能力与临时挂载胶囊栏 (Pinned & Attached Capabilities Strip)
            val allDisplayItems = remember(pinnedCapabilities, attachedMentions) {
                (pinnedCapabilities + attachedMentions).distinctBy { it.id }
            }
            if (allDisplayItems.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(allDisplayItems.size, key = { allDisplayItems[it].id }) { index ->
                        val item = allDisplayItems[index]
                        val isSkill = item.type == MentionType.SKILL
                        val isAttached = attachedMentions.any { it.id == item.id }
                        val isPinned = pinnedCapabilities.any { it.id == item.id }
                        val tagBg = if (isSkill) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                    else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                        val tagBorder = if (isSkill) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                        val tagColor = if (isSkill) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.tertiary

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = tagBg,
                            border = androidx.compose.foundation.BorderStroke(0.8.dp, tagBorder),
                            modifier = Modifier.clickable {
                                if (isAttached) {
                                    onRemoveMention(item)
                                } else if (isPinned) {
                                    onUnpinMention(item.id)
                                }
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                RuntimeIcon(
                                    item.icon,
                                    Modifier.size(12.dp),
                                    tint = tagColor,
                                )
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                                    color = tagColor,
                                )
                                if (isPinned) {
                                    Surface(
                                        color = tagColor.copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(4.dp),
                                    ) {
                                        Text(
                                            stringResource(R.string.chat_pinned_badge),
                                            modifier = Modifier.padding(horizontal = 3.5.dp, vertical = 0.5.dp),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold),
                                            color = tagColor,
                                        )
                                    }
                                }
                                RuntimeIcon(
                                    RuntimeIconName.Close,
                                    Modifier.size(10.dp),
                                    tint = tagColor.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }

                    item {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = androidx.compose.foundation.BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                            modifier = Modifier.clickable { onOpenSkillsMcp() },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                RuntimeIcon(
                                    RuntimeIconName.Plus,
                                    Modifier.size(11.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(R.string.chat_attach_button),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // 🌟 上排主体：弹性文本输入框
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (input.isEmpty()) {
                    Text(
                        text = stringResource(if (running) R.string.chat_input_running else R.string.chat_input_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        val updatedValue = if (newValue.text.length < textFieldValue.text.length) {
                            val oldText = textFieldValue.text
                            val deletedPos = newValue.selection.start
                            val baseRegex = buildMentionRegex(knownMentionNames)
                            val regex = Regex("""(${baseRegex.pattern})\s*""")
                            var handled: TextFieldValue? = null
                            for (match in regex.findAll(oldText)) {
                                val range = match.range
                                if (deletedPos in range.first until (range.last + 1)) {
                                    val before = oldText.substring(0, range.first)
                                    val after = oldText.substring((range.last + 1).coerceAtMost(oldText.length))
                                    val resultText = before + after
                                    val newCursor = range.first.coerceAtMost(resultText.length)
                                    handled = TextFieldValue(resultText, TextRange(newCursor))
                                    break
                                }
                            }
                            handled ?: newValue
                        } else {
                            newValue
                        }
                        textFieldValue = updatedValue
                        if (updatedValue.text != input) {
                            onInputChanged(updatedValue.text)
                        }
                    },
                    visualTransformation = run {
                        val mentionColor = MaterialTheme.colorScheme.primary
                        val mentionBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        remember(knownMentionNames, mentionColor, mentionBg) {
                            MentionVisualTransformation(knownMentionNames, mentionColor, mentionBg)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = 1,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
            }

            // 🌟 下排操作栏：精简工具集 + 状态与发送控制
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 左侧：+ 号展开选单 + 思考深度胶囊
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    var showPlusMenu by rememberSaveable { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { showPlusMenu = true },
                            modifier = Modifier.minimumInteractiveComponentSize(),
                            contentDescription = stringResource(R.string.chat_add_attachment),
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Plus,
                                modifier = Modifier.size(19.dp),
                                tint = if (attachments.isNotEmpty() || attachedMentions.isNotEmpty()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = showPlusMenu,
                            onDismissRequest = { showPlusMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_send_image), fontSize = 13.sp) },
                                leadingIcon = { RuntimeIcon(RuntimeIconName.Image, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showPlusMenu = false
                                    imagePickerLauncher.launch("image/*")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_send_file), fontSize = 13.sp) },
                                leadingIcon = { RuntimeIcon(RuntimeIconName.Document, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary) },
                                onClick = {
                                    showPlusMenu = false
                                    filePickerLauncher.launch("*/*")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_capabilities_short), fontSize = 13.sp) },
                                leadingIcon = { RuntimeIcon(RuntimeIconName.Brain, Modifier.size(16.dp), tint = Color(0xFF10B981)) },
                                onClick = {
                                    showPlusMenu = false
                                    onOpenSkillsMcp()
                                },
                            )
                        }
                    }

                    // ⚡ 思考深度独立胶囊（直接显示：轻度 / 中度 / 深度 / 极度 / 关闭 ^）
                    val effortLabel = when {
                        activeModel?.reasoningMode == "disabled" -> stringResource(R.string.chat_depth_off)
                        activeModel?.reasoningEffort == "low" -> stringResource(R.string.chat_depth_light)
                        activeModel?.reasoningEffort == "extreme" || activeModel?.reasoningEffort == "max" -> stringResource(R.string.chat_depth_extreme)
                        activeModel?.reasoningEffort == "high" -> stringResource(R.string.chat_depth_deep)
                        activeModel?.reasoningEffort == "medium" -> stringResource(R.string.chat_depth_medium)
                        else -> stringResource(R.string.chat_depth_medium)
                    }

                    val effortTint = when {
                        activeModel?.reasoningMode == "disabled" -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        activeModel?.reasoningEffort == "low" -> Color(0xFF10B981)
                        activeModel?.reasoningEffort == "extreme" || activeModel?.reasoningEffort == "max" -> Color(0xFFEC4899)
                        activeModel?.reasoningEffort == "high" -> Color(0xFF8B5CF6)
                        else -> Color(0xFF3B82F6)
                    }

                    Surface(
                        onClick = { showReasoningSlider = !showReasoningSlider },
                        shape = RoundedCornerShape(10.dp),
                        color = if (showReasoningSlider) effortTint.copy(alpha = 0.15f)
                        else effortTint.copy(alpha = 0.08f),
                        border = BorderStroke(
                            0.7.dp,
                            if (showReasoningSlider) effortTint.copy(alpha = 0.45f) else effortTint.copy(alpha = 0.2f),
                        ),
                        modifier = Modifier.padding(start = 2.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Sparkles,
                                modifier = Modifier.size(11.dp),
                                tint = effortTint,
                            )
                            Text(
                                text = effortLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = effortTint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            RuntimeIcon(
                                name = if (showReasoningSlider) RuntimeIconName.ChevronDown else RuntimeIconName.ChevronUp,
                                modifier = Modifier.size(10.dp),
                                tint = effortTint.copy(alpha = 0.75f),
                            )
                        }
                    }
                }

                // 右侧发送 / 停止控制区（圆形灵动按钮）
                val canSend = !initializing && (input.isNotBlank() || attachments.isNotEmpty())
                if (running) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ContextUsageRing(contextUsage)
                        if (canSend) {
                            val sendTint = when (sendMode) {
                                ComposerSendMode.STEER -> Color(0xFF7C4DFF)
                                ComposerSendMode.NEXT_RUN -> MaterialTheme.colorScheme.secondary
                            }
                            val sendDesc = stringResource(R.string.chat_send)
                            Surface(
                                onClick = doSend,
                                shape = CircleShape,
                                color = sendTint,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(32.dp)
                                    .semantics { contentDescription = sendDesc },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    RuntimeIcon(
                                        name = RuntimeIconName.ArrowUp,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color.White,
                                    )
                                }
                            }
                        } else {
                            val stopDesc = stringResource(R.string.chat_stop_generation)
                            Surface(
                                onClick = onStop,
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(32.dp)
                                    .semantics { contentDescription = stopDesc },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    RuntimeIcon(
                                        name = RuntimeIconName.Stop,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onError,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ContextUsageRing(contextUsage)
                        val sendDesc = stringResource(R.string.chat_send)
                        Surface(
                            onClick = { if (canSend) doSend() },
                            enabled = canSend,
                            shape = CircleShape,
                            color = if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .size(32.dp)
                                .semantics { contentDescription = sendDesc },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                RuntimeIcon(
                                    name = RuntimeIconName.ArrowUp,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

