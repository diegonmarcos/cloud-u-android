package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which folder a draft's destroy is opposed to — the decision RUN, with its arguments pinned.
 */
class DraftDestroyFolderTest {

    @Test fun `the cached row's own folder is the answer`() {
        assertEquals(
            "the folder the draft is REALLY in — it may have been filed outside Drafts, and a " +
                "destroy checked against the Drafts role would spare it in silence (#122)",
            "INBOX.Work.Drafts",
            draftDestroyFolder(cachedMailboxId = "INBOX.Work.Drafts", draftsRoleMailboxId = "INBOX.Drafts"),
        )
    }

    @Test fun `with no cache line the account's Drafts role answers`() {
        assertEquals(
            "⛔ THE fallback. A draft the phone never cached, or whose line went in a cache purge " +
                "or a resetSyncState + partial re-query, has no row to read a folder off. Without " +
                "this the caller gets null, enqueues NOTHING, consumes the local row anyway — and " +
                "the server draft it was masking comes back with the text from before the edit " +
                "(#95 × #69), for good, on a screen that closed as a success",
            "INBOX.Drafts",
            draftDestroyFolder(cachedMailboxId = null, draftsRoleMailboxId = "INBOX.Drafts"),
        )
    }

    @Test fun `neither answers, so nothing is destroyed`() {
        assertNull(
            "no cache line and no Drafts role: an order with no folder would be enqueued, run, " +
                "destroy nothing, and the eviction behind it would take the draft off the phone " +
                "all the same. Null leaves it where it can still be seen",
            draftDestroyFolder(cachedMailboxId = null, draftsRoleMailboxId = null),
        )
    }

    @Test fun `the role never overrides a folder the draft really sits in`() {
        assertEquals(
            "the two arguments are not interchangeable: the cached folder wins even when the " +
                "account has a Drafts role, and a null role does not blank it",
            "INBOX.Archive",
            draftDestroyFolder(cachedMailboxId = "INBOX.Archive", draftsRoleMailboxId = null),
        )
    }
}
