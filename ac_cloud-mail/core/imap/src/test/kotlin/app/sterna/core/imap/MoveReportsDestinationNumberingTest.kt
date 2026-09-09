package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a move brings back about the folder the mail LANDED in — the numbering `COPYUID` states
 */
class MoveReportsDestinationNumberingTest {

    /** The plain path: one chunk, one `UID MOVE`, one `COPYUID`. */
    @Test fun `a UID MOVE reports the numbering its COPYUID states`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> "$tag OK [COPYUID 77 1:3 7:9] moved\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Trash")
            }

            assertEquals(mapOf(1L to 7L, 2L to 8L, 3L to 9L), moved.uids)
            assertEquals(
                "the destination's numbering is the first token of COPYUID, and the only " +
                    "statement of it the app will ever get: an Undo has nothing else to oppose",
                77L,
                moved.destinationUidValidity,
            )
        }
    }

    /** The single-message move is written over the plural, and must answer both halves too — it
     *  is the one "delete" takes on an account with a Trash. */
    @Test fun `a single-message move reports the destination's numbering as well`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> "$tag OK [COPYUID 77 4 9] moved\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(4L, "Trash")
            }

            assertEquals("the new UID must still come back", mapOf(4L to 9L), moved.uids)
            assertEquals(77L, moved.destinationUidValidity)
        }
    }

    /** The copy fallback lands the mail with `UID COPY`, whose COPYUID says exactly the same
     *  thing (RFC 4315 §3): a server with no MOVE extension must not cost the Undo its numbering. */
    @Test fun `the copy fallback reports the numbering its own COPYUID states`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("CAPABILITY") ->
                    "* CAPABILITY IMAP4rev1 LITERAL+ UIDPLUS\r\n$tag OK capability completed\r\n"
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> "$tag BAD unknown command\r\n"
                line.startsWith("UID COPY") -> "$tag OK [COPYUID 77 1:3 7:9] copy completed\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Trash")
            }

            val issued = server.issued()
            assertTrue("the fallback did not run: $issued", issued.any { it.startsWith("UID COPY") })
            assertEquals(mapOf(1L to 7L, 2L to 8L, 3L to 9L), moved.uids)
            assertEquals(77L, moved.destinationUidValidity)
        }
    }

    /**
     * THE GUARD THAT MUST NOT MOVE: a server reporting no COPYUID at all. The move happened,
     */
    @Test fun `a server that reports no COPYUID states no numbering either`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> "$tag OK moved\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Trash")
            }

            val issued = server.issued()
            assertTrue("the move did not go out: $issued", issued.any { it.startsWith("UID MOVE") })
            assertEquals(emptyMap<Long, Long>(), moved.uids)
            assertNull(
                "a numbering nobody stated must stay null — it is the refusing value everywhere " +
                    "it is opposed, and inventing one here would license a move under a number " +
                    "the server never gave",
                moved.destinationUidValidity,
            )
        }
    }

    /** A `COPYUID 0` is a server stating nothing, under the convention the whole app applies to
     *  UIDVALIDITY: at or below 0 is "no numbering", never a number to oppose. */
    @Test fun `a COPYUID stating zero states nothing`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> "$tag OK [COPYUID 0 1 7] moved\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L), "Trash")
            }

            assertEquals(mapOf(1L to 7L), moved.uids)
            assertNull(moved.destinationUidValidity)
        }
    }

    /**
     * A move too big for one command is several `UID MOVE`s, so several COPYUIDs — all naming one
     * destination folder. Agreeing, they state one numbering, and that is the answer.
     */
    @Test fun `several chunks agreeing on a numbering state that one numbering`() {
        chunkedServer { 77L }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(manyUids, "Trash")
            }

            assertTrue("only one chunk went out: ${server.issued()}", server.issued().count { it.startsWith("UID MOVE") } > 1)
            assertEquals("every chunk's mapping must survive", manyUids.associateWith { it }, moved.uids)
            assertEquals(77L, moved.destinationUidValidity)
        }
    }

    /**
     * And chunks that DISAGREE state nothing: the destination was renumbered in the middle of the
     */
    @Test fun `chunks disagreeing about the numbering state none`() {
        var seen = 0
        chunkedServer { if (++seen == 1) 77L else 78L }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(manyUids, "Trash")
            }

            assertEquals(
                "the mapping itself still stands — the UIDs came back, it is only the ONE number " +
                    "for all of them that does not exist",
                manyUids.associateWith { it },
                moved.uids,
            )
            assertNull(
                "two numberings for one destination cannot be reduced to one: keeping either " +
                    "would send half these UIDs back out under a number they never belonged to",
                moved.destinationUidValidity,
            )
        }
    }

    /** Enough UIDs to span several `UID MOVE` chunks — the same span [ExpungeTest] uses. */
    private val manyUids = (1L..450L).toList()

    /** A server that answers each `UID MOVE` chunk with a COPYUID mapping the set onto itself, and
     *  the numbering [announce] gives for that chunk, in the order the chunks arrive. */
    private fun chunkedServer(announce: () -> Long): FakeImapServer = FakeImapServer { tag, line ->
        when {
            line.startsWith("SELECT") -> selectResponse(tag, exists = manyUids.size)
            line.startsWith("UID MOVE") -> {
                val set = line.removePrefix("UID MOVE ").substringBefore(' ')
                "$tag OK [COPYUID ${announce()} $set $set] moved\r\n"
            }
            else -> ok(tag)
        }
    }
}
