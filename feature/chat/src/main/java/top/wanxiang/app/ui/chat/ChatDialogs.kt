package top.wanxiang.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import top.wanxiang.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import top.wanxiang.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import top.wanxiang.app.feature.chat.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.wanxiang.app.core.database.AiModelEntity
import top.wanxiang.app.core.database.HarnessSessionEntity
import top.wanxiang.app.runtime.WorkspaceProject
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.core.model.SessionRunState
import top.wanxiang.app.runtime.ProjectType
import top.wanxiang.app.ui.components.ProviderBadge

/** 会话 / 模型 / 新建会话对话框。 */
@Composable
internal fun EditAndResendDialog(
    originalText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(originalText) { mutableStateOf(originalText) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_edit_resend), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.chat_user_request_clipboard)) },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.chat_resend_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.chat_send), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
        },
    )
}

@Composable
internal fun SessionsDialog(
    sessions: List<HarnessSessionEntity>,
    currentSessionId: String,
    sessionRunStates: Map<String, SessionRunState>,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
) {
    // 重命名 / 删除目标只保存 id，旋转后可从 sessions 恢复
    var renameTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeIcon(RuntimeIconName.List, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.chat_session_manager), fontWeight = FontWeight.Bold)
                }
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        stringResource(R.string.chat_session_count, sessions.size),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (sessions.size == 1) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Alert, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.chat_last_session_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions.size, key = { sessions[it].id }) { index ->
                        val session = sessions[index]
                        val isCurrent = session.id == currentSessionId
                        val runState = sessionRunStates[session.id] ?: SessionRunState.IDLE

                        val (dotColor, stateLabel) = when (runState) {
                            SessionRunState.RUNNING -> Color(0xFFF59E0B) to stringResource(R.string.chat_state_running)
                            SessionRunState.WAITING_APPROVAL -> Color(0xFF8B5CF6) to stringResource(R.string.chat_state_approval)
                            SessionRunState.FAILED -> Color(0xFFEF4444) to stringResource(R.string.chat_state_failed)
                            SessionRunState.COMPLETED -> Color(0xFF10B981) to stringResource(R.string.chat_state_completed)
                            SessionRunState.IDLE -> Color(0xFF10B981) to stringResource(R.string.chat_state_ready)
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCurrent) MaterialTheme.colorScheme.surfaceContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = androidx.compose.foundation.BorderStroke(
                                if (isCurrent) 1.5.dp else 1.dp,
                                if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSwitch(session.id) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // 状态指示圆点（绿色完成/橙色进行中/红色失败）
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(dotColor),
                                )

                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            session.title,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (isCurrent) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = RoundedCornerShape(4.dp),
                                            ) {
                                                Text(
                                                    stringResource(R.string.chat_current),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = dotColor.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp),
                                        ) {
                                            Text(
                                                stateLabel,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                ),
                                                color = dotColor,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            )
                                        }

                                        if (session.workspace.isNotBlank()) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                shape = RoundedCornerShape(4.dp),
                                            ) {
                                                Text(
                                                    session.workspace,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        } else {
                                            Text(
                                                stringResource(R.string.chat_isolated_sandbox),
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                IconButton(
                                    onClick = { renameTargetId = session.id },
                                    modifier = Modifier.minimumInteractiveComponentSize(),
                                    contentDescription = stringResource(R.string.chat_rename_session_button),
                                ) {
                                    RuntimeIcon(RuntimeIconName.Settings, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(
                                    onClick = { deleteTargetId = session.id },
                                    modifier = Modifier.minimumInteractiveComponentSize(),
                                    contentDescription = stringResource(R.string.chat_delete_session),
                                ) {
                                    RuntimeIcon(RuntimeIconName.Trash, Modifier.size(15.dp), MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onNew, shape = RoundedCornerShape(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RuntimeIcon(RuntimeIconName.Plus, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimary)
                    Text(stringResource(R.string.chat_new_session))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_close)) }
        },
    )

    val renameTarget = renameTargetId?.let { id -> sessions.firstOrNull { it.id == id } }
    renameTarget?.let { target ->
        RenameSessionDialog(
            currentTitle = target.title,
            onDismiss = { renameTargetId = null },
            onRename = { title ->
                onRename(target.id, title)
                renameTargetId = null
            },
        )
    }

    // 删除会话二次确认（运行中会话也提示，避免误删不可恢复）
    deleteTargetId?.let { targetId ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteTargetId = null },
            title = { Text(stringResource(R.string.chat_delete_session_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.chat_delete_session_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTargetId = null
                        onDelete(targetId)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                    ),
                ) {
                    Text(stringResource(R.string.chat_confirm_delete), color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetId = null }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }
}

@Composable
internal fun RenameSessionDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var title by remember(currentTitle) { mutableStateOf(currentTitle) }
    val titleBlank = title.isBlank()
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rename_session), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.chat_title_label)) },
                singleLine = true,
                isError = titleBlank,
                supportingText = {
                    if (titleBlank) {
                        Text(
                            stringResource(R.string.chat_title_required),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(title) },
                enabled = !titleBlank,
            ) { Text(stringResource(R.string.chat_save), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } },
    )
}

@Composable
internal fun ModelDialog(
    models: List<AiModelEntity>,
    selectedProfileId: String?,
    selectedModelVariant: String?,
    providerModelIds: List<String>,
    discoveringProviderModels: Boolean,
    providerModelDiscoveryError: String?,
    modelPickerProfileId: String?,
    onDismiss: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onSelectSubModel: (profileId: String, subModel: String) -> Unit,
    onOpenModelPicker: (String) -> Unit,
    onCloseModelPicker: () -> Unit,
    onRefreshModels: (String) -> Unit,
    onSwitchModel: (profileId: String, modelId: String) -> Unit,
    onAdd: (String, String, String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var showAdd by rememberSaveable { mutableStateOf(false) }
    // 删除模型档案为破坏性操作，先记录目标 id 并经确认对话框
    var deleteModelTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    if (showAdd) {
        AddModelDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, provider, model, baseUrl ->
                onAdd(name, provider, model, baseUrl)
                showAdd = false
            },
        )
    }
    val pickingProfile = modelPickerProfileId?.let { id -> models.firstOrNull { it.id == id } }
    if (pickingProfile != null) {
        ProviderModelPickerDialog(
            profile = pickingProfile,
            selectedModelVariant = selectedModelVariant.takeIf { pickingProfile.id == selectedProfileId },
            modelIds = providerModelIds,
            discovering = discoveringProviderModels,
            error = providerModelDiscoveryError,
            onDismiss = onCloseModelPicker,
            onRefresh = { onRefreshModels(pickingProfile.id) },
            onSelect = { modelId -> onSwitchModel(pickingProfile.id, modelId) },
        )
        return
    }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_select_model), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.chat_select_provider_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (models.isEmpty()) {
                    Text(
                        stringResource(R.string.chat_no_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(models, key = { it.id }) { model ->
                    val subModels = model.model.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(model.model) }
                    val isProfileSelected = model.id == selectedProfileId
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (isProfileSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                MaterialTheme.shapes.small,
                            )
                            .border(
                                1.dp,
                                if (isProfileSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.Transparent,
                                MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelectProfile(model.id) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ProviderBadge(
                                providerIdOrName = model.provider,
                                size = 26.dp,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    model.name.ifBlank { model.provider },
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    model.provider,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (isProfileSelected) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.chat_current),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            IconButton(
                                onClick = { deleteModelTargetId = model.id },
                                modifier = Modifier.minimumInteractiveComponentSize(),
                                contentDescription = stringResource(R.string.chat_delete_model_profile),
                            ) {
                                RuntimeIcon(RuntimeIconName.Trash, Modifier.size(15.dp), MaterialTheme.colorScheme.error)
                            }
                        }

                        if (subModels.size > 1) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                subModels.forEach { subModel ->
                                    val isSubActive = isProfileSelected && subModel == selectedModelVariant
                                    Surface(
                                        color = if (isSubActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        shape = RoundedCornerShape(8.dp),
                                        border = if (isSubActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                                        modifier = Modifier.clickable {
                                            onSelectSubModel(model.id, subModel)
                                            onDismiss()
                                        },
                                    ) {
                                        Text(
                                            subModel,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = if (isSubActive) FontWeight.Bold else FontWeight.Normal,
                                            ),
                                            color = if (isSubActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (subModels.size <= 1) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        model.model.ifBlank { stringResource(R.string.chat_model_not_set) },
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    )
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            Surface(
                                onClick = { onOpenModelPicker(model.id) },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    RuntimeIcon(RuntimeIconName.Tune, Modifier.size(14.dp), MaterialTheme.colorScheme.onSecondaryContainer)
                                    Text(
                                        stringResource(R.string.chat_switch_model),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showAdd = true }) { Text(stringResource(R.string.chat_add_model), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_close)) }
        },
    )

    // 删除模型档案二次确认
    deleteModelTargetId?.let { targetId ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteModelTargetId = null },
            title = { Text(stringResource(R.string.chat_delete_model_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(R.string.chat_delete_model_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteModelTargetId = null
                        onDelete(targetId)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                    ),
                ) {
                    Text(stringResource(R.string.chat_confirm_delete), color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteModelTargetId = null }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }
}

@Composable
internal fun ProviderModelPickerDialog(
    profile: AiModelEntity,
    selectedModelVariant: String?,
    modelIds: List<String>,
    discovering: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var manualId by remember(profile.id, profile.model, selectedModelVariant) {
        mutableStateOf(selectedModelVariant ?: profile.model.substringBefore(',').trim())
    }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.chat_switch_model_title), fontWeight = FontWeight.Bold)
                Text(
                    profile.name.ifBlank { profile.provider },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.chat_switch_model_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    discovering -> {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.chat_model_discovering),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    error != null && modelIds.isEmpty() -> {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    modelIds.isNotEmpty() -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(modelIds) { modelId ->
                                val selected = modelId == selectedModelVariant
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            else MaterialTheme.colorScheme.surfaceContainerLow,
                                        )
                                        .clickable { onSelect(modelId) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        modelId,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (selected) {
                                        RuntimeIcon(RuntimeIconName.Check, Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
                if (!error.isNullOrBlank() && modelIds.isNotEmpty()) {
                    Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = manualId,
                    onValueChange = { manualId = it },
                    label = { Text(stringResource(R.string.chat_model_id)) },
                    placeholder = { Text("gpt-4o / deepseek-chat") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRefresh, enabled = !discovering) {
                    Text(stringResource(R.string.chat_refresh_models), color = MaterialTheme.colorScheme.primary)
                }
                TextButton(
                    onClick = { onSelect(manualId) },
                    enabled = manualId.isNotBlank(),
                ) {
                    Text(stringResource(R.string.chat_apply_model), color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
        },
    )
}

@Composable
internal fun AddModelDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val defaultProvider = stringResource(R.string.chat_custom_provider)
    var provider by rememberSaveable { mutableStateOf(defaultProvider) }
    var model by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    // baseUrl 基础格式校验：必须以 http:// 或 https:// 开头（留空走供应商默认端点）
    val baseUrlInvalid = baseUrl.isNotBlank() && !baseUrl.trim().startsWith("http://") && !baseUrl.trim().startsWith("https://")
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_add_model), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.chat_optional_name)) }, singleLine = true)
                OutlinedTextField(value = provider, onValueChange = { provider = it }, label = { Text(stringResource(R.string.chat_provider_label)) }, singleLine = true)
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.chat_optional_base_url)) },
                    placeholder = { Text("https://api.openai.com/v1") },
                    isError = baseUrlInvalid,
                    supportingText = {
                        if (baseUrlInvalid) {
                            Text(
                                stringResource(R.string.chat_base_url_invalid),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    singleLine = true,
                )
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text(stringResource(R.string.chat_model_id)) }, placeholder = { Text("deepseek-chat / gpt-4o") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, provider, model, baseUrl) },
                enabled = model.isNotBlank() && !baseUrlInvalid,
            ) { Text(stringResource(R.string.chat_confirm_add), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
        },
    )
}

@Composable
internal fun NewSessionDialog(
    workspaces: List<WorkspaceProject>,
    onDismiss: () -> Unit,
    onCreate: (title: String, workspace: String, projectType: ProjectType) -> Unit,
) {
    val defaultTitle = stringResource(R.string.chat_new_session)
    // 表单状态旋转后保留；枚举以 name 字符串保存
    var title by rememberSaveable { mutableStateOf(defaultTitle) }
    var selected by rememberSaveable { mutableStateOf("") }
    var selectedTypeName by rememberSaveable { mutableStateOf(ProjectType.GENERAL.name) }
    val selectedType = ProjectType.valueOf(selectedTypeName)
    var typeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val quickTags = listOf(
        defaultTitle,
        stringResource(R.string.chat_quick_bug),
        stringResource(R.string.chat_quick_feature),
        stringResource(R.string.chat_quick_environment),
        stringResource(R.string.chat_quick_refactor),
    )

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Plus, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.chat_new_agent_session), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.chat_session_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text(stringResource(R.string.chat_session_name_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                // 快捷预设标题标签
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickTags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (title == tag) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (title == tag) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.Transparent,
                            ),
                            modifier = Modifier.clickable { title = tag },
                        ) {
                            Text(
                                tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (title == tag) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    stringResource(R.string.chat_link_workspace),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        WorkspaceOption(stringResource(R.string.chat_no_workspace), "/root", selected == "", onSelect = {
                            selected = ""
                            selectedTypeName = ProjectType.GENERAL.name
                        })
                    }
                    items(workspaces.size, key = { workspaces[it].linuxPath }) { index ->
                        val ws = workspaces[index]
                        WorkspaceOption(ws.name, ws.linuxPath, selected == ws.linuxPath) {
                            selected = ws.linuxPath
                            selectedTypeName = ws.projectType.name
                        }
                    }
                }

                val selectedProject = workspaces.firstOrNull { it.linuxPath == selected }
                // Keep the detected type as the default, but allow an imported
                // repository to be assigned to a different specialist Agent.
                Text(
                    if (selectedProject == null || selectedProject.projectType == ProjectType.GENERAL) {
                        stringResource(R.string.chat_project_type_manual)
                    } else {
                        stringResource(R.string.chat_project_type_detected)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Box {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().clickable { typeMenuExpanded = true },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                selectedType.displayName,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(stringResource(R.string.chat_select), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    DropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                        ProjectType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.displayName) },
                                onClick = {
                                    selectedTypeName = type.name
                                    typeMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(title.ifBlank { defaultTitle }, selected, selectedType) },
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(stringResource(R.string.chat_create_session))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
        },
    )
}

@Composable
internal fun WorkspaceOption(name: String, path: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(12.dp).clip(CircleShape).background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        Column {
            Text(name.ifBlank { path }, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal))
            if (path.isNotBlank()) {
                Text(path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

