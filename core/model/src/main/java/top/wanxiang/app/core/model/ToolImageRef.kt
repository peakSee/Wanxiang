package top.wanxiang.app.core.model

import kotlinx.serialization.Serializable

/**
 * 工具产物中的图片附件引用（纯路径 / URI，不携带二进制）。
 *
 * - [uri] 通常以 `screenshots://` 或 `wanxiang-browser://` 开头，由 UI 层（runtime/browser）落地。
 * - UI 用 Coil 异步渲染本地文件；模型侧只看 [id] + [uri] + [mime] + 尺寸描述。
 */
@Serializable
data class ToolImageRef(
    val id: String,
    val uri: String,
    val mime: String = "image/png",
    val width: Int = 0,
    val height: Int = 0,
    val caption: String = ""
)
