package top.wanxiang.app.runtime.rootfs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TarStreamExtractorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var destDir: File
    private lateinit var extractor: TarStreamExtractor
    private val recordedSymlinks = mutableListOf<Pair<String, String>>()
    private val recordedChmods = mutableListOf<Pair<String, Int>>()

    private val fakeFsOps = object : FileSystemOps {
        override fun symlink(target: String, linkPath: String) {
            recordedSymlinks.add(target to linkPath)
            val file = File(linkPath)
            file.parentFile?.mkdirs()
            file.writeText("SYMLINK->$target")
        }

        override fun chmod(path: String, mode: Int) {
            recordedChmods.add(path to mode)
        }
    }

    @Before
    fun setUp() {
        destDir = temporaryFolder.newFolder("staging-rootfs")
        extractor = TarStreamExtractor({ _, _ -> }, fakeFsOps)
    }

    @Test
    fun computeRelativeSymlink_convertsAbsoluteGuestPathsCorrectly() {
        // /run 从 var/run 视角看应为 ../run
        val varRun = File(destDir, "var/run")
        assertEquals("../run", extractor.computeRelativeSymlink(destDir, varRun, "/run"))

        // /usr/bin 从 bin 视角看应为 usr/bin
        val bin = File(destDir, "bin")
        assertEquals("usr/bin", extractor.computeRelativeSymlink(destDir, bin, "/usr/bin"))

        // /usr/lib 从 usr/lib64 视角看应为 lib
        val usrLib64 = File(destDir, "usr/lib64")
        assertEquals("lib", extractor.computeRelativeSymlink(destDir, usrLib64, "/usr/lib"))

        // /usr/lib/os-release 从 etc/os-release 视角看应为 ../usr/lib/os-release
        val etcRelease = File(destDir, "etc/os-release")
        assertEquals("../usr/lib/os-release", extractor.computeRelativeSymlink(destDir, etcRelease, "/usr/lib/os-release"))

        // 已经为相对路径的保持原样
        val py = File(destDir, "usr/bin/python")
        assertEquals("python3.11", extractor.computeRelativeSymlink(destDir, py, "python3.11"))
    }

    @Test
    fun extract_readOnlyDirectoryInTar_doesNotPreventSubsequentFilesFromExtracting() = runBlocking {
        val tarBytes = buildTar {
            // 目录带有只读模式 0555 (r-xr-xr-x 无写权限)
            addDirectory("usr/bin", mode = 0b101101101)
            addFile("usr/bin/hello", "hello world".toByteArray(), mode = 0b101101101)
        }

        extractor.extract(ByteArrayInputStream(tarBytes), destDir, handleWhiteouts = false)

        val file = File(destDir, "usr/bin/hello")
        assertTrue(file.isFile)
        assertEquals("hello world", file.readText())
    }

    @Test
    fun extract_overwritingExistingReadOnlyFile_succeedsWithoutError() = runBlocking {
        val dir = File(destDir, "usr/bin")
        dir.mkdirs()
        val target = File(dir, "[")
        target.writeText("old binary")
        target.setReadOnly() // 模拟已存在的只读文件

        val tarBytes = buildTar {
            addFile("usr/bin/[", "new test binary".toByteArray(), mode = 0b111101101)
        }

        extractor.extract(ByteArrayInputStream(tarBytes), destDir, handleWhiteouts = false)

        assertTrue(target.isFile)
        assertEquals("new test binary", target.readText())
    }

    @Test
    fun extract_replacingExistingSymlinkWithRegularFile_replacesCleanly() = runBlocking {
        val dir = File(destDir, "usr/bin")
        dir.mkdirs()
        val target = File(dir, "sh")
        target.writeText("OLD_SYMLINK_OR_FILE")

        val tarBytes = buildTar {
            addFile("usr/bin/sh", "#!/bin/bash\necho ok".toByteArray(), mode = 0b111101101)
        }

        extractor.extract(ByteArrayInputStream(tarBytes), destDir, handleWhiteouts = false)

        assertEquals("#!/bin/bash\necho ok", target.readText())
    }

    @Test
    fun extract_skipsPathTraversalEntriesOutsideDestination() = runBlocking {
        val tarBytes = buildTar {
            addFile("../../escaped.txt", "evil".toByteArray(), mode = 0b110100100)
            addFile("good.txt", "safe".toByteArray(), mode = 0b110100100)
        }

        extractor.extract(ByteArrayInputStream(tarBytes), destDir, handleWhiteouts = false)

        assertTrue(File(destDir, "good.txt").isFile)
        assertFalse(File(destDir.parentFile, "escaped.txt").exists())
    }

    private fun buildTar(builder: TarBuilder.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        TarBuilder(out).apply(builder).finish()
        return out.toByteArray()
    }

    private class TarBuilder(private val out: ByteArrayOutputStream) {
        fun addDirectory(name: String, mode: Int = 0b111101101) {
            val header = ByteArray(512)
            writeString(header, 0, 100, name.trimEnd('/') + "/")
            writeOctal(header, 100, 8, mode.toLong())
            writeOctal(header, 108, 8, 0L) // uid
            writeOctal(header, 116, 8, 0L) // gid
            writeOctal(header, 124, 12, 0L) // size
            writeOctal(header, 136, 12, System.currentTimeMillis() / 1000)
            header[156] = '5'.code.toByte() // TYPE_DIRECTORY
            writeString(header, 257, 6, "ustar\u0000")
            writeString(header, 263, 2, "00")
            fillChecksum(header)
            out.write(header)
        }

        fun addFile(name: String, content: ByteArray, mode: Int = 0b110100100) {
            val header = ByteArray(512)
            writeString(header, 0, 100, name)
            writeOctal(header, 100, 8, mode.toLong())
            writeOctal(header, 108, 8, 0L)
            writeOctal(header, 116, 8, 0L)
            writeOctal(header, 124, 12, content.size.toLong())
            writeOctal(header, 136, 12, System.currentTimeMillis() / 1000)
            header[156] = '0'.code.toByte() // TYPE_REGULAR
            writeString(header, 257, 6, "ustar\u0000")
            writeString(header, 263, 2, "00")
            fillChecksum(header)
            out.write(header)
            out.write(content)
            val padding = (512 - (content.size % 512)) % 512
            if (padding > 0) out.write(ByteArray(padding))
        }

        fun finish() {
            out.write(ByteArray(1024))
        }

        private fun writeString(dest: ByteArray, offset: Int, length: Int, text: String) {
            val bytes = text.toByteArray(StandardCharsets.US_ASCII)
            System.arraycopy(bytes, 0, dest, offset, minOf(bytes.size, length))
        }

        private fun writeOctal(dest: ByteArray, offset: Int, length: Int, value: Long) {
            val octal = "%0${length - 1}o".format(value)
            writeString(dest, offset, length - 1, octal)
            dest[offset + length - 1] = 0
        }

        private fun fillChecksum(header: ByteArray) {
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            val sum = header.sumOf { it.toUByte().toLong() }
            val octal = "%06o".format(sum)
            val octalBytes = octal.toByteArray(StandardCharsets.US_ASCII)
            System.arraycopy(octalBytes, 0, header, 148, 6)
            header[154] = 0
            header[155] = ' '.code.toByte()
        }
    }
}
