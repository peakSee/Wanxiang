package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmRequestCacheTest {

    private fun result(text: String) = ChatResult(content = text, toolCalls = emptyList())

    @Test
    fun `get returns cached result within ttl`() {
        val cache = LlmRequestCache()
        cache.put("k", result("hello"))
        assertEquals("hello", cache.get("k")?.content)
    }

    @Test
    fun `get returns null after ttl expires`() {
        var now = 0L
        val cache = LlmRequestCache(clock = { now })
        cache.put("k", result("hello"))
        now = 60_001L
        assertNull(cache.get("k"))
    }

    @Test
    fun `get refreshes lru order`() {
        val cache = LlmRequestCache(maxEntries = 2)
        cache.put("a", result("a"))
        cache.put("b", result("b"))
        cache.get("a") // 访问 a，a 变最近使用
        cache.put("c", result("c")) // 淘汰最久未用的 b
        assertEquals("a", cache.get("a")?.content)
        assertNull(cache.get("b"))
        assertEquals("c", cache.get("c")?.content)
    }

    @Test
    fun `evicts oldest entry when exceeding max entries`() {
        val cache = LlmRequestCache(maxEntries = 2)
        cache.put("a", result("a"))
        cache.put("b", result("b"))
        cache.put("c", result("c"))
        assertNull(cache.get("a"))
        assertEquals("b", cache.get("b")?.content)
        assertEquals("c", cache.get("c")?.content)
    }
}
