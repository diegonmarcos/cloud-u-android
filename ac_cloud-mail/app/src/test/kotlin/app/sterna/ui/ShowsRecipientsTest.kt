package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sender or recipients? [showsRecipients] is that decision, and both surfaces make it here: the
 */
class ShowsRecipientsTest {

    // -- what #115 is about -------------------------------------------------------------------

    @Test fun `a message sent to yourself, in the Inbox, shows its sender`() {
        assertFalse(showsRecipients(role = "inbox", unified = false, selfAuthored = true))
    }

    @Test fun `a message sent to yourself, in the unified view, shows its sender`() {
        // The default screen, and the likeliest one in the report: with no folder selected the role
        // is null, so a rule written for "inbox" alone would leave the defect exactly where it was
        // reported. The unified view aggregates Inboxes and nothing else, so it IS incoming.
        assertFalse(showsRecipients(role = null, unified = true, selfAuthored = true))
    }

    @Test fun `a message received from somebody else shows its sender`() {
        assertFalse(showsRecipients(role = "inbox", unified = false, selfAuthored = false))
        assertFalse(showsRecipients(role = null, unified = true, selfAuthored = false))
    }

    // -- the witnesses that must not move ------------------------------------------------------

    @Test fun `a sent message moved to the Trash still shows its recipients`() {
        // Codeberg #69. The Trash is not an outgoing folder, so the author test is the only thing
        // that rescues this row; a rule that dropped it outside the Inbox would take it away.
        assertTrue(showsRecipients(role = "trash", unified = false, selfAuthored = true))
    }

    @Test fun `an archived message of your own still shows its recipients`() {
        assertTrue(showsRecipients(role = "archive", unified = false, selfAuthored = true))
    }

    @Test fun `your own message filed in a custom folder still shows its recipients`() {
        // No role: nothing has established that this folder holds incoming mail, so the author
        // test stands. Same answer for a role that has not resolved yet.
        assertTrue(showsRecipients(role = null, unified = false, selfAuthored = true))
        assertTrue(showsRecipients(role = "Invoices", unified = false, selfAuthored = true))
    }

    @Test fun `Sent and Drafts show recipients whoever wrote the message`() {
        // Codeberg #59: in those folders the author is yourself by construction, and a row whose
        // sender cannot be told still has to read right.
        assertTrue(showsRecipients(role = "sent", unified = false, selfAuthored = false))
        assertTrue(showsRecipients(role = "drafts", unified = false, selfAuthored = false))
    }

    // -- the roles as they actually arrive -----------------------------------------------------

    @Test fun `roles are matched however the server spells them`() {
        // JMAP hands the role through unnormalised (only the IMAP side lower-cases it), and the
        // neighbouring helpers in this file already trim and lower-case. A "Sent" that missed the
        // folder test used to fall through to the author test and be right by luck; in the Inbox
        // that luck now runs the other way, so the normalisation is no longer optional.
        assertTrue(showsRecipients(role = "Sent", unified = false, selfAuthored = false))
        assertTrue(showsRecipients(role = " DRAFTS ", unified = false, selfAuthored = false))
        assertFalse(showsRecipients(role = "INBOX", unified = false, selfAuthored = true))
        assertFalse(showsRecipients(role = " Inbox ", unified = false, selfAuthored = true))
    }

    @Test fun `an outgoing folder wins over the unified flag`() {
        // Defensive: the two never coincide on screen (a folder is selected or it is not), but the
        // order of the terms is the guarantee — Sent must never be read as an incoming context.
        assertTrue(showsRecipients(role = "sent", unified = true, selfAuthored = false))
    }

    // -- the author term is NECESSARY, not merely sufficient -----------------------------------

    @Test fun `mail from somebody else keeps its sender wherever it is filed`() {
        // Every case above shows the author term is ENOUGH to get "To: …". None showed it is
        // REQUIRED — and `return selfAuthored` mutated to `return true` passed the whole suite,
        // which on screen means every message in the Archive and the Trash, received from anyone,
        // announced as if you had sent it. These are the cases that can tell the two apart.
        assertFalse(showsRecipients(role = "trash", unified = false, selfAuthored = false))
        assertFalse(showsRecipients(role = "archive", unified = false, selfAuthored = false))
        assertFalse(showsRecipients(role = "junk", unified = false, selfAuthored = false))
        assertFalse(showsRecipients(role = "Invoices", unified = false, selfAuthored = false))
        assertFalse(showsRecipients(role = null, unified = false, selfAuthored = false))
    }

    // -- the third surface: a row inside an unfolded conversation ------------------------------

    private val roles = mapOf(
        ("acc" to "in") to "inbox",
        ("acc" to "out") to "sent",
        ("acc" to "bin") to "trash",
        ("acc" to "invoices") to "Invoices",
        ("other" to "in") to "inbox",
    )

    @Test fun `your own reply inside an incoming thread still shows its recipients`() {
        // THE witness of #69's in-thread half, and it must come out right BY THE ROLE: a reply you
        // wrote lives in Sent, whatever folder the thread is unfolded in.
        assertTrue(showsRecipientsInThread("acc", "out", roles, selfAuthored = true))
        assertTrue(showsRecipientsInThread("acc", "out", roles, selfAuthored = false))
    }

    @Test fun `your own message echoed back into the Inbox shows its sender`() {
        // #115 one level down: a mailing-list echo, or a mail sent to yourself, sitting in the
        // Inbox as a child of the thread. The reader resolves the same role and says the same
        // thing, which is what the line the user just tapped has to agree with.
        assertFalse(showsRecipientsInThread("acc", "in", roles, selfAuthored = true))
    }

    @Test fun `a child in the Trash is still judged by its author`() {
        // #69 again, from inside a thread: nothing about the Trash says incoming.
        assertTrue(showsRecipientsInThread("acc", "bin", roles, selfAuthored = true))
        assertFalse(showsRecipientsInThread("acc", "bin", roles, selfAuthored = false))
    }

    @Test fun `an unknown folder falls back to the author test, not to nonsense`() {
        // No account on the row, no folder on the row, an account whose folders have not synced,
        // or a folder with no role: all "cannot say", and all land on the pre-#115 answer.
        assertTrue(showsRecipientsInThread("acc", "unsynced", roles, selfAuthored = true))
        assertTrue(showsRecipientsInThread(null, "in", roles, selfAuthored = true))
        assertTrue(showsRecipientsInThread("acc", null, roles, selfAuthored = true))
        assertTrue(showsRecipientsInThread("acc", "invoices", roles, selfAuthored = true))
        assertFalse(showsRecipientsInThread("acc", "unsynced", roles, selfAuthored = false))
    }

    @Test fun `the folder is resolved inside the row's own account`() {
        // Servers number folders per account (issue #31): "in" is the Inbox of BOTH accounts here,
        // and reading the role of the wrong one is how a unified-list child gets judged by another
        // account's folder. Same id, one role each, resolved by the pair.
        assertFalse(showsRecipientsInThread("other", "in", roles, selfAuthored = true))
        // A folder id that exists only in another account resolves to nothing here.
        assertTrue(showsRecipientsInThread("other", "out", roles, selfAuthored = true))
        assertEquals("sent", messageFolderRole("acc", "out", roles))
        assertNull(messageFolderRole("other", "out", roles))
    }
}
