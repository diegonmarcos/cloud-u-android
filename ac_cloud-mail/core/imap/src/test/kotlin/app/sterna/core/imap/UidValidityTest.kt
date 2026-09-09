package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * A folder renumbered under a pending destroy (Codeberg #99).
 */
class UidValidityTest {

    /** A server whose Trash is renumbered between two SELECTs, on one connection. */
    private fun renumberingServer(first: Long, then: Long): FakeImapServer {
        val next = AtomicLong(first)
        return FakeImapServer { tag, line ->
            when {
                line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS\r\n$tag OK done\r\n"
                line.startsWith("SELECT") ->
                    selectResponse(tag, exists = 3, uidValidity = next.getAndSet(then))
                else -> ok(tag)
            }
        }
    }

    private fun List<String>.destructive(): List<String> = filter {
        it.startsWith("UID STORE") || it.startsWith("UID EXPUNGE") || it.startsWith("UID MOVE") ||
            it.startsWith("UID COPY") || it == "EXPUNGE"
    }

    @Test fun `a renumbered folder destroys nothing`() {
        renumberingServer(first = 1L, then = 12L).use { server ->
            server.session().use { session ->
                // The confirmation: the ids were read under this numbering.
                val confirmed = session.select("Trash")
                assertEquals(1L, confirmed.uidValidity)

                // Hours later, the worker gets connectivity and selects again.
                val refused = runCatching {
                    session.select("Trash", expectedUidValidity = confirmed.uidValidity)
                    session.delete(listOf(1L, 2L, 3L))
                }.exceptionOrNull()

                assertTrue("expected a refusal, got $refused", refused is ImapUidValidityChanged)
                assertEquals(12L, (refused as ImapUidValidityChanged).observed)
            }

            assertEquals(
                "a destroy went out against a renumbered folder: ${server.issued()}",
                emptyList<String>(),
                server.issued().destructive(),
            )
        }
    }

    /** The refusal is not an [ImapException]: the reconnect-and-retry paths must not absorb it. */
    @Test fun `the refusal is not a protocol error`() {
        renumberingServer(first = 1L, then = 12L).use { server ->
            server.session().use { session ->
                session.select("Trash")
                val refused = runCatching { session.select("Trash", expectedUidValidity = 1L) }.exceptionOrNull()

                assertTrue("an ImapException would be retried away: $refused", refused !is ImapException)
            }
        }
    }

    /** The normal case: same numbering, the purge goes ahead exactly as before. */
    @Test fun `an unchanged numbering destroys as usual`() {
        renumberingServer(first = 7L, then = 7L).use { server ->
            server.session().use { session ->
                session.select("Trash", expectedUidValidity = 7L)
                session.delete(listOf(1L, 2L, 3L))
            }

            assertEquals(listOf("1:3"), server.issued().filter { it.startsWith("UID EXPUNGE") }
                .map { it.removePrefix("UID EXPUNGE ") })
        }
    }

    /** No expectation (the discovery path) asks nothing and refuses nothing. */
    @Test fun `a caller with nothing to compare is never refused`() {
        renumberingServer(first = 1L, then = 12L).use { server ->
            val status = server.session().use { session ->
                session.select("Trash")
                session.select("Trash")
            }

            assertEquals(12L, status.uidValidity)
        }
    }

    /**
     * A server that reports no UIDVALIDITY at all cannot be checked either way. Refusing there
     */
    @Test fun `a server announcing no numbering is not turned into a refusal`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS\r\n$tag OK done\r\n"
                line.startsWith("SELECT") -> "* 3 EXISTS\r\n$tag OK [READ-WRITE] selected\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("Trash", expectedUidValidity = 5L)
                session.delete(listOf(1L))
            }

            assertTrue(server.issued().any { it.startsWith("UID STORE") })
        }
    }
}
