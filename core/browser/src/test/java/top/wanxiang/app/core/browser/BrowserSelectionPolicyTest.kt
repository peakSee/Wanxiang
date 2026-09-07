package top.wanxiang.app.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSelectionPolicyTest {
    private val allHealthy = mapOf(
        BrowserFamily.IN_APP to true,
        BrowserFamily.EXTERNAL_CT to true,
        BrowserFamily.REMOTE_CDP to true
    )

    @Test fun `user-explicit wins`() {
        val sel = BrowserSelectionPolicy.decide(
            requested = BrowserFamily.EXTERNAL_CT,
            urlHint = null,
            prefs = BrowserPreferences.DEFAULT,
            families = allHealthy
        )
        assertEquals(BrowserFamily.EXTERNAL_CT, sel.family)
        assertEquals("user-explicit", sel.reason)
    }

    @Test fun `requested but unhealthy falls back to in-app`() {
        val sel = BrowserSelectionPolicy.decide(
            requested = BrowserFamily.REMOTE_CDP,
            urlHint = null,
            prefs = BrowserPreferences.DEFAULT,
            families = mapOf(BrowserFamily.IN_APP to true, BrowserFamily.REMOTE_CDP to false)
        )
        assertEquals(BrowserFamily.IN_APP, sel.family)
        assertTrue(sel.reason.contains("fallback"))
    }

    @Test fun `loopback url forces in-app`() {
        val sel = BrowserSelectionPolicy.decide(
            requested = null,
            urlHint = "http://127.0.0.1:8080/admin",
            prefs = BrowserPreferences.DEFAULT,
            families = allHealthy
        )
        assertEquals(BrowserFamily.IN_APP, sel.family)
        assertEquals("url-host-loopback", sel.reason)
    }

    @Test fun `default fallback when prefs-resolved family unhealthy`() {
        val sel = BrowserSelectionPolicy.decide(
            requested = null,
            urlHint = null,
            prefs = BrowserPreferences(defaultFamily = BrowserFamily.REMOTE_CDP.name),
            families = mapOf(BrowserFamily.IN_APP to true, BrowserFamily.REMOTE_CDP to false)
        )
        assertEquals(BrowserFamily.IN_APP, sel.family)
        assertTrue(sel.reason.contains("default-by-prefs unavailable"))
    }
}

