package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [refreshedInboxId] RUN: which mailbox the "unread" view's refresh may fire the unarchive-on-reply
 */
class RefreshedInboxIdTest {

    /** The multi-folder pass, as it comes back when the inbox was part of it. */
    private val refreshed = listOf("INBOX", "Archive", "Projets")

    @Test fun `the inbox is picked out of the folders that came back`() {
        assertEquals("INBOX", refreshedInboxId(refreshed, "INBOX"))
        assertEquals("Archive", refreshedInboxId(refreshed, "Archive"))
    }

    /**
     * THE one. Taking the first refresh trusts a position produced in another module: here the
     */
    @Test fun `a first folder that is not the inbox is not mistaken for it`() {
        assertNull(refreshedInboxId(listOf("Archive", "Projets"), "INBOX"))
        assertNull(refreshedInboxId(emptyList(), "INBOX"))
    }

    /** No cached inbox id (an account never synced): nothing is known to be the inbox. */
    @Test fun `an unknown inbox fires the hook for nothing`() {
        assertNull(refreshedInboxId(refreshed, null))
        assertNull(refreshedInboxId(emptyList(), null))
    }
}
