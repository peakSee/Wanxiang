package top.wanxiang.app.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubagentConcurrencyGateTest {
    @Test
    fun `three lanes run concurrently while a fourth waits`() = runBlocking {
        val gate = SubagentConcurrencyGate()
        val started = Channel<Int>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val jobs = (1..4).map { lane ->
            async {
                gate.withPermit {
                    started.send(lane)
                    release.await()
                }
            }
        }

        val running = buildSet {
            repeat(DEFAULT_MAX_CONCURRENT_SUBAGENTS) {
                add(withTimeout(1_000) { started.receive() })
            }
        }
        assertEquals(DEFAULT_MAX_CONCURRENT_SUBAGENTS, running.size)
        assertNull(withTimeoutOrNull(100) { started.receive() })

        release.complete(Unit)
        withTimeout(1_000) { started.receive() }
        jobs.awaitAll()
        Unit
    }
}
