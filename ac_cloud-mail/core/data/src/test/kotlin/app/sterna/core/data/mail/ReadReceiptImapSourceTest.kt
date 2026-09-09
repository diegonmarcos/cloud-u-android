package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The IMAP half of the read-receipt header, read off a real message source — the same source
 */
class ReadReceiptImapSourceTest {

    private fun message(headers: String) =
        headers.trimIndent().replace("\n", "\r\n") + "\r\n\r\nHello.\r\n"

    @Test fun `the header is read off the source`() {
        val raw = message(
            """
            From: Ann Lee <ann@example.org>
            Subject: Six o'clock
            Disposition-Notification-To: Ann Lee <ann@example.org>
            """,
        )

        assertEquals("Ann Lee <ann@example.org>", readReceiptHeaderOf(raw))
        assertEquals(listOf("ann@example.org"), ReadReceiptHeader.recipients(readReceiptHeaderOf(raw)))
    }

    /** Header names are case-insensitive on the wire, and senders use every casing there is. */
    @Test fun `the header name is matched whatever its casing`() {
        val raw = message(
            """
            From: ann@example.org
            DISPOSITION-NOTIFICATION-TO: ann@example.org
            """,
        )

        assertEquals("ann@example.org", readReceiptHeaderOf(raw))
    }

    /** A folded address list is ONE value; read unfolded, it would hand the parser half a list. */
    @Test fun `a folded header comes back whole and names both addresses`() {
        val raw = message(
            """
            From: ann@example.org
            Disposition-Notification-To: Ann Lee <ann@example.org>,
             Receipts <receipts@example.org>
            """,
        )

        assertEquals(
            listOf("ann@example.org", "receipts@example.org"),
            ReadReceiptHeader.recipients(readReceiptHeaderOf(raw)),
        )
    }

    /** The ordinary message: no header, nothing read, nobody named. */
    @Test fun `a message that asks for nothing reads as null`() {
        val raw = message(
            """
            From: ann@example.org
            Subject: Six o'clock
            """,
        )

        assertNull(readReceiptHeaderOf(raw))
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients(readReceiptHeaderOf(raw)))
    }

    /**
     * The pre-standard spellings are NOT read. RFC 8098 §2.1 defines one field name; the two
     */
    @Test fun `the pre-standard spellings are not this header`() {
        val raw = message(
            """
            From: ann@example.org
            Return-Receipt-To: ann@example.org
            X-Confirm-Reading-To: ann@example.org
            """,
        )

        assertNull(readReceiptHeaderOf(raw))
    }

    // ---- the decision the draft save runs on this source, executed --------------------------

    /**
     * THE case that costs data. `ImapClient.fetchSource` answers an EMPTY STRING when the FETCH
     */
    @Test fun `an empty source is an unknown, never the absence of a request`() {
        assertNull(receiptRequestedInSource(""))
        assertNull(receiptRequestedInSource("   \r\n  "))
    }

    /** A source that really is a message answers a real yes/no. */
    @Test fun `a source that could be read answers what it says`() {
        assertEquals(
            true,
            receiptRequestedInSource(
                message(
                    """
                    From: ann@example.org
                    Disposition-Notification-To: ann@example.org
                    """,
                ),
            ),
        )
        assertEquals(
            false,
            receiptRequestedInSource(message("From: ann@example.org")),
        )
    }

    /** A header naming nothing usable is a real "no", not an unknown: it was read, and it names
     *  nobody a receipt could go to. */
    @Test fun `a header that names nobody is a no and not an unknown`() {
        assertEquals(
            false,
            receiptRequestedInSource(
                message(
                    """
                    From: ann@example.org
                    Disposition-Notification-To: undisclosed-recipients:;
                    """,
                ),
            ),
        )
    }

    /** The JMAP half of the same decision, executed: a server that refuses the property answers
     *  null for every message, so its null is an unknown and never a no. */
    @Test fun `a refused property is an unknown while a plain absent header is a no`() {
        assertNull(receiptRequestedInProperty(header = null, serverRefusesProperty = true))
        assertNull(
            receiptRequestedInProperty(header = "ann@example.org", serverRefusesProperty = true),
        )
        assertEquals(false, receiptRequestedInProperty(header = null, serverRefusesProperty = false))
        assertEquals(
            true,
            receiptRequestedInProperty(header = "Ann <ann@example.org>", serverRefusesProperty = false),
        )
    }

    /** A header that is there but names nothing usable is an empty list, never a crash. */
    @Test fun `a header naming nothing usable reads as no recipient`() {
        val raw = message(
            """
            From: ann@example.org
            Disposition-Notification-To: undisclosed-recipients:;
            """,
        )

        assertEquals("undisclosed-recipients:;", readReceiptHeaderOf(raw))
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients(readReceiptHeaderOf(raw)))
    }
}
