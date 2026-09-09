package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHAT THE BODY FETCHES ACTUALLY PUT ON THE WIRE — the three of them, line for line.
 */
class BodyPeekOnTheWireTest {

    /** A FETCH answer carrying [body] for [item] (`BODY[1]<0>`, `BODY[2]`, `BODY[]`). */
    private fun bodyResponse(tag: String, item: String, body: String): String =
        "* 1 FETCH (UID 7 $item {${body.length}}\r\n$body)\r\n$tag OK fetched\r\n"

    /** Run [act] against a server that answers every UID FETCH with [answer]; give back what the
     *  session actually said, LOGIN excluded. */
    private fun conversation(answer: (String) -> String, act: (ImapSession) -> Unit): List<String> {
        val server = FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") -> answer(tag)
                else -> ok(tag)
            }
        }
        return server.use {
            it.session().use { session ->
                session.select("INBOX")
                act(session)
            }
            it.issued()
        }
    }

    @Test fun `a preview fetch peeks, and asks for the first 8 KiB only`() {
        val issued = conversation({ tag -> bodyResponse(tag, "BODY[1]<0>", "Hello") }) { session ->
            session.fetchSectionPartial(7L, "1", IMAP_PREVIEW_FETCH_BYTES)
        }

        assertEquals(
            // Every session opens by asking whether the server knows the RFC 2971 ID
            // command (#173); this fixture answers without it, so nothing is named.
            listOf("CAPABILITY", "SELECT \"INBOX\"", "UID FETCH 7 (BODY.PEEK[1]<0.8192>)", "LOGOUT"),
            issued,
        )
    }

    @Test fun `an attachment fetch peeks`() {
        val issued = conversation({ tag -> bodyResponse(tag, "BODY[2]", "data") }) { session ->
            session.fetchSection(7L, "2")
        }

        // Every session opens by asking whether the server knows the RFC 2971 ID
        // command (#173); this fixture answers without it, so nothing is named.
        assertEquals(listOf("CAPABILITY", "SELECT \"INBOX\"", "UID FETCH 7 (BODY.PEEK[2])", "LOGOUT"), issued)
    }

    @Test fun `a source fetch peeks`() {
        val issued = conversation({ tag -> bodyResponse(tag, "BODY[]", "Subject: hi\r\n\r\nbody") }) { session ->
            session.fetchSource(7L)
        }

        // Every session opens by asking whether the server knows the RFC 2971 ID
        // command (#173); this fixture answers without it, so nothing is named.
        assertEquals(listOf("CAPABILITY", "SELECT \"INBOX\"", "UID FETCH 7 (BODY.PEEK[])", "LOGOUT"), issued)
    }

    /**
     * THE UNPROVEN CLAIM, proven: a PARTIAL fetch has never been made by this app, and the code
     */
    @Test fun `the partial answer is recognised, and its octets come back`() {
        var fetched: String? = null
        conversation({ tag -> bodyResponse(tag, "BODY[1]<0>", "Bonjour a tous") }) { session ->
            fetched = session.fetchSectionPartial(7L, "1", IMAP_PREVIEW_FETCH_BYTES)
        }

        assertEquals("Bonjour a tous", fetched)
    }

    /**
     * "At most 8 KiB" must be TRUE, not merely REQUESTED. `<0.8192>` is a request; nothing above
     */
    @Test fun `an over-long answer is cut to the bound the client asked for`() {
        val huge = "A".repeat(20_000)
        var fetched: String? = null
        conversation({ tag -> bodyResponse(tag, "BODY[1]<0>", huge) }) { session ->
            fetched = session.fetchSectionPartial(7L, "1", IMAP_PREVIEW_FETCH_BYTES)
        }

        assertEquals(IMAP_PREVIEW_FETCH_BYTES, fetched!!.length)
        assertEquals(huge.take(IMAP_PREVIEW_FETCH_BYTES), fetched)
    }

    /** A truncated part is answered with fewer octets than were asked for, and that is not an
     *  error: what came back is what there is. */
    @Test fun `a part shorter than the bound comes back whole`() {
        var fetched: String? = null
        conversation({ tag -> bodyResponse(tag, "BODY[1]<0>", "hi") }) { session ->
            fetched = session.fetchSectionPartial(7L, "1", IMAP_PREVIEW_FETCH_BYTES)
        }

        assertEquals("hi", fetched)
    }

    /**
     * A server that answers something else must yield NOTHING. The item list opens with `UID 7`,
     */
    @Test fun `a FETCH with no body item yields nothing at all`() {
        var fetched: String? = null
        conversation({ tag -> "* 1 FETCH (UID 7 FLAGS (\\Seen))\r\n$tag OK fetched\r\n" }) { session ->
            fetched = session.fetchSectionPartial(7L, "1", IMAP_PREVIEW_FETCH_BYTES)
        }

        assertEquals("", fetched)
    }

    /**
     * `BODY[] NIL` — the item is THERE and names nothing. `ImapParser` tokenises NIL as a Kotlin
     */
    @Test fun `a source answered as BODY NIL yields nothing`() {
        var fetched: String? = null
        conversation({ tag -> "* 1 FETCH (UID 7 BODY[] NIL)\r\n$tag OK fetched\r\n" }) { session ->
            fetched = session.fetchSource(7L)
        }

        assertEquals("", fetched)
    }

    /** Same refusal for the two older fetches, which share the one reader. */
    @Test fun `an attachment answer with no body item yields nothing either`() {
        var section: String? = null
        var source: String? = null
        conversation({ tag -> "* 1 FETCH (UID 7 FLAGS (\\Seen))\r\n$tag OK fetched\r\n" }) { session ->
            section = session.fetchSection(7L, "2")
            source = session.fetchSource(7L)
        }

        assertEquals("", section)
        assertEquals("", source)
    }
}
