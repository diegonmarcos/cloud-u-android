package app.sterna.ui.compose

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "send later" menu is built from [schedulePresetsAt], which is handed the instant instead of
 */
class SchedulePresetsTest {
    private val utc = ZoneId.of("UTC")
    private val paris = ZoneId.of("Europe/Paris")

    private fun millis(zone: ZoneId, h: Int, m: Int = 0, s: Int = 0, nano: Int = 0, day: Int = 18) =
        ZonedDateTime.of(2026, 8, day, h, m, s, nano, zone).toInstant().toEpochMilli()

    private fun presets(zone: ZoneId, at: Long) = schedulePresetsAt(at, zone)

    @Test fun morningOffersAllFourInOrderAndThisEveningIsToday() {
        val now = millis(utc, 10)
        val got = presets(utc, now)
        assertEquals(
            listOf(
                SchedulePreset.IN_1_HOUR,
                SchedulePreset.THIS_EVENING,
                SchedulePreset.TOMORROW_MORNING,
                SchedulePreset.TOMORROW_EVENING,
            ),
            got.map { it.first },
        )
        // Each instant is pinned, not just the labels: "this evening" is TODAY 6 PM.
        assertEquals(millis(utc, 11), got[0].second)
        assertEquals(millis(utc, 18), got[1].second)
        assertEquals(millis(utc, 8, day = 19), got[2].second)
        assertEquals(millis(utc, 18, day = 19), got[3].second)
    }

    @Test fun afterSixPmDropsThisEveningAndLeavesNoDuplicateInstant() {
        val now = millis(utc, 19)
        val got = presets(utc, now)
        assertEquals(
            listOf(SchedulePreset.IN_1_HOUR, SchedulePreset.TOMORROW_MORNING, SchedulePreset.TOMORROW_EVENING),
            got.map { it.first },
        )
        assertFalse("a lapsed evening must be removed, not folded", got.any { it.first == SchedulePreset.THIS_EVENING })
        val instants = got.map { it.second }
        assertEquals("two entries scheduling the same instant", instants.size, instants.toSet().size)
        assertEquals(millis(utc, 20), got[0].second)
        assertEquals(millis(utc, 8, day = 19), got[1].second)
        assertEquals(millis(utc, 18, day = 19), got[2].second)
    }

    @Test fun exactlySixPmDropsThisEveningBecauseTheFilterIsStrictlyAfter() {
        val got = presets(utc, millis(utc, 18, 0, 0, 0))
        assertFalse(
            "18:00:00.000 is not STRICTLY after 18:00:00.000, so the entry goes",
            got.any { it.first == SchedulePreset.THIS_EVENING },
        )
        assertEquals(3, got.size)
    }

    @Test fun oneMilliBeforeSixPmStillOffersThisEvening() {
        val got = presets(utc, millis(utc, 17, 59, 59, 999_000_000))
        assertEquals(4, got.size)
        assertEquals(millis(utc, 18), got.first { it.first == SchedulePreset.THIS_EVENING }.second)
    }

    @Test fun lateEveningGivesTheSameTripletAsSevenPm() {
        val sevenPm = presets(utc, millis(utc, 19)).map { it.first }
        val halfPastEleven = presets(utc, millis(utc, 23, 30))
        assertEquals(sevenPm, halfPastEleven.map { it.first })
        // The two "tomorrow" instants are the same day's 8 AM / 6 PM in both cases.
        assertEquals(millis(utc, 8, day = 19), halfPastEleven[1].second)
        assertEquals(millis(utc, 18, day = 19), halfPastEleven[2].second)
    }

    @Test fun theZoneArgumentDecidesNotTheJvmDefault() {
        // 16:00 UTC on this summer date is 18:00 in Paris (UTC+2). The SAME instant therefore
        // still offers "this evening" read in UTC, and no longer offers it read in Paris.
        val instant = millis(utc, 16)
        assertTrue(presets(utc, instant).any { it.first == SchedulePreset.THIS_EVENING })
        assertFalse(presets(paris, instant).any { it.first == SchedulePreset.THIS_EVENING })
        // And the evening offered in Paris earlier in the day is 18:00 PARIS = 16:00 UTC.
        val parisMorning = presets(paris, millis(paris, 10))
        assertEquals(millis(paris, 18), parisMorning.first { it.first == SchedulePreset.THIS_EVENING }.second)
        assertEquals(millis(utc, 16), parisMorning.first { it.first == SchedulePreset.THIS_EVENING }.second)
    }
}
