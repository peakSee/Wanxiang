package top.wanxiang.app.runtime.proot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuestGroupsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rootfsWithGroupFile(content: String): Pair<File, File> {
        val rootfs = tmp.newFolder("rootfs")
        val etc = File(rootfs, "etc").apply { mkdirs() }
        val groupFile = File(etc, "group").apply { writeText(content) }
        return rootfs to groupFile
    }

    @Test
    fun `appends missing host gids with valid group entry format`() {
        val (rootfs, groupFile) = rootfsWithGroupFile("root:x:0:\ninet:x:3003:\n")
        syncGuestGroups(rootfs, listOf(3003, 9997, 21267))
        val lines = groupFile.readLines()
        assertTrue(lines.contains("root:x:0:"))
        assertEquals(listOf("host_g9997:x:9997:", "host_g21267:x:21267:"), lines.drop(2))
    }

    @Test
    fun `is idempotent across repeated syncs`() {
        val (rootfs, groupFile) = rootfsWithGroupFile("root:x:0:\n")
        syncGuestGroups(rootfs, listOf(9997, 3003))
        syncGuestGroups(rootfs, listOf(9997, 3003, 51267))
        val lines = groupFile.readLines()
        assertEquals(4, lines.size)
        assertTrue(lines.contains("host_g9997:x:9997:"))
        assertTrue(lines.contains("host_g3003:x:3003:"))
        assertTrue(lines.contains("host_g51267:x:51267:"))
    }

    @Test
    fun `parses gids even when groups carry member lists`() {
        val (rootfs, groupFile) = rootfsWithGroupFile("adm:x:4:syslog,root\n")
        syncGuestGroups(rootfs, listOf(4))
        assertEquals("adm:x:4:syslog,root", groupFile.readText().trim())
    }

    @Test
    fun `skips silently when group file or host groups absent`() {
        val rootfs = tmp.newFolder("empty-rootfs")
        syncGuestGroups(rootfs, listOf(9997)) // 无 /etc/group，不抛异常
        assertFalse(File(rootfs, "etc/group").exists())

        val (validRootfs, groupFile) = rootfsWithGroupFile("root:x:0:\n")
        syncGuestGroups(validRootfs, emptyList())
        assertEquals("root:x:0:", groupFile.readText().trim())
    }
}
