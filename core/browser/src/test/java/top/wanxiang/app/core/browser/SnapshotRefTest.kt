package top.wanxiang.app.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class SnapshotRefTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `SnapshotRef roundtrip`() {
        val ref = SnapshotRef(
            ref = "e12",
            tag = "input",
            type = "email",
            role = "input",
            name = "email",
            text = null,
            placeholder = "you@example.com",
            ariaLabel = null,
            interactive = true
        )
        val s = json.encodeToString(SnapshotRef.serializer(), ref)
        val back = json.decodeFromString(SnapshotRef.serializer(), s)
        assertEquals(ref, back)
    }

    @Test fun `PageSnapshot keeps ref lookup`() {
        val refs = mapOf(
            "e1" to SnapshotRef("e1", "button", text = "OK", interactive = true),
            "e2" to SnapshotRef("e2", "div", text = "ignore", interactive = false)
        )
        val snap = PageSnapshot(
            tabId = "t1", url = "https://example.com", title = "Ex",
            refs = refs, domFingerprint = "abc", createdAt = 0L
        )
        assertEquals(refs["e1"], snap.refOf("e1"))
        assertNull(snap.refOf("e99"))
        assertEquals(1, snap.interactiveRefs.size)
        assertTrue(snap.interactiveRefs.first().interactive)
    }

    @Test fun `BrowserFamily parsing handles null and garbage`() {
        assertEquals(BrowserFamily.IN_APP, BrowserFamily.fromRaw(null))
        assertEquals(BrowserFamily.IN_APP, BrowserFamily.fromRaw("garbage"))
        assertEquals(BrowserFamily.EXTERNAL_CT, BrowserFamily.fromRaw("chrome_ct"))
        assertEquals(BrowserFamily.REMOTE_CDP, BrowserFamily.fromRaw("remote-cdp"))
    }
}
