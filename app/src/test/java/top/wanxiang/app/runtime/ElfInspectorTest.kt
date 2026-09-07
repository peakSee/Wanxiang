package top.wanxiang.app.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ElfInspectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsAarch64InterpreterFromProgramHeaders() {
        val elf = temporaryFolder.newFile("bash")
        writeElf(elf, interpreter = "/lib/ld-linux-aarch64.so.1")

        val info = ElfInspector().requireAarch64(elf)

        assertEquals(ElfInspector.EM_AARCH64, info.machine)
        assertEquals("/lib/ld-linux-aarch64.so.1", info.interpreter)
    }

    @Test
    fun acceptsStaticAarch64LoaderWithoutInterpreter() {
        val elf = temporaryFolder.newFile("loader")
        writeElf(elf)

        assertNull(ElfInspector().requireAarch64(elf).interpreter)
    }

    companion object {
        fun writeElf(
            file: File,
            machine: Int = ElfInspector.EM_AARCH64,
            interpreter: String? = null,
        ) {
            val header = ByteArray(64)
            header[0] = 0x7f.toByte()
            header[1] = 'E'.code.toByte()
            header[2] = 'L'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = 2.toByte()
            header[5] = 1.toByte()
            header.putU16(18, machine)
            if (interpreter == null) {
                file.writeBytes(header)
                return
            }

            val interpreterBytes = (interpreter + "\u0000").toByteArray()
            val programHeader = ByteArray(56)
            header.putU64(32, 64)
            header.putU16(54, 56)
            header.putU16(56, 1)
            programHeader.putU32(0, 3)
            programHeader.putU64(8, 64 + 56L)
            programHeader.putU64(32, interpreterBytes.size.toLong())
            file.writeBytes(header + programHeader + interpreterBytes)
        }

        private fun ByteArray.putU16(offset: Int, value: Int) {
            this[offset] = value.toByte()
            this[offset + 1] = (value ushr 8).toByte()
        }

        private fun ByteArray.putU32(offset: Int, value: Int) {
            repeat(4) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
        }

        private fun ByteArray.putU64(offset: Int, value: Long) {
            repeat(8) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
        }
    }
}
