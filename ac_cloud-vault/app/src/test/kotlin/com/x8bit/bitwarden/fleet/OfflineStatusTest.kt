package com.x8bit.bitwarden.fleet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class OfflineStatusTest {
    private val t = Instant.parse("2026-10-23T14:05:00Z")

    @Test
    fun `online and last sync ok shows nothing`() {
        assertNull(OfflineStatus.label(true, false, t, ZoneOffset.UTC))
    }

    @Test
    fun `offline shows the last synced time`() {
        assertEquals(
            "Offline — last synced 2026-10-23 14:05",
            OfflineStatus.label(false, false, t, ZoneOffset.UTC),
        )
    }

    @Test
    fun `online but last sync failed still shows it`() {
        assertEquals(
            "Offline — last synced 2026-10-23 14:05",
            OfflineStatus.label(true, true, t, ZoneOffset.UTC),
        )
    }

    @Test
    fun `never synced`() {
        assertEquals("Offline — not synced yet", OfflineStatus.label(false, false, null, ZoneOffset.UTC))
    }
}
