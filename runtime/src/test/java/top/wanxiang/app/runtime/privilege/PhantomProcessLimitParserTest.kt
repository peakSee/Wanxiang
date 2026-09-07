package top.wanxiang.app.runtime.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhantomProcessLimitParserTest {

    @Test
    fun detectsRaisedProcessLimit() {
        val status = parsePhantomProcessLimit("max=2147483647\nmonitor=true\n")

        assertEquals(PhantomProcessLimitState.REMOVED, status.state)
        assertEquals(2147483647L, status.maxPhantomProcesses)
        assertEquals(true, status.monitoringEnabled)
    }

    @Test
    fun detectsDisabledMonitor() {
        val status = parsePhantomProcessLimit("max=32\nmonitor=false\n")

        assertEquals(PhantomProcessLimitState.REMOVED, status.state)
        assertEquals(false, status.monitoringEnabled)
    }

    @Test
    fun treatsUnsetValuesAsSystemDefaultLimit() {
        val status = parsePhantomProcessLimit("max=null\nmonitor=null\n")

        assertEquals(PhantomProcessLimitState.ACTIVE, status.state)
        assertNull(status.maxPhantomProcesses)
        assertNull(status.monitoringEnabled)
    }

    @Test
    fun detectsExplicitActiveLimit() {
        val status = parsePhantomProcessLimit("max=32\nmonitor=true\n")

        assertEquals(PhantomProcessLimitState.ACTIVE, status.state)
        assertEquals(32L, status.maxPhantomProcesses)
        assertEquals(true, status.monitoringEnabled)
    }
}
