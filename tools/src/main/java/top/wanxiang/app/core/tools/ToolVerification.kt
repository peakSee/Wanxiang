package top.wanxiang.app.core.tools

data class ToolVerification(
    val toolId: String,
    val healthy: Boolean,
    val version: String? = null,
    val detail: String,
)
