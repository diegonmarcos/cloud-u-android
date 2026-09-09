package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [isAtInbox], the rule the list screen's Back gesture rests on: `true` means "this IS the inbox",
 */
class AtInboxTest {

    @Test fun `the unread view is not the inbox on an account whose inbox was never synced`() {
        // THE defect: selection restored to the unread view on a cold start, no cached inbox id,
        // and the reader's first Back closes the app instead of showing her the Inbox.
        assertEquals(false, isAtInbox(Sel.Unread) { null })
    }

    @Test fun `the unread view is not the inbox once the inbox id is known either`() {
        assertEquals(false, isAtInbox(Sel.Unread) { "inbox" })
    }

    @Test fun `the unified inbox is the inbox with no cached id`() {
        assertEquals(true, isAtInbox(Sel.Unified) { null })
    }

    @Test fun `the unified inbox is the inbox with a cached id`() {
        assertEquals(true, isAtInbox(Sel.Unified) { "inbox" })
    }

    @Test fun `a folder with no id is still the inbox selection when none is cached`() {
        // The answer it always had: this IS the selection the ViewModel builds, Sel.Folder(store
        // .inboxMailboxId()), before the inbox id is known. Not the defect, not changed.
        assertEquals(true, isAtInbox(Sel.Folder(null)) { null })
    }

    @Test fun `the inbox folder is the inbox`() {
        assertEquals(true, isAtInbox(Sel.Folder("inbox")) { "inbox" })
    }

    @Test fun `another folder is not the inbox`() {
        assertEquals(false, isAtInbox(Sel.Folder("trash")) { "inbox" })
    }

    @Test fun `another folder is not the inbox when no inbox id is cached`() {
        assertEquals(false, isAtInbox(Sel.Folder("trash")) { null })
    }

    /**
     * The reachable state the eight cases above leave open, and the one a `sel.id ?: inboxMailboxId`
     */
    @Test fun `a folder with no id is not the inbox once one is cached`() {
        assertEquals(false, isAtInbox(Sel.Folder(null)) { "inbox" })
    }

    /**
     * The lambda is not decoration. `AccountStore.inboxMailboxId()` walks `currentAccount()` →
     */
    @Test fun `the inbox id is asked for only when the selection is a folder`() {
        var asked = 0
        val id = { asked++; "inbox" }

        isAtInbox(Sel.Unified, id)
        assertEquals("the unified inbox is the inbox whatever the store knows", 0, asked)

        isAtInbox(Sel.Unread, id)
        assertEquals("the unread view has no folder, so there is nothing to compare", 0, asked)

        isAtInbox(Sel.Folder("trash"), id)
        assertEquals("a folder has to be compared to the inbox id, once", 1, asked)
    }
}
