package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [imapDateTimeMillis] EXECUTED — the reading of `INTERNALDATE`, the server's own delivery stamp.
 */
class InternalDateTest {

    @Test fun `a date-time is read to the millisecond, day space-padded or not`() {
        // 2026-06-01T10:00:00Z. RFC 3501 `date-day-fixed = (SP DIGIT) / 2DIGIT`, so the padded
        // form is the conforming one a server sends; the bare digit is accepted because servers
        // send that too.
        assertEquals(1_780_308_000_000L, imapDateTimeMillis("01-Jun-2026 10:00:00 +0000"))
        assertEquals(1_780_308_000_000L, imapDateTimeMillis(" 1-Jun-2026 10:00:00 +0000"))
        assertEquals(1_780_308_000_000L, imapDateTimeMillis("1-Jun-2026 10:00:00 +0000"))
    }

    @Test fun `the zone is applied, sign included`() {
        // Same wall clock, three zones: a zone read as UTC, or with its sign dropped, lands on a
        // different instant — 10:00 +0200 is 08:00Z, 10:00 -0500 is 15:00Z.
        assertEquals(1_780_300_800_000L, imapDateTimeMillis("01-Jun-2026 10:00:00 +0200"))
        assertEquals(1_780_326_000_000L, imapDateTimeMillis("01-Jun-2026 10:00:00 -0500"))
        // A half-hour zone, so the minutes of the offset cannot be silently ignored.
        assertEquals(1_772_674_689_000L, imapDateTimeMillis("05-Mar-2026 07:08:09 +0530"))
        // The one shape no case above and no round trip below can reach: a NEGATIVE sign AND
        // non-zero minutes. `ZoneOffset.ofHoursMinutes` refuses mixed signs outright, so a `sign *`
        assertEquals(1_772_707_089_000L, imapDateTimeMillis("05-Mar-2026 07:08:09 -0330"))
    }

    @Test fun `it is the inverse of the writer this file already had`() {
        // [imapDateTime] is what an APPEND puts on the wire, and it wraps its answer in the
        // DQUOTEs of a quoted-string. The parser strips those before any reader sees the value
        // (ImapParser.readQuoted), so the round trip has to strip them too — and it is `trim('"')`
        // here, on the test side, precisely because the function under test must NOT do it.
        listOf(1_780_308_000_000L, 946_684_799_000L, 0L, 1_772_674_689_000L)
            .forEach { assertEquals(it, imapDateTimeMillis(imapDateTime(it).trim('"'))) }
    }

    @Test fun `everything else is null, never zero and never now`() {
        listOf(
            "",
            "   ",
            // A month that is not one of the RFC's twelve.
            "01-Jum-2026 10:00:00 +0000",
            // ISO 8601 — what `Instant.toString()` produces, i.e. the shape of the value on the
            // OTHER side of this fix. Reading it here would let a wrong caller pass unnoticed.
            "2026-06-01T10:00:00Z",
            // An RFC 5322 `Date:` header: the very value this whole change stops trusting.
            "Mon, 1 Jun 2026 10:00:00 +0000",
            // Impossible calendar values: java.time refuses them and the refusal is KEPT.
            "32-Jun-2026 10:00:00 +0000",
            "01-Jun-2026 25:00:00 +0000",
            "01-Feb-2026 10:00:00 +9900",
            // Still quoted: the only way one arrives like this is a reader that took the wrong
            // token, and guessing on its behalf would hide that.
            "\"01-Jun-2026 10:00:00 +0000\"",
            // Truncated, or with something after it.
            "01-Jun-2026 10:00:00",
            "01-Jun-2026 10:00:00 +0000 (UTC)",
        ).forEach { assertNull("«$it» must not be read as a date at all", imapDateTimeMillis(it)) }
    }
}
