package top.wanxiang.app.runtime.rootfs

import top.wanxiang.app.runtime.ElfInspector
import top.wanxiang.app.runtime.ElfInspectorTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootfsValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validatesShellAndItsActualElfInterpreter() {
        val rootfs = validRootfs()

        val validation = RootfsValidator(ElfInspector()).validate(rootfs)

        assertEquals("/usr/bin/bash", validation.bashPath)
        assertEquals("/bin/sh", validation.posixShellPath)
        assertEquals("/lib/ld-linux-aarch64.so.1", validation.interpreterPath)
    }

    @Test
    fun rejectsRootfsWhenInterpreterIsMissing() {
        val rootfs = validRootfs()
        File(rootfs, "lib/ld-linux-aarch64.so.1").delete()

        val failure = runCatching { RootfsValidator(ElfInspector()).validate(rootfs) }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("ELF 解释器不存在"))
    }

    @Test
    fun rejectsWrongArchitectureShell() {
        val rootfs = validRootfs()
        ElfInspectorTest.writeElf(
            File(rootfs, "usr/bin/bash"),
            machine = 62,
            interpreter = "/lib/ld-linux-aarch64.so.1",
        )

        val failure = runCatching { RootfsValidator(ElfInspector()).validate(rootfs) }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("不是 ARM64"))
    }

    private fun validRootfs(): File {
        val rootfs = temporaryFolder.newFolder("rootfs")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/os-release").writeText("ID=debian\n")
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "bin").mkdirs()
        File(rootfs, "lib").mkdirs()
        ElfInspectorTest.writeElf(
            File(rootfs, "usr/bin/bash"),
            interpreter = "/lib/ld-linux-aarch64.so.1",
        )
        ElfInspectorTest.writeElf(
            File(rootfs, "bin/sh"),
            interpreter = "/lib/ld-linux-aarch64.so.1",
        )
        ElfInspectorTest.writeElf(File(rootfs, "lib/ld-linux-aarch64.so.1"))
        return rootfs
    }
}
