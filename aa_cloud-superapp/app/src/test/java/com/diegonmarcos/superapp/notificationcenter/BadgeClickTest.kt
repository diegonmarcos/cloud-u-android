package com.diegonmarcos.superapp.notificationcenter

import com.diegonmarcos.superapp.health.StepsToday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Click-target resolution of the Weather / Health badges, and the Health badge's steps rules. */
class BadgeClickTest {
    // HealthConnectClient.SDK_* values (the SDK is not on the app test classpath).
    private val SDK_UNAVAILABLE = 1; private val SDK_UPDATE_REQUIRED = 2; private val SDK_AVAILABLE = 3

    @Test fun weatherOpensTheProvidersPageForThePosition() {
        val u = BadgeClick.weatherUrl(48.8566, 2.3522)
        assertTrue(u, u.startsWith("https://open-meteo.com/en/docs#latitude=48.8566&longitude=2.3522"))
        assertTrue(u.contains("timezone=auto"))
    }
    @Test fun weatherWithoutAFixOpensTheProvidersDocs() {
        assertEquals("https://open-meteo.com/en/docs", BadgeClick.weatherUrl(null, 2.0))
        assertEquals("https://open-meteo.com/en/docs", BadgeClick.weatherUrl(null, null))
    }
    @Test fun weatherUrlUsesDotDecimalsInAnyLocale() {
        val old = java.util.Locale.getDefault()
        try { java.util.Locale.setDefault(java.util.Locale.GERMANY); assertTrue(BadgeClick.weatherUrl(1.5, 2.5).contains("latitude=1.5000")) }
        finally { java.util.Locale.setDefault(old) }
    }
    @Test fun healthOpensNavTimelineSteps() {
        assertEquals("extapp:cloud-nav#page:timeline/steps", BadgeClick.HEALTH_STEPS)
    }

    // ── permission state mapping ─────────────────────────────────────
    @Test fun permissionStateMapping() {
        val g = setOf(StepsToday.READ_STEPS)
        assertEquals(StepsToday.Perm.GRANTED, StepsToday.permState(SDK_AVAILABLE, g))
        assertEquals(StepsToday.Perm.DENIED, StepsToday.permState(SDK_AVAILABLE, emptySet()))
        assertEquals(StepsToday.Perm.DENIED, StepsToday.permState(SDK_AVAILABLE, setOf("android.permission.health.READ_DISTANCE")))
        assertEquals(StepsToday.Perm.UPDATE_REQUIRED, StepsToday.permState(SDK_UPDATE_REQUIRED, g))
        assertEquals(StepsToday.Perm.UNAVAILABLE, StepsToday.permState(SDK_UNAVAILABLE, g))
    }

    // ── aggregation range: the LOCAL day ─────────────────────────────
    @Test fun todayIsTheLocalDayNotTheUtcDay() {
        val zone = ZoneId.of("Pacific/Auckland")  // UTC+12/13: the UTC date differs from the local one
        val now = LocalDateTime.of(2026, 10, 16, 1, 30).atZone(zone).toInstant()
        val (a, b) = StepsToday.todayRange(now, zone)
        assertEquals(LocalDateTime.of(2026, 10, 16, 0, 0), a)
        assertEquals(LocalDateTime.of(2026, 10, 17, 0, 0), b)
    }
    @Test fun todayRangeSpansExactlyOneCalendarDayAcrossDst() {
        val zone = ZoneId.of("Europe/Paris")
        val (a, b) = StepsToday.todayRange(Instant.parse("2026-10-25T10:00:00Z"), zone)  // the 25 h day
        assertEquals(LocalDateTime.of(2026, 10, 25, 0, 0), a)
        assertEquals(LocalDateTime.of(2026, 10, 26, 0, 0), b)
    }

    // ── status line + hint + text ────────────────────────────────────
    @Test fun zeroWithNoOriginsHintsAtSamsungSync() {
        val r = StepsToday.Result(0L, StepsToday.Perm.GRANTED, emptySet(), null)
        assertTrue(StepsToday.hint(r)!!.contains("Samsung Health"))
        assertNull(StepsToday.hint(StepsToday.Result(0L, StepsToday.Perm.GRANTED, setOf("com.sec.android.app.shealth"), 1L)))
    }
    @Test fun deniedAsksForGrantAndNeverShowsZero() {
        val r = StepsToday.Result(null, StepsToday.Perm.DENIED, emptySet(), null)
        assertTrue(StepsToday.hint(r)!!.contains("Grant access"))
        assertEquals("steps not allowed", HealthBadgeModel.stepsText(r))
    }
    @Test fun statusLineNamesSourcePermissionAndLastData() {
        val zone = ZoneId.of("UTC")
        val last = Instant.parse("2026-10-16T14:32:00Z").toEpochMilli()
        val line = StepsToday.statusLine(StepsToday.Result(8421, StepsToday.Perm.GRANTED, setOf("com.sec.android.app.shealth"), last), zone)
        assertEquals("Source: Health Connect | Permission: granted | Last data: 14:32 | From: com.sec.android.app.shealth", line)
        assertTrue(StepsToday.statusLine(StepsToday.Result(null, StepsToday.Perm.DENIED, emptySet(), null), zone).endsWith("Last data: none"))
    }
    @Test fun stepsTextFormats() {
        assertEquals("8,421 steps", HealthBadgeModel.stepsText(StepsToday.Result(8421, StepsToday.Perm.GRANTED, emptySet(), null)))
        assertEquals("steps reading…", HealthBadgeModel.stepsText(null))
    }
}
