package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WHAT `fetchInternalDate` PUTS ON THE WIRE, and what it makes of the answer — driven against a
 */
class InternalDateOnTheWireTest {

    /** Run against a server answering every `UID FETCH` with [answer]; give back what was said and
     *  what the session read. */
    private fun conversation(answer: (String) -> String): Pair<List<String>, Long?> {
        var read: Long? = null
        val server = FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") -> answer(tag)
                else -> ok(tag)
            }
        }
        val issued = server.use {
            it.session().use { session ->
                session.select("INBOX")
                read = session.fetchInternalDate(7L)
            }
            it.issued()
        }
        return issued to read
    }

    /** Every session opens by asking whether the server knows the RFC 2971 ID command (#173); this
     *  fixture answers without it, so nothing is named. */
    private val expectedCommands =
        listOf("CAPABILITY", "SELECT \"INBOX\"", "UID FETCH 7 (INTERNALDATE)", "LOGOUT")

    @Test fun `one fetch, INTERNALDATE alone, read back as millis`() {
        val (issued, read) = conversation { tag ->
            "* 1 FETCH (UID 7 INTERNALDATE \"01-Jun-2026 10:00:00 +0000\")\r\n$tag OK fetched\r\n"
        }

        assertEquals(expectedCommands, issued)
        assertEquals(1_780_308_000_000L, read)
    }

    @Test fun `the server's stamp is taken, not the sender's Date header sitting beside it`() {
        // The forged message: `Date: Sat, 1 Jan 2100`, delivered in 2026. Whoever sent it chose
        // ENVELOPE[0]; nobody outside the server chooses INTERNALDATE. Reading the wrong one files
        // an Autocrypt key with an effective date in 2100, and no later key can beat it.
        val (issued, read) = conversation { tag ->
            "* 1 FETCH (UID 7 INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
                "ENVELOPE (\"Sat, 1 Jan 2100 00:00:00 +0000\" \"Hello\" NIL NIL NIL NIL NIL NIL " +
                "NIL \"<1@masto.top>\"))\r\n$tag OK fetched\r\n"
        }

        assertEquals(expectedCommands, issued)
        assertEquals(1_780_308_000_000L, read)
    }

    @Test fun `a space-padded day, the form the RFC actually prescribes`() {
        val (_, read) = conversation { tag ->
            "* 1 FETCH (UID 7 INTERNALDATE \" 1-Jun-2026 10:00:00 +0000\")\r\n$tag OK fetched\r\n"
        }

        assertEquals(1_780_308_000_000L, read)
    }

    @Test fun `no INTERNALDATE in the answer is null, not a guess`() {
        val (issued, read) = conversation { tag ->
            "* 1 FETCH (UID 7 FLAGS (\\Seen))\r\n$tag OK fetched\r\n"
        }

        assertEquals(expectedCommands, issued)
        assertNull(read)
    }

    @Test fun `no FETCH line at all is null`() {
        val (_, read) = conversation { tag -> "$tag OK fetched\r\n" }

        assertNull(read)
    }

    @Test fun `an INTERNALDATE that is not a date-time is null`() {
        // Refusing costs a key we never had. Defaulting to "now" would date an unknown key with
        // today, and the provider keeps the newest — see [InternalDateTest].
        val (_, read) = conversation { tag ->
            "* 1 FETCH (UID 7 INTERNALDATE \"whenever\")\r\n$tag OK fetched\r\n"
        }

        assertNull(read)
    }
}
