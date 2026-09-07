package top.wanxiang.app.core.network

import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksumVerifierTest {
    @Test
    fun `sha256 and verify on file`() {
        val file = File.createTempFile("chk", ".txt")
        file.deleteOnExit()
        file.writeText("hello wanxiang")
        val verifier = ChecksumVerifier()
        val digest = verifier.sha256(file)
        assertTrue(digest.matches(Regex("[0-9a-f]{64}")))
        verifier.verify(file, digest)
        assertThrows(DownloadError.ChecksumMismatch::class.java) {
            verifier.verify(file, "0".repeat(64))
        }
    }
}
