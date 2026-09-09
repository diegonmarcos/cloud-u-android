package app.sterna.core.data.mail

import app.sterna.core.data.account.MailProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * EXECUTES [rawSourceFromCache] — the decision that decides whether the raw-source cache is asked
 */
class RawSourceFromCacheTest {

    /** A cache lookup that counts how many times it was actually performed. */
    private class Lookup(private val answer: String?) : () -> String? {
        var reads = 0
        override fun invoke(): String? {
            reads++
            return answer
        }
    }

    /**
     * THE CASE THAT COSTS THE DUPLICATE: a caller carrying a frozen numbering. The cache holds
     */
    @Test fun `a stated numbering skips the cache, and does not even look`() {
        val lookup = Lookup("Subject: whoever holds that uid now\r\n")

        assertNull(
            "a caller that froze a numbering must be sent to the server: the cache is keyed by " +
                "account and message id and holds NO numbering, so a hit answers 'the octets we " +
                "last read for that text' — which is precisely what a renumbered folder makes a " +
                "different message",
            rawSourceFromCache(MailProtocol.IMAP, FrozenNumbering.Frozen(42L)) { lookup() },
        )
        assertEquals(
            "and it must not be CONSULTED either: consulting and discarding would still hand the " +
                "answer to any future caller written to use it, and the whole cost of this " +
                "decision is that the lookup does not happen",
            0, lookup.reads,
        )
    }

    /** Any stated number, not only the one this test picked — including the two edge values a
     *  numbering can carry on the wire. */
    @Test fun `every stated numbering skips the cache`() {
        listOf(1L, 42L, 0L, Long.MAX_VALUE).forEach { stated ->
            val lookup = Lookup("cached")
            assertNull("a numbering of $stated is still a numbering the cache cannot answer for", rawSourceFromCache(MailProtocol.IMAP, FrozenNumbering.Frozen(stated)) { lookup() })
            assertEquals(0, lookup.reads)
        }
    }

    /**
     * THE CASE A `Long?` COULD NOT EXPRESS, and the one this volet adds: a row that WAS ticked
     */
    @Test fun `a frozen absence skips the cache too, so the refusal is never late`() {
        val lookup = Lookup("Subject: the octets of whoever holds that uid now\r\n")

        assertNull(
            "Frozen(null) is 'a tick froze nothing at all', which is refused at the wire — the " +
                "cache must not answer in front of that refusal",
            rawSourceFromCache(MailProtocol.IMAP, FrozenNumbering.Frozen(null)) { lookup() },
        )
        assertEquals("and the lookup must not even happen", 0, lookup.reads)
    }

    /**
     * THE INVERSE WITNESS, without which "never answer from the cache" satisfies the case above
     */
    @Test fun `no stated numbering answers from the cache, exactly as before`() {
        val lookup = Lookup("Subject: the message that was read\r\n")

        assertEquals(
            "an ordinary read carries nothing frozen and must be served by the cache",
            "Subject: the message that was read\r\n",
            rawSourceFromCache(MailProtocol.IMAP, FrozenNumbering.NothingFrozen) { lookup() },
        )
        assertEquals("and it must be asked exactly once", 1, lookup.reads)
    }

    /**
     * THE OTHER PROTOCOL, AND IT IS NOT A DETAIL. On JMAP `numberingStampsOfRows` answers an
     */
    @Test fun `a JMAP read is served by the cache whatever was frozen`() {
        listOf(FrozenNumbering.NothingFrozen, FrozenNumbering.Frozen(null), FrozenNumbering.Frozen(42L))
            .forEach { frozen ->
                val lookup = Lookup("Subject: the blob we already downloaded\r\n")
                assertEquals(
                    "a JMAP id survives anything a mailbox can go through, so $frozen opposes " +
                        "nothing and must not cost a whole blob again",
                    "Subject: the blob we already downloaded\r\n",
                    rawSourceFromCache(MailProtocol.JMAP, frozen) { lookup() },
                )
                assertEquals("and the cache must be asked exactly once", 1, lookup.reads)
            }
    }

    /** The inverse witness of the one above, without which "always serve the cache" satisfies it
     *  and the volet is gone: on IMAP the same `Frozen(null)` must still skip. */
    @Test fun `the same frozen absence skips on IMAP and is served on JMAP`() {
        val onImap = Lookup("cached")
        val onJmap = Lookup("cached")

        assertNull(
            "IMAP: a uid means one message under one numbering, so a hit would sail past the " +
                "SELECT that refuses",
            rawSourceFromCache(MailProtocol.IMAP, FrozenNumbering.Frozen(null)) { onImap() },
        )
        assertEquals("cached", rawSourceFromCache(MailProtocol.JMAP, FrozenNumbering.Frozen(null)) { onJmap() })
        assertEquals("the IMAP lookup must not happen", 0, onImap.reads)
        assertEquals("the JMAP lookup must happen once", 1, onJmap.reads)
    }

    /** A miss is a miss: the caller fetches. The decision reports what the cache said, and does
     *  not turn an absent entry into anything else. */
    @Test fun `a miss with nothing frozen is a miss, and the lookup did happen`() {
        val lookup = Lookup(null)

        assertNull(rawSourceFromCache(MailProtocol.IMAP, FrozenNumbering.NothingFrozen) { lookup() })
        assertEquals(1, lookup.reads)
    }
}
