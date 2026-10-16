package com.diegonmarcos.cloudnav.tracker

import androidx.health.connect.client.HealthConnectClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class StepsDiagnosticsTest {
    private val req = setOf("android.permission.health.READ_STEPS")

    @Test fun permissionStateMapping() {
        assertEquals(PermState.GRANTED, StepsDiagnostics.permState(HealthConnectClient.SDK_AVAILABLE, req, req))
        assertEquals(PermState.DENIED, StepsDiagnostics.permState(HealthConnectClient.SDK_AVAILABLE, emptySet(), req))
        assertEquals(PermState.UPDATE_REQUIRED, StepsDiagnostics.permState(HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED, req, req))
        assertEquals(PermState.UNAVAILABLE, StepsDiagnostics.permState(HealthConnectClient.SDK_UNAVAILABLE, req, req))
    }

    @Test fun aggregationRangeIsTheLocalDay() {
        val zone = ZoneId.of("America/Los_Angeles")
        val now = LocalDateTime.of(2026, 10, 15, 22, 0).atZone(zone).toInstant()  // already the 16th in UTC
        val (a, b) = StepsDiagnostics.todayRange(now, zone)
        assertEquals(LocalDateTime.of(2026, 10, 15, 0, 0), a)
        assertEquals(LocalDateTime.of(2026, 10, 16, 0, 0), b)
    }

    @Test fun zeroAndNoOriginsHintsAtSamsungHealthSync() {
        assertTrue(StepsDiagnostics.hint(PermState.GRANTED, 0, emptySet())!!.contains("Samsung Health"))
        assertNull(StepsDiagnostics.hint(PermState.GRANTED, 0, setOf("com.sec.android.app.shealth")))
        assertNull(StepsDiagnostics.hint(PermState.GRANTED, 5, emptySet()))
        assertTrue(StepsDiagnostics.hint(PermState.DENIED, 0, emptySet())!!.contains("Grant access"))
    }

    @Test fun statusLine() {
        val last = Instant.parse("2026-10-16T14:32:00Z").toEpochMilli()
        assertEquals("Source: Health Connect | Permission: granted | Last data: 14:32 | Origins: a.b",
            StepsDiagnostics.statusLine("Health Connect", PermState.GRANTED, last, setOf("a.b"), ZoneId.of("UTC")))
        assertEquals("Source: x | Permission: not granted | Last data: none",
            StepsDiagnostics.statusLine("x", PermState.DENIED, null, emptySet(), ZoneId.of("UTC")))
    }

    @Test fun deepLinkResolvesToTimelineSteps() {
        assertEquals(NavDeepLink.Target("timeline", "steps"), NavDeepLink.parse(NavDeepLink.STEPS))
        assertEquals(NavDeepLink.Target("places", null), NavDeepLink.parse("page:places"))
        assertNull(NavDeepLink.parse("https://x"))
        assertNull(NavDeepLink.parse("page:"))
        assertNull(NavDeepLink.parse(null))
        assertNotNull(NavDeepLink.parse(" page:timeline/steps "))
    }
}
