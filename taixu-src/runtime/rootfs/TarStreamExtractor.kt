package top.wkbin.taixu.runtime.rootfs

import android.system.Os
import top.wkbin.taixu.core.common.logging.AppLogger
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface FileSystemOps {
    fun symlink(target: String, linkPath: String)
    fun chmod(path: String, mode: Int)
}

internal class AndroidFileSystemOps : FileSystemOps {
    override fun symlink(target: String, linkPath: String) {
        try {
            Os.symlink(target, linkPath)
        } catch (e: NoClassDefFoundError) {
            Files.createSymbolicLink(File(linkPath).toPath(), File(target).toPath())
        } catch (e: UnsatisfiedLinkError) {
            Files.createSymbolicLink(File(linkPath).toPath(), File(target).toPath())
        }
    }

    override fun chmod(path: String, mode: Int) {
        try {
            Os.chmod(path, mode)
        } catch (e: Throwable) {
            val file = File(path)
            if (mode and 0x100 != 0) file.setReadable(true, false)
            if (mode and 0x80 != 0) file.setWritable(true, false)
            if (mode and 0x40 != 0) file.setExecutable(true, false)
        }
    }
}

@Singleton
class TarStreamExtractor internal constructor(
    private val logWarning: (String, Throwable?) -> Unit,
    private val fsOps: FileSystemOps,
) : RootfsExtractor {

    @Inject
    constructor(logger: AppLogger) : this({ msg, err -> logger.w(msg, err) }, AndroidFileSystemOps())

    override suspend fun extract(
        input: InputStream,
        destination: File,
        handleWhiteouts: Boolean,
    ) = withContext(Dispatchers.IO) {
        destination.mkdirs()
        ensurePathWritable(destination, destination)
        val destPath = destination.toPath().toAbsolutePath().normalize()

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
                logWarning("Skipping invalid tar header block", null)
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

            val cleanEntryName = entryName.trimStart('/', '.').replace('\\', '/')
            if (cleanEntryName.isBlank()) {
                skipPaddedData(input, header.size)
                continue
            }

            val candidate = destPath.resolve(cleanEntryName).normalize()
            if (!candidate.startsWith(destPath)) {
                logWarning("Skipping tar entry outside destination: $entryName", null)
                skipPaddedData(input, header.size)
                continue
            }
            val target = candidate.toFile()

            if (handleWhiteouts) {
                val parent = target.parentFile ?: destination
                when {
                    target.name == ".wh..wh..opq" -> {
                        ensurePathWritable(parent, destination)
                        parent.listFiles().orEmpty().forEach(::deleteTree)
                        skipPaddedData(input, header.size)
                        continue
                    }
                    target.name.startsWith(".wh.") -> {
                        ensurePathWritable(parent, destination)
                        deleteTree(File(parent, target.name.removePrefix(".wh.")))
                        skipPaddedData(input, header.size)
                        continue
                    }
                }
            }

            when (header.typeFlag) {
                TYPE_DIRECTORY -> {
                    ensurePathWritable(target.parentFile ?: destination, destination)
                    target.mkdirs()
                    // 必须保留 owner rwx (0700)，否则后续同一目录下的文件/子目录解压时会触发 EACCES (Permission denied)
                    val safeDirMode = if (header.mode > 0) (header.mode and MODE_MASK) or MODE_OWNER_RWX else MODE_DIR_DEFAULT
                    applyMode(target, safeDirMode)
                }
                TYPE_SYMLINK -> {
                    ensurePathWritable(target.parentFile ?: destination, destination)
                    target.parentFile?.mkdirs()
                    deleteTree(target)
                    val effectiveLink = computeRelativeSymlink(destination, target, linkName)
                    try {
                        fsOps.symlink(effectiveLink, target.absolutePath)
                    } catch (e: Throwable) {
                        logWarning("Failed to create symlink $effectiveLink at ${target.absolutePath}, retrying after force delete", e)
                        deleteTree(target)
                        fsOps.symlink(effectiveLink, target.absolutePath)
                    }
                }
                TYPE_HARDLINK -> {
                    ensurePathWritable(target.parentFile ?: destination, destination)
                    target.parentFile?.mkdirs()
                    val cleanLinkName = linkName.trimStart('/', '.').replace('\\', '/')
                    val candidateSource = destPath.resolve(cleanLinkName).normalize()
                    if (!candidateSource.startsWith(destPath)) {
                        logWarning("Skipping hardlink outside destination: $linkName", null)
                        skipPaddedData(input, header.size)
                        continue
                    }
                    deferredHardlinks += target to candidateSource.toFile()
                }
                TYPE_REGULAR, TYPE_REGULAR_ALT -> {
                    ensurePathWritable(target.parentFile ?: destination, destination)
                    target.parentFile?.mkdirs()
                    deleteTree(target)
                    target.outputStream().use { output ->
                        copyData(input, header.size, output)
                    }
                    val safeFileMode = if (header.mode > 0) (header.mode and MODE_MASK) or MODE_OWNER_RW else MODE_FILE_DEFAULT
                    applyMode(target, safeFileMode)
                }
                else -> {
                    skipPaddedData(input, header.size)
                }
            }
        }

        deferredHardlinks.forEach { (target, source) ->
            if (!source.isFile) return@forEach
            ensurePathWritable(target.parentFile ?: destination, destination)
            deleteTree(target)
            target.parentFile?.mkdirs()
            runCatching { Files.createLink(target.toPath(), source.toPath()) }
                .onFailure {
                    logWarning("Hardlink unsupported, copying ${source.name} instead", it)
                    source.copyTo(target, overwrite = true)
                }
        }

        // 解压完成后，确保所有目录都有 owner rwx 权限，防止 rootfs 被宿主锁定无法读写或清理
        destination.walkBottomUp().forEach { file ->
            if (file.isDirectory) {
                file.setReadable(true, true)
                file.setWritable(true, true)
                file.setExecutable(true, true)
            }
        }
    }

    internal fun computeRelativeSymlink(
        destination: File,
        targetFile: File,
        linkName: String,
    ): String {
        if (!linkName.startsWith("/")) return linkName
        val destPath = destination.toPath().toAbsolutePath().normalize()
        val parentDir = targetFile.parentFile ?: destination
        val parentPath = parentDir.toPath().toAbsolutePath().normalize()
        val targetInGuest = destPath.resolve(linkName.trimStart('/')).normalize()
        if (!targetInGuest.startsWith(destPath)) {
            return parentPath.relativize(destPath).toString().replace('\\', '/')
        }
        return parentPath.relativize(targetInGuest).toString().replace('\\', '/')
    }

    private fun ensurePathWritable(file: File, root: File) {
        var curr: File? = file
        val rootParent = root.parentFile
        while (curr != null && curr != rootParent) {
            if (curr.exists() && !curr.canWrite()) {
                curr.setWritable(true, true)
                runCatching { fsOps.chmod(curr.absolutePath, MODE_OWNER_RWX) }
            }
            curr = curr.parentFile
        }
    }

    private fun deleteTree(file: File) {
        val path = file.toPath()
        if (Files.isSymbolicLink(path)) {
            runCatching { Files.delete(path) }
            return
        }
        if (file.isDirectory) {
            file.walkBottomUp().forEach { sub ->
                if (!sub.canWrite()) {
                    sub.setWritable(true, true)
                }
                val subPath = sub.toPath()
                if (Files.isSymbolicLink(subPath)) {
                    runCatching { Files.delete(subPath) }
                } else if (!sub.isDirectory) {
                    sub.delete()
                }
            }
            file.delete()
        } else {
            if (!file.canWrite()) {
                file.setWritable(true, true)
            }
            runCatching { Files.deleteIfExists(path) }
        }
    }

    private fun applyMode(file: File, mode: Int) {
        if (mode <= 0) return
        runCatching { fsOps.chmod(file.absolutePath, mode) }
            .onFailure { logWarning("Failed to chmod ${file.absolutePath} to ${Integer.toOctalString(mode)}", it) }
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

        const val MODE_MASK = 0xfff // 07777
        const val MODE_OWNER_RWX = 0x1c0 // 0700
        const val MODE_DIR_DEFAULT = 0x1ed // 0755
        const val MODE_OWNER_RW = 0x180 // 0600
        const val MODE_FILE_DEFAULT = 0x1a4 // 0644
    }
}

