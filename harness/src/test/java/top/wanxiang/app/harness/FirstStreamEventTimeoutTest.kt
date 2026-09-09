package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstStreamEventTimeoutTest {
    @Test
    fun `resolveFirstEventTimeout scales with estimated input tokens`() {
        assertEquals(90_000L, ProviderClient.resolveFirstEventTimeoutMs(1_000))
        assertEquals(90_000L, ProviderClient.resolveFirstEventTimeoutMs(40_000))
        assertEquals(150_000L, ProviderClient.resolveFirstEventTimeoutMs(40_001))
        assertEquals(150_000L, ProviderClient.resolveFirstEventTimeoutMs(80_000))
        assertEquals(240_000L, ProviderClient.resolveFirstEventTimeoutMs(80_001))
        assertEquals(240_000L, ProviderClient.resolveFirstEventTimeoutMs(104_122))
    }
}
