package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule behind "opening a notification shows the folder the message lives in" (issue #91) —
 */
class NotificationFolderSwitchTest {

    private val known = listOf(INBOX, ARCHIVE, PROJECTS)

    private fun resolve(
        notification: String?,
        selected: String? = INBOX,
        unified: Boolean = false,
        folders: Collection<String> = known,
    ) = NotificationFolderSwitch.resolve(notification, selected, unified, folders)

    @Test fun `a message from another folder switches the list to it`() {
        assertEquals(ARCHIVE, resolve(notification = ARCHIVE, selected = INBOX))
    }

    @Test fun `a snoozed message woken up in Archive lands the list in Archive`() {
        // The reported case: the list is on the Inbox, the wake-up notification is for a
        // message that lives in Archive, so Back must land in Archive and not in the Inbox.
        assertEquals(ARCHIVE, resolve(notification = ARCHIVE, selected = INBOX))
    }

    @Test fun `a custom folder is switched to like any other`() {
        assertEquals(PROJECTS, resolve(notification = PROJECTS, selected = INBOX))
    }

    @Test fun `a message from the folder already shown changes nothing`() {
        assertNull(resolve(notification = INBOX, selected = INBOX))
        assertNull(resolve(notification = ARCHIVE, selected = ARCHIVE))
    }

    @Test fun `the Inbox is switched back to from another folder`() {
        // The list is parked in Archive and mail arrives in the Inbox: the same rule applies in
        // that direction too, which is why the inbox carries its mailbox id in the notification
        // even though it carries no folder sub-text.
        assertEquals(INBOX, resolve(notification = INBOX, selected = ARCHIVE))
    }

    @Test fun `a notification without a folder changes nothing`() {
        assertNull(resolve(notification = null, selected = INBOX))
        assertNull(resolve(notification = "", selected = INBOX))
    }

    @Test fun `a folder that no longer exists is not switched to`() {
        // Deleted from another client between the notification and the tap: falling back to the
        // Inbox is fix-polish-navigation's job, switching INTO the ghost would undo it.
        assertNull(resolve(notification = "deleted-since", selected = INBOX))
    }

    @Test fun `a folder list that is not loaded yet is no verdict`() {
        // An empty list means "not known yet", never "the folder is gone" — same conservative
        // reading as selectionIsGone. The caller parks the request instead of asking, but the
        // rule must not answer "switch" on nothing either.
        assertNull(resolve(notification = ARCHIVE, selected = INBOX, folders = emptyList()))
    }

    @Test fun `the unified inbox is left alone`() {
        // "All inboxes" is a mode the user chose, not a folder: leaving it drops them into one
        // account's folder they must then re-leave. Deliberate — see NotificationFolderSwitch.
        assertNull(resolve(notification = ARCHIVE, selected = null, unified = true))
    }

    @Test fun `the unified inbox is left alone even for an inbox message`() {
        assertNull(resolve(notification = INBOX, selected = null, unified = true))
    }

    @Test fun `a selection that is no folder at all still switches`() {
        // Sel.Folder(null): no inbox id known yet, so there is nothing to compare against and
        // the notification's folder is as good an answer as any.
        assertEquals(ARCHIVE, resolve(notification = ARCHIVE, selected = null))
    }

    private companion object {
        const val INBOX = "mbx-inbox"
        const val ARCHIVE = "mbx-archive"
        const val PROJECTS = "mbx-projects"
    }
}
