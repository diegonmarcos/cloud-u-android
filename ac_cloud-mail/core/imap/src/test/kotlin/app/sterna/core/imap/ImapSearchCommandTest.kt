package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The `UID SEARCH` key list is the whole IMAP advanced search: a wrong space, a missing quote or
 */
class ImapSearchCommandTest {

    private fun keys(criteria: ImapSearchCriteria) = buildImapSearch(criteria).keys

    private fun dayMillis(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun endOfDayMillis(year: Int, month: Int, day: Int): Long =
        dayMillis(year, month, day) + 24L * 60 * 60 * 1000 - 1000

    // ---- one criterion at a time ----

    @Test
    fun `free text alone becomes a TEXT key`() {
        assertEquals("""TEXT "invoice"""", keys(ImapSearchCriteria(text = "invoice")))
    }

    @Test
    fun `sender alone becomes a FROM key`() {
        assertEquals("""FROM "alex@masto.top"""", keys(ImapSearchCriteria(from = "alex@masto.top")))
    }

    @Test
    fun `subject alone becomes a SUBJECT key`() {
        assertEquals("""SUBJECT "facture"""", keys(ImapSearchCriteria(subject = "facture")))
    }

    /** OR is prefix notation and binds exactly two keys: `OR <key> <key>`, then the AND chain. */
    @Test
    fun `recipient becomes a prefixed OR over TO and CC`() {
        assertEquals(
            """OR TO "team@masto.top" CC "team@masto.top"""",
            keys(ImapSearchCriteria(recipient = "team@masto.top")),
        )
    }

    /**
     * `FLAGGED` is a standard `SEARCH` key (RFC 3501 §6.4.4) taking no argument: the SERVER
     */
    @Test
    fun `flagged alone becomes a bare FLAGGED key`() {
        assertEquals("FLAGGED", keys(ImapSearchCriteria(flagged = true)))
    }

    /** The witness: unticked, no key. Without it the test above would pass on a constant filter. */
    @Test
    fun `an unticked flagged switch emits no FLAGGED key`() {
        val command = keys(ImapSearchCriteria(from = "alex@masto.top", flagged = false))
        assertEquals("""FROM "alex@masto.top"""", command)
        assertFalse(command.contains("FLAGGED"))
    }

    /** Cheapest key first: it discards the bulk of the folder before a body is ever opened. */
    @Test
    fun `flagged leads the key list, ahead of the expensive keys`() {
        assertEquals(
            """FLAGGED FROM "alex@masto.top" TEXT "invoice"""",
            keys(ImapSearchCriteria(text = "invoice", from = "alex@masto.top", flagged = true)),
        )
    }

    /** A flag name is ASCII, so ticking the box alone must never ask for a charset declaration. */
    @Test
    fun `flagged on its own never declares a charset`() {
        val command = buildImapSearch(ImapSearchCriteria(flagged = true))
        assertFalse(command.needsUtf8)
        assertEquals("FLAGGED", command.arguments())
    }

    @Test
    fun `after becomes SINCE with an english day-month-year date`() {
        assertEquals("SINCE 1-Jun-2026", keys(ImapSearchCriteria(afterMillis = dayMillis(2026, 6, 1))))
    }

    /** BEFORE is exclusive, so the picked day itself must stay in range: the day after is sent. */
    @Test
    fun `before is rendered as the day after the requested one`() {
        assertEquals("BEFORE 16-Jun-2026", keys(ImapSearchCriteria(beforeMillis = endOfDayMillis(2026, 6, 15))))
    }

    // ---- no HEADER key comes out of here, whatever is asked for (#95) ----

    /**
     * The witness for the key this builder no longer has. A `HEADER "Message-ID"` key was how a
     */
    @Test
    fun `no combination of criteria emits a HEADER key`() {
        // Every field the class still has, at once; the exact key list this builds is pinned by
        // `a full query combines every key, free text last`. Here: not one HEADER among them.
        val everything = keys(
            ImapSearchCriteria(
                text = "invoice",
                from = "alex@masto.top",
                recipient = "team@masto.top",
                subject = "Half written",
                flagged = true,
                afterMillis = dayMillis(2026, 6, 1),
                beforeMillis = endOfDayMillis(2026, 6, 15),
            ),
        )
        assertFalse("no search key of Sterna's asks a server about a header: $everything", everything.contains("HEADER"))
    }

    // ---- the defect this replaces ----

    @Test
    fun `blank free text emits no TEXT key at all`() {
        // `TEXT ""` matches EVERY message: that is how filters-only searches used to hand back
        // the whole inbox dressed up as a result set.
        val command = keys(ImapSearchCriteria(text = "   ", from = "alex@masto.top"))
        assertEquals("""FROM "alex@masto.top"""", command)
        assertFalse(command.contains("TEXT"))
    }

    @Test
    fun `an attachment-only search still sends a valid key list`() {
        // Nothing is expressible server-side; ALL keeps the command legal and the local
        // BODYSTRUCTURE filter narrows it down.
        assertEquals("ALL", keys(ImapSearchCriteria()))
    }

    // ---- everything at once ----

    @Test
    fun `a full query combines every key, free text last`() {
        val command = buildImapSearch(
            ImapSearchCriteria(
                text = "invoice",
                from = "alex@masto.top",
                recipient = "team@masto.top",
                subject = "facture",
                flagged = true,
                afterMillis = dayMillis(2026, 6, 1),
                beforeMillis = endOfDayMillis(2026, 6, 15),
            ),
        )
        assertEquals(
            """FLAGGED FROM "alex@masto.top" OR TO "team@masto.top" CC "team@masto.top" """ +
                """SUBJECT "facture" SINCE 1-Jun-2026 BEFORE 16-Jun-2026 TEXT "invoice"""",
            command.keys,
        )
        assertFalse(command.needsUtf8)
        assertEquals(command.keys, command.arguments())
    }

    // ---- quoting and injection ----

    @Test
    fun `values are quoted and inner quotes and backslashes escaped`() {
        assertEquals("""SUBJECT "say \"hi\""""", keys(ImapSearchCriteria(subject = """say "hi"""")))
        assertEquals("""SUBJECT "a\\b"""", keys(ImapSearchCriteria(subject = """a\b""")))
    }

    @Test
    fun `a newline in a search value is refused, never sent`() {
        // A raw CRLF would end the command line and inject a second authenticated command.
        val error = runCatching { keys(ImapSearchCriteria(subject = "a\r\nDELETE INBOX")) }.exceptionOrNull()
        assertTrue(error is ImapException)
    }

    @Test
    fun `values are trimmed but inner spaces kept`() {
        assertEquals("""SUBJECT "two words"""", keys(ImapSearchCriteria(subject = "  two words  ")))
    }

    // ---- charset ----

    @Test
    fun `a non-ascii value asks for a UTF-8 charset declaration`() {
        val command = buildImapSearch(ImapSearchCriteria(subject = "école"))
        assertTrue(command.needsUtf8)
        assertEquals("""CHARSET UTF-8 SUBJECT "école"""", command.arguments())
        assertEquals("""SUBJECT "école"""", command.arguments(declareUtf8 = false))
    }

    @Test
    fun `an ascii-only query never declares a charset`() {
        val command = buildImapSearch(ImapSearchCriteria(subject = "invoice"))
        assertFalse(command.needsUtf8)
        assertEquals("""SUBJECT "invoice"""", command.arguments())
    }

    // ---- dates are independent of the device locale and of the JVM's default zone ----

    @Test
    fun `month names stay english whatever the default locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            assertEquals("SINCE 3-May-2026", keys(ImapSearchCriteria(afterMillis = dayMillis(2026, 5, 3))))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `dates are read in UTC, matching the picker that produced them`() {
        // The date picker hands back UTC midnight; reading it in the device zone could shift
        // the boundary by a day for anyone east or west of Greenwich.
        val millis = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        assertEquals("SINCE 1-Jan-2026", keys(ImapSearchCriteria(afterMillis = millis)))
    }

    @Test
    fun `a december bound rolls over into the next year`() {
        assertEquals("BEFORE 1-Jan-2027", keys(ImapSearchCriteria(beforeMillis = endOfDayMillis(2026, 12, 31))))
    }
}
