package app.sterna.core.data.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deleteDestroysMessage] EXECUTED — the decision that routes the swipe either to a move or to the
 */
class DeleteDestroysMessageTest {

    @Test fun `an account with no Trash destroys nothing`() {
        // THE defect: delete() resolves-or-creates the bin now, so "no Trash" is a move, not a
        // destroy. Answering true here erases the message on an ordinary swipe.
        assertFalse(
            "no Trash resolved: the delete moves to the one delete() resolves or creates",
            deleteDestroysMessage(trashMailboxId = null, messageMailboxId = "mbInbox"),
        )
    }

    @Test fun `a message somewhere else is moved, not destroyed`() {
        assertFalse(deleteDestroysMessage(trashMailboxId = "mbTrash", messageMailboxId = "mbInbox"))
    }

    @Test fun `a message already in the Trash is destroyed`() {
        // The half that must stay alive: without it a delete inside the bin would move the
        // message onto its own folder and the user could never get rid of it.
        assertTrue(deleteDestroysMessage(trashMailboxId = "mbTrash", messageMailboxId = "mbTrash"))
    }

    @Test fun `a folder we cannot read is moved, never destroyed`() {
        // A search hit carries the folder the crawl froze — empty, or absent. Destruction is not
        // decided on a supposition; a move onto the folder it may already sit in loses nothing.
        assertFalse("no folder at all", deleteDestroysMessage("mbTrash", null))
        assertFalse("an empty folder", deleteDestroysMessage("mbTrash", ""))
        assertFalse("a blank folder", deleteDestroysMessage("mbTrash", "   "))
    }

    @Test fun `two unknowns do not make a match`() {
        // The trap of the plain `==`: with the short-circuit gone, a null Trash and a null folder
        // are equal, and the account with no Trash gets its destroy back through the side door.
        assertFalse("both absent", deleteDestroysMessage(null, null))
        assertFalse("both empty", deleteDestroysMessage("", ""))
        assertFalse("a blank Trash id is not a folder", deleteDestroysMessage("", "mbInbox"))
    }
}
