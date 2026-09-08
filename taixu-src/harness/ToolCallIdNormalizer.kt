package top.wkbin.taixu.harness

import java.util.UUID

/**
 * 统一的大模型 ToolCall ID 规范化与全局唯一性保障器。
 *
 * 背景：
 * SQLite `harness_entries` 表对 `id` 列有严格的全局唯一约束 (SQLITE_CONSTRAINT_UNIQUE)。
 * 许多大模型服务商（如智谱 GLM、国内部分中转接口、开源模型等）在多轮对话中返回的
 * tool_call id 往往是固定的简单序号（如 "call_0"），或者返回空字符串。
 * 若直接将其作为 entry.id 存入数据库，跨轮次、重试或分支切换时必将触发唯一约束冲突崩溃。
 *
 * 本类确保：
 * 1. 空 ID 自动补全为符合 OpenAI 协议的合法 ID（call_<random16>）。
 * 2. 清洗非法字符（保留字母、数字、下划线、短横线）。
 * 3. 拼接短 UUID 后缀（形如 <sanitized>_<random8>），保证跨会话、跨轮次绝对唯一，
 *    同时在下一轮发回大模型 API 时，因 assistant.tool_calls[i].id 与 tool.tool_call_id 保持一致，
 *    符合各大厂商协议要求。
 */
object ToolCallIdNormalizer {

    private const val DEFAULT_PREFIX = "call"
    private const val MAX_BASE_LENGTH = 32

    fun normalize(rawId: String?): String {
        val trimmed = rawId?.trim().orEmpty()
        val randomSuffix = UUID.randomUUID().toString().replace("-", "").take(8)
        if (trimmed.isEmpty()) {
            val fullRandom = UUID.randomUUID().toString().replace("-", "").take(16)
            return "${DEFAULT_PREFIX}_$fullRandom"
        }
        val sanitized = trimmed.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (sanitized.isEmpty()) {
            val fullRandom = UUID.randomUUID().toString().replace("-", "").take(16)
            return "${DEFAULT_PREFIX}_$fullRandom"
        }
        val base = sanitized.take(MAX_BASE_LENGTH)
        return "${base}_$randomSuffix"
    }
}
