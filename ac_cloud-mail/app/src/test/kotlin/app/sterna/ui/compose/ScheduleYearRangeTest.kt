package app.sterna.ui.compose

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The first year the "pick date and time" calendar draws (#161), decided by [scheduleFirstYear].
 */
class ScheduleYearRangeTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val la = ZoneId.of("America/Los_Angeles")
    private val kiritimati = ZoneId.of("Pacific/Kiritimati") // UTC+14, no DST

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun `the list starts on the current year, not on Material's 1900`() {
        // The bench reading, replayed: August 2026 on screen, and the chevron must open on 2026.
        assertEquals(2026, scheduleFirstYear(at(paris, 2026, 8, 20, 23, 18), paris))
        assertNotEquals(1900, scheduleFirstYear(at(paris, 2026, 8, 20, 23, 18), paris))
    }

    @Test fun `an ordinary afternoon in Paris`() {
        assertEquals(2027, scheduleFirstYear(at(paris, 2027, 3, 1, 15), paris))
    }

    @Test fun `west of UTC on New Year's Eve, the year that is ENDING is the first offered`() {
        // 31 December, 23:00 in Los Angeles is already 1 January, 07:00 UTC. Read in UTC the list
        // would start on 2027 — the user, still in 2026, could no longer reach 31 December, the
        // one day they are most likely to be scheduling for.
        val now = at(la, 2026, 12, 31, 23)
        assertEquals(2027, ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).year)
        assertEquals(2026, scheduleFirstYear(now, la))
    }

    @Test fun `far east of UTC on New Year's Day, the year that is BEGINNING is the first offered`() {
        // 1 January, 01:00 at UTC+14 is still 31 December, 11:00 UTC. Read in UTC the list would
        // start on a year that is over, and the first entry the user sees would be a dead one.
        val now = at(kiritimati, 2027, 1, 1, 1)
        assertEquals(2026, ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).year)
        assertEquals(2027, scheduleFirstYear(now, kiritimati))
    }
}
