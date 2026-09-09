package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the NEWEST end of a folder, driven against a scripted server on loopback (#95).
 */
class NewestUidsTest {

    /** The whole point, against [ImapSession.allUids] on the very same answer. */
    @Test
    fun `a folder deeper than the cap yields its highest uids, not its first`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 120)
                line.startsWith("UID SEARCH") -> searchResponse(tag, (1L..120L).toList())
                else -> ok(tag)
            }
        }.use { server ->
            val (newest, oldest) = server.session().use { session ->
                session.select("Drafts")
                session.newestUids(cap = 5) to session.allUids(cap = 5)
            }

            assertEquals(listOf(116L, 117L, 118L, 119L, 120L), newest)
            // The witness that the two really are opposite ends of one answer.
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), oldest)
        }
    }

    /** One command, no envelope fetched to build the list, and `ALL` is the key sent. */
    @Test
    fun `it costs one UID SEARCH ALL and no fetch`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID SEARCH") -> searchResponse(tag, listOf(1L, 2L, 3L))
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("Drafts")
                session.newestUids(cap = 50)
            }

            // Every session opens by asking whether the server knows the RFC 2971 ID
            // command (#173); this fixture answers without it, so nothing is named.
            assertEquals(
                listOf("CAPABILITY", """SELECT "Drafts"""", "UID SEARCH ALL", "LOGOUT"),
                server.issued(),
            )
        }
    }

    /**
     * A split answer, and the tail is the answer. A server is free to spread its result over
     */
    @Test
    fun `a result split over several untagged lines is read whole, tail included`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 9)
                line.startsWith("UID SEARCH") ->
                    "* SEARCH 1 2 3\r\n* SEARCH 4 5 6\r\n* SEARCH 7 8 9\r\n$tag OK search completed\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val newest = server.session().use { session ->
                session.select("Drafts")
                session.newestUids(cap = 4)
            }

            assertEquals(listOf(6L, 7L, 8L, 9L), newest)
        }
    }

    /**
     * The ids are the HIGHEST, not merely the last ones listed: RFC 3501 promises no order at all
     * for a `SEARCH` response, and "the newest" is a claim about UID values.
     */
    @Test
    fun `an answer the server did not sort still yields the highest uids`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 6)
                line.startsWith("UID SEARCH") -> "* SEARCH 40 7 12 3 39 1\r\n$tag OK search completed\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val newest = server.session().use { session ->
                session.select("Drafts")
                session.newestUids(cap = 3)
            }

            assertEquals(listOf(12L, 39L, 40L), newest)
        }
    }

    /** A folder smaller than the cap comes back whole, and in ascending order. */
    @Test
    fun `a folder shallower than the cap yields all of it`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID SEARCH") -> searchResponse(tag, listOf(2L, 5L, 8L))
                else -> ok(tag)
            }
        }.use { server ->
            val newest = server.session().use { session ->
                session.select("Drafts")
                session.newestUids(cap = 50)
            }

            assertEquals(listOf(2L, 5L, 8L), newest)
        }
    }

    /** An empty folder is an empty list — and the caller reads that as "append it". */
    @Test
    fun `an empty folder yields nothing`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 0)
                line.startsWith("UID SEARCH") -> searchResponse(tag, emptyList())
                else -> ok(tag)
            }
        }.use { server ->
            val newest = server.session().use { session ->
                session.select("Drafts")
                session.newestUids(cap = 50)
            }

            assertEquals(emptyList<Long>(), newest)
        }
    }

    /** A cap of zero asks the server nothing at all. */
    @Test
    fun `a cap of zero sends no command`() {
        FakeImapServer { tag, _ -> ok(tag) }.use { server ->
            val newest = server.session().use { it.newestUids(cap = 0) }

            assertEquals(emptyList<Long>(), newest)
            // The CAPABILITY is the connect-time ID question (RFC 2971, #173), not the enumeration.
            assertEquals(listOf("CAPABILITY", "LOGOUT"), server.issued())
        }
    }

    /**
     * A refused search THROWS. Answering an empty list instead would be an invented fact — "the
     */
    @Test
    fun `a server refusing the search fails instead of reporting an empty folder`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID SEARCH") -> "$tag NO [SERVERBUG] search unavailable\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("Drafts")
                val failure = runCatching { session.newestUids(cap = 50) }.exceptionOrNull()
                assertTrue("expected an ImapException, got $failure", failure is ImapException)
            }
        }
    }
}
