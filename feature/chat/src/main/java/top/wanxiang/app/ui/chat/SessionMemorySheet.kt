package top.wanxiang.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.wanxiang.app.core.database.AgentMemoryEntity
import top.wanxiang.app.core.database.AgentScratchpadEntity
import top.wanxiang.app.feature.chat.R
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName

/**
 * 会话工作记忆抽屉：长期记忆（memory 工具写入的 AgentMemoryEntity，全量观察流）
 * 与任务草稿便签（scratchpad 工具写入的 AgentScratchpadEntity）的管理入口——
 * 模型记了什么、存了什么草稿，用户可查、可删。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionMemorySheet(
    memories: List<AgentMemoryEntity>,
    scratchpads: List<AgentScratchpadEntity>,
    onDeleteMemory: (String) -> Unit,
    onDeleteScratchpad: (String) -> Unit,
    onClearScratchpads: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    // 破坏性操作待确认目标：非 null 时弹出二次确认
    var confirmTarget by rememberSaveable { mutableStateOf<String?>(null) } // "clear" / "memory:<id>"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Brain, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.chat_memory_sheet_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // —— 长期记忆 ——
                item(key = "header_memories") {
                    SectionHeader(
                        icon = RuntimeIconName.Brain,
                        title = stringResource(R.string.chat_memory_section, memories.size),
                    )
                }
                if (memories.isEmpty()) {
                    item(key = "memories_empty") {
                        EmptyHint(stringResource(R.string.chat_memory_empty))
                    }
                } else {
                    items(memories.size, key = { memories[it].id }) { index ->
                        val memory = memories[index]
                        MemoryRow(
                            scope = memory.scope,
                            kind = memory.kind,
                            keyText = memory.key,
                            value = memory.value,
                            onDelete = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                confirmTarget = "memory:${memory.id}"
                            },
                        )
                    }
                }

                // —— 任务草稿 ——
                item(key = "header_scratchpads") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SectionHeader(
                            icon = RuntimeIconName.Copy,
                            title = stringResource(R.string.chat_scratchpad_section, scratchpads.size),
                            modifier = Modifier.weight(1f),
                        )
                        if (scratchpads.isNotEmpty()) {
                            TextButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                confirmTarget = "clear"
                            }) {
                                Text(
                                    stringResource(R.string.chat_scratchpad_clear),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                if (scratchpads.isEmpty()) {
                    item(key = "scratchpads_empty") {
                        EmptyHint(stringResource(R.string.chat_scratchpad_empty))
                    }
                } else {
                    items(scratchpads.size, key = { scratchpads[it].key }) { index ->
                        val pad = scratchpads[index]
                        MemoryRow(
                            scope = null,
                            kind = null,
                            keyText = pad.key,
                            value = pad.value,
                            onDelete = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onDeleteScratchpad(pad.key)
                            },
                        )
                    }
                }

                item(key = "sheet_bottom_spacer") {
                    Spacer(Modifier.size(24.dp))
                }
            }
        }
    }

    // 破坏性操作二次确认（清空草稿 / 删除单条记忆），样式对齐 DistroManagementScreen 的确认弹窗
    confirmTarget?.let { target ->
        val isClear = target == "clear"
        RuntimeAlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = {
                Text(
                    text = stringResource(
                        if (isClear) R.string.chat_clear_scratchpad_title else R.string.chat_delete_memory_title,
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            },
            text = {
                Text(
                    text = stringResource(
                        if (isClear) R.string.chat_clear_scratchpad_message else R.string.chat_delete_memory_message,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isClear) {
                            onClearScratchpads()
                        } else {
                            onDeleteMemory(target.removePrefix("memory:"))
                        }
                        confirmTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(
                        text = stringResource(if (isClear) R.string.chat_confirm_clear else R.string.chat_confirm_delete),
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) {
                    Text(text = stringResource(R.string.chat_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(
    icon: RuntimeIconName,
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        RuntimeIcon(icon, Modifier.size(15.dp), MaterialTheme.colorScheme.primary)
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun MemoryRow(
    scope: String?,
    kind: String?,
    keyText: String,
    value: String,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (scope != null) {
                        Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp)) {
                            Text(
                                scope,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (kind != null) {
                        Text(
                            kind,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        keyText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    RuntimeIcon(
                        RuntimeIconName.Close,
                        Modifier.size(16.dp),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
