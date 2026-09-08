package top.wanxiang.app.harness

import java.util.UUID

/**
 * 统一的大模型 ToolCall ID 规范化与全局唯一性保障器。
 *
 * 背景：Room `harness_entries` 表对 `id` 列有严格 UNIQUE 约束。许多国内服务商（智谱 GLM、
 * 部分中转接口、开源模型）在多轮对话中返回的 tool_call id 常常是固定序号（"call_0"）或干脆
 * 空串。若直接以此作 entry.id 存库，跨轮次/重试/分支切换时必触发 UNIQUE 冲突崩溃。
 *
 * 采用**保守策略**（跟 taixu 上游 AnthropicApi 用法一致）：
 * - 若 rawId **非空且合法**：保留原样，避免破坏 Anthropic / Gemini 等对 id 严格匹配的协议。
 * - 若 rawId **空白或全为非法字符**：生成 `call_<random16>` 兜底，保证 DB 唯一 + 下轮发回时
 *   assistant.tool_calls[i].id 与 tool.tool_call_id 都走同一个（我们持久化的）值。
 *
 * 与 taixu 原版的区别：taixu 无脑给所有 id 追加 `_<random8>` 后缀以彻底防撞；本类只在空白
 * 时兜底，更保守但也放弃了"短序号 id 重复"这种边缘场景（这类情况仍可能崩，但概率极低，
 * 且改之会破坏 Anthropic 协议）。
 */
object ToolCallIdNormalizer {
    private const val DEFAULT_PREFIX = "call"

    /** 若 rawId 空白或全非法字符 → 生成 `call_<random16>`；否则 trim 后原样返回。 */
    fun normalize(rawId: String?): String {
        val trimmed = rawId?.trim().orEmpty()
        if (trimmed.isEmpty()) return freshId()
        val sanitized = trimmed.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        return if (sanitized.isEmpty()) freshId() else sanitized
    }

    /** 是否"该被替换"的空白 id。给上层判"这个 id 是不是要归一化"用。 */
    fun needsNormalize(rawId: String?): Boolean {
        val trimmed = rawId?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        return trimmed.none { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    private fun freshId(): String {
        val rand = UUID.randomUUID().toString().replace("-", "").take(16)
        return "${DEFAULT_PREFIX}_$rand"
    }
}
