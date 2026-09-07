package top.wanxiang.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import top.wanxiang.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import top.wanxiang.app.ui.components.RuntimeSwitch as Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import top.wanxiang.app.feature.chat.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.wanxiang.app.core.model.ApprovalMode
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.core.model.McpConnectionState
import top.wanxiang.app.core.model.AgentSkill
import top.wanxiang.app.core.model.McpServerConfig

/** 底部弹层：能力挂载面板、推理强度滑块、审批模式选择。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillsAndMcpSheet(
    allSkills: List<AgentSkill>,
    mcpServers: List<McpServerConfig>,
    mcpConnectionStates: Map<String, McpConnectionState>,
    pinnedMentionIds: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onToggleSkill: (String, Boolean) -> Unit,
    onToggleMcpServer: (String, Boolean) -> Unit,
    onTogglePin: (String) -> Unit = {},
    onNavigateToSettings: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val activeSkillsCount = remember(allSkills) { allSkills.count { it.isEnabled } }
    val activeMcpCount = remember(mcpServers) { mcpServers.count { it.isEnabled } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 头部标题与统计
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_capabilities),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.chat_capabilities_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_capabilities_enabled, activeSkillsCount + activeMcpCount),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            // 分段标签页
            SecondaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            stringResource(R.string.chat_skills_count, activeSkillsCount, allSkills.size),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                            ),
                        )
                    },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            stringResource(R.string.chat_mcp_count, activeMcpCount, mcpServers.size),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                            ),
                        )
                    },
                )
            }

            // 列表内容展示
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
            ) {
                if (selectedTab == 0) {
                    // Skills 列表
                    if (allSkills.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.chat_no_skills),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(allSkills.size, key = { allSkills[it].id }) { index ->
                                val skill = allSkills[index]
                                val isPinned = skill.id in pinnedMentionIds || skill.name.lowercase() in pinnedMentionIds
                                Surface(
                                    color = if (skill.isEnabled) MaterialTheme.colorScheme.surfaceContainerHigh
                                    else MaterialTheme.colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (skill.isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    text = skill.name,
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontWeight = FontWeight.SemiBold,
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                Surface(
                                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                                    shape = RoundedCornerShape(6.dp),
                                                ) {
                                                    Text(
                                                        text = skill.category,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                        ),
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                    )
                                                }
                                            }
                                            if (skill.description.isNotBlank()) {
                                                Text(
                                                    text = skill.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(top = 2.dp),
                                                )
                                            }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isPinned) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                                border = androidx.compose.foundation.BorderStroke(
                                                    0.8.dp,
                                                    if (isPinned) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                                ),
                                                modifier = Modifier
                                                    .minimumInteractiveComponentSize()
                                                    .clickable { onTogglePin(skill.id) },
                                            ) {
                                                Text(
                                                    if (isPinned) stringResource(R.string.chat_pinned_on) else stringResource(R.string.chat_pinned_off),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 10.sp,
                                                        fontWeight = if (isPinned) FontWeight.Bold else FontWeight.Normal,
                                                    ),
                                                    color = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                )
                                            }

                                            Switch(
                                                checked = skill.isEnabled,
                                                onCheckedChange = { onToggleSkill(skill.id, it) },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // MCP 插件列表
                    if (mcpServers.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.chat_no_mcp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(mcpServers.size, key = { mcpServers[it].id }) { index ->
                                val server = mcpServers[index]
                                val isPinned = server.id in pinnedMentionIds || server.name.lowercase() in pinnedMentionIds
                                val connState = mcpConnectionStates[server.id] ?: McpConnectionState.UNKNOWN
                                Surface(
                                    color = if (server.isEnabled) MaterialTheme.colorScheme.surfaceContainerHigh
                                    else MaterialTheme.colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (server.isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    text = server.name,
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontWeight = FontWeight.SemiBold,
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                // 连通状态点补充文本语义，避免 TalkBack 只感知到一块颜色
                                                val connStateDesc = when {
                                                    !server.isEnabled -> stringResource(R.string.chat_mcp_state_disabled)
                                                    connState == McpConnectionState.CHECKING -> stringResource(R.string.chat_mcp_state_checking)
                                                    connState == McpConnectionState.ONLINE -> stringResource(R.string.chat_mcp_state_online)
                                                    connState == McpConnectionState.OFFLINE -> stringResource(R.string.chat_mcp_state_offline)
                                                    else -> stringResource(R.string.chat_mcp_state_unknown)
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .semantics { contentDescription = connStateDesc }
                                                        .size(9.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            when {
                                                                !server.isEnabled -> MaterialTheme.colorScheme.outlineVariant
                                                                connState == McpConnectionState.CHECKING -> MaterialTheme.colorScheme.tertiary
                                                                connState == McpConnectionState.ONLINE -> Color(0xFF2E7D32)
                                                                connState == McpConnectionState.OFFLINE -> MaterialTheme.colorScheme.error
                                                                else -> MaterialTheme.colorScheme.outline
                                                            }
                                                        ),
                                                )
                                                Surface(
                                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                                    shape = RoundedCornerShape(6.dp),
                                                ) {
                                                    Text(
                                                        text = server.transportType.name,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                        ),
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                    )
                                                }
                                            }
                                            val endpointDesc = if (server.command.isNotBlank()) server.command else server.serverUrl
                                            if (endpointDesc.isNotBlank()) {
                                                Text(
                                                    text = endpointDesc,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp,
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(top = 2.dp),
                                                )
                                            }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isPinned) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                                border = androidx.compose.foundation.BorderStroke(
                                                    0.8.dp,
                                                    if (isPinned) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                                ),
                                                modifier = Modifier
                                                    .minimumInteractiveComponentSize()
                                                    .clickable { onTogglePin(server.id) },
                                            ) {
                                                Text(
                                                    if (isPinned) stringResource(R.string.chat_pinned_on) else stringResource(R.string.chat_pinned_off),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 10.sp,
                                                        fontWeight = if (isPinned) FontWeight.Bold else FontWeight.Normal,
                                                    ),
                                                    color = if (isPinned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                )
                                            }

                                            Switch(
                                                checked = server.isEnabled,
                                                onCheckedChange = { onToggleMcpServer(server.id, it) },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 底部操作区：直达管理
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToSettings),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Settings,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.chat_manage_capabilities),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
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

/**
 * ⚡ 思考/推理强度悬浮调节面板（ChatGPT 同款平滑胶囊滑块）
 */
@Composable
internal fun ReasoningEffortSlider(
    currentMode: String?,
    currentEffort: String?,
    onSelect: (mode: String?, effort: String?) -> Unit,
    onClose: () -> Unit,
) {
    val levels = listOf(
        Triple(stringResource(R.string.chat_reasoning_disabled), stringResource(R.string.chat_reasoning_disabled_description), "disabled" to null),
        Triple(stringResource(R.string.chat_reasoning_low), stringResource(R.string.chat_reasoning_low_description), "enabled" to "low"),
        Triple(stringResource(R.string.chat_reasoning_medium), stringResource(R.string.chat_reasoning_medium_description), "enabled" to "medium"),
        Triple(stringResource(R.string.chat_reasoning_high), stringResource(R.string.chat_reasoning_high_description), "enabled" to "high"),
        Triple(stringResource(R.string.chat_reasoning_extreme), stringResource(R.string.chat_reasoning_extreme_description), "enabled" to "max"),
    )

    val currentIndex = when {
        currentMode == "disabled" -> 0
        currentMode == "enabled" && currentEffort == "low" -> 1
        currentMode == "enabled" && currentEffort == "medium" -> 2
        currentMode == "enabled" && currentEffort == "high" -> 3
        currentMode == "enabled" && (currentEffort == "extreme" || currentEffort == "max") -> 4
        else -> 2 // 默认中推理
    }

    var selectedIndex by remember(currentIndex) { mutableIntStateOf(currentIndex) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val activeLevel = levels.getOrElse(selectedIndex) { levels[2] }

            // 顶部居中大字标题与说明（还原 ChatGPT 样式）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Spacer(Modifier.size(20.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = activeLevel.first,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        ),
                        color = when (selectedIndex) {
                            0 -> MaterialTheme.colorScheme.onSurfaceVariant
                            1 -> Color(0xFF10B981)
                            2 -> Color(0xFF3B82F6)
                            3 -> Color(0xFF8B5CF6)
                            4 -> Color(0xFFEC4899)
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                    Text(
                        text = activeLevel.second,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    contentDescription = stringResource(R.string.chat_close),
                ) {
                    RuntimeIcon(RuntimeIconName.Close, Modifier.size(13.dp), MaterialTheme.colorScheme.outline)
                }
            }

            // 现代化连续胶囊滑动条 (Smooth Segmented Slider)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    levels.indices.forEach { index ->
                        val isSelected = index == selectedIndex
                        val itemColor = when (index) {
                            0 -> MaterialTheme.colorScheme.onSurface
                            1 -> Color(0xFF10B981)
                            2 -> Color(0xFF3B82F6)
                            3 -> Color(0xFF8B5CF6)
                            4 -> Color(0xFFEC4899)
                            else -> MaterialTheme.colorScheme.primary
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    if (isSelected) {
                                        when (index) {
                                            0 -> MaterialTheme.colorScheme.surfaceContainer
                                            1 -> Color(0xFF10B981).copy(alpha = 0.25f)
                                            2 -> Color(0xFF3B82F6).copy(alpha = 0.25f)
                                            3 -> Color(0xFF8B5CF6).copy(alpha = 0.25f)
                                            4 -> Color(0xFFEC4899).copy(alpha = 0.25f)
                                            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                        }
                                    } else Color.Transparent,
                                )
                                .clickable {
                                    selectedIndex = index
                                    val (mode, effort) = levels[index].third
                                    onSelect(mode, effort)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = when (index) {
                                    0 -> stringResource(R.string.chat_depth_off)
                                    1 -> stringResource(R.string.chat_depth_light)
                                    2 -> stringResource(R.string.chat_depth_medium)
                                    3 -> stringResource(R.string.chat_depth_deep)
                                    4 -> stringResource(R.string.chat_depth_extreme)
                                    else -> ""
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.5.sp,
                                ),
                                color = if (isSelected) itemColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            )
                        }
                    }
                }
            }

            // 💡 说明提示：必须模型支持才生效
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RuntimeIcon(
                        name = RuntimeIconName.Info,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.chat_reasoning_model_support_notice),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 审批模式选择底部弹层（完全访问 / 询问 / 每次审批）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApprovalModeSheet(
    currentApprovalMode: ApprovalMode,
    onSelect: (ApprovalMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.chat_menu_approval_mode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(
                    onClick = onDismiss,
                    contentDescription = stringResource(R.string.chat_close),
                ) {
                    RuntimeIcon(RuntimeIconName.Close, Modifier.size(20.dp))
                }
            }

            Text(
                text = stringResource(R.string.chat_approval_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            listOf(
                Triple(ApprovalMode.REQUEST, stringResource(R.string.chat_approval_request_title), stringResource(R.string.chat_approval_request_description)),
                Triple(ApprovalMode.ASSISTED, stringResource(R.string.chat_approval_assisted_title), stringResource(R.string.chat_approval_assisted_description)),
                Triple(ApprovalMode.FULL_ACCESS, stringResource(R.string.chat_approval_full_access_title), stringResource(R.string.chat_approval_full_access_description)),
            ).forEach { (mode, title, desc) ->
                val isSelected = mode == currentApprovalMode
                Surface(
                    onClick = { onSelect(mode) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RuntimeIcon(
                            name = if (isSelected) RuntimeIconName.Check else RuntimeIconName.Shield,
                            modifier = Modifier.size(22.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

