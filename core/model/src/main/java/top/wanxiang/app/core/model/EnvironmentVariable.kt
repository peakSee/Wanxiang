package top.wanxiang.app.core.model

import kotlinx.serialization.Serializable

/** User-managed environment variable metadata. The value is never included here. */
@Serializable
data class EnvironmentVariable(
    val id: String,
    val key: String,
    val note: String = "",
    val createdAt: Long = 0L,
)
