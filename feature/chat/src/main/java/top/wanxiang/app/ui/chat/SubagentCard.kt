package top.wanxiang.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import top.wanxiang.app.feature.chat.R
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.ToolResult
import top.wanxiang.app.harness.SubagentArgsParser
import top.wanxiang.app.harness.session.ConversationBranch
import top.wanxiang.app.harness.session.ConversationBranchKind
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName

/**
 * Subagent 子智能体任务派发卡片：
 * 可视化展示主智能体派发给子智能体的任务列表、各角色执行状态及输出结论。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubagentCard(
    call: ToolCall,
    result: ToolResult?,
    modifier: Modifier = Modifier,
    subagentBranches: List<ConversationBranch> = emptyList(),
    onOpenSubagent: ((ConversationBranch) -> Unit)? = null,
    onViewDetails: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(result != null) }
    val rotationState by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "arrow_anim")

    val defaultTaskName = stringResource(R.string.chat_subtask)
    val tasks = remember(call.args, defaultTaskName) {
        SubagentArgsParser.parse(call.args, defaultTaskName).map { spec ->
            val routingLabel = spec.role.ifBlank { "${spec.department} · ${spec.agentQuery}" }
            Triple(spec.taskName, routingLabel, spec.prompt)
        }
    }

    val isFinished = result != null
    val isSuccess = result?.success ?: true

    val cardBorderColor = if (isFinished) {
        if (isSuccess) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
    } else {
        Color(0xFFF59E0B).copy(alpha = 0.5f)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(0.6.dp, cardBorderColor.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Brain,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                stringResource(R.string.chat_subagent_delegation),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Surface(
                                color = if (isFinished) {
                                    if (isSuccess) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f)
                                } else Color(0xFFF59E0B).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                Text(
                                    text = stringResource(if (isFinished) (if (isSuccess) R.string.chat_completed else R.string.chat_interrupted) else R.string.chat_parallel_running),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                    color = if (isFinished) (if (isSuccess) Color(0xFF10B981) else Color(0xFFEF4444)) else Color(0xFFF59E0B),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }

                        Text(
                            stringResource(R.string.chat_subtask_count, tasks.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                RuntimeIcon(
                    name = RuntimeIconName.ChevronDown,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotationState),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Task Badges List：FlowRow 自动换行，任务多时不再溢出屏幕
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tasks.forEach { (taskName, role, _) ->
                    val roleKey = role.lowercase()
                    val (roleColor, roleIcon) = when (roleKey) {
                        "researcher" -> Color(0xFF3B82F6) to RuntimeIconName.File
                        "coder" -> Color(0xFF10B981) to RuntimeIconName.Terminal
                        "tester" -> Color(0xFFF59E0B) to RuntimeIconName.Code
                        else -> MaterialTheme.colorScheme.primary to RuntimeIconName.Logs
                    }
                    // 英文角色标识资源化为本地化标签；未识别角色保留原始值
                    val roleLabel = when (roleKey) {
                        "researcher" -> stringResource(R.string.chat_subagent_role_researcher)
                        "coder" -> stringResource(R.string.chat_subagent_role_coder)
                        "tester" -> stringResource(R.string.chat_subagent_role_tester)
                        "assistant" -> stringResource(R.string.chat_subagent_role_assistant)
                        else -> role
                    }
                    // 直接定位该任务对应的子智能体 lane（运行中也可打开看实时进展）
                    val targetBranch = remember(subagentBranches, taskName, role) {
                        matchSubagentBranch(subagentBranches, taskName, role)
                    }
                    val clickable = targetBranch != null && onOpenSubagent != null

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = roleColor.copy(alpha = 0.1f),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, roleColor.copy(alpha = 0.3f)),
                        modifier = Modifier.clickable(enabled = clickable) {
                            targetBranch?.let { onOpenSubagent?.invoke(it) }
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            RuntimeIcon(name = roleIcon, modifier = Modifier.size(12.dp), tint = roleColor)
                            Text(
                                text = "$roleLabel: $taskName",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                color = roleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (clickable) {
                                RuntimeIcon(
                                    name = RuntimeIconName.ChevronRight,
                                    modifier = Modifier.size(11.dp),
                                    tint = roleColor,
                                )
                            }
                        }
                    }
                }
            }

            // Expandable Content
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (result != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    stringResource(R.string.chat_execution_summary),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = result.output,
                                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Text(
                            stringResource(R.string.chat_subagent_running),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }

                    // 入口：打开分支面板里的子智能体 lane，查看完整调研过程与最终成果
                    if (onViewDetails != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onViewDetails) {
                                Text(
                                    stringResource(R.string.chat_subagent_view_details),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                )
                                Spacer(Modifier.width(4.dp))
                                RuntimeIcon(
                                    name = RuntimeIconName.ChevronRight,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 把协同卡片里的某个子任务匹配到它的 lane 分支：
 * lane 名为 subagent:<roleId>:<uuid>，分支名为「任务目标 · roleId」。
 * 精确 role 调用先按 role + 任务名匹配；索引派发尚未在调用参数中解析出 role，退化到任务名。
 */
private fun matchSubagentBranch(
    branches: List<ConversationBranch>,
    taskName: String,
    role: String,
): ConversationBranch? {
    val subs = branches.filter {
        it.kind == ConversationBranchKind.SUBAGENT && !it.laneName.isNullOrBlank()
    }
    if (subs.isEmpty()) return null
    val roleOf: (ConversationBranch) -> String = {
        it.laneName.orEmpty().removePrefix("subagent:").substringBefore(':')
    }
    subs.firstOrNull { branch ->
        roleOf(branch).equals(role, ignoreCase = true) &&
            branch.name.substringBefore(" · ").trim() == taskName.trim()
    }?.let { return it }
    subs.firstOrNull { roleOf(it).equals(role, ignoreCase = true) }?.let { return it }
    // 模型可能用中文角色名派发（lane 里落的是 profile.id），再退化到仅按任务名匹配
    return subs.firstOrNull { it.name.substringBefore(" · ").trim() == taskName.trim() }
}
