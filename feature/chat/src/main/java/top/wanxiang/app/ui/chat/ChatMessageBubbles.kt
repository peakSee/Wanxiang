package top.wanxiang.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import top.wanxiang.app.ui.components.RuntimeAlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.LruCache
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import top.wanxiang.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import top.wanxiang.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import top.wanxiang.app.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import top.wanxiang.app.feature.chat.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.CapabilityEvent
import top.wanxiang.app.harness.UserMessage
import top.wanxiang.app.harness.checkpoint.RewindScope
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/** 消息气泡：用户/助手气泡、思考块、任务计划卡、能力事件卡。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserBubble(
    message: UserMessage,
    knownMentionNames: List<String> = emptyList(),
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCreateBranch: () -> Unit,
    onRewind: (RewindScope) -> Unit = {},
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    // 删除消息为破坏性操作，先经确认对话框
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    // 撤回到此轮：先选择撤回范围（代码/对话/两者）
    var showRewindScopeDialog by rememberSaveable { mutableStateOf(false) }

    val copyText = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.chat_user_request_clipboard), message.text))
        Toast.makeText(context, context.getString(R.string.chat_request_copied), Toast.LENGTH_SHORT).show()
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 🌟 1. 多模态图片预览卡片
            if (message.imageUrls.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 2.dp),
                ) {
                    itemsIndexed(
                        items = message.imageUrls,
                        key = { index, _ -> "${message.id}:$index" },
                    ) { index, imageUrl ->
                        ImageThumbnail(
                            imageUrl = imageUrl,
                            cacheKey = "chat-image-${message.id}-$index",
                            onClick = { showMenu = true },
                        )
                    }
                }
            }

            // 🌟 2. 用户文字气泡
            if (message.text.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .clickable { showMenu = true },
                ) {
                    val mentionColor = MaterialTheme.colorScheme.primary
                    val mentionBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    val annotatedText = remember(message.text, knownMentionNames, mentionColor, mentionBg) {
                        formatMentionText(message.text, knownMentionNames, mentionColor, mentionBg)
                    }
                    Text(
                        text = annotatedText,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            } else if (message.imageUrls.isNotEmpty()) {
                // 纯图片消息：显示小菜单按钮
                Surface(
                    onClick = { showMenu = true },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape,
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        RuntimeIcon(
                            RuntimeIconName.More,
                            Modifier.size(15.dp),
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_copy)) },
                leadingIcon = { RuntimeIcon(RuntimeIconName.Copy, Modifier.size(16.dp)) },
                onClick = {
                    showMenu = false
                    copyText()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_edit_resend)) },
                leadingIcon = { RuntimeIcon(RuntimeIconName.Edit, Modifier.size(16.dp)) },
                onClick = {
                    showMenu = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_create_branch)) },
                leadingIcon = { RuntimeIcon(RuntimeIconName.Hub, Modifier.size(16.dp)) },
                onClick = {
                    showMenu = false
                    onCreateBranch()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_rewind)) },
                leadingIcon = { RuntimeIcon(RuntimeIconName.Reverse, Modifier.size(16.dp)) },
                onClick = {
                    showMenu = false
                    showRewindScopeDialog = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    RuntimeIcon(
                        RuntimeIconName.Trash,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    showMenu = false
                    showDeleteConfirm = true
                },
            )
        }
    }

    // 删除消息二次确认
    if (showDeleteConfirm) {
        RuntimeAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.chat_delete_message_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(R.string.chat_delete_message_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.chat_confirm_delete), color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }

    // 撤回范围选择：仅代码 / 仅对话 / 两者
    if (showRewindScopeDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showRewindScopeDialog = false },
            title = { Text(stringResource(R.string.chat_rewind_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.chat_rewind_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RewindScopeOption(
                        label = stringResource(R.string.chat_rewind_code),
                        description = stringResource(R.string.chat_rewind_code_desc),
                        icon = RuntimeIconName.Code,
                        onClick = { showRewindScopeDialog = false; onRewind(RewindScope.CODE) },
                    )
                    RewindScopeOption(
                        label = stringResource(R.string.chat_rewind_conversation),
                        description = stringResource(R.string.chat_rewind_conversation_desc),
                        icon = RuntimeIconName.Chat,
                        onClick = { showRewindScopeDialog = false; onRewind(RewindScope.CONVERSATION) },
                    )
                    RewindScopeOption(
                        label = stringResource(R.string.chat_rewind_both),
                        description = stringResource(R.string.chat_rewind_both_desc),
                        icon = RuntimeIconName.Reverse,
                        onClick = { showRewindScopeDialog = false; onRewind(RewindScope.BOTH) },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showRewindScopeDialog = false }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }
}

/** 撤回范围选择行：图标 + 标题 + 说明，整行可点。 */
@Composable
private fun RewindScopeOption(
    label: String,
    description: String,
    icon: RuntimeIconName,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RuntimeIcon(icon, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 🌟 多模态图片优雅缩略图卡片（支持 Base64 Data URL 与本地文件路径）。
 *
 * Base64 解码不能放在 remember 里：remember 的计算发生在 Compose 主线程，历史大图会
 * 直接阻塞切换智枢的首帧。交给 Coil 后，DataUriFetcher/图片解码在后台执行，并按缩略图
 * 的实际尺寸采样；稳定的 cacheKey 也让 Navigation3 重组页面时直接命中内存缓存。
 */
@Composable
internal fun ImageThumbnail(
    imageUrl: String,
    cacheKey: String,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val thumbnailPx = with(LocalDensity.current) { 130.dp.roundToPx() }
    val request = remember(imageUrl, cacheKey, thumbnailPx) {
        val data: Any = when {
            imageUrl.startsWith("file://") -> java.io.File(imageUrl.removePrefix("file://"))
            imageUrl.startsWith("/") -> java.io.File(imageUrl)
            else -> imageUrl
        }
        ImageRequest.Builder(context)
            .data(data)
            .size(thumbnailPx, thumbnailPx)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = stringResource(R.string.chat_user_image),
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(width = 130.dp, height = 130.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                RoundedCornerShape(12.dp),
            )
            // 与文字气泡一致的点击语义：弹出操作菜单
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

@Composable
internal fun AssistantBubble(
    message: AssistantText,
    defaultExpanded: Boolean,
    live: Boolean = false,
    showRegenerate: Boolean = false,
    onRegenerate: () -> Unit = {},
    onCreateBranch: () -> Unit = {},
) {
    val reasoning = message.reasoning
    val context = LocalContext.current
    val generatedImagePayload = remember(message.id, message.text.length) {
        val cacheKey = "${message.id}:${message.text.length}"
        generatedImageFlagCache.get(cacheKey)
            ?: message.text.contains("data:image/", ignoreCase = true).also { found ->
                generatedImageFlagCache.put(cacheKey, found)
            }
    }

    val copyAll = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.chat_ai_response_clipboard), message.text))
        Toast.makeText(context, context.getString(R.string.chat_response_copied), Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (!reasoning.isNullOrBlank()) {
            ThinkingBlock(
                id = message.id,
                reasoning = reasoning,
                defaultExpanded = defaultExpanded,
                live = live,
            )
        }

        if (!live && !generatedImagePayload) {
            val planSteps = remember(message.text) { extractTaskPlanSteps(message.text) }
            if (planSteps.size >= 2) {
                TaskPlanCard(steps = planSteps)
            }
        }

        if (message.text.isNotBlank()) {
            if (live) {
                val liveImageDataStart = remember(message.text) {
                    message.text.indexOf("data:image/", ignoreCase = true)
                }
                if (liveImageDataStart >= 0) {
                    val humanPrefix = remember(message.text, liveImageDataStart) {
                        val markdownStart = message.text.lastIndexOf("![", liveImageDataStart)
                            .takeIf { it >= 0 } ?: liveImageDataStart
                        message.text.substring(0, markdownStart).trim().take(8_000)
                    }
                    if (humanPrefix.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                text = humanPrefix,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.size(width = 260.dp, height = 180.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                        ) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                            Text(
                                text = stringResource(R.string.chat_receiving_image),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    SelectionContainer {
                        Text(
                            text = message.text.take(MAX_LIVE_TEXT_CHARS),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                MarkdownText(
                    markdown = message.text,
                    modifier = Modifier.fillMaxWidth(),
                    contentCacheKey = if (generatedImagePayload) {
                        "assistant:${message.id}:${message.text.length}"
                    } else {
                        null
                    },
                )
            }
        }

        // 底部动作栏：耗时信息 + 复制按钮
        if (!live && message.text.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                message.totalMs?.let {
                    Text(
                        stringResource(R.string.chat_elapsed, formatChatDuration(it)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                // Provider 报告的本轮 token 用量明细（输入/输出/缓存命中与命中率）。
                val tokenParts = buildList {
                    message.promptTokens?.let { add("↑${formatTokenCount(it)}") }
                    message.completionTokens?.let { add("↓${formatTokenCount(it)}") }
                    message.cachedTokens?.takeIf { cached -> cached > 0 }?.let { cached ->
                        val prompt = message.promptTokens ?: 0
                        val pct = if (prompt > 0) ((cached.toLong() * 100L) / prompt.toLong()).toInt().coerceIn(1, 100) else null
                        if (pct != null) {
                            add("⚡${formatTokenCount(cached)} ($pct%)")
                        } else {
                            add("⚡${formatTokenCount(cached)}")
                        }
                    }
                }

                if (tokenParts.isNotEmpty()) {
                    Text(
                        tokenParts.joinToString(" "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Spacer(Modifier.weight(1f))

                if (showRegenerate) {
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(26.dp), contentDescription = stringResource(R.string.chat_regenerate)) {
                        RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }

                IconButton(onClick = onCreateBranch, modifier = Modifier.size(26.dp), contentDescription = stringResource(R.string.chat_branch_from_here)) {
                    RuntimeIcon(RuntimeIconName.Hub, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 复制按钮
                IconButton(onClick = copyAll, modifier = Modifier.size(24.dp), contentDescription = stringResource(R.string.chat_copy)) {
                    RuntimeIcon(
                        RuntimeIconName.Copy,
                        Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private const val MAX_LIVE_TEXT_CHARS = 64_000
/** Avoid rescanning a multi-megabyte Base64 response whenever its lazy-list item re-enters composition. */
private val generatedImageFlagCache = LruCache<String, Boolean>(64)

private data class TaskStepItem(
    val index: Int,
    val title: String,
    val isCompleted: Boolean,
)

private val taskPlanCache = LruCache<String, List<TaskStepItem>>(200)

private fun extractTaskPlanSteps(text: String): List<TaskStepItem> {
    taskPlanCache.get(text)?.let { return it }
    val lines = text.lines()
    val steps = mutableListOf<TaskStepItem>()
    val checkboxRegex = Regex("""^(\s*[-*]|\s*\d+[\.\)])?\s*\[([ xX])\]\s*(.+)""")
    var idx = 1
    for (line in lines) {
        val match = checkboxRegex.find(line.trim())
        if (match != null) {
            val isChecked = match.groupValues[2].equals("x", ignoreCase = true)
            val title = match.groupValues[3].trim()
            if (title.isNotBlank()) {
                steps.add(TaskStepItem(index = idx++, title = title, isCompleted = isChecked))
            }
        }
    }
    taskPlanCache.put(text, steps)
    return steps
}

@Composable
private fun TaskPlanCard(
    steps: List<TaskStepItem>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    val completedCount = steps.count { it.isCompleted }
    val progress = if (steps.isNotEmpty()) completedCount.toFloat() / steps.size else 0f
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                expanded = !expanded
            },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    RuntimeIcon(
                        RuntimeIconName.Code,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                Text(
                    text = stringResource(R.string.chat_plan),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.weight(1f))

                Surface(
                    color = if (completedCount == steps.size) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_plan_completed, completedCount, steps.size),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                        ),
                        color = if (completedCount == steps.size) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                RuntimeIcon(
                    if (expanded) RuntimeIconName.ChevronDown else RuntimeIconName.ChevronRight,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 进度条
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    steps.forEach { step ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (step.isCompleted) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (step.isCompleted) {
                                    Text(
                                        "✓",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }

                            Text(
                                text = step.title,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = if (step.isCompleted) FontWeight.Normal else FontWeight.Medium,
                                ),
                                color = if (step.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ThinkingBlock(
    id: String,
    reasoning: String,
    defaultExpanded: Boolean,
    live: Boolean = false,
) {
    var expanded by rememberSaveable(id) { mutableStateOf(defaultExpanded) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val copyToClipboard = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.chat_reasoning_clipboard), reasoning))
        Toast.makeText(context, context.getString(R.string.chat_reasoning_copied), Toast.LENGTH_SHORT).show()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                expanded = !expanded
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
        ) {
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (live) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    RuntimeIcon(
                        RuntimeIconName.Brain,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }

            Text(
                stringResource(if (live) R.string.chat_deep_reasoning else R.string.chat_reasoning_process),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))

            if (expanded) {
                IconButton(
                    onClick = copyToClipboard,
                    modifier = Modifier.size(22.dp),
                    contentDescription = stringResource(R.string.chat_copy_reasoning),
                ) {
                    RuntimeIcon(
                        RuntimeIconName.Copy,
                        Modifier.size(11.dp),
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }

            RuntimeIcon(
                if (expanded) RuntimeIconName.ChevronDown else RuntimeIconName.ChevronRight,
                Modifier.size(11.dp),
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        // 左侧极简灰色强调线，对齐上方 16dp 容器的水平中轴 (x = 8dp)
                        val lineX = 8.dp.toPx()
                        drawLine(
                            color = Color(0xFF6B7280).copy(alpha = 0.35f),
                            start = Offset(lineX, 0f),
                            end = Offset(lineX, size.height),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    }
                    .padding(start = 22.dp, top = 1.dp, bottom = 2.dp),
            ) {
                if (live) {
                    SelectionContainer {
                        Text(
                            text = reasoning,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            ),
                        )
                    }
                } else {
                    MarkdownText(
                        reasoning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun CapabilityEventCard(event: CapabilityEvent) {
    val isSkill = event.kind == CapabilityEvent.Kind.SKILL
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.size(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            RuntimeIcon(
                if (isSkill) RuntimeIconName.Brain else RuntimeIconName.Cpu,
                Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            )
        }
        Text(
            stringResource(
                if (isSkill) R.string.chat_capability_skill_fmt else R.string.chat_capability_mcp_fmt,
                event.name,
            ),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
        if (event.details.isNotBlank()) {
            Text(
                "· ${event.details}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
