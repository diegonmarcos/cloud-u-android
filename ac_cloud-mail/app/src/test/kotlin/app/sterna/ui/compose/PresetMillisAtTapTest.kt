package app.sterna.ui.compose

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [presetMillisAtTap] is the decision the "send later" menu takes AT THE TAP, instead of scheduling
 */
class PresetMillisAtTapTest {
    private val utc = ZoneId.of("UTC")
    private val paris = ZoneId.of("Europe/Paris")

    private fun millis(zone: ZoneId, h: Int, m: Int = 0, s: Int = 0, nano: Int = 0, day: Int = 18) =
        ZonedDateTime.of(2026, 8, day, h, m, s, nano, zone).toInstant().toEpochMilli()

    /** What the menu really draws at [at] for [preset] — the setup of each case, not its expectation. */
    private fun drawn(preset: SchedulePreset, at: Long, zone: ZoneId = utc): Long =
        schedulePresetsAt(at, zone).first { it.first == preset }.second

    @Test fun `this evening drawn at 5_55 PM and tapped at 6_05 PM is refused`() {
        // The symptom that opened this branch, and the one G1 replays on the bench.
        assertEquals("at 5:55 PM the menu draws This evening = today 6 PM", millis(utc, 18), drawn(SchedulePreset.THIS_EVENING, millis(utc, 17, 55)))
        assertNull(
            "ten minutes later that entry must be REFUSED (null), not scheduled: 6 PM is behind " +
                "us, and enqueue turns a past instant into an immediate, irreversible send",
            presetMillisAtTap(SchedulePreset.THIS_EVENING, millis(utc, 18), millis(utc, 18, 5), utc),
        )
    }

    @Test fun `this evening drawn yesterday morning and tapped this morning is refused`() {
        // THE TRAP the refusal must not be built on: read against a FRESH list this tap looks
        // perfectly legal — at 10 AM on the 19th "This evening" is offered again. The entry under
        // the finger is not that one: it was drawn on the 18th and is worth 6 PM ON THE 18TH,
        // sixteen hours gone. Reading the fresh list would let it through, i.e. send at once.
        assertEquals("at 10 AM on the 19th the menu does offer This evening = 19th 6 PM", millis(utc, 18, day = 19), drawn(SchedulePreset.THIS_EVENING, millis(utc, 10, day = 19)))
        assertNull(
            "an entry drawn yesterday morning must be refused whatever a fresh list offers today: " +
                "the instant under the finger is the 18th at 6 PM, sixteen hours behind the tap",
            presetMillisAtTap(SchedulePreset.THIS_EVENING, millis(utc, 18, day = 18), millis(utc, 10, day = 19), utc),
        )
    }

    @Test fun `tomorrow morning drawn before midnight keeps the day the menu showed`() {
        // THE REGRESSION this test exists for. Absolute entries are NOT re-computed at the tap:
        // drawn on the 18th at 11:50 PM, "Tomorrow, 8 AM" is the 19th at 8. Asking the rule again
        assertEquals("drawn on the 18th at 11:50 PM, Tomorrow 8 AM is the 19th at 8", millis(utc, 8, day = 19), drawn(SchedulePreset.TOMORROW_MORNING, millis(utc, 23, 50, day = 18)))
        assertEquals(
            "tapped five minutes past midnight the entry must still be worth the 19th at 8 AM — " +
                "the day it was SHOWN for. The 20th at 8 AM is the re-computed answer, and it is " +
                "wrong by a whole day",
            millis(utc, 8, day = 19),
            presetMillisAtTap(SchedulePreset.TOMORROW_MORNING, millis(utc, 8, day = 19), millis(utc, 0, 5, day = 19), utc),
        )
    }

    @Test fun `in 1 hour counts from the tap, not from the drawing`() {
        // The one RELATIVE entry, and the only one re-read: it promised an hour from the finger.
        // Drawn at 5:55 PM it was worth 6:55 PM; tapped at 6:05 it must be worth 7:05, pinned to
        // the millisecond. (Bench relevé G1 reads exactly this.)
        assertEquals("at 5:55 PM the menu draws In 1 hour = 6:55 PM", millis(utc, 18, 55), drawn(SchedulePreset.IN_1_HOUR, millis(utc, 17, 55)))
        assertEquals(
            "In 1 hour must run from the tap: 6:05 PM + 1 h = 7:05 PM, not the 6:55 PM the menu drew",
            millis(utc, 19, 5),
            presetMillisAtTap(SchedulePreset.IN_1_HOUR, millis(utc, 18, 55), millis(utc, 18, 5), utc),
        )
    }

    @Test fun `the relative preset ignores the drawing, the absolute ones ARE the drawing`() {
        val now = millis(utc, 10)
        // Deliberately absurd drawn values, so nothing here can be satisfied by a coincidence.
        assertEquals(
            "In 1 hour is re-read whatever the menu drew: 10 AM + 1 h, even from an epoch stamp",
            millis(utc, 11),
            presetMillisAtTap(SchedulePreset.IN_1_HOUR, 0L, now, utc),
        )
        val odd = millis(utc, 12, 34, 56, day = 19)
        for (preset in listOf(SchedulePreset.THIS_EVENING, SchedulePreset.TOMORROW_MORNING, SchedulePreset.TOMORROW_EVENING)) {
            assertEquals(
                "$preset must hand back the instant it was drawn for, untouched — re-deriving it " +
                    "from the preset would answer 6 PM or 8 AM and silently retime the message",
                odd,
                presetMillisAtTap(preset, odd, now, utc),
            )
        }
    }

    @Test fun `an absolute entry is not re-read in another zone`() {
        val now = millis(utc, 10)
        val evening = millis(utc, 18)
        // The drawn instant already carries the zone it was drawn in. Re-deriving "6 PM" in Paris
        // would answer 6 PM PARIS = 4 PM UTC — two hours off, for a traveller or for a device whose
        // zone changed while the menu stood open.
        assertEquals("read in UTC the drawn evening comes back whole", evening, presetMillisAtTap(SchedulePreset.THIS_EVENING, evening, now, utc))
        assertEquals(
            "read in Paris it must come back exactly the same: the answer is the drawn instant, " +
                "not 6 PM re-derived in the zone handed to this call",
            evening,
            presetMillisAtTap(SchedulePreset.THIS_EVENING, evening, now, paris),
        )
        // The zone does travel to the rule for the relative entry — 10 AM UTC + 1 h, either way.
        assertEquals(millis(utc, 11), presetMillisAtTap(SchedulePreset.IN_1_HOUR, 0L, now, utc))
        assertEquals(millis(utc, 11), presetMillisAtTap(SchedulePreset.IN_1_HOUR, 0L, now, paris))
    }

    @Test fun `the boundary is strictly after the tap, to the millisecond`() {
        val evening = millis(utc, 18)
        assertNotNull(
            "one millisecond before 6 PM the drawn evening is still ahead of the finger",
            presetMillisAtTap(SchedulePreset.THIS_EVENING, evening, millis(utc, 17, 59, 59, 999_000_000), utc),
        )
        assertNull(
            "18:00:00.000 is not STRICTLY after 18:00:00.000: tapping on the stroke of six must " +
                "refuse, exactly like pickedScheduleMillis does, not schedule a zero-delay send",
            presetMillisAtTap(SchedulePreset.THIS_EVENING, evening, evening, utc),
        )
    }
}
