package top.wanxiang.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class QuickPhrase(
    val id: String,
    val title: String,
    val content: String,
    val description: String = "",
    val iconName: String = "Play",
    val targetProjectType: String? = null,
    val isEnabled: Boolean = true,
    val sortOrder: Int = 0,
    val isBuiltin: Boolean = false,
)
