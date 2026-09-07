package top.wanxiang.app.runtime.browser.hook

import java.util.concurrent.atomic.AtomicLong

/**
 * 网络请求/响应 body 存储（阶段 1 只存文本；二进制以 `"[binary N bytes]"` 占位）。
 *
 * 总字节预算 LRU 逐出：超出 [totalBudgetBytes] 时淘汰最旧条目（body 替换为大小标记）。
 */
class NetworkBodyStore(
    private val totalBudgetBytes: Long = 6L * 1024 * 1024,
) {
    data class NetworkBody(
        val id: String,
        val tabId: String,
        val requestBody: String,
        val responseBody: String,
        val at: Long = System.currentTimeMillis(),
    ) {
        val bytes: Long get() = requestBody.length.toLong() + responseBody.length.toLong()
    }

    private val lock = Any()
    // accessOrder=true：get 即刷新新旧
    private val map = LinkedHashMap<String, NetworkBody>(32, 0.75f, true)
    private val totalBytes = AtomicLong(0)

    fun put(body: NetworkBody) {
        if (body.bytes > totalBudgetBytes) return
        synchronized(lock) {
            removeLocked(body.id)
            map[body.id] = body
            totalBytes.addAndGet(body.bytes)
            evictLocked()
        }
    }

    fun get(id: String): NetworkBody? = synchronized(lock) { map[id] }

    fun size(): Int = synchronized(lock) { map.size }

    fun totalStoredBytes(): Long = totalBytes.get()

    fun clear() {
        synchronized(lock) {
            map.clear()
            totalBytes.set(0)
        }
    }

    fun clearForTab(tabId: String) {
        synchronized(lock) {
            val before = map.size
            map.entries.removeIf { it.value.tabId == tabId }
            if (map.size != before) recountLocked()
        }
    }

    private fun removeLocked(id: String) {
        map.remove(id)?.let { totalBytes.addAndGet(-it.bytes) }
    }

    private fun evictLocked() {
        val toEvict = ArrayList<NetworkBody>()
        for (body in map.values) {
            if (totalBytes.get() <= totalBudgetBytes) break
            toEvict.add(body)
        }
        toEvict.forEach { body ->
            val marker = NetworkBody(
                id = body.id,
                tabId = body.tabId,
                requestBody = "[evicted ${body.requestBody.length}B]",
                responseBody = "[evicted ${body.responseBody.length}B]",
                at = body.at,
            )
            map[body.id] = marker
            totalBytes.addAndGet(marker.bytes - body.bytes)
        }
    }

    private fun recountLocked() {
        totalBytes.set(map.values.sumOf { it.bytes })
    }
}
