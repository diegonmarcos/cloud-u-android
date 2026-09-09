package app.sterna.ui.compose

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-picked "send later" instant (#161), decided by [pickedScheduleMillis] and
 * [scheduleDaySelectable] — the two locks in front of scheduling into the PAST.
 *
 * Why they matter more than they look: `ScheduledSends.enqueue` computes its delay as
 * `(sendAtMillis - now).coerceAtLeast(0)`, so an instant already gone is not refused anywhere — it
 * fires the message AT ONCE, and a send has no way back. Nothing between the menu and that
 * coercion checks the future. Both functions are handed the clock instead of reading it, so "an
 * hour already gone today" is playable without moving the bench's clock.
 *
 * Every case pins the resulting MILLIS, and a zone whose offset is not zero, because the trap
 * here is arithmetic: Material's date picker speaks UTC midnight, the send happens in the device's
 * zone, and composing the instant in the wrong one moves it by hours or by a whole day without a
 * word on screen.
 */
class PickedScheduleTest {
    private val paris = ZoneId.of("Europe/Paris") // UTC+2 in August
    private val la = ZoneId.of("America/Los_Angeles") // UTC−7 in August
    private val kiritimati = ZoneId.of("Pacific/Kiritimati") // UTC+14, no DST

    /** A day exactly as Material's `DatePicker` hands it back: midnight UTC, never local midnight. */
    private fun dayUtc(y: Int, m: Int, d: Int) =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    // -- pickedScheduleMillis -------------------------------------------------------------------

    @Test fun `a future day and time is the instant of the ZONE, not of UTC`() {
        val now = at(paris, 2026, 8, 18, 10)
        val got = pickedScheduleMillis(dayUtc(2026, 8, 20), 9, 30, paris, now)
        // 20 August, 9:30 in Paris = 07:30 UTC. The epoch millis is pinned as a literal so that a
        // rewrite of the composition cannot move it and still agree with a recomputed expectation.
        assertEquals(1787211000000L, got)
        assertEquals(at(paris, 2026, 8, 20, 9, 30), got)
        // The same wall clock read in UTC is two hours later — that is the whole distance between
        // this function and the one that composes the instant in UTC.
        assertNotEquals(at(ZoneOffset.UTC, 2026, 8, 20, 9, 30), got)
        assertEquals(1787218200000L, at(ZoneOffset.UTC, 2026, 8, 20, 9, 30))
    }

    @Test fun `far east of UTC the chosen hour is not even on the same UTC day`() {
        val now = at(kiritimati, 2026, 8, 18, 1)
        val got = pickedScheduleMillis(dayUtc(2026, 8, 20), 9, 0, kiritimati, now)
        // 20 August, 9 AM at UTC+14 is 19 August, 19:00 UTC: composing in UTC would schedule the
        // message 14 hours late, on the following day, with nothing on screen saying so.
        assertEquals(1787166000000L, got)
        assertEquals(at(kiritimati, 2026, 8, 20, 9), got)
        assertNotEquals(at(ZoneOffset.UTC, 2026, 8, 20, 9), got)
    }

    @Test fun `today at an hour already gone is refused`() {
        val now = at(paris, 2026, 8, 18, 10)
        // The case the calendar's own bound cannot catch: the DAY is today, and today is
        // selectable. Left to the enqueue, this would have sent the message on the spot.
        assertNull(pickedScheduleMillis(dayUtc(2026, 8, 18), 9, 0, paris, now))
        assertNull(pickedScheduleMillis(dayUtc(2026, 8, 18), 9, 59, paris, now))
        assertNull(pickedScheduleMillis(dayUtc(2026, 8, 18), 0, 0, paris, now))
    }

    @Test fun `exactly now is refused, strictly after is the rule`() {
        val now = at(paris, 2026, 8, 18, 10)
        assertEquals(1787040000000L, now)
        assertNull(pickedScheduleMillis(dayUtc(2026, 8, 18), 10, 0, paris, now))
    }

    @Test fun `one minute after now is accepted, and is that minute`() {
        val now = at(paris, 2026, 8, 18, 10)
        val got = pickedScheduleMillis(dayUtc(2026, 8, 18), 10, 1, paris, now)
        assertEquals(1787040060000L, got)
        assertEquals(now + 60_000L, got)
    }

    @Test fun `a past day is refused even at an hour still to come today`() {
        val now = at(paris, 2026, 8, 18, 10)
        assertNull(pickedScheduleMillis(dayUtc(2026, 8, 17), 23, 59, paris, now))
    }

    @Test fun `WEST of UTC the picker's own day is the day the message goes out`() {
        // The case the other zones cannot catch, and the one the contre-expertise found missing.
        // The picker hands back UTC midnight; read in a zone BEHIND UTC that instant still belongs
        // to the PREVIOUS civil day (20 August, 00:00 UTC is 19 August, 17:00 in Los Angeles), so
        // reading the day in `zone` instead of UTC silently moves the send back a whole day. Paris
        // and Kiritimati are both AHEAD of UTC and agree with either reading — they cannot see it.
        val now = at(la, 2026, 8, 18, 18)
        assertEquals(1787101200000L, now)
        val got = pickedScheduleMillis(dayUtc(2026, 8, 20), 9, 30, la, now)
        assertEquals(1787243400000L, got)
        assertEquals(at(la, 2026, 8, 20, 9, 30), got)
        // What the wrong reading would have scheduled: the 19th, a day early, and the toast and
        // the Scheduled list would both agree with it — nothing on screen would give it away.
        assertNotEquals(at(la, 2026, 8, 19, 9, 30), got)
        assertEquals(1787157000000L, at(la, 2026, 8, 19, 9, 30))
    }

    @Test fun `WEST of UTC an hour still to come TODAY is accepted, not refused`() {
        // The same trap the other way round: reading the day in `zone` turns today into yesterday,
        // and an instant the user may perfectly well ask for comes back refused, with the OK
        // button greyed and no wording to say why.
        val now = at(la, 2026, 8, 18, 18)
        val got = pickedScheduleMillis(dayUtc(2026, 8, 18), 20, 0, la, now)
        assertEquals(1787108400000L, got)
        assertEquals(at(la, 2026, 8, 18, 20), got)
    }

    // -- scheduleDaySelectable ------------------------------------------------------------------

    @Test fun `yesterday is refused, today and tomorrow are offered`() {
        val now = at(paris, 2026, 8, 18, 10)
        assertFalse(scheduleDaySelectable(dayUtc(2026, 8, 17), now, paris))
        assertTrue(scheduleDaySelectable(dayUtc(2026, 8, 18), now, paris))
        assertTrue(scheduleDaySelectable(dayUtc(2026, 8, 19), now, paris))
        assertTrue(scheduleDaySelectable(dayUtc(2027, 3, 1), now, paris))
    }

    @Test fun `west of UTC in the evening, today is still offered`() {
        // 18 August, 6 PM in Los Angeles is already 19 August, 01:00 UTC. The picker's "today"
        // (18 August, midnight UTC) is then 25 hours BEHIND the clock: any comparison of that
        // millis against the instant, or against the local start of day, would strike today off
        // the calendar and force the user to a day they did not want.
        val now = at(la, 2026, 8, 18, 18)
        assertEquals(1787101200000L, now)
        assertTrue(dayUtc(2026, 8, 18) < now)
        assertTrue(scheduleDaySelectable(dayUtc(2026, 8, 18), now, la))
        assertFalse(scheduleDaySelectable(dayUtc(2026, 8, 17), now, la))
        assertTrue(scheduleDaySelectable(dayUtc(2026, 8, 19), now, la))
    }

    @Test fun `east of UTC in the small hours, yesterday stays refused`() {
        // 18 August, 01:00 at UTC+14 is still 17 August, 11:00 UTC. Here the local day RUNS AHEAD
        // of the UTC one, and a "within the last 24 hours" style comparison would put yesterday
        // back on the calendar — that assertion is what this pins.
        val now = at(kiritimati, 2026, 8, 18, 1)
        assertTrue(dayUtc(2026, 8, 17) + 86_400_000L > now)
        assertFalse(scheduleDaySelectable(dayUtc(2026, 8, 17), now, kiritimati))
        assertTrue(scheduleDaySelectable(dayUtc(2026, 8, 18), now, kiritimati))
        assertTrue(scheduleDaySelectable(dayUtc(2026, 8, 19), now, kiritimati))
    }

    // -- scheduleClockOpensAt -------------------------------------------------------------------

    /**
     * The whole point of the volet, asserted as such: the clock is never allowed to open on a
     * state its OWN OK button refuses. Lock 2 is asked the exact hour and minute the dial was
     * handed, with the same day, zone and clock.
     */
    private fun assertOwnOkWouldLight(day: Long, opensAt: LocalTime, zone: ZoneId, now: Long) {
        assertNotNull(
            "⛔ the clock opened on $opensAt, an instant pickedScheduleMillis REFUSES: the OK " +
                "button is greyed the moment the dialog appears, and there is no wording anywhere " +
                "to say why. That is the dead dialog the bench filmed at 23:20.",
            pickedScheduleMillis(day, opensAt.hour, opensAt.minute, zone, now),
        )
    }

    @Test fun `THE BENCH RED - at 23h20 on today, the clock opens on the last minute of today`() {
        // Filmed on emu, 2026-08-20 23:20 UTC: "now + 1 h" is 00:20 TOMORROW, but only its hour and
        // minute were kept and pasted onto the day the calendar returned — today. The dial opened
        // on 00:20 today, some 23 hours in the PAST, lock 2 refused it, and OK was grey from the
        // first frame with nothing on screen to explain it.
        val now = at(paris, 2026, 8, 18, 23, 20)
        val got = scheduleClockOpensAt(dayUtc(2026, 8, 18), now, paris)
        assertEquals(LocalTime.of(23, 59), got)
        assertNotEquals(LocalTime.of(0, 20), got)
        assertOwnOkWouldLight(dayUtc(2026, 8, 18), got, paris, now)
    }

    @Test fun `23h00 sharp and 23h59 both fall back to the last minute of today`() {
        // 23:00 is the exact boundary: an hour ahead is midnight, the first instant of the NEXT
        // civil day, so the rule has to be "after the chosen day", not "more than a day after".
        val elevenPm = at(paris, 2026, 8, 18, 23, 0)
        assertEquals(LocalTime.of(23, 59), scheduleClockOpensAt(dayUtc(2026, 8, 18), elevenPm, paris))
        assertOwnOkWouldLight(dayUtc(2026, 8, 18), LocalTime.of(23, 59), paris, elevenPm)

        val lastMinute = at(paris, 2026, 8, 18, 23, 59)
        assertEquals(LocalTime.of(23, 59), scheduleClockOpensAt(dayUtc(2026, 8, 18), lastMinute, paris))
        // And here it is already too late: at 23:59:00 sharp, 23:59 IS now, and the rule is
        // strictly after — so OK opens grey. One second earlier it would have been lit. That
        // last minute is the honest dead end, not a hole to patch.
        assertNull(pickedScheduleMillis(dayUtc(2026, 8, 18), 23, 59, paris, lastMinute))
        assertNotNull(pickedScheduleMillis(dayUtc(2026, 8, 18), 23, 59, paris, lastMinute - 1))
    }

    @Test fun `an ordinary hour today opens an hour ahead, to the minute`() {
        val now = at(paris, 2026, 8, 18, 10, 0)
        val got = scheduleClockOpensAt(dayUtc(2026, 8, 18), now, paris)
        assertEquals(LocalTime.of(11, 0), got)
        assertOwnOkWouldLight(dayUtc(2026, 8, 18), got, paris, now)
    }

    @Test fun `minutes are carried, seconds are dropped`() {
        val now = at(paris, 2026, 8, 18, 10, 37) + 42_000L
        val got = scheduleClockOpensAt(dayUtc(2026, 8, 18), now, paris)
        // A dial has no seconds hand; carrying 11:37:42 into it would round somewhere invisible.
        assertEquals(LocalTime.of(11, 37), got)
        assertOwnOkWouldLight(dayUtc(2026, 8, 18), got, paris, now)
    }

    @Test fun `on a FUTURE day, past midnight is normal and must not be clamped`() {
        // The bench read this one GREEN (B2) and it stays green: any hour of a later day is ahead,
        // so 00:20 is a perfectly good opening there. Clamping every 23:xx clock to 23:59 would
        // break a case that was never broken.
        val now = at(paris, 2026, 8, 18, 23, 20)
        val got = scheduleClockOpensAt(dayUtc(2026, 8, 20), now, paris)
        assertEquals(LocalTime.of(0, 20), got)
        assertNotEquals(LocalTime.of(23, 59), got)
        assertNotNull(pickedScheduleMillis(dayUtc(2026, 8, 20), got.hour, got.minute, paris, now))
    }

    @Test fun `WEST of UTC the chosen day is read in UTC, as the picker speaks it`() {
        // The case a zone AHEAD of UTC cannot catch. 18 August midnight UTC is 17 August,
        // 17:00 in Los Angeles: read in `zone` the picked day would be the 17th, "an hour ahead"
        // would land on the 18th, i.e. AFTER it, and the dial would open on 23:59 for a user who
        // asked for today at 10 in the morning. Almost fourteen hours late, silently.
        val now = at(la, 2026, 8, 18, 10, 0)
        val got = scheduleClockOpensAt(dayUtc(2026, 8, 18), now, la)
        assertEquals(LocalTime.of(11, 0), got)
        assertNotEquals(LocalTime.of(23, 59), got)
        assertOwnOkWouldLight(dayUtc(2026, 8, 18), got, la, now)
    }

    @Test fun `WEST of UTC, late today still falls back to the last minute of today`() {
        val now = at(la, 2026, 8, 18, 23, 20)
        val got = scheduleClockOpensAt(dayUtc(2026, 8, 18), now, la)
        assertEquals(LocalTime.of(23, 59), got)
        assertOwnOkWouldLight(dayUtc(2026, 8, 18), got, la, now)
    }

    @Test fun `EAST of UTC, both an ordinary hour and the late fallback hold`() {
        // UTC+14: the local day runs a whole day ahead of the UTC one, which is where an
        // instant-to-instant comparison would first go wrong.
        val small = at(kiritimati, 2026, 8, 18, 1, 0)
        val gotSmall = scheduleClockOpensAt(dayUtc(2026, 8, 18), small, kiritimati)
        assertEquals(LocalTime.of(2, 0), gotSmall)
        assertOwnOkWouldLight(dayUtc(2026, 8, 18), gotSmall, kiritimati, small)

        val late = at(kiritimati, 2026, 8, 18, 23, 30)
        val gotLate = scheduleClockOpensAt(dayUtc(2026, 8, 18), late, kiritimati)
        assertEquals(LocalTime.of(23, 59), gotLate)
        assertOwnOkWouldLight(dayUtc(2026, 8, 18), gotLate, kiritimati, late)
    }

    @Test fun `in the very last minute of the day nothing is offerable, and that is honest`() {
        // Deliberately NOT patched up. At 23:59:30 the chosen day has no future minute left
        // at all: the clock opens on 23:59 and its OK is grey. Opening on "now + 1 minute" instead
        // would have hidden it for sixty seconds and reopened the silent refusal a minute later.
        val now = at(paris, 2026, 8, 18, 23, 59) + 30_000L
        val got = scheduleClockOpensAt(dayUtc(2026, 8, 18), now, paris)
        assertEquals(LocalTime.of(23, 59), got)
        assertNull(pickedScheduleMillis(dayUtc(2026, 8, 18), got.hour, got.minute, paris, now))
    }

    @Test fun `on the night the clocks GO BACK, an hour ahead is not offerable and 23-59 is`() {
        // The case a rule of our own got wrong, and no ordinary date can see. Paris, 25 October
        // 2026: 03:00 CEST becomes 02:00 CET, so 02:40 happens TWICE. At 02:40 CEST an hour ahead
        // is 02:40 CET — the same wall time, the same civil date, so "did the date roll over?"
        // answers no and hands 02:40 back. Rebuilding 02:40 on that day picks the FIRST pass, at
        // the summer offset, which is `now` itself: refused, OK grey from the first frame, and not
        // one word to explain it. Exactly the defect the bench filmed at 23:20, once a year at
        // 2 in the morning.
        val now = at(paris, 2026, 10, 25, 2, 40) // the first 02:40, CEST
        val ahead = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now + 3_600_000L), paris)
        assertEquals(2, ahead.hour) // an hour later it is 02:40 all over again
        assertEquals(40, ahead.minute)
        assertEquals(25, ahead.dayOfMonth) // and the same civil day: no roll-over to notice

        val got = scheduleClockOpensAt(dayUtc(2026, 10, 25), now, paris)
        assertNotEquals(LocalTime.of(2, 40), got)
        assertEquals(LocalTime.of(23, 59), got)
        assertOwnOkWouldLight(dayUtc(2026, 10, 25), got, paris, now)
    }

    @Test fun `on the night the clocks GO FORWARD an ordinary hour ahead still holds`() {
        // The mirror night, 29 March 2026: 02:00 CET jumps to 03:00 CEST. At 00:30 an hour ahead
        // is 01:30, a wall time that exists, and it must be offered as on any other day — the
        // fallback is for what the day cannot take, not for every day that shifts.
        val now = at(paris, 2026, 3, 29, 0, 30)
        val got = scheduleClockOpensAt(dayUtc(2026, 3, 29), now, paris)
        assertEquals(LocalTime.of(1, 30), got)
        assertOwnOkWouldLight(dayUtc(2026, 3, 29), got, paris, now)
    }

    @Test fun `a clock opened over no day at all, 1970, still refuses`() {
        // The composer passes `scheduleDay ?: 0L`. 1970 is long past, so the fallback applies and
        // lock 2 refuses whatever comes out — the dialog can only be cancelled, which is the
        // intended reading of that 0L.
        val now = at(paris, 2026, 8, 18, 10, 0)
        val got = scheduleClockOpensAt(0L, now, paris)
        assertEquals(LocalTime.of(23, 59), got)
        assertNull(pickedScheduleMillis(0L, got.hour, got.minute, paris, now))
    }
}
