package top.wanxiang.app.harness.mcp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.model.McpServerConfig
import top.wanxiang.app.core.model.McpTransportType

class McpStdioTransportLifecycleTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val commandBuilder = McpCommandBuilder()

    private val echoServer = McpServerConfig(
        id = "echo",
        name = "echo",
        transportType = McpTransportType.STDIO,
        command = "/bin/echo",
        args = listOf("hello"),
    )

    private fun newTransport(factory: McpStdioChannelFactory): McpStdioTransport =
        McpStdioTransport(json, commandBuilder, factory)

    @Test
    fun `idle sweep reaps stale connections but skips busy ones`() = runBlocking {
        val factory = RecordingFactory()
        val transport = newTransport(factory)
        repeat(3) { i ->
            val server = echoServer.copy(id = "server-" + i)
            transport.injectConnection(server, FakeMcpChannel(aliveAfterOpen = true))
        }
        assertEquals(3, transport.test_connectionKeys().size)
        val busyId = transport.test_connectionKeys().first()
        transport.test_markConnectionInFlight(busyId, true)
        transport.rewindIdleForTest(McpStdioTransport.IDLE_TIMEOUT_MS + 60_000L)
        val reaped = transport.sweepIdleOnce(System.currentTimeMillis())
        assertEquals("only the two idle ones should be reaped", 2, reaped)
        assertTrue(transport.test_connectionKeys().contains(busyId))
        transport.test_connectionKeys().forEach { id ->
            if (id == busyId) return@forEach
            assertFalse("idle connection must be evicted: " + id, transport.test_connectionKeys().contains(id))
        }
    }

    @Test
    fun `markActive refreshes last activity so a freshly used connection is not reaped`() = runBlocking {
        val transport = newTransport(RecordingFactory())
        val id = "fresh"
        transport.injectConnection(echoServer.copy(id = id), FakeMcpChannel(aliveAfterOpen = true))
        transport.rewindIdleForTest(McpStdioTransport.IDLE_TIMEOUT_MS + 60_000L)
        transport.test_markConnectionActive(id)
        assertEquals(0, transport.sweepIdleOnce(System.currentTimeMillis()))
        assertTrue(transport.test_connectionKeys().contains(id))
    }

    @Test
    fun `startup timeout applies a fail-fast cooldown`() = runBlocking {
        val factory = HangingFactory()
        val transport = newTransport(factory)
        // First attempt hangs past the startup budget; withTimeoutOrNull caps the wait so the
        // test does not block for the full cooldown duration.
        withTimeoutOrNull<Unit>(McpStdioTransport.STARTUP_TIMEOUT_MS * 2 + 2_000L) {
            runCatching { transport.discover(echoServer) }
        }
        val second = runCatching { transport.discover(echoServer) }.exceptionOrNull()
        assertNotNull("second attempt must short-circuit via cooldown", second)
        assertTrue(
            "second failure should report cooldown, got: " + second!!.message,
            second.message.orEmpty().contains("冷却"),
        )
    }

    @Test
    fun `excess garbage frames trip the ignore threshold instead of stalling`() = runBlocking {
        val channel = FakeMcpChannel(aliveAfterOpen = true).also { runBlocking { it.feedGarbage(McpStdioTransport.MAX_IGNORED_FRAMES + 8) } }
        val transport = newTransport(FakeChannelFactory(channel))
        val failure: Throwable? = withTimeoutOrNull<Throwable?>(5_000L) {
            runCatching { transport.discover(echoServer) }.exceptionOrNull()
        }
        assertTrue("discover must not hang past the ignore-frame threshold", failure != null)
        assertTrue("transport must not retain a poisoned channel", transport.test_connectionKeys().isEmpty())
    }

    @Test
    fun `process death during request is reported instead of stalling`() = runBlocking {
        val channel = FakeMcpChannel(aliveAfterOpen = true)
        val transport = newTransport(FakeChannelFactory(channel))
        transport.injectConnection(echoServer, channel)
        channel.killIncoming("server crashed")
        val failure = withTimeoutOrNull<Throwable?>(5_000L) {
        runCatching { transport.discover(echoServer) }.exceptionOrNull()
        }
        assertNotNull("discovery must surface channel death as a failure", failure)
        assertFalse(
            "cooldown must NOT trigger on channel death (subprocess died, not sandbox)",
            failure!!.message.orEmpty().contains("冷却"),
        )
    }

    @Test
    fun `cooldown is cleared when a subsequent startup succeeds`() = runBlocking {
        val factory = ConditionalFactory(
            first = HangingFactory(),
            second = FakeChannelFactory(RespondingMcpChannel()),
        )
        val transport = newTransport(factory)
        withTimeoutOrNull<Unit>(McpStdioTransport.STARTUP_TIMEOUT_MS * 2 + 2_000L) {
            runCatching { transport.discover(echoServer) }
            factory.switch()
            // Manual checks intentionally bypass cooldown. A successful startup must clear it.
            assertTrue(transport.check(echoServer))
        }
        val third = runCatching { transport.discover(echoServer) }
        assertTrue("discover should reuse the successful connection, got: " + third.exceptionOrNull()?.message, third.isSuccess)
    }

    private class FakeMcpChannel(
        private val aliveAfterOpen: Boolean,
        // Tests preload more than MAX_IGNORED_FRAMES before discover() starts consuming.
        // An unbounded fake keeps that setup synchronous without coupling it to producer timing.
        override val incoming: Channel<String> = Channel(capacity = Channel.UNLIMITED),
    ) : McpStdioChannel {
        @Volatile
        private var alive: Boolean = aliveAfterOpen
        override val isAlive: Boolean get() = alive
        override suspend fun writeLine(line: String) { if (!alive) error("write after close") }
        override suspend fun close() { alive = false; incoming.close() }
        fun killIncoming(reason: String) { alive = false; incoming.close(IllegalStateException(reason)) }
        suspend fun feedGarbage(count: Int) { for (i in 0 until count) incoming.send("garbage " + i) }
    }

    private class FakeChannelFactory(private val channel: McpStdioChannel) : McpStdioChannelFactory {
        override suspend fun open(server: McpServerConfig): McpStdioChannel = channel
    }

    /** Minimal protocol-aware fake used when a test needs initialization to really succeed. */
    private class RespondingMcpChannel : McpStdioChannel {
        override val incoming: Channel<String> = Channel(Channel.UNLIMITED)
        @Volatile private var alive = true
        override val isAlive: Boolean get() = alive

        override suspend fun writeLine(line: String) {
            val id = Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(line)?.groupValues?.get(1) ?: return
            val result = when {
                "\"method\":\"initialize\"" in line -> "{\"protocolVersion\":\"$MCP_PROTOCOL_VERSION\"}"
                "\"method\":\"tools/list\"" in line -> "{\"tools\":[]}"
                else -> return
            }
            incoming.send("{\"jsonrpc\":\"2.0\",\"id\":\"$id\",\"result\":$result}")
        }

        override suspend fun close() {
            alive = false
            incoming.close()
        }
    }

    private class HangingFactory : McpStdioChannelFactory {
        var openAttempts = 0
        override suspend fun open(server: McpServerConfig): McpStdioChannel {
            openAttempts++
            kotlinx.coroutines.awaitCancellation()
        }
    }

    private class ConditionalFactory(
        first: McpStdioChannelFactory,
        second: McpStdioChannelFactory,
    ) : McpStdioChannelFactory {
        private var current: McpStdioChannelFactory = first
        private val next: McpStdioChannelFactory = second
        fun switch() { current = next }
        override suspend fun open(server: McpServerConfig): McpStdioChannel = current.open(server)
    }

    private class RecordingFactory : McpStdioChannelFactory {
        var created = 0
        override suspend fun open(server: McpServerConfig): McpStdioChannel {
            created++
            return FakeMcpChannel(aliveAfterOpen = true)
        }
    }
}

internal fun McpStdioTransport.injectConnection(server: McpServerConfig, channel: McpStdioChannel) {
    this.injectConnectionForTest(server, channel)
}

/** Test-only lifecycle probes. Reflection stays out of the production artifact. */
internal fun McpStdioTransport.rewindIdleForTest(ageMs: Long) {
    val target = System.currentTimeMillis() - ageMs
    reflectedConnections().values.forEach { connection ->
        val field = connection.javaClass.getDeclaredField("lastActivityMs").apply { isAccessible = true }
        field.setLong(connection, target)
    }
}

@Suppress("FunctionName")
internal fun McpStdioTransport.test_connectionKeys(): Set<String> {
    val field = McpStdioTransport::class.java.getDeclaredField("connections").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val map = field.get(this) as java.util.concurrent.ConcurrentHashMap<String, Any>
    return map.keys.toSet()
}

@Suppress("FunctionName")
internal fun McpStdioTransport.test_markConnectionActive(serverId: String) {
    val connections = reflectedConnections()
    val connection = connections[serverId] ?: error("no connection for $serverId")
    val method = connection.javaClass.getDeclaredMethod("markActive").apply { isAccessible = true }
    method.invoke(connection)
}

@Suppress("FunctionName")
internal fun McpStdioTransport.test_markConnectionInFlight(serverId: String, inFlight: Boolean) {
    val connections = reflectedConnections()
    val connection = connections[serverId] ?: error("no connection for $serverId")
    val field = connection.javaClass.getDeclaredField("mutex").apply { isAccessible = true }
    val mutex = field.get(connection) as kotlinx.coroutines.sync.Mutex
    if (inFlight) runBlocking { mutex.lock() } else mutex.unlock()
}

private fun McpStdioTransport.reflectedConnections(): java.util.concurrent.ConcurrentHashMap<String, Any> {
    val field = McpStdioTransport::class.java.getDeclaredField("connections").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    return field.get(this) as java.util.concurrent.ConcurrentHashMap<String, Any>
}
