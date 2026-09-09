package app.sterna.ui.connect

import app.sterna.core.data.mail.NotifyRead
import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The decision an ADD takes about the new inbox's notification baseline, EXECUTED —
 */
class AddedInboxBaselineTest {

    /**
     * The test that kills `read.emails.map { it.id }`.
     */
    @Test fun `a new inbox remembers every id the bounded read listed, not only the hydrated ones`() {
        val read = NotifyRead(
            emails = listOf(Email(id = "b"), Email(id = "d")),
            baselineIds = listOf("a", "b", "c", "d", "e"),
        )
        assertEquals(
            "the baseline of a freshly added inbox must be the read's whole id list — seeding the " +
                "hydrated rows instead forgets what the cap shed, and a forgotten id announces as " +
                "new mail later",
            listOf("a", "b", "c", "d", "e"),
            baselineForAddedInbox(read, hasBaseline = false),
        )
        // IDENTITY, not just equality, and it is the assertion that survives a small fixture.
        // Equality on five ids stays green under `read.baselineIds.take(NOTIFY_CANDIDATE_MAX)` —
        assertSame(
            "the decision must hand back the read's own id list, untouched: anything that copies " +
                "it through take/map/filter can bound the baseline, and a bounded baseline forgets " +
                "ids that then announce as new mail and leave the Archive server-side",
            read.baselineIds,
            baselineForAddedInbox(read, hasBaseline = false),
        )
    }

    /**
     * A re-authentication, or the re-add of an account already set up here, finds a LIVE baseline.
     */
    @Test fun `an add that finds a baseline already there writes nothing`() {
        val read = NotifyRead(
            emails = listOf(Email(id = "b"), Email(id = "d")),
            baselineIds = listOf("a", "b", "c", "d", "e"),
        )
        assertNull(
            "an add must never replace a baseline that already exists: what that baseline is " +
                "still holding has not been announced yet",
            baselineForAddedInbox(read, hasBaseline = true),
        )
    }

    /**
     * An empty read writes NOTHING, and this is the assertion that keeps the fix from being
     */
    @Test fun `an empty read seeds nothing, because empty may be a page that could not answer`() {
        assertNull(
            "an empty id list must not be written as a baseline: on IMAP it is indistinguishable " +
                "from a page that could not state what the folder holds, and marking the folder " +
                "known while remembering nothing makes the next pass announce the whole age floor " +
                "and unarchive it server-side",
            baselineForAddedInbox(NotifyRead(emptyList(), emptyList()), hasBaseline = false),
        )
    }
}
