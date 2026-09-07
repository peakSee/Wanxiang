package top.wanxiang.app.core.tools

import top.wanxiang.app.runtime.RuntimePathManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

sealed interface LocalPluginPreparationEvent {
    data class Copying(
        val message: String,
        val bytesCopied: Long,
        val totalBytes: Long,
    ) : LocalPluginPreparationEvent {
        val fraction: Float
            get() = if (totalBytes > 0L) {
                (bytesCopied.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
            } else {
                1f
            }
    }

    data class Ready(val payloadPath: String?) : LocalPluginPreparationEvent
}

/** Copies an imported plugin payload into the distro-owned /opt/wanxiang tree. */
@Singleton
class LocalPluginPayloadManager @Inject constructor(
    private val registry: ToolRegistry,
    private val pathManager: RuntimePathManager,
) {
    fun prepare(toolId: String, distroId: String): Flow<LocalPluginPreparationEvent> = flow {
        val source = registry.localPayloadRoot(toolId)?.takeIf { it.isDirectory }
        if (source == null) {
            emit(LocalPluginPreparationEvent.Ready(null))
            return@flow
        }
        val importsRoot = File(pathManager.wanxiangRootDir(distroId), "imports").apply { mkdirs() }
        val target = File(importsRoot, toolId)
        val canonicalRoot = importsRoot.canonicalFile
        require(target.canonicalFile.toPath().startsWith(canonicalRoot.toPath())) { "非法插件 ID：$toolId" }
        target.deleteRecursively()

        val sourceFiles = source.walkTopDown().filter { it.isFile }.toList()
        val totalBytes = sourceFiles.sumOf { it.length() }
        var copiedBytes = 0L
        var nextProgressReport = 0L
        target.mkdirs()

        for (sourceFile in sourceFiles) {
            val relativePath = sourceFile.relativeTo(source).invariantSeparatorsPath
            val targetFile = File(target, relativePath)
            targetFile.parentFile?.mkdirs()
            sourceFile.inputStream().use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copiedBytes += read
                        if (copiedBytes >= nextProgressReport || copiedBytes == totalBytes) {
                            emit(
                                LocalPluginPreparationEvent.Copying(
                                    message = "[COPY] 正在复制到沙盒目录：$relativePath · " +
                                        "${formatBytes(copiedBytes)}/${formatBytes(totalBytes)}",
                                    bytesCopied = copiedBytes,
                                    totalBytes = totalBytes,
                                ),
                            )
                            nextProgressReport = copiedBytes + COPY_PROGRESS_INTERVAL_BYTES
                        }
                    }
                }
            }
            targetFile.setReadable(true, false)
            if (targetFile.extension.equals("sh", ignoreCase = true)) {
                normalizeShellScript(targetFile)
            }
            if (targetFile.extension.equals("sh", ignoreCase = true) || "/bin/" in targetFile.invariantSeparatorsPath) {
                targetFile.setExecutable(true, false)
            }
        }
        emit(LocalPluginPreparationEvent.Ready("/opt/wanxiang/imports/$toolId"))
    }.flowOn(Dispatchers.IO)

    /** Windows-created ZIPs commonly carry BOM/CRLF and no executable bit. */
    private fun normalizeShellScript(file: File) {
        val original = file.readBytes()
        val withoutBom = if (
            original.size >= 3 &&
            original[0] == 0xEF.toByte() &&
            original[1] == 0xBB.toByte() &&
            original[2] == 0xBF.toByte()
        ) {
            original.copyOfRange(3, original.size)
        } else {
            original
        }
        var text = withoutBom.toString(Charsets.UTF_8)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        if (!text.startsWith("#!")) text = "#!/bin/sh\n$text"
        file.writeText(text, Charsets.UTF_8)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 1024 * 1024
        const val COPY_PROGRESS_INTERVAL_BYTES = 32L * 1024L * 1024L
    }
}
