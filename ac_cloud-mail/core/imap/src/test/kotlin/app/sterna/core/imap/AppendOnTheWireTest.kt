package app.sterna.core.imap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * WHAT AN APPEND PUTS ON THE WIRE, byte for byte — the primitive a cross-account move stands on
 */
class AppendOnTheWireTest {

    /** A message whose bytes are NOT valid UTF-8 as a whole: `П` (D0 9F) then `é` as one ISO
     *  octet (E9), then a bare 0xFF. Any re-encoding on the way out changes the count. */
    private val eightBit = byteArrayOf(
        0xD0.toByte(), 0x9F.toByte(), 0x20, 0xE9.toByte(), 0x20, 0xFF.toByte(), 0x0D, 0x0A,
    )

    private class Appended(val issued: List<String>, val literals: List<ByteArray>, val uid: Long?)

    /** Run [act] against a server that answers every APPEND with `+`, then [completion]. */
    private fun appendAgainst(completion: (String) -> String, act: (ImapSession) -> Long?): Appended {
        val server = FakeImapServer(
            afterLiteral = { tag, _, _ -> completion(tag) },
        ) { tag, line ->
            if (line.startsWith("APPEND")) "+ go ahead\r\n" else ok(tag)
        }
        return server.use {
            val uid = it.session().use { session -> act(session) }
            Appended(it.issued(), it.literals.toList(), uid)
        }
    }

    private fun appendUidCompletion(tag: String) = "$tag OK [APPENDUID 38505 3955] APPEND completed\r\n"

    // ---- the octets ----

    @Test fun `eight-bit bytes leave the client as themselves and size the literal`() {
        val result = appendAgainst(::appendUidCompletion) { session ->
            session.append("INBOX", eightBit, "\\Seen")
        }

        assertEquals(1, result.literals.size)
        assertArrayEquals(eightBit, result.literals[0])
        // Every session opens by asking whether the server knows the RFC 2971 ID
        // command (#173); this fixture answers without it, so nothing is named.
        assertEquals(
            listOf("CAPABILITY", "APPEND \"INBOX\" (\\Seen) {8}", "LOGOUT"),
            result.issued,
        )
    }

    @Test fun `a date goes out as a quoted RFC 3501 date-time between the flags and the literal`() {
        val at = Instant.parse("2026-09-01T21:15:03Z").toEpochMilli()
        val result = appendAgainst(::appendUidCompletion) { session ->
            session.append("Archive", eightBit, "\\Seen", internalDate = at)
        }

        assertEquals(
            listOf("CAPABILITY", "APPEND \"Archive\" (\\Seen) \" 1-Sep-2026 21:15:03 +0000\" {8}", "LOGOUT"),
            result.issued,
        )
        assertArrayEquals(eightBit, result.literals.single())
    }

    @Test fun `a date with no flags sits right after the mailbox`() {
        val at = Instant.parse("2026-12-25T08:05:09Z").toEpochMilli()
        val result = appendAgainst(::appendUidCompletion) { session ->
            session.append("Archive", byteArrayOf(0x41, 0x0D, 0x0A), "", internalDate = at)
        }

        assertEquals(
            listOf("CAPABILITY", "APPEND \"Archive\" \"25-Dec-2026 08:05:09 +0000\" {3}", "LOGOUT"),
            result.issued,
        )
    }

    /** The string overload is the two callers of today (Sent copy, draft): UTF-8, unchanged. */
    @Test fun `the string overload still encodes UTF-8 and emits the line of before`() {
        val result = appendAgainst(::appendUidCompletion) { session ->
            session.append("Sent", "Subject: é\r\n\r\n", "\\Seen")
            null
        }

        assertEquals(
            // "Subject: é\r\n\r\n" is 14 chars and 15 bytes: the é is C3 A9.
            listOf("CAPABILITY", "APPEND \"Sent\" (\\Seen) {15}", "LOGOUT"),
            result.issued,
        )
        assertArrayEquals(
            byteArrayOf(
                'S'.code.toByte(), 'u'.code.toByte(), 'b'.code.toByte(), 'j'.code.toByte(),
                'e'.code.toByte(), 'c'.code.toByte(), 't'.code.toByte(), ':'.code.toByte(),
                ' '.code.toByte(), 0xC3.toByte(), 0xA9.toByte(), 0x0D, 0x0A, 0x0D, 0x0A,
            ),
            result.literals.single(),
        )
    }

    // ---- the answer ----

    @Test fun `APPENDUID names the copy`() {
        val result = appendAgainst(::appendUidCompletion) { session ->
            session.append("INBOX", eightBit, "\\Seen")
        }
        assertEquals(3955L, result.uid)
    }

    @Test fun `a completion without APPENDUID names nothing`() {
        val result = appendAgainst({ tag -> "$tag OK APPEND completed\r\n" }) { session ->
            session.append("INBOX", eightBit, "\\Seen")
        }
        assertNull(result.uid)
    }

    @Test fun `a NO completion is a failure, not a nameless copy`() {
        val thrown = runCatching {
            appendAgainst({ tag -> "$tag NO [OVERQUOTA] APPEND failed\r\n" }) { session ->
                session.append("INBOX", eightBit, "\\Seen")
            }
        }.exceptionOrNull()
        val imap = thrown as? ImapException ?: throw AssertionError("expected an ImapException, got $thrown")
        assertEquals("OVERQUOTA", imap.responseCode)
    }

    // ---- the two pure pieces, executed on their own ----

    @Test fun `the response code is read off the tagged line as the parser hands it`() {
        assertEquals(
            3955L,
            appendUidIn(listOf("a2", "OK", "[APPENDUID", "38505", "3955]", "APPEND", "completed")),
        )
        assertNull(appendUidIn(listOf("a2", "OK", "APPEND", "completed")))
        // Lower case, and the code somewhere other than first: still the uid, not the validity.
        assertEquals(7L, appendUidIn(listOf("a2", "OK", "[appenduid", "1", "7]", "done")))
        assertNull(appendUidIn(emptyList<Any?>()))
    }

    @Test fun `the date-time is the fixed RFC 3501 shape, in UTC`() {
        assertEquals("\" 1-Sep-2026 21:15:03 +0000\"", imapDateTime(Instant.parse("2026-09-01T21:15:03Z").toEpochMilli()))
        assertEquals("\"25-Dec-2026 08:05:09 +0000\"", imapDateTime(Instant.parse("2026-12-25T08:05:09Z").toEpochMilli()))
        assertEquals("\"31-Jan-2027 00:00:00 +0000\"", imapDateTime(Instant.parse("2027-01-31T00:00:00Z").toEpochMilli()))
        // Milliseconds are dropped, not rounded: the grammar has seconds only.
        assertEquals("\"10-Jun-2026 12:30:59 +0000\"", imapDateTime(Instant.parse("2026-06-10T12:30:59.999Z").toEpochMilli()))
    }
}
