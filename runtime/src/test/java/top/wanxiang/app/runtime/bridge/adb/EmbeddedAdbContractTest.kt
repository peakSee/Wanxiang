package top.wanxiang.app.runtime.bridge.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedAdbContractTest {

    @Test
    fun logcatRequestDefaultsAreValid() {
        val request = EmbeddedAdbManager.LogcatRequest()
        assertEquals("", request.packageName)
        assertEquals("", request.tag)
        assertEquals('V', request.priority)
        assertEquals("", request.keyword)
        assertEquals(200, request.lines)
    }

    @Test
    fun connectionStatesExposeExpectedProperties() {
        val connected = EmbeddedAdbManager.ConnectionState.Connected("127.0.0.1", 5555)
        assertEquals("127.0.0.1", connected.host)
        assertEquals(5555, connected.port)

        val failed = EmbeddedAdbManager.ConnectionState.Failed("配对失败")
        assertEquals("配对失败", failed.message)
    }

    @Test
    fun discoveryStateTracksEndpoints() {
        val pairing = listOf(EmbeddedAdbManager.Endpoint("pairing-service", "127.0.0.1", 37159))
        val connect = listOf(EmbeddedAdbManager.Endpoint("connect-service", "127.0.0.1", 41235))
        val state = EmbeddedAdbManager.DiscoveryState(
            running = true,
            pairingEndpoints = pairing,
            connectEndpoints = connect,
        )
        assertTrue(state.running)
        assertEquals(1, state.pairingEndpoints.size)
        assertEquals(37159, state.pairingEndpoints.first().port)
        assertEquals(1, state.connectEndpoints.size)
        assertEquals(41235, state.connectEndpoints.first().port)
    }

    @Test
    fun shellOutcomeReflectsStatus() {
        val successOutcome = EmbeddedAdbManager.ShellOutcome(0, "ok\n", true)
        assertEquals(0, successOutcome.exitCode)
        assertTrue(successOutcome.success)
        assertEquals("ok\n", successOutcome.output)

        val failureOutcome = EmbeddedAdbManager.ShellOutcome(1, "error", false)
        assertEquals(1, failureOutcome.exitCode)
        assertFalse(failureOutcome.success)
    }
}
