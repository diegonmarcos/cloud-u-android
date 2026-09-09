package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The header a RESTORED view claims, EXECUTED — both halves of it.
 */
class RestoredMetaTest {

    // -- the header the cold start opens on --------------------------------------------------

    @Test
    fun `a restored unified view claims the internal label and the total`() {
        assertEquals(
            Meta("All inboxes", "All inboxes", 9),
            restoredMeta(Sel.Unified, "inbox", "alex@example.test", "Inbox", 4, 9),
        )
    }

    @Test
    fun `a restored unread view is left exactly as selectUnread writes it`() {
        assertEquals(
            Meta("alex@example.test", "Inbox", 4),
            restoredMeta(Sel.Unread, "inbox", "alex@example.test", "Inbox", 4, 9),
        )
    }

    @Test
    fun `the inbox restored keeps the name and the count it always had`() {
        assertEquals(
            Meta("alex@example.test", "Inbox", 4),
            restoredMeta(Sel.Folder("inbox"), "inbox", "alex@example.test", "Inbox", 4, 9),
        )
    }

    @Test
    fun `another folder restored claims NO name and NO count`() {
        assertEquals(
            "Reopened on another folder, the header must not carry the Inbox's name: the name " +
                "arrives a beat later from the cached folder list (restoredFolderMeta). The 0 is " +
                "NOT a displayed lie being corrected — MailUi.unreadCount is drawn only under " +
                "ui.unified, where the value is replaced — it is a refusal to carry a number that " +
                "belongs to another folder, for whoever reads it next.",
            Meta("alex@example.test", "", 0),
            restoredMeta(Sel.Folder("trash"), "inbox", "alex@example.test", "Inbox", 4, 9),
        )
    }

    @Test
    fun `another folder restored before the inbox id is known claims nothing either`() {
        assertEquals(
            Meta("alex@example.test", "", 0),
            restoredMeta(Sel.Folder("trash"), null, "alex@example.test", "Inbox", 4, 9),
        )
    }

    @Test
    fun `the inbox with no id yet is still the inbox`() {
        assertEquals(
            "Sel.Folder(null) with no inbox id in the store is 'the Inbox, id not known yet' — " +
                "the state a never-synced account is in. It keeps the answer it always had.",
            Meta("alex@example.test", "Inbox", 4),
            restoredMeta(Sel.Folder(null), null, "alex@example.test", "Inbox", 4, 9),
        )
    }

    // -- naming that folder from the cached list ---------------------------------------------

    @Test
    fun `nothing waiting decides nothing`() {
        assertEquals(
            RestoredMetaStep(settled = false, meta = null),
            restoredFolderMeta(null, Sel.Folder("trash"), listOf(TRASH), LABEL),
        )
    }

    @Test
    fun `an empty list is not known yet, so the one shot stays armed`() {
        assertEquals(
            "An empty folder list means 'no list yet', never 'the folder is gone' — the same " +
                "conservative reading applyNotificationFolder and selectionIsUnreachable take. " +
                "Spending the one shot here would leave the header blank until the next refresh " +
                "succeeds, i.e. for ever in airplane mode.",
            RestoredMetaStep(settled = false, meta = null),
            restoredFolderMeta("trash", Sel.Folder("trash"), emptyList(), LABEL),
        )
    }

    @Test
    fun `a reader who has moved keeps her own header`() {
        assertEquals(
            "The selection moved between the cold start and the list arriving (a tapped " +
                "notification, a tap in the drawer). The header is hers; the memory is spent " +
                "without writing.",
            RestoredMetaStep(settled = true, meta = null),
            restoredFolderMeta("trash", Sel.Folder("archive"), listOf(TRASH, ARCHIVE), LABEL),
        )
    }

    @Test
    fun `a folder that is no longer in the list is left to the inbox fallback`() {
        assertEquals(
            RestoredMetaStep(settled = true, meta = null),
            restoredFolderMeta("trash", Sel.Folder("trash"), listOf(ARCHIVE), LABEL),
        )
    }

    @Test
    fun `the restored folder is named and counted from the cached list`() {
        assertEquals(
            RestoredMetaStep(settled = true, meta = Meta(LABEL, "Corbeille", 3)),
            restoredFolderMeta("trash", Sel.Folder("trash"), listOf(ARCHIVE, TRASH), LABEL),
        )
    }

    @Test
    fun `the unified view is not a folder, and is never overwritten`() {
        assertEquals(
            RestoredMetaStep(settled = true, meta = null),
            restoredFolderMeta("trash", Sel.Unified, listOf(TRASH), LABEL),
        )
    }

    private companion object {
        const val LABEL = "alex@example.test"
        val TRASH = Mailbox(id = "trash", name = "Corbeille", role = "trash", unreadEmails = 3)
        val ARCHIVE = Mailbox(id = "archive", name = "Archive", unreadEmails = 7)
    }
}
