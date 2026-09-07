package top.wanxiang.app.core.model

/** Tool execution authority enforced by the host, independently of model prompts. */
enum class ApprovalMode(val id: String) {
    REQUEST("request"),
    ASSISTED("assisted"),
    FULL_ACCESS("full_access");

    companion object {
        fun fromId(id: String?): ApprovalMode = entries.firstOrNull { it.id == id } ?: ASSISTED
    }
}

