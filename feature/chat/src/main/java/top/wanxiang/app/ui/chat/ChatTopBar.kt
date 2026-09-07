package top.wanxiang.app.ui.chat

import android.app.Activity
import android.provider.Settings
import top.wanxiang.app.ui.chat.floating.FloatingChatService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import top.wanxiang.app.ui.components.WanXiangBrandBadge
import top.wanxiang.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import top.wanxiang.app.feature.chat.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.wanxiang.app.core.database.AiModelEntity
import top.wanxiang.app.core.model.ApprovalMode
import top.wanxiang.app.harness.session.ConversationBranch
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.harness.events.HarnessEvent

/**
 * 顶部栏：品牌标题/工作区状态行 + 工作台工具条（模型·审批·分支·运行）。
 */
@Composable
internal fun ChatTopBar(
    workspace: String,
    distroDisplayName: String,
    activeModel: AiModelEntity?,
    approvalMode: ApprovalMode,
    currentBranch: ConversationBranch?,
    runtimeEvents: List<HarnessEvent>,
    running: Boolean,
    onShowFloatingPermissionDialog: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenApprovalModes: () -> Unit,
    onOpenBranches: () -> Unit,
    onOpenRuntime: () -> Unit,
    onOpenBrowser: (() -> Unit)? = null,
    browserHighlight: Boolean = false,
    onOpenGit: () -> Unit = {},
    gitUncommittedCount: Int = 0,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        // 第 1 行：品牌 Badge + 标题/工作区 + 右侧模型胶囊与操作按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 左侧：品牌 Badge + 标题/状态
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                WanXiangBrandBadge(28.dp)
                Column {
                    Text(
                        text = stringResource(R.string.chat_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.chat_status,
                            if (workspace.isNotBlank()) workspace else stringResource(R.string.chat_default_workspace),
                            distroDisplayName,
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 右侧：Git + 小窗 + 会话抽屉
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 🌟 0. Git 面板（绑定当前会话，不跨会话共享）
                androidx.compose.foundation.layout.Box {
                    IconButton(
                        onClick = onOpenGit,
                        contentDescription = stringResource(R.string.chat_open_git),
                    ) {
                        RuntimeIcon(RuntimeIconName.GitBranch, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (gitUncommittedCount > 0) {
                        val badgeText = if (gitUncommittedCount > 99) "99+" else gitUncommittedCount.toString()
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .align(androidx.compose.ui.Alignment.TopEnd)
                                .padding(top = 4.dp, end = 4.dp)
                                .background(MaterialTheme.colorScheme.error, androidx.compose.foundation.shape.CircleShape)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                badgeText,
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // 🌟 1. 智枢悬浮小窗收起按钮 (Collapse to Floating Window)
                IconButton(
                    onClick = {
                        if (Settings.canDrawOverlays(context)) {
                            FloatingChatService.start(context)
                            (context as? Activity)?.moveTaskToBack(true)
                        } else {
                            onShowFloatingPermissionDialog()
                        }
                    },
                    contentDescription = stringResource(R.string.chat_floating_collapse),
                ) {
                    RuntimeIcon(RuntimeIconName.OpenInNew, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                }

                // 🌟 2. 会话抽屉/列表（内部包含「新建会话」功能）
                IconButton(
                    onClick = onOpenSessions,
                    contentDescription = stringResource(R.string.chat_open_session_list),
                ) {
                    RuntimeIcon(RuntimeIconName.List, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 第 2 行：全屏极薄无边框矩形 Dev Toolbar (模型 · 审批 · 分支 · 运行)
        CollapsibleChatWorkbenchStrip(
            activeModel = activeModel,
            approvalMode = approvalMode,
            currentBranch = currentBranch,
            runtimeEvents = runtimeEvents,
            running = running,
            onOpenModels = onOpenModels,
            onOpenApprovalModes = onOpenApprovalModes,
            onOpenBranches = onOpenBranches,
            onOpenRuntime = onOpenRuntime,
            onOpenBrowser = onOpenBrowser,
            browserHighlight = browserHighlight,
        )
    }
}
