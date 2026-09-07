package top.wanxiang.app.harness.mcp

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class McpEndpointPolicyTest {
    @Test
    fun `uses the protocol version exposed by apk mcp`() {
        assertEquals("2025-06-18", MCP_PROTOCOL_VERSION)
        assertEquals(MCP_PROTOCOL_VERSION, McpInitializeParams().protocolVersion)
    }

    @Test
    fun `allows loopback and 192 168 cleartext hosts`() {
        assertTrue(isAllowedCleartextMcpHost("localhost"))
        assertTrue(isAllowedCleartextMcpHost("127.0.0.1"))
        assertTrue(isAllowedCleartextMcpHost("::1"))
        assertTrue(isAllowedCleartextMcpHost("192.168.0.1"))
        assertTrue(isAllowedCleartextMcpHost("192.168.31.113"))
        assertTrue(isAllowedCleartextMcpHost("192.168.255.255"))
    }

    @Test
    fun `rejects other or malformed cleartext hosts`() {
        assertFalse(isAllowedCleartextMcpHost("10.0.0.1"))
        assertFalse(isAllowedCleartextMcpHost("172.19.0.1"))
        assertFalse(isAllowedCleartextMcpHost("example.com"))
        assertFalse(isAllowedCleartextMcpHost("192.169.0.1"))
        assertFalse(isAllowedCleartextMcpHost("192.168.256.1"))
        assertFalse(isAllowedCleartextMcpHost("192.168.1"))
    }

    @Test
    fun `preserves the configured mcp path without appending rest routes`() {
        val endpoint = validatedMcpHttpEndpoint("http://127.0.0.1:8787/mcp")

        assertEquals("127.0.0.1", endpoint.host)
        assertEquals(8787, endpoint.port)
        assertEquals("/mcp", endpoint.encodedPath)
    }

    @Test
    fun `requires https outside the allowed local ranges`() {
        assertThrows(IllegalArgumentException::class.java) {
            validatedMcpHttpEndpoint("http://example.com/mcp")
        }
        assertEquals(
            "https://example.com/mcp",
            validatedMcpHttpEndpoint("https://example.com/mcp").toString(),
        )
    }

    @Test
    fun `legacy message endpoint must remain on the configured origin`() {
        val base = validatedMcpHttpEndpoint("https://example.com/sse")
        assertEquals(
            "https://example.com/messages",
            validatedDerivedMcpEndpoint(base, base.resolve("/messages")!!).toString(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            validatedDerivedMcpEndpoint(base, validatedMcpHttpEndpoint("https://attacker.example/messages"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedDerivedMcpEndpoint(base, "http://example.com/messages".toHttpUrl())
        }
    }
}
