package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the list must fall back to the Inbox because the folder on screen no longer exists
 * (Codeberg #89) — and, just as importantly, when it must NOT.
 */
class FolderSelectionTest {

    private fun mailbox(id: String, role: String? = null) = Mailbox(id = id, name = id, role = role)

    private val folders = listOf(
        mailbox("inbox-1", role = "inbox"),
        mailbox("sent-1", role = "sent"),
        mailbox("custom-1"),
    )

    @Test fun `a folder still in the list stays selected`() {
        assertFalse(selectionIsGone("custom-1", folders))
    }

    @Test fun `the inbox itself stays selected`() {
        assertFalse(selectionIsGone("inbox-1", folders))
    }

    @Test fun `a folder deleted from the list falls back`() {
        assertTrue(selectionIsGone("custom-1", folders.filterNot { it.id == "custom-1" }))
    }

    @Test fun `a subfolder deleted along with its parent falls back`() {
        // A delete takes the whole subtree out of the cache at once; the child is the selection.
        val withChild = folders + mailbox("custom-1/child")
        val afterDelete = withChild.filterNot { it.id.startsWith("custom-1") }
        assertTrue(selectionIsGone("custom-1/child", afterDelete))
    }

    @Test fun `the unified inbox has no folder to lose`() {
        assertFalse(selectionIsGone(null, folders))
        assertFalse(selectionIsGone(null, emptyList()))
    }

    @Test fun `an empty folder list means not loaded yet, never gone`() {
        // First launch, an account switch before the new account's folders are cached, a
        // cleared cache: bouncing to the Inbox here would fight the user for no reason.
        assertFalse(selectionIsGone("custom-1", emptyList()))
    }

    @Test fun `ids are matched exactly, not by prefix`() {
        // IMAP ids are paths: a sibling whose id merely starts the same must not count as
        // the selected folder still being there.
        assertTrue(selectionIsGone("custom-1", listOf(mailbox("custom-10"), mailbox("custom-1/child"))))
    }
}
