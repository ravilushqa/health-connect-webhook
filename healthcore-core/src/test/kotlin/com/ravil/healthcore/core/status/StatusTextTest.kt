package com.ravil.healthcore.core.status

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class StatusTextTest {
    private val now = Instant.parse("2026-01-10T12:00:00Z")

    @Test
    fun ago() {
        assertEquals("just now", StatusText.ago(now.minusSeconds(5), now))
        assertEquals("just now", StatusText.ago(now.plusSeconds(30), now))
        assertEquals("5 min ago", StatusText.ago(now.minusSeconds(300), now))
        assertEquals("3 h ago", StatusText.ago(now.minusSeconds(3 * 3600), now))
        assertEquals("47 h ago", StatusText.ago(now.minusSeconds(47 * 3600), now))
        assertEquals("3 d ago", StatusText.ago(now.minusSeconds(3 * 86400), now))
    }

    @Test
    fun `token state never shows the token`() {
        assertEquals("Not set", StatusText.tokenState(false, null, now))
        assertEquals("Saved", StatusText.tokenState(true, null, now))
        assertEquals("Saved · updated 2 h ago", StatusText.tokenState(true, now.minusSeconds(7200), now))
    }

    @Test
    fun labels() {
        assertEquals("Delivered", StatusText.label("delivered"))
        assertEquals("Token rejected", StatusText.label("unauthorized"))
        assertEquals("Something new", StatusText.label("something_new"))
    }
}
