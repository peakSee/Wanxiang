package top.wanxiang.app.runtime.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthUrlDetectorTest {
    private val detector = AuthUrlDetector()

    @Test
    fun findsOAuthAndDeviceLinksOnly() {
        val links = detector.find(
            "Open https://auth.example.test/oauth/authorize?client_id=demo. " +
                "Docs: https://example.test/docs",
        )

        assertEquals(1, links.size)
        assertEquals("auth.example.test", links.single().host)
    }

    @Test
    fun keepsDistinctLinksAndRequiresHttps() {
        val links = detector.find(
            "http://example.test/login https://example.test/login https://example.test/login",
        )

        assertTrue(links.map { it.url }.single() == "https://example.test/login")
    }
}
