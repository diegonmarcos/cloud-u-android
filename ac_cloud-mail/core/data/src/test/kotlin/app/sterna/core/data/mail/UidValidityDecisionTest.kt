package app.sterna.core.data.mail

import app.sterna.core.data.mail.UidValidity.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a recorded UIDVALIDITY licenses (Codeberg #99) — the decision itself, with no server and
 */
class UidValidityDecisionTest {

    @Test fun `the same numbering is the ordinary case`() {
        assertEquals(Verdict.SAME, UidValidity.verdict(recorded = 42L, observed = 42L))
    }

    @Test fun `a different numbering is a renumbering`() {
        assertEquals(Verdict.CHANGED, UidValidity.verdict(recorded = 42L, observed = 43L))
        // Lower, too: nothing says a new numbering is bigger than the old one.
        assertEquals(Verdict.CHANGED, UidValidity.verdict(recorded = 42L, observed = 7L))
    }

    @Test fun `a folder seen for the first time is recorded, not refused`() {
        assertEquals(Verdict.FIRST_SIGHT, UidValidity.verdict(recorded = null, observed = 42L))
    }

    /** A server that announces no UIDVALIDITY cannot be checked; refusing would break the folder
     *  outright, which is the wrong direction for a guard about DESTROYING. */
    @Test fun `a server announcing nothing is unverifiable, not changed`() {
        assertEquals(Verdict.UNVERIFIABLE, UidValidity.verdict(recorded = 42L, observed = 0L))
        assertEquals(Verdict.UNVERIFIABLE, UidValidity.verdict(recorded = null, observed = 0L))
        assertEquals(Verdict.UNVERIFIABLE, UidValidity.verdict(recorded = 0L, observed = 42L))
    }

    // ---- what the server STATED --------------------------------------------------------------
    //
    // One rule, two kinds of caller, and that is why it has a name. `ImapMailboxStatus
    // .uidValidity` is a `Long` where 0 means "the server said nothing"; every row is WRITTEN with

    /** Nothing stated is not a numbering: `null` in, `null` out, and the column stays NULL. */
    @Test fun `a server that stated nothing states no numbering`() {
        assertNull(UidValidity.stated(null))
    }

    /**
     * AND NEITHER IS ZERO — the case the whole function exists for. `ImapMailboxStatus` carries
     */
    @Test fun `zero is what the server did not say, not a numbering`() {
        assertNull(UidValidity.stated(0L))
    }

    /** A negative cannot be a UIDVALIDITY either (RFC 3501 makes it a 32-bit unsigned), and the
     *  answer is the same refusal rather than a value nothing can match. */
    @Test fun `a negative numbering is refused like a missing one`() {
        assertNull(UidValidity.stated(-1L))
    }

    /** A numbering the server did state comes back untouched — the ordinary case, and the one that
     *  fails the moment someone "simplifies" the rule into a constant. */
    @Test fun `a stated numbering comes back as it was stated`() {
        assertEquals(1L, UidValidity.stated(1L))
        assertEquals(42L, UidValidity.stated(42L))
        assertEquals(Long.MAX_VALUE, UidValidity.stated(Long.MAX_VALUE))
    }

    // ---- the destroy decision ---------------------------------------------------------------

    /**
     * THE data-loss guard. A snapshot with no numbering cannot be shown to still mean anything —
     */
    @Test fun `a snapshot with no numbering destroys nothing`() {
        assertFalse(UidValidity.mayDestroy(null))
        assertFalse(UidValidity.mayDestroy(0L))
        assertFalse(UidValidity.mayDestroy(-1L))
    }

    @Test fun `a snapshot carrying its numbering may be destroyed`() {
        assertTrue(UidValidity.mayDestroy(1L))
        assertTrue(UidValidity.mayDestroy(Long.MAX_VALUE))
    }

    // ---- what a snapshot records ------------------------------------------------------------

    @Test fun `what the server reported is what the snapshot records`() {
        assertEquals(9L, TrashPurge.snapshotUidValidity(observed = 9L, recorded = 4L))
    }

    /**
     * Offline: the server could not be asked and the CACHED ids stood in. Those ids were fetched
     */
    @Test fun `an offline snapshot inherits the numbering its cached ids belong to`() {
        assertEquals(4L, TrashPurge.snapshotUidValidity(observed = null, recorded = 4L))
        assertEquals(4L, TrashPurge.snapshotUidValidity(observed = 0L, recorded = 4L))
    }

    @Test fun `knowing nothing records nothing`() {
        assertEquals(null, TrashPurge.snapshotUidValidity(observed = null, recorded = null))
        assertEquals(null, TrashPurge.snapshotUidValidity(observed = 0L, recorded = 0L))
    }

    // ---- the body-cache pattern -------------------------------------------------------------

    /** A folder called `a_b` must not take `axb`'s cached bodies with it: `_` is a SQL wildcard. */
    @Test fun `the body-cache pattern escapes SQL wildcards`() {
        assertEquals("imap:acc:a\\_b:%", UidValidity.bodyCacheIdPrefix("acc", "a_b"))
        assertEquals("imap:acc:100\\%:%", UidValidity.bodyCacheIdPrefix("acc", "100%"))
        assertEquals("imap:acc:Trash:%", UidValidity.bodyCacheIdPrefix("acc", "Trash"))
    }

    /** The pattern is derived from the id format itself, so the two cannot drift apart. */
    @Test fun `the pattern matches the ids that format produces`() {
        val id = ImapMailService.emailId("acc", "Trash", 17L)
        val pattern = UidValidity.bodyCacheIdPrefix("acc", "Trash").removeSuffix("%")

        assertTrue("$id does not start with $pattern", id.startsWith(pattern))
    }
}
