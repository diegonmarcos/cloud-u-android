package app.sterna.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "(Draft)" chip on a list row — [showsDraftBadge], run as the pure function it is.
 */
class ShowsDraftBadgeTest {

    private val roles = mapOf(
        ("a" to "d") to "drafts",
        ("a" to "in") to "inbox",
        ("a" to "out") to "sent",
        ("a" to "bin") to "trash",
        ("a" to "old") to "archive",
        ("a" to "invoices") to "Invoices",
        // The same folder id in a sibling account, with a different role. Issue #31/#121.
        ("b" to "d") to "inbox",
        // As some servers actually spell it: JMAP hands the role through unnormalised.
        ("a" to "capital") to "Drafts",
        ("a" to "padded") to " drafts ",
    )

    // -- the keyword path: a freshly synced JMAP row ---------------------------------------------

    @Test fun `a row that still carries the draft keyword shows the chip wherever it sits`() {
        // #69: a draft dragged to the Trash has left the Drafts folder but kept the keyword, and it
        // has to stay distinguishable from a sent mail (both read "To: …" since 1.3.11).
        assertTrue(showsDraftBadge(isDraft = true, accountId = "a", mailboxId = "bin", roles = roles))
        assertTrue(showsDraftBadge(isDraft = true, accountId = "a", mailboxId = "in", roles = roles))
        assertTrue(showsDraftBadge(isDraft = true, accountId = "a", mailboxId = "invoices", roles = roles))
        assertTrue(showsDraftBadge(isDraft = true, accountId = null, mailboxId = null, roles = roles))
        assertTrue(showsDraftBadge(isDraft = true, accountId = "a", mailboxId = "d", roles = roles))
    }

    // -- the folder path: IMAP, and JMAP after process death -------------------------------------

    @Test fun `a row in the Drafts folder shows the chip without the keyword`() {
        // THE CASE THIS BRANCH IS FOR. On IMAP the keyword is never recorded at all; on JMAP it
        // is lost with the process and a delta sync does not bring it back. The folder role does,
        // from the cache, on the first frame, with no resynchronisation.
        assertTrue(
            "a row in a folder whose role is 'drafts' must be chipped even with no keyword: that " +
                "is every row of an IMAP Drafts folder, and every row of a JMAP one after the " +
                "process was killed.",
            showsDraftBadge(isDraft = false, accountId = "a", mailboxId = "d", roles = roles),
        )
    }

    @Test fun `the role is matched however the server spells it`() {
        // Without trim + lowercase, a server answering "Drafts" leaves the reported symptom exactly
        // where it was found — the Drafts folder with no chip on any row.
        assertTrue(
            "a server spelling the role 'Drafts' must be understood — unnormalised, the Drafts " +
                "folder stays exactly as reported: no chip on any row.",
            showsDraftBadge(isDraft = false, accountId = "a", mailboxId = "capital", roles = roles),
        )
        assertTrue(
            "and one that pads it too.",
            showsDraftBadge(isDraft = false, accountId = "a", mailboxId = "padded", roles = roles),
        )
    }

    // -- the negative witnesses: the chip must stay rare -----------------------------------------

    @Test fun `no chip on a row that is neither a draft nor in the Drafts folder`() {
        // Every other role, one by one: the Inbox and Sent especially, where a chip on every row
        // would be the loudest possible regression.
        assertFalse(showsDraftBadge(isDraft = false, accountId = "a", mailboxId = "in", roles = roles))
        assertFalse(showsDraftBadge(isDraft = false, accountId = "a", mailboxId = "out", roles = roles))
        assertFalse(showsDraftBadge(isDraft = false, accountId = "a", mailboxId = "bin", roles = roles))
        assertFalse(showsDraftBadge(isDraft = false, accountId = "a", mailboxId = "old", roles = roles))
        assertFalse(showsDraftBadge(isDraft = false, accountId = "a", mailboxId = "invoices", roles = roles))
    }

    @Test fun `an unknown folder answers no, rather than throwing`() {
        // A folder the cache knows no role for, an account whose folders have not synced yet, a row
        // with no account or no folder on it (search hits, the unified view's own state): all
        // "cannot say", and "cannot say" is no chip — the pre-existing behaviour, not nonsense.
        assertFalse(showsDraftBadge(isDraft = false, accountId = "a", mailboxId = "unsynced", roles = roles))
        assertFalse(showsDraftBadge(isDraft = false, accountId = "zz", mailboxId = "d", roles = roles))
        assertFalse(showsDraftBadge(isDraft = false, accountId = null, mailboxId = "d", roles = roles))
        assertFalse(showsDraftBadge(isDraft = false, accountId = "a", mailboxId = null, roles = roles))
        assertFalse(showsDraftBadge(isDraft = false, accountId = null, mailboxId = null, roles = roles))
        assertFalse(showsDraftBadge(isDraft = false, accountId = "a", mailboxId = "d", roles = emptyMap()))
    }

    @Test fun `the folder is resolved inside the row's own account`() {
        // Issue #31/#121: servers number folders per account. "d" is Drafts in account a and the
        // Inbox in account b; a bare folder id would put a chip on every row of b's Inbox in the
        // unified list. One role each, resolved by the (account, folder) pair.
        assertFalse(
            "folder 'd' is Drafts in account a and the Inbox in account b: asking about b must " +
                "give no chip, or the unified list chips every row of b's Inbox.",
            showsDraftBadge(isDraft = false, accountId = "b", mailboxId = "d", roles = roles),
        )
    }
}
