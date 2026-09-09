package app.sterna.core.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Exercises the hand-rolled iCalendar reader without a device. */
class ICalendarTest {

    private fun ics(vararg body: String): String =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0") + body + "END:VCALENDAR").joinToString("\r\n")

    @Test fun utcEvent() {
        val event = ICalendar.parse(
            ics(
                "METHOD:REQUEST",
                "BEGIN:VEVENT",
                "SUMMARY:Sprint review",
                "DTSTART:20260703T140000Z",
                "DTEND:20260703T150000Z",
                "END:VEVENT",
            ),
        )!!
        assertEquals("Sprint review", event.title)
        assertEquals("REQUEST", event.method)
        assertFalse(event.allDay)
        assertEquals(Instant.parse("2026-07-03T14:00:00Z").toEpochMilli(), event.startMillis)
        assertEquals(Instant.parse("2026-07-03T15:00:00Z").toEpochMilli(), event.endMillis)
    }

    @Test fun tzidEvent() {
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "SUMMARY:Lunch",
                "DTSTART;TZID=Europe/Paris:20260703T120000",
                "DTEND;TZID=Europe/Paris:20260703T130000",
                "LOCATION:Paris office",
                "END:VEVENT",
            ),
        )!!
        val expected = LocalDateTime.of(2026, 7, 3, 12, 0)
            .atZone(ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()
        assertEquals(expected, event.startMillis)
        assertEquals("Paris office", event.location)
        assertFalse(event.allDay)
    }

    @Test fun allDayEvent() {
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "SUMMARY:Public holiday",
                "DTSTART;VALUE=DATE:20260703",
                "DTEND;VALUE=DATE:20260704",
                "END:VEVENT",
            ),
        )!!
        assertTrue(event.allDay)
        val expected = LocalDate.of(2026, 7, 3)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, event.startMillis)
    }

    @Test fun durationComputesEnd() {
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "SUMMARY:Call",
                "DTSTART:20260703T140000Z",
                "DURATION:PT1H30M",
                "END:VEVENT",
            ),
        )!!
        assertEquals(event.startMillis + 90 * 60 * 1000L, event.endMillis)
    }

    @Test fun dtendWinsOverDuration() {
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "DTSTART:20260703T140000Z",
                "DTEND:20260703T160000Z",
                "DURATION:PT1H",
                "END:VEVENT",
            ),
        )!!
        assertEquals(Instant.parse("2026-07-03T16:00:00Z").toEpochMilli(), event.endMillis)
    }

    @Test fun foldedLinesAndAttendees() {
        // A folded SUMMARY (continuation lines start with a space) and three ATTENDEEs.
        val raw = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "SUMMARY:Quarterly planning with the",
            "  extended leadership team",
            "DTSTART:20260703T140000Z",
            "ATTENDEE;CN=Ann:mailto:ann@example.com",
            "ATTENDEE;CN=Bob:mailto:bob@example.com",
            "ATTENDEE:mailto:cara@example.com",
            "RRULE:FREQ=WEEKLY;COUNT=4",
            "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n")
        val event = ICalendar.parse(raw)!!
        assertEquals("Quarterly planning with the extended leadership team", event.title)
        assertEquals(3, event.attendeeCount)
        assertTrue(event.recurs)
    }

    @Test fun escapedText() {
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "SUMMARY:Lunch\\, drinks\\; then talk",
                "DESCRIPTION:Line one\\nLine two\\\\end",
                "DTSTART:20260703T140000Z",
                "END:VEVENT",
            ),
        )!!
        assertEquals("Lunch, drinks; then talk", event.title)
        assertEquals("Line one\nLine two\\end", event.description)
    }

    @Test fun organizerStripsMailto() {
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "DTSTART:20260703T140000Z",
                "ORGANIZER:mailto:chair@example.com",
                "END:VEVENT",
            ),
        )!!
        assertEquals("chair@example.com", event.organizer)
    }

    @Test fun methodRequest() {
        val event = ICalendar.parse(
            ics("METHOD:REQUEST", "BEGIN:VEVENT", "DTSTART:20260703T140000Z", "END:VEVENT"),
        )!!
        assertEquals("REQUEST", event.method)
        assertFalse(event.cancelled)
    }

    @Test fun methodCancel() {
        val event = ICalendar.parse(
            ics(
                "METHOD:CANCEL",
                "BEGIN:VEVENT",
                "DTSTART:20260703T140000Z",
                "STATUS:CANCELLED",
                "END:VEVENT",
            ),
        )!!
        assertEquals("CANCEL", event.method)
        assertTrue(event.cancelled)
    }

    @Test fun firstVeventOnly() {
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "SUMMARY:First",
                "DTSTART:20260703T140000Z",
                "END:VEVENT",
                "BEGIN:VEVENT",
                "SUMMARY:Second",
                "DTSTART:20260704T140000Z",
                "END:VEVENT",
            ),
        )!!
        assertEquals("First", event.title)
    }

    @Test fun garbageReturnsNull() {
        assertNull(ICalendar.parse("this is not a calendar at all"))
    }

    @Test fun emptyReturnsNull() {
        assertNull(ICalendar.parse(""))
        assertNull(ICalendar.parse(null))
    }

    @Test fun noStartReturnsNull() {
        assertNull(
            ICalendar.parse(ics("BEGIN:VEVENT", "SUMMARY:No date", "END:VEVENT")),
        )
    }

    @Test fun badTzidFallsBackGracefully() {
        // An unknown TZID must not throw; it falls back to the system zone.
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "DTSTART;TZID=Mars/Olympus:20260703T140000",
                "END:VEVENT",
            ),
        )!!
        val expected = LocalDateTime.of(2026, 7, 3, 14, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, event.startMillis)
    }

    // ---- Reply-oriented parsing (phase 2) --------------------------------------------------

    private fun request(): ParsedEvent = ICalendar.parse(
        ics(
            "METHOD:REQUEST",
            "BEGIN:VEVENT",
            "UID:abc-123@example.com",
            "SEQUENCE:2",
            "SUMMARY:Sprint review",
            "ORGANIZER;CN=Chair Person:mailto:chair@example.com",
            "DTSTART;TZID=Europe/Paris:20260703T140000",
            "DTEND;TZID=Europe/Paris:20260703T150000",
            "ATTENDEE;CN=Ann:mailto:ann@example.com",
            "ATTENDEE;CN=Bob Builder:mailto:bob@example.com",
            "END:VEVENT",
        ),
    )!!

    @Test fun parsesUidSequenceOrganizerAttendees() {
        val e = request()
        assertEquals("abc-123@example.com", e.uid)
        assertEquals(2, e.sequence)
        assertEquals("chair@example.com", e.organizerEmail)
        assertEquals("Chair Person", e.organizerCn)
        assertEquals(2, e.attendees.size)
        assertEquals("ann@example.com", e.attendees[0].email)
        assertEquals("Ann", e.attendees[0].cn)
        assertEquals("bob@example.com", e.attendees[1].email)
    }

    @Test fun keepsRawDtStartForEcho() {
        val e = request()
        assertEquals("DTSTART;TZID=Europe/Paris:20260703T140000", e.rawDtStart)
        assertEquals("DTEND;TZID=Europe/Paris:20260703T150000", e.rawDtEnd)
        assertEquals("UID:abc-123@example.com", e.rawUid)
        assertEquals("SEQUENCE:2", e.rawSequence)
    }

    @Test fun sequenceDefaultsToZero() {
        val e = ICalendar.parse(
            ics("BEGIN:VEVENT", "DTSTART:20260703T140000Z", "END:VEVENT"),
        )!!
        assertEquals(0, e.sequence)
        assertNull(e.uid)
    }

    @Test fun buildReplyAccepted() {
        val ics = ICalendar.buildReply(
            event = request(),
            attendeeEmail = "ann@example.com",
            attendeeCn = "Ann",
            partstat = "ACCEPTED",
            nowMillis = Instant.parse("2026-06-28T09:30:00Z").toEpochMilli(),
        )
        // CRLF line endings.
        assertTrue(ics.contains("\r\n"))
        val lines = ics.split("\r\n")
        assertTrue("METHOD:REPLY" in lines)
        assertTrue("VERSION:2.0" in lines)
        assertTrue("PRODID:-//Sterna Mail//EN" in lines)
        assertTrue("UID:abc-123@example.com" in lines)
        // Organizer echoed as mailto with its CN.
        assertTrue(lines.any { it.startsWith("ORGANIZER") && it.contains("mailto:chair@example.com") })
        // Raw DTSTART echoed verbatim (time-zone preserved).
        assertTrue("DTSTART;TZID=Europe/Paris:20260703T140000" in lines)
        assertTrue("SEQUENCE:2" in lines)
        // DTSTAMP formatted as UTC.
        assertTrue("DTSTAMP:20260628T093000Z" in lines)
        // The replying attendee with PARTSTAT.
        assertTrue("ATTENDEE;PARTSTAT=ACCEPTED;CN=Ann:mailto:ann@example.com" in lines)
        assertTrue("SUMMARY:Sprint review" in lines)
    }

    @Test fun buildReplyDeclinedAndTentative() {
        val declined = ICalendar.buildReply(request(), "ann@example.com", null, "DECLINED", 0L)
        assertTrue(declined.split("\r\n").any { it.startsWith("ATTENDEE;PARTSTAT=DECLINED:") })
        assertTrue("METHOD:REPLY" in declined.split("\r\n"))
        val tentative = ICalendar.buildReply(request(), "ann@example.com", null, "TENTATIVE", 0L)
        assertTrue(tentative.split("\r\n").any { it.startsWith("ATTENDEE;PARTSTAT=TENTATIVE:") })
    }

    @Test fun buildReplyFoldsLongLines() {
        val longUid = "x".repeat(120)
        val e = ICalendar.parse(
            ics("BEGIN:VEVENT", "UID:$longUid", "DTSTART:20260703T140000Z", "END:VEVENT"),
        )!!
        val ics = ICalendar.buildReply(e, "a@b.com", null, "ACCEPTED", 0L)
        // No physical line may exceed 75 octets (folding inserts CRLF + space).
        ics.split("\r\n").forEach { assertTrue(it.toByteArray().size <= 75) }
    }

    // ---- Unfolding: bounded work on hostile input ------------------------------------------

    @Test fun tabContinuationIsUnfoldedLikeASpace() {
        val raw = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "SUMMARY:Budget review for the",
            "\t next fiscal year",
            "DESCRIPTION:Agenda:",
            "\t item one\\, item two",
            "DTSTART:20260703T140000Z",
            "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n")
        val event = ICalendar.parse(raw)!!
        assertEquals("Budget review for the next fiscal year", event.title)
        assertEquals("Agenda: item one, item two", event.description)
    }

    @Test fun lfOnlyAndCrlfGiveTheSameEvent() {
        val lines = listOf(
            "BEGIN:VCALENDAR",
            "METHOD:REQUEST",
            "BEGIN:VEVENT",
            "UID:mixed-endings@example.com",
            "SUMMARY:All-hands with a very long title that the",
            " \tsender folded twice",
            " and again",
            "ORGANIZER;CN=Chair Person:mailto:chair@example.com",
            "ATTENDEE;CN=Ann:mailto:ann@example.com",
            "DTSTART;TZID=Europe/Paris:20260703T140000",
            "DTEND;TZID=Europe/Paris:20260703T153000",
            "LOCATION:Room 3",
            "END:VEVENT",
            "END:VCALENDAR",
        )
        val crlf = ICalendar.parse(lines.joinToString("\r\n"))!!
        val lf = ICalendar.parse(lines.joinToString("\n"))!!
        assertEquals(crlf, lf)
        assertEquals("All-hands with a very long title that the\tsender folded twiceand again", crlf.title)
        assertEquals("Room 3", crlf.location)
        assertEquals(1, crlf.attendeeCount)
        assertEquals("mixed-endings@example.com", crlf.uid)
    }

    @Test fun aTrailingLineWithoutABreakIsStillRead() {
        val raw = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART:20260703T140000Z\r\nSUMMARY:Last line"
        assertEquals("Last line", ICalendar.parse(raw)?.title)
    }

    @Test fun aFloodOfContinuationsUnfoldsInBoundedTime() {
        // 250 000 one-character continuation lines: rebuilding the logical line per continuation
        // is quadratic (tens of billions of character copies) and hangs the reader with no
        // exception to catch. Unfolding linearly, this is milliseconds.
        val folds = 250_000
        val raw = buildString(4 * folds + 128) {
            append("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART:20260703T140000Z\r\nSUMMARY:x")
            repeat(folds) { append("\r\n x") }
            append("\r\nEND:VEVENT\r\nEND:VCALENDAR")
        }
        val started = System.nanoTime()
        val event = ICalendar.parse(raw)!!
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(folds + 1, event.title!!.length)
        // Deliberately generous: the linear version runs in a few milliseconds, the quadratic
        // one in minutes. Anything in between is still a red flag, without being CI-flaky.
        assertTrue("unfolding took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test fun anAbsurdlyLargeInviteIsRefusedInsteadOfParsed() {
        val padding = "X".repeat(ICalendar.MAX_SOURCE_CHARS)
        val raw = ics("BEGIN:VEVENT", "DTSTART:20260703T140000Z", "SUMMARY:$padding", "END:VEVENT")
        assertTrue(raw.length > ICalendar.MAX_SOURCE_CHARS)
        assertNull(ICalendar.parse(raw))
    }

    @Test fun anInviteJustUnderTheCapIsStillParsed() {
        val padding = "X".repeat(ICalendar.MAX_SOURCE_CHARS / 2)
        val event = ICalendar.parse(
            ics("BEGIN:VEVENT", "DTSTART:20260703T140000Z", "SUMMARY:$padding", "END:VEVENT"),
        )!!
        assertEquals(padding, event.title)
    }
}
