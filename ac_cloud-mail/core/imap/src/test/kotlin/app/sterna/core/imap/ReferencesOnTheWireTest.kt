package app.sterna.core.imap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * THE `References` HEADER TRAVELS WITH THE LIST PAGE — what the four list fetches put on the wire,
 */
class ReferencesOnTheWireTest {

    /**
     * One `FETCH` answer line for [uid]: the list items every fixture of this module carries,
     */
    private fun fetchLine(seq: Int, uid: Long, item: String): String =
        "* $seq FETCH (UID $uid FLAGS (\\Seen) INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
            "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" \"Message $uid\" " +
            "((\"Alex Rivera\" NIL \"alex.rivera\" \"masto.top\")) NIL NIL " +
            "((\"Team\" NIL \"team\" \"masto.top\")) NIL NIL NIL \"<$uid@masto.top>\") " +
            "BODYSTRUCTURE (\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"7bit\" 12 1) " +
            "$item)\r\n"

    /** The item as a literal: `<key> {n}\r\n<block>`. */
    private fun literal(key: String, block: String) = "$key {${block.length}}\r\n$block"

    /** Run [act] against a server answering every FETCH (plain or UID) with [answer]. */
    private fun conversation(
        answer: (tag: String, line: String) -> String,
        act: (ImapSession, ImapMailboxStatus) -> Unit,
    ): List<String> {
        val server = FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") || line.startsWith("FETCH") -> answer(tag, line)
                else -> ok(tag)
            }
        }
        return server.use {
            it.session().use { session ->
                val status = session.select("INBOX")
                act(session, status)
            }
            it.issued()
        }
    }

    /** `fetchUids(7, 9)` against a server answering the two uids with [item7] and [item9]. */
    private fun page(item7: String, item9: String): Map<Long, ImapMessage> {
        var fetched: List<ImapMessage> = emptyList()
        conversation({ tag, _ ->
            fetchLine(1, 7L, item7) + fetchLine(2, 9L, item9) + "$tag OK fetched\r\n"
        }) { session, _ ->
            fetched = session.fetchUids(listOf(7L, 9L))
        }
        return fetched.associateBy { it.uid }
    }

    /** (a) A `References` folded over two lines comes back unfolded, on one line, both ids kept. */
    @Test fun `a folded References is unfolded onto one line, each message its own`() {
        val got = page(
            literal("BODY[HEADER.FIELDS (REFERENCES)]", "References: <a@x>\r\n <b@x>\r\n\r\n"),
            literal("BODY[HEADER.FIELDS (REFERENCES)]", "References: <root@y>\r\n\r\n"),
        )

        assertEquals(setOf(7L, 9L), got.keys)
        assertEquals("<a@x> <b@x>", got[7L]!!.references)
        assertEquals("<root@y>", got[9L]!!.references)
        // The envelope beside it is still read: the new item did not shift the pairs.
        assertEquals("<7@masto.top>", got[7L]!!.messageId)
        assertEquals("Message 9", got[9L]!!.subject)
    }

    /** (b) The key as a server may spell it: lower case, field name quoted. Same reading. */
    @Test fun `the item key is matched whatever its case, and with the field name quoted`() {
        val got = page(
            literal("body[header.fields (references)]", "References: <a@x>\r\n <b@x>\r\n\r\n"),
            literal("BODY[HEADER.FIELDS (\"REFERENCES\")]", "references: <root@y>\r\n\r\n"),
        )

        assertEquals("<a@x> <b@x>", got[7L]!!.references)
        assertEquals("<root@y>", got[9L]!!.references)
    }

    /** (c) `NIL`, and an empty header block: no `References` is null, never "" and never a
     *  neighbouring item's value. */
    @Test fun `NIL and an empty header block both read as no References`() {
        val got = page(
            "BODY[HEADER.FIELDS (REFERENCES)] NIL",
            literal("BODY[HEADER.FIELDS (REFERENCES)]", "\r\n"),
        )

        assertEquals(setOf(7L, 9L), got.keys)
        assertNull(got[7L]!!.references)
        assertNull(got[9L]!!.references)
        // And the pairs after a NIL item are not shifted either.
        assertEquals("<7@masto.top>", got[7L]!!.messageId)
    }

    /** A block that names the header but carries nothing after the colon is no References. */
    @Test fun `a References line with an empty value is null`() {
        val got = page(
            literal("BODY[HEADER.FIELDS (REFERENCES)]", "References: \r\n\r\n"),
            literal("BODY[HEADER.FIELDS (REFERENCES)]", "References:\r\n\t<only@z>\r\n\r\n"),
        )

        assertNull(got[7L]!!.references)
        assertEquals("<only@z>", got[9L]!!.references)
    }

    /**
     * A block carrying MORE than the one header asked for: only `References` is read, by name. A
     */
    @Test fun `a block with several headers reads References alone, not In-Reply-To`() {
        val got = page(
            literal(
                "BODY[HEADER.FIELDS (REFERENCES)]",
                "In-Reply-To: <parent@x>\r\nReferences: <root@x> <parent@x>\r\n\r\n",
            ),
            literal(
                "BODY[HEADER.FIELDS (REFERENCES)]",
                "References: <root@y>\r\nIn-Reply-To: <parent@y>\r\n\r\n",
            ),
        )

        assertEquals("<root@x> <parent@x>", got[7L]!!.references)
        assertEquals("<root@y>", got[9L]!!.references)
    }

    /**
     * The same block with bare `\n` line ends (no `\r`), and a folded `References`: the header
     */
    @Test fun `a block with bare LF line ends and a folded References is read the same`() {
        val got = page(
            literal(
                "BODY[HEADER.FIELDS (REFERENCES)]",
                "In-Reply-To: <parent@x>\nReferences: <root@x>\n <parent@x>\n\n",
            ),
            literal("BODY[HEADER.FIELDS (REFERENCES)]", "References: <root@y>\n\n"),
        )

        assertEquals("<root@x> <parent@x>", got[7L]!!.references)
        assertEquals("<root@y>", got[9L]!!.references)
    }

    /**
     * The body readers do not take the header item for a body. No body fetch asks for it, but they
     */
    @Test fun `a body fetch answered with the header item first still reads the body`() {
        var fetched = ""
        var page: Map<Long, String> = emptyMap()
        conversation({ tag, _ ->
            "* 1 FETCH (UID 7 " + literal("BODY[HEADER.FIELDS (REFERENCES)]", "References: <a@x>\r\n\r\n") +
                " BODY[1]<0> {4}\r\nnine)\r\n$tag OK fetched\r\n"
        }) { session, _ ->
            fetched = session.fetchSectionPartial(7L, "1", IMAP_PREVIEW_FETCH_BYTES)
            page = session.fetchSectionPartials(listOf(7L), "1", IMAP_PREVIEW_FETCH_BYTES)
        }

        assertEquals("nine", fetched)
        assertEquals(mapOf(7L to "nine"), page)
    }

    /**
     * (d) The four list fetches — [ImapSession.fetchByUid], [ImapSession.fetchPage],
     */
    @Test fun `the four list fetches ask for References, and peek it`() {
        val issued = conversation({ tag, _ ->
            fetchLine(1, 7L, "BODY[HEADER.FIELDS (REFERENCES)] NIL") + "$tag OK fetched\r\n"
        }) { session, status ->
            session.fetchByUid(7L)
            session.fetchPage(exists = 1, offset = 0, limit = 10)
            runBlocking { session.walkFolder(status, limit = 10, pageSize = 10) { } }
            session.fetchUids(listOf(7L, 9L))
        }

        val items = "(UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (REFERENCES)])"
        assertEquals(
            listOf(
                "UID FETCH 7 $items",
                "FETCH 1:1 $items",
                "FETCH 1:1 $items",
                "UID FETCH 7,9 $items",
            ),
            issued.filter { it.startsWith("FETCH") || it.startsWith("UID FETCH") },
        )
    }
}
