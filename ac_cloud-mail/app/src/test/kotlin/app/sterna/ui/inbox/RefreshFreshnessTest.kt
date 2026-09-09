package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real test of #178's freshness guard: it EXECUTES the decision, on literal instants.
 *
 * Nothing here recomputes the rule to choose what it replays. Every instant is written out
 * (`T0`, `T0 + 29_000`, …) and every expectation is a constant, so inverting the shipped condition
 * has to redden something. `RefreshFreshnessWiringLintTest` covers the other half — which callers
 * are behind the guard and which must never be — because `InboxViewModel` is an `AndroidViewModel`
 * and cannot be built on the JVM.
 */
class RefreshFreshnessTest {

    // -- the window ------------------------------------------------------------------------------

    @Test fun `a scope reconciled 29 seconds ago is still fresh`() {
        val entries = listOf(FreshScope(ACCOUNT_A, INBOX, T0))
        assertTrue("29 s after a successful reconcile, the window is still open", isFresh(entries, listOf(ACCOUNT_A to INBOX), T0 + 29_000L))
    }

    @Test fun `at exactly 30 seconds it is no longer fresh`() {
        val entries = listOf(FreshScope(ACCOUNT_A, INBOX, T0))
        assertFalse("the window is 30 s and its far edge is excluded", isFresh(entries, listOf(ACCOUNT_A to INBOX), T0 + 30_000L))
    }

    @Test fun `at 31 seconds it is no longer fresh`() {
        val entries = listOf(FreshScope(ACCOUNT_A, INBOX, T0))
        assertFalse("past the window, the view is reconciled again", isFresh(entries, listOf(ACCOUNT_A to INBOX), T0 + 31_000L))
    }

    /**
     * The wall clock can move BACKWARDS (NTP, a hand-set date). A stamp left in the future must
     * read as stale, or the list stops reconciling until the clock catches up — possibly never.
     */
    @Test fun `a stamp in the future is not fresh`() {
        val entries = listOf(FreshScope(ACCOUNT_A, INBOX, T0 + 5_000L))
        assertFalse(
            "a stamp 5 s in the future means the clock went back; fresh would hold the list until " +
                "the clock caught up",
            isFresh(entries, listOf(ACCOUNT_A to INBOX), T0),
        )
    }

    @Test fun `a stamp far in the future is not fresh either`() {
        val entries = listOf(FreshScope(ACCOUNT_A, INBOX, T0 + 400_000L))
        assertFalse(
            "a stamp far in the future would freeze the list for as long as the gap lasts",
            isFresh(entries, listOf(ACCOUNT_A to INBOX), T0),
        )
    }

    /** Corrupted preferences must not come out as "fresh for the next 292 million years". */
    @Test fun `a nonsense stamp does not overflow into freshness`() {
        assertFalse(
            "Long.MIN_VALUE must not overflow `now - at` into a small positive number, i.e. into fresh",
            isFresh(listOf(FreshScope(ACCOUNT_A, INBOX, Long.MIN_VALUE)), listOf(ACCOUNT_A to INBOX), T0),
        )
        assertFalse(
            "Long.MAX_VALUE is the future, however far",
            isFresh(listOf(FreshScope(ACCOUNT_A, INBOX, Long.MAX_VALUE)), listOf(ACCOUNT_A to INBOX), T0),
        )
    }

    // -- the key ---------------------------------------------------------------------------------

    /**
     * #92 / #121: two accounts on the SAME server number their mailboxes independently, so the id
     * `"1"` names a different folder in each. A register keyed on the folder id alone would let
     * account A's fresh Inbox skip account B's first ever fetch.
     */
    @Test fun `the same folder id under another account is not fresh`() {
        val entries = listOf(FreshScope(ACCOUNT_A, INBOX, T0))
        assertTrue("account A's own folder is the one that was read", isFresh(entries, listOf(ACCOUNT_A to INBOX), T0 + 1_000L))
        assertFalse(
            "the id \"a1\" names a DIFFERENT folder under account B: keyed on the folder id alone, " +
                "B's first ever fetch would be skipped and its list left empty (#92/#121)",
            isFresh(entries, listOf(ACCOUNT_B to INBOX), T0 + 1_000L),
        )
    }

    @Test fun `another folder of the same account is not fresh`() {
        val entries = listOf(FreshScope(ACCOUNT_A, INBOX, T0))
        assertFalse("reading the Inbox says nothing about the Archive", isFresh(entries, listOf(ACCOUNT_A to ARCHIVE), T0 + 1_000L))
    }

    // -- what a view spans -----------------------------------------------------------------------

    /**
     * The never-synced account's state. Fresh would skip the first fetch and leave an empty list
     * that nothing but a pull gesture could fill.
     */
    @Test fun `an empty scope list is never fresh`() {
        assertFalse(
            "no scope is the never-synced account's state: fresh would skip the first fetch and " +
                "leave an empty list nothing but a pull gesture could fill",
            isFresh(listOf(FreshScope(ACCOUNT_A, INBOX, T0)), emptyList(), T0),
        )
        assertFalse("and an empty register does not make it fresh either", isFresh(emptyList(), emptyList(), T0))
    }

    @Test fun `an empty register is never fresh`() {
        assertFalse("nothing has ever been reconciled", isFresh(emptyList(), listOf(ACCOUNT_A to INBOX), T0))
    }

    @Test fun `a unified view is fresh only when every account is`() {
        val both = listOf(ACCOUNT_A to INBOX, ACCOUNT_B to INBOX)
        val allFresh = listOf(FreshScope(ACCOUNT_A, INBOX, T0), FreshScope(ACCOUNT_B, INBOX, T0))
        assertTrue("both accounts came back a second ago", isFresh(allFresh, both, T0 + 1_000L))

        val oneStale = listOf(FreshScope(ACCOUNT_A, INBOX, T0), FreshScope(ACCOUNT_B, INBOX, T0 - 60_000L))
        assertFalse("one stale account is a stale unified inbox", isFresh(oneStale, both, T0 + 1_000L))

        val oneMissing = listOf(FreshScope(ACCOUNT_A, INBOX, T0))
        assertFalse("an account that was never reconciled is not fresh by omission", isFresh(oneMissing, both, T0 + 1_000L))
    }

    // -- recording -------------------------------------------------------------------------------

    @Test fun `recording a scope makes it fresh and leaves the others alone`() {
        val before = listOf(FreshScope(ACCOUNT_B, INBOX, T0))
        val after = recordFresh(before, listOf(ACCOUNT_A to INBOX), T0 + 1_000L)
        assertEquals(
            listOf(FreshScope(ACCOUNT_B, INBOX, T0), FreshScope(ACCOUNT_A, INBOX, T0 + 1_000L)),
            after,
        )
    }

    @Test fun `recording a scope again overwrites its entry rather than doubling it`() {
        val once = recordFresh(emptyList(), listOf(ACCOUNT_A to INBOX), T0)
        val twice = recordFresh(once, listOf(ACCOUNT_A to INBOX), T0 + 5_000L)
        assertEquals(listOf(FreshScope(ACCOUNT_A, INBOX, T0 + 5_000L)), twice)
    }

    @Test fun `recording the same scope twice in one call stores it once`() {
        val entries = recordFresh(emptyList(), listOf(ACCOUNT_A to INBOX, ACCOUNT_A to INBOX), T0)
        assertEquals(listOf(FreshScope(ACCOUNT_A, INBOX, T0)), entries)
    }

    @Test fun `recording prunes what has fallen out of the window, past and future`() {
        val before = listOf(
            FreshScope(ACCOUNT_B, INBOX, T0 - 30_000L), // exactly out
            FreshScope(ACCOUNT_B, ARCHIVE, T0 - 29_000L), // still in
            FreshScope(ACCOUNT_C, INBOX, T0 + 60_000L), // clock went back: drop it
        )
        val after = recordFresh(before, listOf(ACCOUNT_A to INBOX), T0)
        assertEquals(
            listOf(FreshScope(ACCOUNT_B, ARCHIVE, T0 - 29_000L), FreshScope(ACCOUNT_A, INBOX, T0)),
            after,
        )
    }

    @Test fun `an unrecorded scope stays stale`() {
        val after = recordFresh(emptyList(), listOf(ACCOUNT_A to INBOX), T0)
        assertFalse("recording the Inbox must not vouch for every folder", isFresh(after, listOf(ACCOUNT_A to ARCHIVE), T0))
    }

    // -- storage ---------------------------------------------------------------------------------

    @Test fun `a register survives the round trip unchanged`() {
        val entries = listOf(
            FreshScope(ACCOUNT_A, INBOX, T0),
            FreshScope(ACCOUNT_B, "INBOX/Travaux en cours", T0 - 1L),
            FreshScope(ACCOUNT_C, "id with spaces & = signs", 0L),
        )
        assertEquals(entries, decodeFreshness(encodeFreshness(entries)))
    }

    @Test fun `an empty register stores as nothing at all`() {
        assertNull(encodeFreshness(emptyList()))
        assertEquals(emptyList<FreshScope>(), decodeFreshness(null))
        assertEquals(emptyList<FreshScope>(), decodeFreshness(""))
    }

    @Test fun `a corrupted register loses the bad lines and keeps the good ones`() {
        val good = "$ACCOUNT_A\t$INBOX\t$T0"
        val stored = listOf(
            good,
            "$ACCOUNT_B\t$INBOX", // a field short
            "$ACCOUNT_B\t$INBOX\tnot-a-number", // unreadable stamp
            "", // blank line
            "\t\t", // empty ids
            "$ACCOUNT_C\t$INBOX\t${T0}\textra", // a field too many
        ).joinToString("\n")
        assertEquals(listOf(FreshScope(ACCOUNT_A, INBOX, T0)), decodeFreshness(stored))
    }

    /**
     * A folder id can carry the separator: an IMAP path is decoded from modified UTF-7 with
     * nothing filtered out, so a mailbox named with a line feed travels as `&AAo-` and comes back
     * holding U+000A. Such an entry is not stored — so that folder is simply reconciled again,
     * which is the safe direction — and it does not corrupt its neighbours.
     */
    @Test fun `a folder id carrying a separator is refused, and poisons nothing`() {
        val entries = listOf(
            FreshScope(ACCOUNT_A, "INBOX/two\nlines", T0),
            FreshScope(ACCOUNT_A, "INBOX/tab\there", T0),
            FreshScope(ACCOUNT_B, INBOX, T0),
        )
        assertEquals(
            "an id holding a separator is dropped on the way to disk, and the entry after it is not",
            listOf(FreshScope(ACCOUNT_B, INBOX, T0)),
            decodeFreshness(encodeFreshness(entries)),
        )
        assertFalse(
            "a folder we decline to remember is reconciled again — the safe direction",
            isFresh(decodeFreshness(encodeFreshness(entries)), listOf(ACCOUNT_A to "INBOX/two\nlines"), T0),
        )
        assertTrue(
            "and its neighbour is untouched",
            isFresh(decodeFreshness(encodeFreshness(entries)), listOf(ACCOUNT_B to INBOX), T0),
        )
    }

    @Test fun `an unstorable entry does not survive a record and a round trip`() {
        val recorded = recordFresh(emptyList(), listOf(ACCOUNT_A to "INBOX/two\nlines"), T0)
        assertEquals(1, recorded.size) // it is IN the list in memory …
        assertNull(encodeFreshness(recorded)) // … and refused on the way to disk
    }

    // -- what a sign-out takes with it (PRIVACY.md: "removed when you remove the account") --------

    /**
     * The register is read back with [decodeFreshness] and pinned entry by entry, and the string
     * itself is pinned too: an answer that merely looks shorter is not a proof that the right
     * account left.
     */
    @Test fun `the removed account's entries go and the survivor's stay`() {
        val stored = "$ACCOUNT_A\t$INBOX\t$T0\n$ACCOUNT_B\t$ARCHIVE\t$T0"
        val pruned = prunedFreshness(stored, setOf(ACCOUNT_A))
        assertEquals(
            "only the surviving account may be left in the register",
            listOf(FreshScope(ACCOUNT_B, ARCHIVE, T0)),
            decodeFreshness(pruned),
        )
        assertEquals("and it is re-encoded as itself", "$ACCOUNT_B\t$ARCHIVE\t$T0", pruned)
    }

    /** A removed account can have visited several folders; they all go in one pass. */
    @Test fun `several folders of the removed account all go at once`() {
        val stored = "$ACCOUNT_A\t$INBOX\t$T0\n$ACCOUNT_A\t$ARCHIVE\t$T0\n$ACCOUNT_B\t$ARCHIVE\t$T0"
        assertEquals(
            listOf(FreshScope(ACCOUNT_B, ARCHIVE, T0)),
            decodeFreshness(prunedFreshness(stored, setOf(ACCOUNT_A))),
        )
    }

    /**
     * The `accountId` decides, never the `mailboxId`. Two accounts on the same server number
     * their mailboxes independently (#92 / #121), so the survivor's entry here carries the very
     * same folder id as the removed account's and must stay.
     */
    @Test fun `two accounts sharing a mailbox id lose only the removed one's entry`() {
        val stored = "$ACCOUNT_A\t$INBOX\t$T0\n$ACCOUNT_B\t$INBOX\t$T0"
        assertEquals(
            listOf(FreshScope(ACCOUNT_B, INBOX, T0)),
            decodeFreshness(prunedFreshness(stored, setOf(ACCOUNT_A))),
        )
    }

    /** A login and its sub-accounts leave together (#31): every named id is filtered out. */
    @Test fun `a cascade removes every id it names`() {
        val stored = "$ACCOUNT_A\t$INBOX\t$T0\n$ACCOUNT_B\t$INBOX\t$T0\n$ACCOUNT_C\t$INBOX\t$T0"
        assertEquals(
            listOf(FreshScope(ACCOUNT_C, INBOX, T0)),
            decodeFreshness(prunedFreshness(stored, setOf(ACCOUNT_A, ACCOUNT_B))),
        )
    }

    @Test fun `removing the last account present takes the key away`() {
        assertNull(
            "nothing is left to remember, so the preference must go, not stay as an empty string",
            prunedFreshness("$ACCOUNT_A\t$INBOX\t$T0", setOf(ACCOUNT_A)),
        )
    }

    @Test fun `nothing stored gives nothing, and nothing removed keeps the register`() {
        assertNull(prunedFreshness(null, setOf(ACCOUNT_A)))
        assertEquals(
            listOf(FreshScope(ACCOUNT_A, INBOX, T0), FreshScope(ACCOUNT_B, INBOX, T0)),
            decodeFreshness(
                prunedFreshness("$ACCOUNT_A\t$INBOX\t$T0\n$ACCOUNT_B\t$INBOX\t$T0", emptySet()),
            ),
        )
    }

    /**
     * It prunes by ACCOUNT, not by time. A surviving account's entry stamped well outside the
     * window is stale, and staleness is [isFresh]'s business, read at the guard: dropping it here —
     * or restamping it — would be a second answer to a question that already has one.
     */
    @Test fun `a survivor's out of window entry is carried through with its own stamp`() {
        val stored = "$ACCOUNT_A\t$INBOX\t$T0\n$ACCOUNT_B\t$INBOX\t${T0 - 300_000L}"
        assertEquals(
            listOf(FreshScope(ACCOUNT_B, INBOX, T0 - 300_000L)),
            decodeFreshness(prunedFreshness(stored, setOf(ACCOUNT_A))),
        )
    }

    private companion object {
        /** A literal instant; nothing here derives an expectation from the window's value. */
        const val T0 = 1_756_000_000_000L

        const val ACCOUNT_A = "11111111-1111-1111-1111-111111111111"
        const val ACCOUNT_B = "22222222-2222-2222-2222-222222222222"
        const val ACCOUNT_C = "33333333-3333-3333-3333-333333333333"

        /** Same id under two accounts on purpose — Stalwart numbers mailboxes per account. */
        const val INBOX = "a1"
        const val ARCHIVE = "a7"
    }
}
