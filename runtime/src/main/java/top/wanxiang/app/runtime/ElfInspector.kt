package top.wanxiang.app.runtime

import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/** Minimal ELF64 inspector used to reject a wrong-architecture or incomplete runtime. */
@Singleton
class ElfInspector @Inject constructor() {

    fun inspect(file: File): ElfInfo {
        require(file.isFile) { "ELF 文件不存在：${file.absolutePath}" }
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= ELF64_HEADER_SIZE) { "ELF 文件不完整：${file.absolutePath}" }
            val header = ByteArray(ELF64_HEADER_SIZE)
            input.readFully(header)
            require(
                header[0] == 0x7f.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() &&
                    header[3] == 'F'.code.toByte(),
            ) { "不是有效 ELF 文件：${file.absolutePath}" }
            require(header[EI_CLASS].toInt() == ELFCLASS64) {
                "仅支持 64 位 ELF：${file.absolutePath}"
            }
            require(header[EI_DATA].toInt() == ELFDATA2LSB) {
                "仅支持小端 ELF：${file.absolutePath}"
            }

            val machine = header.u16(EMACHINE_OFFSET)
            val programHeaderOffset = header.u64(PHOFF_OFFSET)
            val programHeaderSize = header.u16(PHENTSIZE_OFFSET)
            val programHeaderCount = header.u16(PHNUM_OFFSET)
            require(programHeaderSize >= ELF64_PROGRAM_HEADER_SIZE || programHeaderCount == 0) {
                "ELF Program Header 不完整：${file.absolutePath}"
            }

            var interpreter: String? = null
            repeat(programHeaderCount) { index ->
                val offset = programHeaderOffset + index.toLong() * programHeaderSize
                require(offset >= 0 && offset + ELF64_PROGRAM_HEADER_SIZE <= input.length()) {
                    "ELF Program Header 越界：${file.absolutePath}"
                }
                input.seek(offset)
                val programHeader = ByteArray(ELF64_PROGRAM_HEADER_SIZE)
                input.readFully(programHeader)
                if (programHeader.u32(PTYPE_OFFSET) == PT_INTERP.toLong()) {
                    val interpreterOffset = programHeader.u64(POFFSET_OFFSET)
                    val interpreterSize = programHeader.u64(PFILESZ_OFFSET)
                    require(interpreterSize in 2..MAX_INTERPRETER_BYTES.toLong()) {
                        "ELF 解释器字段无效：${file.absolutePath}"
                    }
                    require(
                        interpreterOffset >= 0 &&
                            interpreterOffset + interpreterSize <= input.length(),
                    ) { "ELF 解释器字段越界：${file.absolutePath}" }
                    input.seek(interpreterOffset)
                    val bytes = ByteArray(interpreterSize.toInt())
                    input.readFully(bytes)
                    interpreter = bytes.toString(Charsets.UTF_8).trimEnd('\u0000')
                }
            }
            return ElfInfo(machine = machine, interpreter = interpreter)
        }
    }

    fun requireAarch64(file: File): ElfInfo = inspect(file).also { info ->
        require(info.machine == EM_AARCH64) {
            "ELF 架构不是 ARM64（machine=${info.machine}）：${file.absolutePath}"
        }
    }

    data class ElfInfo(
        val machine: Int,
        val interpreter: String?,
    )

    companion object {
        const val EM_AARCH64 = 183

        private const val ELF64_HEADER_SIZE = 64
        private const val ELF64_PROGRAM_HEADER_SIZE = 56
        private const val MAX_INTERPRETER_BYTES = 4096
        private const val ELFCLASS64 = 2
        private const val ELFDATA2LSB = 1
        private const val PT_INTERP = 3
        private const val EI_CLASS = 4
        private const val EI_DATA = 5
        private const val EMACHINE_OFFSET = 18
        private const val PHOFF_OFFSET = 32
        private const val PHENTSIZE_OFFSET = 54
        private const val PHNUM_OFFSET = 56
        private const val PTYPE_OFFSET = 0
        private const val POFFSET_OFFSET = 8
        private const val PFILESZ_OFFSET = 32

        private fun ByteArray.u16(offset: Int): Int =
            (this[offset].toInt() and 0xff) or
                ((this[offset + 1].toInt() and 0xff) shl 8)

        private fun ByteArray.u32(offset: Int): Long =
            (this[offset].toLong() and 0xff) or
                ((this[offset + 1].toLong() and 0xff) shl 8) or
                ((this[offset + 2].toLong() and 0xff) shl 16) or
                ((this[offset + 3].toLong() and 0xff) shl 24)

        private fun ByteArray.u64(offset: Int): Long {
            var value = 0L
            for (index in 0 until 8) {
                value = value or ((this[offset + index].toLong() and 0xff) shl (index * 8))
            }
            return value
        }
    }
}
