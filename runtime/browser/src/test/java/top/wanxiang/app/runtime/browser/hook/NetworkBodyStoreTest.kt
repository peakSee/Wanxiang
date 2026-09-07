package top.wanxiang.app.runtime.browser.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkBodyStoreTest {

    private fun body(id: String, reqLen: Int, resLen: Int, tabId: String = "t") =
        NetworkBodyStore.NetworkBody(
            id = id,
            tabId = tabId,
            requestBody = "r".repeat(reqLen),
            responseBody = "s".repeat(resLen),
        )

    @Test
    fun `put and get round trip`() {
        val s = NetworkBodyStore()
        s.put(body("n1", 10, 20))
        val got = s.get("n1")
        assertNotNull(got)
        assertEquals(10, got!!.requestBody.length)
        assertEquals(30, s.totalStoredBytes())
    }

    @Test
    fun `oversized entry rejected`() {
        val s = NetworkBodyStore(totalBudgetBytes = 100)
        s.put(body("big", 200, 0))
        assertNull(s.get("big"))
        assertEquals(0, s.totalStoredBytes())
    }

    @Test
    fun `budget overflow evicts oldest`() {
        val s = NetworkBodyStore(totalBudgetBytes = 150)
        s.put(body("old", 50, 50))       // 100B
        Thread.sleep(2)
        s.put(body("mid", 20, 20))       // 40B → 140B
        Thread.sleep(2)
        s.put(body("new", 50, 50))       // 100B → 240B > 150 → 逐出 old（替换为大小标记）
        val old = s.get("old")
        assertNotNull(old)
        assertTrue(old!!.requestBody.startsWith("[evicted"))
        assertNotNull(s.get("mid"))
        assertNotNull(s.get("new"))
        assertTrue(s.totalStoredBytes() <= 150 + 64) // 逐出标记本身占少量字节
    }

    @Test
    fun `clearForTab only touches given tab`() {
        val s = NetworkBodyStore()
        s.put(body("a", 5, 5, tabId = "tabA"))
        s.put(body("b", 5, 5, tabId = "tabB"))
        s.clearForTab("tabA")
        assertNull(s.get("a"))
        assertNotNull(s.get("b"))
    }

    @Test
    fun `clear removes everything`() {
        val s = NetworkBodyStore()
        s.put(body("a", 5, 5))
        s.put(body("b", 5, 5))
        s.clear()
        assertEquals(0, s.size())
        assertEquals(0L, s.totalStoredBytes())
    }
}
