package top.wanxiang.app.ui.chat

import top.wanxiang.app.harness.AssistantText
import top.wanxiang.app.harness.HarnessMessage
import top.wanxiang.app.harness.CapabilityEvent
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.UserMessage

/** 消息流渲染的通用纯函数工具：间距判定 / 推理去重 / 时长与 token 格式化。 */
internal fun isThinkingOrActionItem(item: ChatRenderItem?): Boolean {
    if (item !is ChatRenderItem.MessageItem) return false
    return when (val msg = item.message) {
        is ToolCall, is CapabilityEvent -> true
        is AssistantText -> msg.text.isBlank() || msg.reasoning != null
        else -> false
    }
}

internal fun reasoningAlreadyShown(messages: List<HarnessMessage>, index: Int, reasoning: String?): Boolean {
    if (reasoning == null) return false
    var i = index - 1
    while (i >= 0) {
        val m = messages[i]
        if (m is UserMessage) break
        if (m is AssistantText && m.reasoning == reasoning) return true
        if (m is ToolCall && m.reasoning == reasoning) return true
        i--
    }
    return false
}

internal fun formatChatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return when {
        totalSeconds < 10 -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
        totalSeconds < 60 -> "${totalSeconds}s"
        else -> "${totalSeconds / 60}m${totalSeconds % 60}s"
    }
}

/** Token 计数紧凑格式：1234 → 1.2k，用于气泡底部的用量明细。 */
internal fun formatTokenCount(tokens: Int): String = when {
    tokens >= 10_000 -> String.format(java.util.Locale.US, "%.1fk", tokens / 1000f)
    tokens >= 1_000 -> String.format(java.util.Locale.US, "%.2fk", tokens / 1000f)
    else -> tokens.toString()
}
