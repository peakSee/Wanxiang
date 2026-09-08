package top.wanxiang.app.runtime.rootfs

import android.system.Os
import top.wanxiang.app.core.common.logging.AppLogger
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class TarStreamExtractor @Inject constructor(
    private val logger: AppLogger,
) : RootfsExtractor {

    override suspend fun extract(
        input: InputStream,
        destination: File,
        handleWhiteouts: Boolean,
    ) = withContext(Dispatchers.IO) {
        destination.mkdirs()
        val destinationRoot = destination.canonicalFile

        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        var pendingPax = emptyMap<String, String>()
        val globalPax = mutableMapOf<String, String>()
        val deferredHardlinks = mutableListOf<Pair<File, File>>()

        while (true) {
            val headerBytes = ByteArray(HEADER_SIZE)
            val headerBytesRead = readFully(input, headerBytes)
            if (headerBytesRead == 0) break
            if (headerBytesRead < HEADER_SIZE) break
            if (headerBytes.all { it == ZERO }) break

            val header = parseHeader(headerBytes)
            if (header == null) {
                logger.w("Skipping invalid tar header block")
                continue
            }

            when (header.typeFlag) {
                TYPE_LONG_NAME -> {
                    pendingLongName = readPaddedData(input, header.size)
                        .toString(StandardCharsets.UTF_8)
                        .trimEnd('\u0000')
                    continue
                }
                TYPE_LONG_LINK -> {
                    pendingLongLink = readPaddedData(input, header.size)
                        .toString(StandardCharsets.UTF_8)
                        .trimEnd('\u0000')
                    continue
                }
                TYPE_PAX -> {
                    pendingPax = parsePax(readPaddedData(input, header.size))
                    continue
                }
                TYPE_GLOBAL_PAX -> {
                    globalPax.putAll(parsePax(readPaddedData(input, header.size)))
                    continue
                }
            }

            val entryName = pendingLongName ?: pendingPax["path"] ?: globalPax["path"] ?: header.name
            val linkName = pendingLongLink ?: pendingPax["linkpath"] ?: globalPax["linkpath"] ?: header.linkName
            pendingLongName = null
            pendingLongLink = null
            pendingPax = emptyMap()

            val target = File(destination, entryName).canonicalFile
            if (!isInside(root = destinationRoot, candidate = target)) {
                logger.w("Skipping tar entry outside destination: $entryName")
                skipPaddedData(input, header.size)
                continue
            }

            if (handleWhiteouts) {
                val parent = target.parentFile ?: destinationRoot
                when {
                    target.name == ".wh..wh..opq" -> {
                        parent.listFiles().orEmpty().forEach(::deleteTree)
                        skipPaddedData(input, header.size)
                        continue
                    }
                    target.name.startsWith(".wh.") -> {
                        deleteTree(File(parent, target.name.removePrefix(".wh.")))
                        skipPaddedData(input, header.size)
                        continue
                    }
                }
            }

            when (header.typeFlag) {
                TYPE_DIRECTORY -> {
                    target.mkdirs()
                    applyMode(target, header.mode)
                }
                TYPE_SYMLINK -> {
                    target.parentFile?.mkdirs()
                    if (target.exists()) target.delete()
                    runCatching { Os.symlink(linkName, target.absolutePath) }
                        .onFailure {
                            // Android 11+ 部分设备 Os.symlink 抛 UnsatisfiedLinkError；
                            // 走 java.nio.Files.createSymbolicLink 兜底（内部也调 symlink(2) 但 API 稳定）
                            runCatching {
                                java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(linkName))
                            }.onFailure { logger.w("Failed to symlink $target", it) }
                        }
                }
                TYPE_HARDLINK -> {
                    target.parentFile?.mkdirs()
                    val source = File(destination, linkName).canonicalFile
                    if (!isInside(root = destinationRoot, candidate = source)) {
                        logger.w("Skipping hardlink outside destination: $linkName")
                        skipPaddedData(input, header.size)
                        continue
                    }
                    deferredHardlinks += target to source
                }
                TYPE_REGULAR, TYPE_REGULAR_ALT -> {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        copyData(input, header.size, output)
                    }
                    applyMode(target, header.mode)
                }
                else -> {
                    skipPaddedData(input, header.size)
                }
            }
        }
        deferredHardlinks.forEach { (target, source) ->
            if (!source.isFile) return@forEach
            if (target.exists()) deleteTree(target)
            target.parentFile?.mkdirs()
            runCatching { Files.createLink(target.toPath(), source.toPath()) }
                .onFailure {
                    logger.w("Hardlink unsupported, copying ${source.name} instead", it)
                    source.copyTo(target, overwrite = true)
                }
        }
    }

    private fun deleteTree(file: File) {
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) file.deleteRecursively() else file.delete()
    }

    private fun applyMode(file: File, mode: Int) {
        if (mode <= 0) return
        val ok = runCatching { Os.chmod(file.absolutePath, mode) }.isSuccess
        if (!ok) {
            // Os.chmod 在部分 Android 版本 / SELinux 上下文里会 EACCES。
            // 兜底用 java.io.File#setReadable/setWritable/setExecutable（走 syscall chmod 但由
            // framework 层包装，权限模型对 app 更宽松）。三档对应 owner r/w/x 位。
            runCatching {
                if (mode and 0x100 != 0) file.setReadable(true, false)
                if (mode and 0x080 != 0) file.setWritable(true, false)
                if (mode and 0x040 != 0) file.setExecutable(true, false)
            }.onFailure { logger.w("Failed to chmod and Java fallback for ${file.absolutePath}", it) }
        }
    }

    private fun isInside(root: File, candidate: File): Boolean {
        val rootPath = root.absolutePath
        val candidatePath = candidate.absolutePath
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    private fun parseHeader(bytes: ByteArray): TarHeader? {
        if (bytes.size < HEADER_SIZE) return null

        val shortName = bytes.decodeAscii(0, NAME_LENGTH)
        val prefix = bytes.decodeAscii(PREFIX_OFFSET, PREFIX_LENGTH)
        val name = if (prefix.isBlank()) shortName else "$prefix/$shortName"
        val mode = bytes.decodeAscii(MODE_OFFSET, MODE_LENGTH).trim().toIntOrNull(8) ?: 0
        val size = bytes.decodeAscii(SIZE_OFFSET, SIZE_LENGTH).trim().toLongOrNull(8) ?: 0L
        val typeFlag = bytes[TYPE_FLAG_OFFSET].toInt().toChar()
        val linkName = bytes.decodeAscii(LINK_NAME_OFFSET, LINK_NAME_LENGTH)

        return TarHeader(
            name = name,
            mode = mode,
            size = size,
            typeFlag = typeFlag,
            linkName = linkName,
        )
    }

    private fun ByteArray.decodeAscii(offset: Int, length: Int): String {
        val end = (offset + length).coerceAtMost(size)
        var valueEnd = end
        while (valueEnd > offset && this[valueEnd - 1].toInt() == 0) {
            valueEnd--
        }
        return String(this, offset, valueEnd - offset, StandardCharsets.US_ASCII)
    }

    private fun parsePax(bytes: ByteArray): Map<String, String> {
        val text = bytes.toString(StandardCharsets.UTF_8)
        val result = mutableMapOf<String, String>()
        var offset = 0
        while (offset < text.length) {
            val space = text.indexOf(' ', offset)
            if (space <= offset) break
            val length = text.substring(offset, space).toIntOrNull() ?: break
            val end = (offset + length).coerceAtMost(text.length)
            val record = text.substring(space + 1, end).trimEnd('\n')
            val separator = record.indexOf('=')
            if (separator > 0) result[record.substring(0, separator)] = record.substring(separator + 1)
            offset += length
        }
        return result
    }

    private fun copyData(input: InputStream, size: Long, output: java.io.OutputStream) {
        var remaining = size
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        skipPadding(input, size)
    }

    private fun skipPaddedData(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read < 0) break
            remaining -= read
        }
        skipPadding(input, size)
    }

    private fun readPaddedData(input: InputStream, size: Long): ByteArray {
        if (size <= 0) {
            skipPadding(input, 0)
            return ByteArray(0)
        }
        require(size <= MAX_IN_MEMORY_ENTRY_SIZE) {
            "Tar metadata entry exceeds $MAX_IN_MEMORY_ENTRY_SIZE bytes"
        }
        val bytes = ByteArray(size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) break
            offset += read
        }
        skipPadding(input, size)
        return bytes
    }

    private fun skipPadding(input: InputStream, dataSize: Long) {
        val padding = ((HEADER_SIZE - (dataSize % HEADER_SIZE)) % HEADER_SIZE).toInt()
        if (padding == 0) return
        val buffer = ByteArray(padding)
        readFully(input, buffer)
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private data class TarHeader(
        val name: String,
        val mode: Int,
        val size: Long,
        val typeFlag: Char,
        val linkName: String,
    )

    private companion object {
        const val HEADER_SIZE = 512
        const val NAME_LENGTH = 100
        const val MODE_OFFSET = 100
        const val MODE_LENGTH = 8
        const val SIZE_OFFSET = 124
        const val SIZE_LENGTH = 12
        const val TYPE_FLAG_OFFSET = 156
        const val LINK_NAME_OFFSET = 157
        const val LINK_NAME_LENGTH = 100
        const val PREFIX_OFFSET = 345
        const val PREFIX_LENGTH = 155
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_IN_MEMORY_ENTRY_SIZE = 4L * 1024L * 1024L

        const val TYPE_REGULAR = '0'
        const val TYPE_REGULAR_ALT = '\u0000'
        const val TYPE_HARDLINK = '1'
        const val TYPE_SYMLINK = '2'
        const val TYPE_DIRECTORY = '5'
        const val TYPE_LONG_NAME = 'L'
        const val TYPE_LONG_LINK = 'K'
        const val TYPE_PAX = 'x'
        const val TYPE_GLOBAL_PAX = 'g'

        val ZERO: Byte = 0
    }
}

