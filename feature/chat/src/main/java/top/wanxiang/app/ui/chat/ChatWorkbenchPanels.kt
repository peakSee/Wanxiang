package top.wanxiang.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import top.wanxiang.app.core.database.AgentMemoryEntity
import top.wanxiang.app.core.database.AgentScratchpadEntity
import top.wanxiang.app.core.database.AiModelEntity
import top.wanxiang.app.core.model.ApprovalMode
import top.wanxiang.app.feature.chat.R
import top.wanxiang.app.harness.QueuedPrompt
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.CapabilityEvent
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.harness.UserMessage
import top.wanxiang.app.harness.events.HarnessEvent
import top.wanxiang.app.harness.queue.PromptQueue
import top.wanxiang.app.harness.session.ConversationBranch
import top.wanxiang.app.harness.session.ConversationBranchKind
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator

@Composable
internal fun CollapsibleChatWorkbenchStrip(
    activeModel: AiModelEntity?,
    approvalMode: ApprovalMode,
    currentBranch: ConversationBranch?,
    runtimeEvents: List<HarnessEvent>,
    running: Boolean,
    onOpenModels: () -> Unit,
    onOpenApprovalModes: () -> Unit,
    onOpenBranches: () -> Unit,
    onOpenRuntime: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenBrowser: (() -> Unit)? = null,
    browserHighlight: Boolean = false,
) {
    val roundCount = runtimeEvents.count { it is HarnessEvent.ProviderRoundStarted }
    val activeModelName = activeModel?.let { entity ->
        entity.model.split(",").firstOrNull()?.trim().takeUnless { it.isNullOrBlank() } ?: entity.name
    } ?: stringResource(R.string.chat_no_model_selected)

    val (modeLabelRes, modeColor) = when (approvalMode) {
        ApprovalMode.FULL_ACCESS -> R.string.chat_approval_full_access to Color(0xFFE65100)
        ApprovalMode.ASSISTED -> R.string.chat_approval_assisted to Color(0xFF1976D2)
        ApprovalMode.REQUEST -> R.string.chat_approval_request to Color(0xFF388E3C)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.65f),
        shape = RectangleShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.Start),
        ) {
            // 1. 模型选择项
            WorkbenchStatusItem(
                icon = RuntimeIconName.Brain,
                label = activeModelName,
                tint = MaterialTheme.colorScheme.primary,
                onClick = onOpenModels,
            )

            StatusDivider()

            // 2. 审批模式/权重项
            WorkbenchStatusItem(
                icon = RuntimeIconName.Shield,
                label = stringResource(modeLabelRes),
                tint = modeColor,
                onClick = onOpenApprovalModes,
            )

            StatusDivider()

            // 3. 分支状态项
            WorkbenchStatusItem(
                icon = RuntimeIconName.Hub,
                label = currentBranch?.name ?: stringResource(R.string.chat_main_line),
                tint = MaterialTheme.colorScheme.primary,
                onClick = onOpenBranches,
            )

            StatusDivider()

            // 4. 运行时/轮次状态项
            WorkbenchStatusItem(
                icon = RuntimeIconName.Logs,
                label = if (roundCount > 0) {
                    stringResource(R.string.chat_round_number, roundCount)
                } else {
                    stringResource(R.string.chat_event_count, runtimeEvents.size)
                },
                tint = if (running) Color(0xFF7C4DFF) else MaterialTheme.colorScheme.tertiary,
                highlight = running,
                onClick = onOpenRuntime,
            )

            // 5. 浏览器入口（轮次之后）：agent 在浏览器产生新动态时高亮提示
            if (onOpenBrowser != null) {
                StatusDivider()
                WorkbenchStatusItem(
                    icon = RuntimeIconName.Globe,
                    label = if (browserHighlight) "浏览器 •" else "浏览器",
                    tint = if (browserHighlight) Color(0xFF3F8FFF) else MaterialTheme.colorScheme.onSurfaceVariant,
                    highlight = browserHighlight,
                    onClick = onOpenBrowser,
                )
            }
        }
    }
}

@Composable
private fun StatusDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(width = 1.dp, height = 9.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    )
}

@Composable
private fun WorkbenchStatusItem(
    icon: RuntimeIconName,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = if (highlight) tint.copy(alpha = 0.14f) else Color.Transparent,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            RuntimeIcon(
                name = icon,
                modifier = Modifier.size(11.dp),
                tint = tint,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (highlight) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BranchBrowserSheet(
    branches: List<ConversationBranch>,
    running: Boolean,
    onDismiss: () -> Unit,
    onSwitch: (ConversationBranch) -> Unit,
    onOpenSubagent: (ConversationBranch) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.chat_branches_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.chat_branches_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
            if (branches.isEmpty()) {
                RuntimeCard {
                    Text(
                        stringResource(R.string.chat_no_branches),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val conversation = branches.filter { it.kind != ConversationBranchKind.SUBAGENT }
                val subagents = branches.filter { it.kind == ConversationBranchKind.SUBAGENT }
                SectionLabel(stringResource(R.string.chat_conversation_paths), conversation.size)
                conversation.forEach { branch ->
                    BranchCard(branch, enabled = !running && !branch.isBusy, onClick = { onSwitch(branch) })
                }
                if (subagents.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.chat_subagent_lanes), subagents.size)
                    subagents.forEach { branch ->
                        BranchCard(
                            branch = branch,
                            enabled = branch.laneName != null,
                            onClick = { onOpenSubagent(branch) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionLabel(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BranchCard(branch: ConversationBranch, enabled: Boolean, onClick: () -> Unit) {
    val tint = when (branch.kind) {
        ConversationBranchKind.MAIN -> MaterialTheme.colorScheme.primary
        ConversationBranchKind.BRANCH -> Color(0xFF7C4DFF)
        ConversationBranchKind.SUBAGENT -> MaterialTheme.colorScheme.tertiary
        ConversationBranchKind.HISTORY -> MaterialTheme.colorScheme.secondary
    }
    RuntimeCard(
        containerColor = if (branch.isCurrent) tint.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = if (branch.isCurrent) tint.copy(alpha = 0.55f) else Color.Transparent,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        onClick = if (enabled && !branch.isCurrent) onClick else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                RuntimeIcon(if (branch.kind == ConversationBranchKind.SUBAGENT) RuntimeIconName.Bot else RuntimeIconName.Hub, Modifier.size(18.dp), tint)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(branch.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (branch.isCurrent) MiniBadge(stringResource(R.string.chat_badge_current), tint)
                    if (branch.isBusy) MiniBadge(stringResource(R.string.chat_badge_busy), Color(0xFF7C4DFF))
                    if (branch.faulted) MiniBadge(stringResource(R.string.chat_badge_faulted), MaterialTheme.colorScheme.error)
                }
                Text(
                    branch.preview.ifBlank { stringResource(R.string.chat_branch_start) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (branch.isBusy) {
                    // 低优先级修复：running 禁用需给出解释，否则用户不知道为何点不动
                    Text(
                        stringResource(
                            if (branch.kind == ConversationBranchKind.SUBAGENT) {
                                R.string.chat_subagent_live_hint
                            } else {
                                R.string.chat_branch_busy_hint
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                    )
                }
                Text(
                    stringResource(R.string.chat_branch_records, branch.depth) + " · " + stringResource(R.string.chat_branch_tool_calls, branch.toolCallCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (enabled && !branch.isCurrent) RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(18.dp), tint)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubagentResultSheet(
    state: SubagentResultUiState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val branch = state.branch
    val finalResult = state.messages.filterIsInstance<AssistantText>().lastOrNull { it.text.isNotBlank() }
    val task = state.messages.filterIsInstance<UserMessage>().firstOrNull()
    val toolResults = remember(state.messages) {
        state.messages.filterIsInstance<ToolResult>().associateBy { it.toolCallId }
    }
    var processExpanded by rememberSaveable(branch.id) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(38.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        RuntimeIcon(RuntimeIconName.Bot, Modifier.size(20.dp), MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            branch.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.chat_subagent_result_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (branch.isBusy) MiniBadge(stringResource(R.string.chat_badge_busy), Color(0xFF7C4DFF))
                    if (branch.faulted) MiniBadge(stringResource(R.string.chat_badge_faulted), MaterialTheme.colorScheme.error)
                }
            }

            if (state.loading) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RuntimeCircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.chat_subagent_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (state.error != null) {
                item {
                    RuntimeCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                        Text(state.error, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            } else {
                task?.let { taskMessage ->
                    item {
                        RuntimeCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Text(
                                stringResource(R.string.chat_subagent_task),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Spacer(Modifier.height(4.dp))
                            SelectionContainer {
                                Text(
                                    subagentTaskDetails(taskMessage.text),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        stringResource(R.string.chat_subagent_final_result),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                item {
                    RuntimeCard(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.38f),
                        borderColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f),
                    ) {
                        if (finalResult != null) {
                            MarkdownText(finalResult.text, Modifier.fillMaxWidth())
                        } else {
                            Text(
                                stringResource(
                                    if (branch.isBusy) R.string.chat_subagent_pending else R.string.chat_subagent_no_result,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item {
                    RuntimeCard(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        onClick = { processExpanded = !processExpanded },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RuntimeIcon(RuntimeIconName.Logs, Modifier.size(17.dp), MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.chat_subagent_process, branch.depth, branch.toolCallCount),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            RuntimeIcon(
                                RuntimeIconName.ChevronDown,
                                Modifier.size(18.dp).rotate(if (processExpanded) 180f else 0f),
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (processExpanded) {
                    items(
                        items = state.messages.filter { it !is ToolResult && it.id != finalResult?.id },
                        key = { it.id },
                    ) { message ->
                        when (message) {
                            is UserMessage -> Unit
                            is AssistantText -> RuntimeCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                                MarkdownText(message.text, Modifier.fillMaxWidth())
                            }
                            is ToolCall -> ToolCard(
                                call = message,
                                result = toolResults[message.id],
                                workspace = "",
                                onOpenFile = null,
                                running = branch.isBusy,
                                liveStatus = null,
                            )
                            is CapabilityEvent -> Unit
                            is ToolResult -> Unit
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.chat_subagent_refresh)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_close)) }
                }
            }
        }
    }
}

private fun subagentTaskDetails(prompt: String): String {
    val details = prompt.substringAfter("任务详情：", missingDelimiterValue = prompt)
        .substringBefore("你是被主智能体派发的子智能体")
        .trim()
    return details.ifBlank { prompt.trim() }
}

@Composable
private fun MiniBadge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuntimeTimelineSheet(
    events: List<HarnessEvent>,
    messages: List<top.wanxiang.app.harness.HarnessMessage>,
    memories: List<AgentMemoryEntity> = emptyList(),
    scratchpads: List<AgentScratchpadEntity> = emptyList(),
    onDeleteMemory: (String) -> Unit = {},
    onDeleteScratchpad: (String) -> Unit = {},
    onClearScratchpads: () -> Unit = {},
    onNavigateToMessage: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val (rounds, lifecycle) = remember(events, messages) { buildRoundGroups(events, messages) }
        var memoriesExpanded by rememberSaveable { mutableStateOf(false) }
        var scratchpadsExpanded by rememberSaveable { mutableStateOf(false) }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column {
                    Text(
                        stringResource(R.string.chat_runtime_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.chat_runtime_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                }
            }

            // 1. 长期工作记忆折叠卡片 (Collapsible Long-term Memory Section)
            item {
                CollapsibleRuntimeSectionCard(
                    title = stringResource(R.string.chat_memory_section, memories.size),
                    icon = RuntimeIconName.Brain,
                    tint = MaterialTheme.colorScheme.secondary,
                    expanded = memoriesExpanded,
                    onToggle = { memoriesExpanded = !memoriesExpanded },
                ) {
                    if (memories.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_memory_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            memories.forEach { memory ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = memory.key,
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Text(
                                                text = limitDiagnosticText(memory.value),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = DIAGNOSTIC_PREVIEW_LINES,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        top.wanxiang.app.ui.components.RuntimeIconButton(
                                            onClick = { onDeleteMemory(memory.id) },
                                            modifier = Modifier.size(24.dp),
                                        ) {
                                            RuntimeIcon(RuntimeIconName.Trash, Modifier.size(14.dp), MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. 任务草稿折叠卡片 (Collapsible Scratchpad Section)
            item {
                CollapsibleRuntimeSectionCard(
                    title = stringResource(R.string.chat_scratchpad_section, scratchpads.size),
                    icon = RuntimeIconName.File,
                    tint = MaterialTheme.colorScheme.tertiary,
                    expanded = scratchpadsExpanded,
                    onToggle = { scratchpadsExpanded = !scratchpadsExpanded },
                ) {
                    if (scratchpads.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_scratchpad_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            scratchpads.forEach { pad ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = pad.key.ifBlank { "草稿" },
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.tertiary,
                                            )
                                            Text(
                                                text = limitDiagnosticText(pad.value),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = DIAGNOSTIC_PREVIEW_LINES,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        top.wanxiang.app.ui.components.RuntimeIconButton(
                                            onClick = { onDeleteScratchpad(pad.key) },
                                            modifier = Modifier.size(24.dp),
                                        ) {
                                            RuntimeIcon(RuntimeIconName.Trash, Modifier.size(14.dp), MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (events.isEmpty()) {
                item {
                    RuntimeCard {
                        Text(
                            stringResource(R.string.chat_no_events),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // 汇总统计
                val roundCount = events.count { it is HarnessEvent.ProviderRoundStarted }
                val toolCount = events.count { it is HarnessEvent.ToolCallStarted }
                val approvalCount = events.count { it is HarnessEvent.ApprovalRequested }
                val recoveryCount = events.count { it is HarnessEvent.RecoveryApplied }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatPill(RuntimeIconName.Brain, roundCount.toString(), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                        StatPill(RuntimeIconName.Wrench, toolCount.toString(), MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                        StatPill(RuntimeIconName.Shield, approvalCount.toString(), Color(0xFFB25E00), Modifier.weight(1f))
                        StatPill(RuntimeIconName.Refresh, recoveryCount.toString(), MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }

                // 最新一轮在最上，组内按发生顺序链式展示；轮间以分隔线区隔。
                items(rounds.asReversed()) { round ->
                    RoundSection(round, onNavigateToMessage)
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                if (lifecycle.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.chat_tl_lifecycle),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(lifecycle) { event -> RuntimeEventRow(event) }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleRuntimeSectionCard(
    title: String,
    icon: RuntimeIconName,
    tint: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RuntimeIcon(icon, Modifier.size(16.dp), tint)
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                RuntimeIcon(
                    name = if (expanded) RuntimeIconName.ChevronUp else RuntimeIconName.ChevronDown,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Box(modifier = Modifier.padding(top = 4.dp)) {
                    content()
                }
            }
        }
    }
}

/** 某一轮的完整调用链：模型响应 + 工具执行 + 审批事件。 */
internal class RoundGroup(
    val key: Long,
    val roundNumber: Int,
    var displayIndex: Int = 0,
    val modelId: String?,
    val startedAt: Long,
    var userPromptMessage: top.wanxiang.app.harness.UserMessage? = null,
) {
    val entries = mutableListOf<TimelineEntry>()
}

/** 轮内条目：展开详情时从 [messages] 回查 payload。 */
internal sealed interface TimelineEntry {
    val timestamp: Long

    data class Assistant(
        override val timestamp: Long,
        val entryId: String,
        val inputTokens: Long,
        val outputTokens: Long,
        val message: top.wanxiang.app.harness.AssistantText?,
    ) : TimelineEntry

    data class Tool(
        override val timestamp: Long,
        val callId: String,
        var name: String,
        var settled: HarnessEvent.ToolCallSettled?,
        var callMessage: top.wanxiang.app.harness.ToolCall?,
        var result: top.wanxiang.app.harness.ToolResult?,
    ) : TimelineEntry

    data class Approval(override val timestamp: Long, val toolName: String, val riskLevel: String) : TimelineEntry
}

/** 把扁平事件流切成（轮次组，会话级生命周期）两段；tool call 与 settled 结果就地配对。 */
internal fun buildRoundGroups(
    events: List<HarnessEvent>,
    messages: List<top.wanxiang.app.harness.HarnessMessage>,
): Pair<List<RoundGroup>, List<HarnessEvent>> {
    val toolResultsById = messages.filterIsInstance<top.wanxiang.app.harness.ToolResult>()
        .associateBy { it.toolCallId }
    val callsById = messages.filterIsInstance<top.wanxiang.app.harness.ToolCall>().associateBy { it.id }
    val assistantsById = messages.filterIsInstance<top.wanxiang.app.harness.AssistantText>().associateBy { it.id }
    val userMessages = messages.filterIsInstance<top.wanxiang.app.harness.UserMessage>()

    val rounds = mutableListOf<RoundGroup>()
    val lifecycle = mutableListOf<HarnessEvent>()
    var current: RoundGroup? = null
    val toolIndexByCallId = mutableMapOf<String, Int>()

    fun newGroup(event: HarnessEvent.ProviderRoundStarted) {
        val userMsg = userMessages.filter { it.createdAt <= event.timestamp }.lastOrNull()
            ?: userMessages.getOrNull(event.round)
            ?: userMessages.lastOrNull()
        current = RoundGroup(
            key = event.round.toLong() * 1_000_000_000L + event.timestamp,
            roundNumber = event.round,
            displayIndex = rounds.size + 1,
            modelId = event.modelId,
            startedAt = event.timestamp,
            userPromptMessage = userMsg,
        ).also { rounds += it }
        toolIndexByCallId.clear()
    }

    fun getOrCreateGroup(): RoundGroup {
        return current ?: RoundGroup(
            key = 0L,
            roundNumber = 0,
            displayIndex = rounds.size + 1,
            modelId = null,
            startedAt = System.currentTimeMillis(),
            userPromptMessage = userMessages.lastOrNull(),
        ).also {
            rounds += it
            current = it
        }
    }

    for (event in events) {
        when (event) {
            is HarnessEvent.ProviderRoundStarted -> newGroup(event)
            is HarnessEvent.ProviderRoundSettled -> getOrCreateGroup().entries.add(
                TimelineEntry.Assistant(
                    event.timestamp,
                    event.entryId.orEmpty(),
                    event.inputTokens,
                    event.outputTokens,
                    assistantsById[event.entryId],
                ),
            )
            is HarnessEvent.ToolCallStarted -> {
                val group = getOrCreateGroup()
                val entry = TimelineEntry.Tool(
                    timestamp = event.timestamp,
                    callId = event.toolCallId,
                    name = event.toolName,
                    settled = null,
                    callMessage = callsById[event.toolCallId],
                    result = toolResultsById[event.toolCallId],
                )
                group.entries.add(entry)
                toolIndexByCallId[event.toolCallId] = group.entries.lastIndex
            }
            is HarnessEvent.ToolCallSettled -> {
                val group = getOrCreateGroup()
                val index = toolIndexByCallId[event.toolCallId]
                val entry = (index?.let { group.entries.getOrNull(it) } as? TimelineEntry.Tool)
                    ?: TimelineEntry.Tool(event.timestamp, event.toolCallId, event.toolName, null, callsById[event.toolCallId], toolResultsById[event.toolCallId])
                        .also { group.entries.add(it); toolIndexByCallId[event.toolCallId] = group.entries.lastIndex }
                entry.settled = event
            }
            is HarnessEvent.ApprovalRequested -> getOrCreateGroup().entries.add(
                TimelineEntry.Approval(event.timestamp, event.toolName, event.riskLevel),
            )
            else -> lifecycle += event
        }
    }
    rounds.forEachIndexed { idx, round ->
        round.displayIndex = idx + 1
    }
    return rounds to lifecycle
}

@Composable
private fun RoundSection(
    group: RoundGroup,
    onNavigateToMessage: ((String) -> Unit)? = null,
) {
    val assistantEntries = group.entries.filterIsInstance<TimelineEntry.Assistant>()
    val totalIn = assistantEntries.sumOf { it.inputTokens }
    val totalOut = assistantEntries.sumOf { it.outputTokens }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        ) {
            Text(
                stringResource(R.string.chat_tl_round, if (group.displayIndex > 0) group.displayIndex else (group.roundNumber + 1)),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            group.modelId?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            if (totalIn > 0 || totalOut > 0) {
                Text(
                    "↑$totalIn ↓$totalOut",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        group.userPromptMessage?.let { userMsg ->
            val cleanPrompt = userMsg.text
                .substringBefore("\n\n[附件：")
                .substringBefore("\n\n[Attachment:")
                .trim()
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = onNavigateToMessage != null) {
                        onNavigateToMessage?.invoke(userMsg.id)
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RuntimeIcon(
                        RuntimeIconName.Prompt,
                        Modifier.size(15.dp),
                        MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = cleanPrompt.ifBlank { stringResource(R.string.chat_user_request_clipboard) },
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (onNavigateToMessage != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                stringResource(R.string.chat_navigate_to_message),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            RuntimeIcon(
                                RuntimeIconName.ChevronRight,
                                Modifier.size(13.dp),
                                MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            group.entries.forEachIndexed { index, entry ->
                RoundEntryRow(entry, isLast = index == group.entries.lastIndex)
            }
        }
    }
}

@Composable
private fun RoundEntryRow(entry: TimelineEntry, isLast: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    val visual = when (entry) {
        is TimelineEntry.Assistant -> Triple(RuntimeIconName.Sparkles, Color(0xFF7C4DFF), stringResource(R.string.chat_tl_model_response))
        is TimelineEntry.Approval -> Triple(RuntimeIconName.Shield, Color(0xFFB25E00), entry.toolName)
        is TimelineEntry.Tool -> {
            val settled = entry.settled
            val icon = when {
                settled == null -> RuntimeIconName.Wrench
                settled.success -> RuntimeIconName.Check
                else -> RuntimeIconName.Alert
            }
            val color = when {
                settled == null -> MaterialTheme.colorScheme.tertiary
                settled.success -> Color(0xFF2E7D32)
                else -> MaterialTheme.colorScheme.error
            }
            Triple(icon, color, entry.name)
        }
    }
    val (icon, color, title) = visual
    val detail: String? = when (entry) {
        is TimelineEntry.Assistant -> stringResource(R.string.chat_token_usage, entry.inputTokens, entry.outputTokens)
        is TimelineEntry.Approval -> entry.riskLevel
        is TimelineEntry.Tool -> buildString {
            append(entry.settled?.durationMs?.let(::formatPanelDuration) ?: stringResource(R.string.chat_tl_running))
            entry.callMessage?.args?.get("command")?.let { cmd ->
                append(" · ").append(cmd.toString().replace('\n', ' ').take(80))
            }
        }
    }
    val expandable: Boolean = when (entry) {
        is TimelineEntry.Tool -> true
        is TimelineEntry.Assistant ->
            entry.message != null && (entry.message.text.isNotBlank() || !entry.message.reasoning.isNullOrBlank())
        is TimelineEntry.Approval -> false
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = expandable) { expanded = !expanded },
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 链式节点：圆点图标 + 连接线，未轮条目首尾相接。
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(28.dp).background(color.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
                    RuntimeIcon(icon, Modifier.size(15.dp), color)
                }
                if (!isLast) {
                    Box(Modifier.width(1.dp).height(22.dp).background(MaterialTheme.colorScheme.outlineVariant))
                }
            }
            Column(Modifier.weight(1f).padding(top = 3.dp, bottom = if (isLast) 4.dp else 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatEventTime(entry.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (expandable) {
                RuntimeIcon(
                    if (expanded) RuntimeIconName.ChevronDown else RuntimeIconName.ChevronRight,
                    Modifier.size(14.dp).padding(top = 6.dp),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && expandable,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Box(Modifier.padding(start = 38.dp, bottom = 8.dp)) {
                when (entry) {
                    is TimelineEntry.Tool -> ToolExpandContent(entry)
                    is TimelineEntry.Assistant -> entry.message?.let { AssistantExpandContent(it) }
                    is TimelineEntry.Approval -> Unit
                }
            }
        }
    }
}

@Composable
private fun ToolExpandContent(entry: TimelineEntry.Tool) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entry.callMessage?.let { call ->
            PayloadBox(stringResource(R.string.chat_tl_args), prettyArgs(call.args))
        }
        val result = entry.result
        PayloadBox(
            stringResource(R.string.chat_tl_result),
            result?.output?.ifBlank { stringResource(R.string.chat_result_empty) }
                ?: stringResource(R.string.chat_result_pending),
        )
    }
}

@Composable
private fun AssistantExpandContent(message: top.wanxiang.app.harness.AssistantText) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (message.text.isNotBlank()) {
            PayloadBox(
                stringResource(R.string.chat_tl_response),
                sanitizeModelResponseForDiagnostics(message.text),
            )
        }
        message.reasoning?.takeIf { it.isNotBlank() }?.let {
            PayloadBox(stringResource(R.string.chat_tl_reasoning), it)
        }
    }
}

/** 展开详情中的长文本容器：等宽字体 + 一键复制按钮，彻底避免内部手势拦截与底部抽屉滑动冲突。 */
@Composable
private fun PayloadBox(label: String, text: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            top.wanxiang.app.ui.components.RuntimeIconButton(
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
                    android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(20.dp),
            ) {
                RuntimeIcon(RuntimeIconName.Copy, Modifier.size(12.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp)) {
            Text(
                text = limitDiagnosticText(text),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun StatPill(icon: RuntimeIconName, count: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            RuntimeIcon(icon, Modifier.size(14.dp), color)
            Spacer(Modifier.width(5.dp))
            Text(
                count,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = color,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RuntimeEventRow(event: HarnessEvent) {
    val visual = eventVisual(event)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(30.dp).background(visual.color.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
                RuntimeIcon(visual.icon, Modifier.size(16.dp), visual.color)
            }
            Box(Modifier.width(1.dp).height(34.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Column(Modifier.weight(1f).padding(top = 2.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    visual.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatEventTime(event.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            Text(
                visual.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class EventVisual(val icon: RuntimeIconName, val color: Color, val title: String, val detail: String)

@Composable
private fun eventVisual(event: HarnessEvent): EventVisual = when (event) {
    is HarnessEvent.OperationStarted -> EventVisual(
        RuntimeIconName.Play, MaterialTheme.colorScheme.primary,
        stringResource(R.string.chat_event_operation_started),
        stringResource(R.string.chat_operation_lane, event.laneName),
    )
    is HarnessEvent.OperationFinished -> EventVisual(
        if (event.outcome == "completed") RuntimeIconName.Check else RuntimeIconName.Stop,
        if (event.outcome == "completed") Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
        if (event.outcome == "completed") stringResource(R.string.chat_event_operation_completed) else stringResource(R.string.chat_event_operation_ended),
        event.detail ?: event.outcome,
    )
    is HarnessEvent.ProviderRoundStarted -> EventVisual(
        RuntimeIconName.Brain, Color(0xFF7C4DFF),
        stringResource(R.string.chat_event_provider_round, event.round + 1),
        buildString {
            append(stringResource(R.string.chat_attempt_count, event.attempt + 1))
            event.modelId?.let { append(" · $it") }
        },
    )
    is HarnessEvent.ProviderRoundSettled -> EventVisual(
        RuntimeIconName.Sparkles, Color(0xFF7C4DFF),
        stringResource(R.string.chat_event_provider_settled),
        stringResource(R.string.chat_token_usage, event.inputTokens, event.outputTokens),
    )
    is HarnessEvent.ToolCallStarted -> EventVisual(
        RuntimeIconName.Wrench, MaterialTheme.colorScheme.tertiary,
        stringResource(R.string.chat_event_tool_started, event.toolName),
        stringResource(R.string.chat_event_tool_executing),
    )
    is HarnessEvent.ToolCallSettled -> EventVisual(
        if (event.success) RuntimeIconName.Check else RuntimeIconName.Alert,
        if (event.success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
        if (event.success) stringResource(R.string.chat_event_tool_completed, event.toolName)
        else stringResource(R.string.chat_event_tool_failed, event.toolName),
        event.durationMs?.let { stringResource(R.string.chat_event_duration, formatPanelDuration(it)) }
            ?: stringResource(R.string.chat_event_settled),
    )
    is HarnessEvent.ApprovalRequested -> EventVisual(
        RuntimeIconName.Shield, Color(0xFFB25E00),
        stringResource(R.string.chat_event_approval),
        "${event.toolName} · ${event.riskLevel}",
    )
    is HarnessEvent.RecoveryApplied -> EventVisual(
        RuntimeIconName.Refresh, MaterialTheme.colorScheme.secondary,
        stringResource(R.string.chat_event_recovery),
        event.detail ?: event.outcome,
    )
    is HarnessEvent.PermissionRequired -> EventVisual(
        RuntimeIconName.Shield, Color(0xFFB25E00),
        stringResource(R.string.chat_event_permission_required, event.permission),
        event.reason,
    )
    is HarnessEvent.PlanStepProgress -> EventVisual(
        when (event.status) {
            "COMPLETED" -> RuntimeIconName.Check
            "FAILED" -> RuntimeIconName.Alert
            "RUNNING" -> RuntimeIconName.Play
            else -> RuntimeIconName.Sparkles
        },
        when (event.status) {
            "COMPLETED" -> Color(0xFF2E7D32)
            "FAILED" -> MaterialTheme.colorScheme.error
            "RUNNING" -> MaterialTheme.colorScheme.primary
            else -> Color(0xFF7C4DFF)
        },
        "工序 ${event.stepId} · ${event.title}",
        event.resultSummary ?: "状态: ${event.status}",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueuedPromptStack(
    prompts: List<QueuedPrompt>,
    onEdit: (QueuedPrompt) -> Unit,
    onRemove: (QueuedPrompt) -> Unit,
    onConvertToSteer: ((QueuedPrompt) -> Unit)? = null,
) {
    if (prompts.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        prompts.forEach { prompt ->
            val (label, tint) = when (prompt.queue) {
                PromptQueue.STEER -> stringResource(R.string.chat_label_steer) to Color(0xFF7C4DFF)
                PromptQueue.FOLLOW_UP -> stringResource(R.string.chat_label_follow_up) to MaterialTheme.colorScheme.tertiary
                PromptQueue.NEXT_RUN -> stringResource(R.string.chat_label_queued) to MaterialTheme.colorScheme.secondary
            }
            Surface(color = tint.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MiniBadge(label, tint)
                    Text(
                        prompt.message.text.ifBlank { stringResource(R.string.chat_attachment_message) },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 若当前消息处于排队状态，支持一键转为修正指令
                    if (prompt.queue == PromptQueue.NEXT_RUN && onConvertToSteer != null) {
                        Surface(
                            onClick = { onConvertToSteer(prompt) },
                            color = Color(0xFF7C4DFF).copy(alpha = 0.14f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(0.8.dp, Color(0xFF7C4DFF).copy(alpha = 0.4f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                RuntimeIcon(RuntimeIconName.Tune, Modifier.size(11.dp), Color(0xFF7C4DFF))
                                Text(
                                    stringResource(R.string.chat_label_steer),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                    color = Color(0xFF7C4DFF),
                                )
                            }
                        }
                    }
                    Surface(
                        onClick = { onEdit(prompt) },
                        color = Color.Transparent,
                        shape = CircleShape,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        RuntimeIcon(RuntimeIconName.Edit, Modifier.padding(3.dp).size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(
                        onClick = { onRemove(prompt) },
                        color = Color.Transparent,
                        shape = CircleShape,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        RuntimeIcon(RuntimeIconName.Close, Modifier.padding(3.dp).size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun CreateBranchDialog(messageId: String, onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val defaultName = stringResource(R.string.chat_branch_default_name)
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_create_branch)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.chat_create_branch_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48) },
                    label = { Text(stringResource(R.string.chat_branch_name)) },
                    placeholder = { Text(stringResource(R.string.chat_branch_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(messageId, name.ifBlank { defaultName }) }) { Text(stringResource(R.string.chat_create_and_switch)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } },
    )
}

private fun formatEventTime(timestamp: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
private fun formatPanelDuration(ms: Long): String = if (ms < 1_000) "${ms}ms" else String.format(Locale.getDefault(), "%.1fs", ms / 1_000.0)

private val PAYLOAD_JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }

private fun prettyArgs(args: JsonObject): String =
    runCatching { PAYLOAD_JSON.encodeToString(JsonObject.serializer(), args) }.getOrDefault(args.toString())

internal fun limitDiagnosticText(text: String, maxChars: Int = MAX_DIAGNOSTIC_TEXT_CHARS): String {
    if (text.length <= maxChars) return text
    return text.take(maxChars) + "\n…（内容过长，已截断）"
}

/**
 * Runtime details are diagnostics, not a media transport. Keep the assistant message intact for
 * chat rendering, but never copy a generated image's multi-megabyte Base64 payload into the
 * details panel or clipboard.
 */
internal fun sanitizeModelResponseForDiagnostics(
    text: String,
    maxChars: Int = MAX_DIAGNOSTIC_TEXT_CHARS,
): String {
    val out = StringBuilder(minOf(text.length, maxChars))
    var cursor = 0
    while (cursor < text.length && out.length < maxChars) {
        val dataStart = text.indexOf("data:image/", cursor, ignoreCase = true)
        val jsonKeyStart = text.indexOf("\"b64_json\"", cursor, ignoreCase = true)
        val nextStart = listOf(dataStart, jsonKeyStart).filter { it >= 0 }.minOrNull()
        if (nextStart == null) {
            out.append(text, cursor, minOf(text.length, cursor + (maxChars - out.length)))
            break
        }

        if (nextStart == dataStart) {
            val markdownStart = text.lastIndexOf("![", dataStart).takeIf { start ->
                start >= cursor && text.indexOf("](", start).let { it in start until dataStart }
            }
            val htmlStart = text.lastIndexOf("<img", dataStart, ignoreCase = true).takeIf { it >= cursor }
            val mediaStart = listOfNotNull(markdownStart, htmlStart).maxOrNull() ?: dataStart
            out.append(text, cursor, minOf(mediaStart, cursor + (maxChars - out.length)))
            val mime = text.substring(dataStart + 5, text.indexOf(';', dataStart).takeIf { it > dataStart } ?: dataStart)
                .take(40)
                .ifBlank { "image" }
            if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
            out.append("[图片数据已隐藏：").append(mime).append(']')
            val end = text.indexOfAny(charArrayOf(')', '"', '\'', '>', ' ', '\n', '\r', '\t'), dataStart)
            cursor = if (end >= 0) end + 1 else text.length
        } else {
            out.append(text, cursor, minOf(jsonKeyStart, cursor + (maxChars - out.length)))
            out.append("\"b64_json\":\"[图片 Base64 已隐藏]\"")
            val colon = text.indexOf(':', jsonKeyStart + 10)
            val valueStart = if (colon >= 0) text.indexOf('"', colon + 1) else -1
            val valueEnd = if (valueStart >= 0) text.indexOf('"', valueStart + 1) else -1
            cursor = if (valueEnd >= 0) valueEnd + 1 else text.length
        }
    }
    return limitDiagnosticText(out.toString(), maxChars)
}

private const val MAX_DIAGNOSTIC_TEXT_CHARS = 32_000
private const val DIAGNOSTIC_PREVIEW_LINES = 12
