package app.sterna.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which folders drop which actions (Codeberg #82). Drafts and Sent drop the incoming-mail-only
 */
class FolderActionsTest {

    @Test fun `drafts is an outgoing folder`() {
        assertTrue(isOutgoingFolder("drafts"))
    }

    @Test fun `sent is an outgoing folder`() {
        assertTrue(isOutgoingFolder("sent"))
    }

    @Test fun `inbox keeps the actions`() {
        assertFalse(isOutgoingFolder("inbox"))
    }

    @Test fun `archive keeps the actions`() {
        assertFalse(isOutgoingFolder("archive"))
    }

    @Test fun `junk and trash keep the outgoing-rule actions`() {
        // Only Snooze goes there (see below): "Not spam" is the whole point of the Spam folder,
        // and a deleted or refused message can legitimately still be unread.
        assertFalse(isOutgoingFolder("junk"))
        assertFalse(isOutgoingFolder("trash"))
    }

    @Test fun `an unknown or custom folder keeps the actions`() {
        assertFalse(isOutgoingFolder(null))
        assertFalse(isOutgoingFolder("Invoices"))
        assertFalse(isOutgoingFolder(""))
    }

    @Test fun `role matching ignores case and stray spacing`() {
        assertTrue(isOutgoingFolder("Drafts"))
        assertTrue(isOutgoingFolder(" SENT "))
    }

    // --- Snooze goes further than the outgoing rule (Codeberg #82) ---

    @Test fun `spam drops snooze`() {
        assertFalse(canSnoozeIn("junk"))
        assertFalse(canSnoozeIn("spam"))
    }

    @Test fun `trash drops snooze`() {
        assertFalse(canSnoozeIn("trash"))
    }

    @Test fun `drafts and sent still drop snooze`() {
        assertFalse(canSnoozeIn("drafts"))
        assertFalse(canSnoozeIn("sent"))
    }

    @Test fun `snooze stays where mail is still waiting`() {
        assertTrue(canSnoozeIn("inbox"))
        assertTrue(canSnoozeIn("archive"))
        assertTrue(canSnoozeIn(null))
        assertTrue(canSnoozeIn("Invoices"))
        assertTrue(canSnoozeIn(""))
    }

    @Test fun `snooze role matching ignores case and stray spacing`() {
        assertFalse(canSnoozeIn(" Trash "))
        assertFalse(canSnoozeIn("JUNK"))
    }

}
