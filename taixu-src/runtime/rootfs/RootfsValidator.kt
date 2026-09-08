package top.wkbin.taixu.runtime.rootfs

import top.wkbin.taixu.runtime.ElfInspector
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton

/** Validates the complete shell -> PT_INTERP -> dynamic-loader chain before PRoot is started. */
@Singleton
class RootfsValidator @Inject constructor(
    private val elfInspector: ElfInspector,
) {
    fun validate(rootfs: File): RootfsValidation {
        require(rootfs.isDirectory) { "RootFS 目录不存在" }
        require(
            File(rootfs, "etc/os-release").isFile ||
                File(rootfs, "usr/lib/os-release").isFile,
        ) { "RootFS 缺少 os-release" }

        val bash = resolveFirstExecutable(rootfs, BASH_PATHS)
        val posixShell = resolveFirstExecutable(rootfs, POSIX_SHELL_PATHS)
            ?: throw IllegalArgumentException("RootFS 缺少可用的 /bin/sh")
        val primary = bash ?: posixShell
        val primaryInfo = elfInspector.requireAarch64(primary.file)
        val interpreterPath = primaryInfo.interpreter
            ?.takeIf { it.startsWith('/') }
            ?: throw IllegalArgumentException("${primary.guestPath} 缺少有效的 ELF 解释器路径")
        val interpreter = resolveGuestPath(rootfs, interpreterPath)
        require(interpreter.isFile) {
            "${primary.guestPath} 的 ELF 解释器不存在：$interpreterPath"
        }
        elfInspector.requireAarch64(interpreter)

        if (bash != null) {
            val shInfo = elfInspector.requireAarch64(posixShell.file)
            require(shInfo.interpreter == interpreterPath) {
                "/bin/sh 与 Bash 使用了不同的 ELF 解释器"
            }
        }

        return RootfsValidation(
            bashPath = primary.guestPath,
            posixShellPath = posixShell.guestPath,
            interpreterPath = interpreterPath,
        )
    }

    fun isValid(rootfs: File): Boolean = runCatching { validate(rootfs) }.isSuccess

    private fun resolveFirstExecutable(rootfs: File, paths: List<String>): ResolvedGuestFile? =
        paths.firstNotNullOfOrNull { guestPath ->
            runCatching { resolveGuestPath(rootfs, guestPath) }
                .getOrNull()
                ?.takeIf { it.isFile }
                ?.let { ResolvedGuestFile(guestPath, it) }
        }

    /** Resolve absolute and relative symlinks as guest paths, never as Android host paths. */
    private fun resolveGuestPath(rootfs: File, guestPath: String): File {
        require(guestPath.startsWith('/')) { "Guest 路径必须是绝对路径：$guestPath" }
        val root = rootfs.toPath().toAbsolutePath().normalize()
        val pending = ArrayDeque(
            guestPath.split('/').filter { it.isNotBlank() && it != "." },
        )
        var current = root
        var symlinkCount = 0
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            require(part != "..") { "Guest 路径不允许越过 RootFS：$guestPath" }
            val candidate = current.resolve(part).normalize()
            require(candidate.startsWith(root)) { "Guest 路径越过 RootFS：$guestPath" }
            if (Files.isSymbolicLink(candidate)) {
                require(++symlinkCount <= MAX_SYMLINK_DEPTH) { "符号链接层级过深：$guestPath" }
                val link = Files.readSymbolicLink(candidate)
                val linkParts = link.toString().replace('\\', '/').split('/')
                    .filter { it.isNotBlank() && it != "." }
                current = if (link.isAbsolute || link.toString().startsWith('/')) {
                    root
                } else {
                    candidate.parent
                }
                for (index in linkParts.indices.reversed()) {
                    pending.addFirst(linkParts[index])
                }
            } else {
                current = candidate
            }
        }
        require(current.startsWith(root)) { "Guest 路径越过 RootFS：$guestPath" }
        return current.toFile()
    }

    data class RootfsValidation(
        val bashPath: String,
        val posixShellPath: String,
        val interpreterPath: String,
    )

    private data class ResolvedGuestFile(
        val guestPath: String,
        val file: File,
    )

    private companion object {
        const val MAX_SYMLINK_DEPTH = 32
        val BASH_PATHS = listOf("/bin/bash", "/usr/bin/bash")
        val POSIX_SHELL_PATHS = listOf("/bin/sh", "/usr/bin/sh")
    }
}
