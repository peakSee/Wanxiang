package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessRetryPolicyTest {
    @Test
    fun `large request limits automatic retries to one`() {
        assertEquals(1, HarnessLoop.maxNetworkRetriesFor(64_000, 3))
        assertEquals(1, HarnessLoop.maxNetworkRetriesFor(100_000, 5))
    }

    @Test
    fun `small request keeps configured retry count`() {
        assertEquals(3, HarnessLoop.maxNetworkRetriesFor(63_999, 3))
    }

    @Test
    fun `large request never increases a stricter policy`() {
        assertEquals(0, HarnessLoop.maxNetworkRetriesFor(80_000, 0))
    }
}
