package top.wanxiang.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEndpointPolicyTest {
    @Test
    fun acceptsHttpsAndHttpHosts() {
        assertTrue(ProviderEndpointPolicy.isSafeBaseUrl("https://api.example.com/v1"))
        assertTrue(ProviderEndpointPolicy.isSafeBaseUrl("http://www.wlgs.top:8521/v1"))
        assertTrue(ProviderEndpointPolicy.isSafeBaseUrl("http://localhost:8080/v1"))
        assertTrue(ProviderEndpointPolicy.isSafeBaseUrl("http://127.0.0.1:8080"))
        assertTrue(ProviderEndpointPolicy.isSafeBaseUrl("http://[::1]:8080"))
        assertTrue(ProviderEndpointPolicy.isSafeBaseUrl("http://192.168.1.100:11434/v1"))
        assertTrue(ProviderEndpointPolicy.isSafeBaseUrl("http://10.0.0.5:8000"))
    }

    @Test
    fun normalizesUnicodeDashesAndFullwidthChars() {
        // Unicode U+2011 (non-breaking hyphen)
        val unicodeHyphenUrl = "https://api.agnes\u2011ai.cn/v1"
        assertTrue(ProviderEndpointPolicy.isSafeBaseUrl(unicodeHyphenUrl))
        assertEquals("https://api.agnes-ai.cn/v1", ProviderEndpointPolicy.normalizeUrl(unicodeHyphenUrl))

        // Fullwidth colon, slashes, and dots
        val fullwidthUrl = "https：／／api.example.com／v1"
        assertEquals("https://api.example.com/v1", ProviderEndpointPolicy.normalizeUrl(fullwidthUrl))
        assertTrue(ProviderEndpointPolicy.isSafeBaseUrl(fullwidthUrl))

        // Automatic scheme completion
        assertEquals("https://api.agnes-ai.cn/v1", ProviderEndpointPolicy.normalizeUrl("api.agnes-ai.cn/v1"))
        assertEquals("http://192.168.1.50:11434/v1", ProviderEndpointPolicy.normalizeUrl("192.168.1.50:11434/v1"))
    }

    @Test
    fun rejectsLookalikeHostsAndUnsafeSchemes() {
        assertFalse(ProviderEndpointPolicy.isSafeBaseUrl("file:///data/local/tmp/api"))
        assertFalse(ProviderEndpointPolicy.isSafeBaseUrl("https://user:pass@example.com/v1"))
        assertFalse(ProviderEndpointPolicy.isSafeBaseUrl("javascript:alert(1)"))
    }
}
