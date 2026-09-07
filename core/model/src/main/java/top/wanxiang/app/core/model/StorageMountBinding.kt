package top.wanxiang.app.core.model

import kotlinx.serialization.Serializable

/**
 * 宿主 (Android) 与沙箱 (Linux PRoot) 存储挂载绑定
 * 对应 PRoot 命令: -b <hostPath>:<guestPath>
 */
@Serializable
data class StorageMountBinding(
    val id: String,
    val name: String,
    val hostPath: String,
    val guestPath: String,
    val enabled: Boolean = true,
    val isSystemDefault: Boolean = false,
) : java.io.Serializable
