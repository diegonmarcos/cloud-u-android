package app.sterna.ui.message

import app.sterna.core.imap.CryptoKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePagingTest {
    private val ids = listOf<String?>("a", "b", "c", "d")

    @Test fun `anchor found wins over fallback`() {
        assertEquals(2, MessagePaging.resolveInitialPage(ids, anchorId = "c", fallbackIndex = 0))
    }

    @Test fun `anchor missing falls back to index`() {
        assertEquals(1, MessagePaging.resolveInitialPage(ids, anchorId = "z", fallbackIndex = 1))
    }

    @Test fun `fallback beyond the end is clamped to the last entry`() {
        assertEquals(3, MessagePaging.resolveInitialPage(ids, anchorId = "z", fallbackIndex = 99))
    }

    @Test fun `negative fallback is clamped to the first entry`() {
        assertEquals(0, MessagePaging.resolveInitialPage(ids, anchorId = "z", fallbackIndex = -5))
    }

    @Test fun `empty list resolves to zero`() {
        assertEquals(0, MessagePaging.resolveInitialPage(emptyList(), anchorId = "a", fallbackIndex = 7))
    }

    @Test fun `null gaps in the loaded window are skipped when matching`() {
        val withGaps = listOf<String?>(null, "b", null, "d")
        assertEquals(3, MessagePaging.resolveInitialPage(withGaps, anchorId = "d", fallbackIndex = 0))
    }

    // --- hasPrevious / hasNext: the position line's chevrons (Codeberg #13) ---

    @Test fun `both chevrons are live in the middle of a conversation`() {
        assertTrue(MessagePaging.hasPrevious(page = 1, pageCount = 3))
        assertTrue(MessagePaging.hasNext(page = 1, pageCount = 3))
    }

    @Test fun `the first message greys out the previous chevron only`() {
        assertFalse(MessagePaging.hasPrevious(page = 0, pageCount = 3))
        assertTrue(MessagePaging.hasNext(page = 0, pageCount = 3))
    }

    @Test fun `the last message greys out the next chevron only`() {
        assertTrue(MessagePaging.hasPrevious(page = 2, pageCount = 3))
        assertFalse(MessagePaging.hasNext(page = 2, pageCount = 3))
    }

    @Test fun `a two-message conversation is live at both ends in turn`() {
        assertFalse(MessagePaging.hasPrevious(page = 0, pageCount = 2))
        assertTrue(MessagePaging.hasNext(page = 0, pageCount = 2))
        assertTrue(MessagePaging.hasPrevious(page = 1, pageCount = 2))
        assertFalse(MessagePaging.hasNext(page = 1, pageCount = 2))
    }

    @Test fun `a lone message has nowhere to go in either direction`() {
        assertFalse(MessagePaging.hasPrevious(page = 0, pageCount = 1))
        assertFalse(MessagePaging.hasNext(page = 0, pageCount = 1))
    }

    // --- mergeEntries: the reading session's sticky entry list ---

    private fun merge(stable: List<String>, live: List<String>) =
        MessagePaging.mergeEntries(stable, live) { it }

    @Test fun `empty stable adopts the live list`() {
        assertEquals(listOf("a", "b"), merge(emptyList(), listOf("a", "b")))
    }

    @Test fun `a removed entry keeps its slot`() {
        assertEquals(listOf("a", "b", "c"), merge(listOf("a", "b", "c"), listOf("b", "c")))
    }

    @Test fun `unread-filter cascade - successive removals never shrink the session list`() {
        // Unread-only list [a,b,c]; a is read and drops out, then b: the pager's entries
        // must stay [a,b,c] throughout so the settled page never re-binds.
        var entries = merge(listOf("a", "b", "c"), listOf("b", "c"))
        entries = merge(entries, listOf("c"))
        assertEquals(listOf("a", "b", "c"), entries)
    }

    @Test fun `older mail paged in appends at the end`() {
        assertEquals(listOf("a", "b", "c", "d"), merge(listOf("a", "b"), listOf("a", "b", "c", "d")))
    }

    @Test fun `new mail prepends at the top`() {
        assertEquals(listOf("x", "a", "b"), merge(listOf("a", "b"), listOf("x", "a", "b")))
    }

    @Test fun `an insertion lands between its live neighbours`() {
        assertEquals(listOf("a", "b", "c"), merge(listOf("a", "c"), listOf("a", "b", "c")))
    }

    @Test fun `appends still merge after a removal`() {
        assertEquals(listOf("a", "b", "c"), merge(listOf("a", "b"), listOf("b", "c")))
    }

    @Test fun `empty live leaves the session list untouched`() {
        assertEquals(listOf("a", "b"), merge(listOf("a", "b"), emptyList()))
    }

    @Test fun `identical lists come back equal`() {
        assertEquals(listOf("a", "b"), merge(listOf("a", "b"), listOf("a", "b")))
    }

    // --- entryKey: the reader's (account, id) identity (#92) ---
    //
    // JMAP ids are per account, so two accounts on one server can hold the same message id.
    // The reader's page key and its "already loaded?" guard must separate them.

    @Test fun `the same id under two accounts gets two different keys`() {
        assertNotEquals(
            MessagePaging.entryKey("M42", "acct-a"),
            MessagePaging.entryKey("M42", "acct-b"),
        )
    }

    @Test fun `the same id under the same account gets the same key`() {
        assertEquals(
            MessagePaging.entryKey("M42", "acct-a"),
            MessagePaging.entryKey("M42", "acct-a"),
        )
    }

    @Test fun `two ids under one account stay distinct`() {
        assertNotEquals(
            MessagePaging.entryKey("M42", "acct-a"),
            MessagePaging.entryKey("M43", "acct-a"),
        )
    }

    @Test fun `a missing account is a stable key, not a crash or a wildcard`() {
        assertEquals(MessagePaging.entryKey("M42", null), MessagePaging.entryKey("M42", null))
        assertNotEquals(MessagePaging.entryKey("M42", null), MessagePaging.entryKey("M43", null))
        // "no account" must not collide with a real account either.
        assertNotEquals(MessagePaging.entryKey("M42", null), MessagePaging.entryKey("M42", "acct-a"))
    }

    @Test fun `no pair of parts can be glued into another pair's key`() {
        // The separator is not a legal character in an id, so account+id can't be re-cut:
        // ("a", "b-c") and ("a-b", "c") must not land on the same key.
        assertNotEquals(
            MessagePaging.entryKey(accountId = "a", emailId = "b-c"),
            MessagePaging.entryKey(accountId = "a-b", emailId = "c"),
        )
    }

    @Test fun `for a single account the keys are as discriminating as the bare ids`() {
        // Granularity guarantee: within one account the key partitions the ids exactly as the
        // bare id did, so nothing about single-account paging changes.
        val ids = listOf("a", "b", "c", "d")
        val keys = ids.map { MessagePaging.entryKey(it, "solo") }
        assertEquals(ids.size, keys.toSet().size)
    }

    // --- needsLoad: the reading view's load guard (settle-gating, then #92) ---
    //
    // The first question is `settled`: the pager composes one page each side of the one being
    // read, and on an IMAP account a load fetches the whole source, which imports the sender's
    // announced key into OpenKeychain. A load on an unsettled page is a key deposited from a
    // message nobody opened, and nothing in this app can take it back.

    @Test fun `a page the reader has not settled on never loads, however new its id`() {
        // The warmed neighbour: nothing loaded here yet, so every id-based rule says "load".
        assertFalse(
            MessagePaging.needsLoad(
                settled = false,
                loadedId = null,
                loadedAccountId = null,
                emailId = "M42",
                accountId = "acct-a",
                failed = false,
            ),
        )
    }

    @Test fun `an unsettled page in the error state does not load either`() {
        // The trap the old shape fell into: the guard read "no reload needed AND not in error",
        // an OR in disguise, so an error state re-loaded whatever the page's status. A neighbour
        // whose previous fetch failed is still a message nobody touched.
        assertFalse(
            MessagePaging.needsLoad(
                settled = false,
                loadedId = "M42",
                loadedAccountId = "acct-a",
                emailId = "M42",
                accountId = "acct-a",
                failed = true,
            ),
        )
        // …and not even when the id is new as well: `settled` gates everything.
        assertFalse(
            MessagePaging.needsLoad(
                settled = false,
                loadedId = "M41",
                loadedAccountId = "acct-a",
                emailId = "M42",
                accountId = "acct-a",
                failed = true,
            ),
        )
    }

    @Test fun `the settled page loads a message it has not loaded yet`() {
        assertTrue(
            MessagePaging.needsLoad(
                settled = true,
                loadedId = null,
                loadedAccountId = null,
                emailId = "M42",
                accountId = "acct-a",
                failed = false,
            ),
        )
    }

    @Test fun `the settled page does not reload what it already holds`() {
        assertFalse(
            MessagePaging.needsLoad(
                settled = true,
                loadedId = "M42",
                loadedAccountId = "acct-a",
                emailId = "M42",
                accountId = "acct-a",
                failed = false,
            ),
        )
    }

    @Test fun `the settled page retries the message it failed to load`() {
        // The Retry button, and the recomposition after a failure: same id, same account, but the
        // last attempt ended in error, so asking again must go through.
        assertTrue(
            MessagePaging.needsLoad(
                settled = true,
                loadedId = "M42",
                loadedAccountId = "acct-a",
                emailId = "M42",
                accountId = "acct-a",
                failed = true,
            ),
        )
    }

    /** [MessagePaging.needsLoad] for a settled page whose last load did not fail — where the #92
     *  cases below all live. It forwards its arguments and decides nothing. */
    private fun settledOk(loadedId: String?, loadedAccountId: String?, emailId: String, accountId: String?) =
        MessagePaging.needsLoad(
            settled = true,
            loadedId = loadedId,
            loadedAccountId = loadedAccountId,
            emailId = emailId,
            accountId = accountId,
            failed = false,
        )

    @Test fun `nothing loaded yet always loads`() {
        assertTrue(settledOk(null, null, "M42", "acct-a"))
        assertTrue(settledOk(null, null, "M42", null))
    }

    @Test fun `the same message under the same account does not reload`() {
        assertFalse(settledOk("M42", "acct-a", "M42", "acct-a"))
        assertFalse(settledOk("M42", null, "M42", null))
    }

    @Test fun `the same id under another account reloads`() {
        // The reported defect: the guard saw the id alone, returned early, and left the
        // previously loaded account's message on screen.
        assertTrue(settledOk("M42", "acct-a", "M42", "acct-b"))
    }

    @Test fun `switching between no account and an account reloads`() {
        assertTrue(settledOk("M42", null, "M42", "acct-a"))
        assertTrue(settledOk("M42", "acct-a", "M42", null))
    }

    @Test fun `another id reloads whatever the account`() {
        assertTrue(settledOk("M42", "acct-a", "M43", "acct-a"))
        assertTrue(settledOk("M42", "acct-a", "M43", "acct-b"))
    }

    @Test fun `the guard agrees with the page key, once the page has settled`() {
        // One rule, two uses: on the settled page, a reload is needed exactly when the entry key
        // changes. Unsettled, the two part company on purpose — see the tests above.
        fun agree(id1: String, acc1: String?, id2: String, acc2: String?) {
            val differentKey = MessagePaging.entryKey(id1, acc1) != MessagePaging.entryKey(id2, acc2)
            assertEquals(differentKey, settledOk(id1, acc1, id2, acc2))
        }
        agree("M42", "acct-a", "M42", "acct-a")
        agree("M42", "acct-a", "M42", "acct-b")
        agree("M42", "acct-a", "M43", "acct-a")
        agree("M42", null, "M42", null)
        agree("M42", null, "M42", "acct-a")
    }

    // --- settledEntry: which MESSAGE the reader is settled on (bench G-décalage, 2026-09-04) ---
    //
    // The answer is an entry, never a page number. A page number is refreshed only around a
    // scroll, so a list that moves underneath an open reader leaves it naming the neighbour above
    // — which then loads, and on IMAP a load files that sender's announced key in OpenKeychain.

    @Test fun `at rest the settled entry is the one under the reader's eyes`() {
        assertEquals("b", MessagePaging.settledEntry(previous = "a", atRest = "b", scrolling = false))
    }

    @Test fun `a page merely brushed past while scrolling never becomes the settled one`() {
        // G16: the finger crosses the neighbour's page mid-gesture. `isScrollInProgress` is true
        // for the whole of it, drag and fling alike, so the previously settled message stands —
        // this is the guard that carries the value of the old `settledPage`.
        assertEquals("a", MessagePaging.settledEntry(previous = "a", atRest = "b", scrolling = true))
        assertEquals("a", MessagePaging.settledEntry(previous = "a", atRest = null, scrolling = true))
    }

    @Test fun `at rest on an entry that is gone the settled entry is null, not the last one`() {
        // The fixed chrome (star, delete, move) publishes itself from this: keeping the previous
        // message here would leave those actions wired to a message no longer in the list.
        assertNull(MessagePaging.settledEntry(previous = "a", atRest = null, scrolling = false))
    }

    @Test fun `nothing settled yet adopts the first entry seen at rest`() {
        assertEquals("a", MessagePaging.settledEntry(previous = null, atRest = "a", scrolling = false))
    }

    /**
     * What an insertion at the top of the mailbox does to a RANK and to an IDENTITY, played with
     */
    @Test fun `mail injected at the top moves the rank, never the identity`() {
        val neigh = "GB-NEIGH" to "acct-a"
        val open = "GB-OPEN" to "acct-a"
        val older = "GB-OLD" to "acct-a"
        val injected = "GB-INJ" to "acct-a"
        val idOf = { e: Pair<String, String?> -> MessagePaging.entryKey(e.first, e.second) }

        val before = MessagePaging.mergeEntries(emptyList(), listOf(neigh, open, older), idOf)
        // The reader settles on GB-OPEN: page 1 of [GB-NEIGH, GB-OPEN, GB-OLD].
        val pageSettledOn = before.indexOfFirst { idOf(it) == idOf(open) }
        assertEquals(1, pageSettledOn)

        // New mail arrives and merges in at the top. No gesture of any kind.
        val after = MessagePaging.mergeEntries(before, listOf(injected, neigh, open, older), idOf)
        assertEquals(listOf(injected, neigh, open, older), after)

        // BY RANK — the page number the reader settled on now names the message ABOVE hers. This
        // is the bench red: GB-NEIGH's page believed itself settled, loaded, and its sender's key
        // landed in OpenKeychain from a message nobody opened.
        assertEquals(
            "after an insertion above her, the settled page NUMBER names the neighbour",
            neigh,
            after[pageSettledOn],
        )

        // BY IDENTITY — GB-OPEN is still in the list, and it is no longer at that number. Holding
        // the settled page as the entry is what survives the shift; holding it as a rank does not.
        val whereOpenIsNow = after.indexOfFirst { idOf(it) == idOf(open) }
        assertNotEquals(pageSettledOn, whereOpenIsNow)
        assertEquals(open, after[whereOpenIsNow])
    }

    /**
     * And the two ways the screen can then be driven, run through the shipped decision: at rest on
     */
    @Test fun `the settled message survives the shift, whichever way the pager reports it`() {
        val open = "GB-OPEN" to "acct-a"
        val neigh = "GB-NEIGH" to "acct-a"

        val atRestOnOpen = MessagePaging.settledEntry(previous = open, atRest = open, scrolling = false)
        assertEquals(open, atRestOnOpen)

        // A report that arrives WHILE the list is still moving must not be taken: the neighbour
        // sitting at the old number is exactly what `scrolling` refuses.
        assertEquals(open, MessagePaging.settledEntry(previous = open, atRest = neigh, scrolling = true))
    }

    // --- warmable: may this cached body be put on screen before the finger arrives? ---
    //
    // The decision is EXECUTED here, on values, never re-derived by the test. The call site
    //    that feeds it (MessageViewModel.warmFromCache) is pinned separately, line for line, by
    //    LoadOnSettleCallSiteTest: a pure function is green against any arguments at all.

    @Test fun `a plain cached body with no inline image is warmed`() {
        assertTrue(
            MessagePaging.warmable(
                cryptoKind = null,
                inlineParts = 0,
                cachedInlineImages = 0,
                senderAllowedRemoteImages = false,
            ),
        )
    }

    @Test fun `an encrypted body is never warmed, whatever its images`() {
        // Warming one would paint the PGP armour, in clear, on a page nobody has swiped to.
        assertFalse(
            MessagePaging.warmable(
                cryptoKind = CryptoKind.PGP_ENCRYPTED,
                inlineParts = 0,
                cachedInlineImages = 0,
                senderAllowedRemoteImages = false,
            ),
        )
        assertFalse(
            MessagePaging.warmable(
                cryptoKind = CryptoKind.PGP_ENCRYPTED,
                inlineParts = 2,
                cachedInlineImages = 2,
                senderAllowedRemoteImages = false,
            ),
        )
    }

    @Test fun `a signed body is never warmed either`() {
        assertFalse(
            MessagePaging.warmable(
                cryptoKind = CryptoKind.PGP_SIGNED,
                inlineParts = 0,
                cachedInlineImages = 0,
                senderAllowedRemoteImages = false,
            ),
        )
    }

    @Test fun `inline armour is never warmed either`() {
        assertFalse(
            MessagePaging.warmable(
                cryptoKind = CryptoKind.PGP_INLINE,
                inlineParts = 0,
                cachedInlineImages = 0,
                senderAllowedRemoteImages = false,
            ),
        )
    }

    @Test fun `a body whose inline images are still missing is not warmed`() {
        // The JMAP prefetch writes `inlineImages = emptyMap()`. Warming this builds a document
        // with holes; settling on it downloads the images, `html` is rebuilt on `msg.inlineImages`
        // and the WebView RELOADS — a white flash, worse than the spinner it replaced.
        assertFalse(
            MessagePaging.warmable(
                cryptoKind = null,
                inlineParts = 1,
                cachedInlineImages = 0,
                senderAllowedRemoteImages = false,
            ),
        )
    }

    @Test fun `a body whose inline images are already cached is warmed`() {
        assertTrue(
            MessagePaging.warmable(
                cryptoKind = null,
                inlineParts = 1,
                cachedInlineImages = 1,
                senderAllowedRemoteImages = false,
            ),
        )
    }

    @Test fun `the predicate is exactly the condition under which ensureInlineImages does nothing`() {
        // MailRepository.ensureInlineImages returns the cached body untouched when
        // `inlineImageParts().isEmpty() || inlineImages.isNotEmpty()`. Warming under anything
        // WIDER is what makes the WebView reload; this pins the two halves and the corner where
        // more images are cached than the body references.
        assertTrue(MessagePaging.warmable(null, 0, cachedInlineImages = 3, senderAllowedRemoteImages = false))
        assertTrue(MessagePaging.warmable(null, 3, cachedInlineImages = 1, senderAllowedRemoteImages = false))
        assertFalse(MessagePaging.warmable(null, 3, cachedInlineImages = 0, senderAllowedRemoteImages = false))
    }

    /**
     * THE REFUSAL, PINNED — but NOT for the reason first written here. That reason was: a
     */
    @Test fun `a body whose sender loads remote images automatically is never warmed`() {
        assertFalse(
            "an allowlisted sender's body warmed on an unswiped page reloads under the finger",
            MessagePaging.warmable(
                cryptoKind = null,
                inlineParts = 0,
                cachedInlineImages = 0,
                senderAllowedRemoteImages = true,
            ),
        )
        assertFalse(
            "the refusal must not depend on the inline-image counts: they are a different question",
            MessagePaging.warmable(
                cryptoKind = null,
                inlineParts = 2,
                cachedInlineImages = 2,
                senderAllowedRemoteImages = true,
            ),
        )
    }
}
