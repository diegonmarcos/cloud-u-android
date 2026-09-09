package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import app.sterna.ui.NotificationFolderSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether a tapped notification is allowed to switch the list to the message's own folder (#91)
 */
class NotificationFolderToShowTest {

    private fun folder(
        id: String,
        role: String? = null,
        parentId: String? = null,
        subscribed: Boolean = true,
    ) = Mailbox(id = id, name = id, role = role, parentId = parentId, isSubscribed = subscribed)

    /** One account's whole folder list, as the cache delivers it. */
    private val folders = listOf(
        folder("inbox", role = "inbox"),
        folder("vieux", subscribed = false),
        folder("archive", role = "archive", subscribed = false),
        folder("projet", subscribed = false),
        folder("projet/done", parentId = "projet"),
        folder("travail"),
    )

    /** The shipped path, whole list in, exactly as `InboxViewModel.applyNotificationFolder` calls it. */
    private fun resolved(notificationMailboxId: String?) = NotificationFolderSwitch.resolve(
        notificationMailboxId = notificationMailboxId,
        selectedMailboxId = "inbox",
        unifiedView = false,
        knownMailboxIds = folders.map { it.id },
    )

    @Test fun `the fixture is what the rule is supposed to see`() {
        assertEquals(
            listOf("inbox", "archive", "projet", "projet/done", "travail"),
            visibleFolders(folders, true).map { it.id },
        )
    }

    @Test fun `a notification for a SUBSCRIBED folder still switches the list to it`() {
        assertEquals("travail", notificationFolderToShow(resolved("travail"), folders, true))
    }

    @Test fun `a notification for a folder the setting hides does NOT switch the list`() {
        // The trap this closes: the list would be left titled `vieux`, a folder the drawer no
        // longer lists and "move to" no longer offers, with no way back into it. The message
        // itself still opens — the reader opens it, not this.
        assertNull(notificationFolderToShow(resolved("vieux"), folders, true))
    }

    @Test fun `with the setting off the same notification switches the list, as it always has`() {
        // #91 for everybody else: the default every account is on must be untouched.
        assertEquals("vieux", notificationFolderToShow(resolved("vieux"), folders, false))
    }

    @Test fun `an unsubscribed folder that carries a role is visible, so the switch is honoured`() {
        assertEquals("archive", notificationFolderToShow(resolved("archive"), folders, true))
    }

    @Test fun `an unsubscribed parent of a visible folder is visible too`() {
        assertEquals("projet", notificationFolderToShow(resolved("projet"), folders, true))
    }

    @Test fun `nothing to switch to stays nothing to switch to`() {
        // resolve() already answers null for the folder on screen, for an absent folder and for a
        // notification carrying none; this must not invent a target out of any of them.
        assertNull(notificationFolderToShow(resolved("inbox"), folders, true))
        assertNull(notificationFolderToShow(resolved("supprime"), folders, true))
        assertNull(notificationFolderToShow(resolved(null), folders, true))
        assertNull(notificationFolderToShow(null, folders, false))
    }
}
