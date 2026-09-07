package top.wanxiang.app.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.wanxiang.app.feature.chat.R
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * 用户选取的图片或附件元数据
 */
data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val isImage: Boolean,
    val sizeBytes: Long,
    val uri: Uri,
    val hostFilePath: String? = null,
    val guestFilePath: String? = null,
    val base64DataUrl: String? = null,
) {
    val localFilePath: String get() = guestFilePath ?: hostFilePath.orEmpty()
}

object AttachmentHelper {
    private const val TAG = "AttachmentHelper"

    fun processUri(context: Context, uri: Uri, isImage: Boolean): ChatAttachment? {
        val contentResolver = context.contentResolver
        var name = if (isImage) "image_${System.currentTimeMillis()}.jpg" else "file_${System.currentTimeMillis()}"
        var size = 0L

        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        }

        // 统一复制到所有发行版共用的 Runtime 目录，PRoot 固定挂载为 /attachments。
        val attachmentsDir = File(context.filesDir, "linux-runtime/attachments").apply { mkdirs() }
        val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val targetFile = File(attachmentsDir, "${System.currentTimeMillis()}_$safeName")

        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (size == 0L) size = targetFile.length()

            val guestPath = "/attachments/${targetFile.name}"
            val sourceMimeType = contentResolver.getType(uri)
                ?.takeIf { it.startsWith("image/", ignoreCase = true) }
                ?: "image/jpeg"
            val base64 = if (isImage) {
                runCatching {
                    val boundsOptions = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeFile(targetFile.absolutePath, boundsOptions)

                    var inSampleSize = 1
                    val maxDimension = 2048
                    if (boundsOptions.outHeight > maxDimension || boundsOptions.outWidth > maxDimension) {
                        val halfHeight = boundsOptions.outHeight / 2
                        val halfWidth = boundsOptions.outWidth / 2
                        while ((halfHeight / inSampleSize) >= maxDimension || (halfWidth / inSampleSize) >= maxDimension) {
                            inSampleSize *= 2
                        }
                    }

                    val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                        this.inSampleSize = inSampleSize
                    }
                    val bitmap = android.graphics.BitmapFactory.decodeFile(targetFile.absolutePath, decodeOptions)
                    if (bitmap != null) {
                        val baos = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                        val bytes = baos.toByteArray()
                        val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        "data:image/jpeg;base64,$encoded"
                    } else {
                        val bytes = targetFile.readBytes()
                        val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        "data:$sourceMimeType;base64,$encoded"
                    }
                }.getOrNull()
            } else null

            ChatAttachment(
                name = name,
                isImage = isImage,
                sizeBytes = size,
                uri = uri,
                hostFilePath = targetFile.absolutePath,
                guestFilePath = guestPath,
                base64DataUrl = base64,
            )
        } catch (e: Exception) {
            // 失败不能静默：记录日志并由 ChatViewModel 统计后以 Toast 上抛给用户
            android.util.Log.w(TAG, "附件处理失败 uri=$uri", e)
            null
        }
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}

/**
 * 待发送附件预览栏
 */
@Composable
fun AttachmentPreviewRow(
    attachments: List<ChatAttachment>,
    onRemove: (ChatAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return

    LazyRow(
        modifier = modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.id }) { item ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = androidx.compose.foundation.BorderStroke(
                    0.8.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                if (item.isImage) Color(0xFF3B82F6).copy(alpha = 0.15f)
                                else Color(0xFF10B981).copy(alpha = 0.15f),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        RuntimeIcon(
                            name = if (item.isImage) RuntimeIconName.Image else RuntimeIconName.Attach,
                            modifier = Modifier.size(13.dp),
                            tint = if (item.isImage) Color(0xFF3B82F6) else Color(0xFF10B981),
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = AttachmentHelper.formatFileSize(item.sizeBytes),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val removeLabel = stringResource(R.string.chat_remove_attachment)
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clickable { onRemove(item) }
                            .semantics { contentDescription = removeLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Close,
                                modifier = Modifier.size(10.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
