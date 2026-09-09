package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WHICH FOLDER a row of the unread view came from — the whole of issue #169, and nothing else.
 */
class UnreadRowFolderTest {

    private fun row(id: String, account: String?, mailbox: String?) =
        Email(id = id, accountId = account, mailboxId = mailbox)

    private fun folder(id: String, name: String, role: String?) =
        Mailbox(id = id, name = name, role = role)

    private val folders = listOf(
        folder("mb-inbox", "INBOX", "inbox"),
        folder("mb-arch", "Archive", "archive"),
        folder("mb-proj", "Projets", null),
    )

    /** The unread view of account [account], over [folders]. */
    private fun unreadView(account: String? = "acc-1") = MailUi(
        accountId = account,
        accountName = "a",
        mailboxName = "m",
        unreadCount = 0,
        selectedMailboxId = null,
        unified = false,
        unreadView = true,
        mailboxes = folders,
        refreshing = false,
        error = null,
    )

    // -- the symptom -------------------------------------------------------------------------

    /**
     * The test that carries the value. Two rows of ONE object, one per folder it is filed in —
     */
    @Test
    fun `the same message filed in two folders names each folder`() {
        val ui = unreadView()
        val inInbox = unreadRowFolder(
            email = row("imap:acc-1:mb-inbox:42", account = "acc-1", mailbox = "mb-inbox"),
            ui = ui,
            folderTrusted = true,
            inViewCount = 1,
        )
        val inArchive = unreadRowFolder(
            email = row("imap:acc-1:mb-arch:17", account = "acc-1", mailbox = "mb-arch"),
            ui = ui,
            folderTrusted = true,
            inViewCount = 1,
        )
        assertEquals(
            "the copy paged out of the Inbox must name the Inbox",
            folder("mb-inbox", "INBOX", "inbox"),
            inInbox,
        )
        assertEquals(
            "the copy paged out of the Archive must name the Archive — this is the row the reporter " +
                "reads as a duplicate, and the folder is the only thing that tells it from the one " +
                "above it",
            folder("mb-arch", "Archive", "archive"),
            inArchive,
        )
        assertEquals(
            "two rows of one object must not answer the same folder: the label would then be drawn " +
                "twice, identical, and explain nothing",
            false,
            inInbox == inArchive,
        )
        assertEquals(
            "and the two labels must differ at the source [mailboxDisplayName] reads — same role or " +
                "same name and the chip is noise",
            false,
            inInbox?.role == inArchive?.role || inInbox?.name == inArchive?.name,
        )
    }

    /**
     * A folder the user made has no role, and [mailboxDisplayName] hands back its raw name. Unread
     */
    @Test
    fun `a folder with no role is named too`() {
        assertEquals(
            "'Projets' has no special-use role; that is not a reason to stay silent about it",
            folder("mb-proj", "Projets", null),
            unreadRowFolder(
                email = row("imap:acc-1:mb-proj:3", account = "acc-1", mailbox = "mb-proj"),
                ui = unreadView(),
                folderTrusted = true,
                inViewCount = 1,
            ),
        )
    }

    /**
     * A COLLAPSED CONVERSATION names nothing, and this is the row the chip could lie about.
     */
    @Test
    fun `a row standing for several messages names no folder at all`() {
        val ui = unreadView()
        val representative = row("imap:acc-1:mb-arch:17", account = "acc-1", mailbox = "mb-arch")
        assertNull(
            "the row stands for 2 messages of the list, filed who knows where; naming the newest " +
                "one's folder would be a claim about the whole row",
            unreadRowFolder(representative, ui, folderTrusted = true, inViewCount = 2),
        )
        assertEquals(
            "…and the very same row, standing for itself alone, names its folder — the flat-view " +
                "and IMAP case, which is the reporter's",
            folder("mb-arch", "Archive", "archive"),
            unreadRowFolder(representative, ui, folderTrusted = true, inViewCount = 1),
        )
    }

    // -- the views that must stay silent -------------------------------------------------------

    @Test
    fun `a single-folder view names nothing`() {
        assertNull(
            "every row of a folder view is in the folder named at the top of the screen; repeating " +
                "it under each row is noise",
            unreadRowFolder(
                email = row("1", account = "acc-1", mailbox = "mb-arch"),
                ui = unreadView().copy(unreadView = false, selectedMailboxId = "mb-arch"),
                folderTrusted = true,
                inViewCount = 1,
            ),
        )
    }

    @Test
    fun `the unified inbox keeps its chip for the account`() {
        assertNull(
            "there is ONE chip slot on a row and the unified inbox spends it on the account, which " +
                "is the question there — the two must not fight over it",
            unreadRowFolder(
                email = row("1", account = "acc-1", mailbox = "mb-arch"),
                ui = unreadView().copy(unified = true, unreadView = false),
                folderTrusted = true,
                inViewCount = 1,
            ),
        )
    }

    // -- what cannot be named honestly ---------------------------------------------------------

    /**
     * A search hit carries the folder it was crawled in, which the server may have contradicted
     */
    @Test
    fun `a search hit names nothing, its folder is frozen at crawl time`() {
        assertNull(
            "the FTS row's mailboxId is a snapshot; a message archived, indexed, then moved from " +
                "another client would be labelled 'Archive' forever",
            unreadRowFolder(
                email = row("1", account = "acc-1", mailbox = "mb-arch"),
                ui = unreadView(),
                folderTrusted = false,
                inViewCount = 1,
            ),
        )
    }

    /**
     * The #31/#121 guard, and the one this file exists to hold: [MailUi.mailboxes] is ONE
     */
    @Test
    fun `a row of another account names nothing, even when its folder id exists here`() {
        assertNull(
            "'mb-arch' is a valid id in acc-1's folder list and means nothing in acc-2's; matching " +
                "on the bare id would name the neighbouring account's homonymous folder",
            unreadRowFolder(
                email = row("imap:acc-2:mb-arch:9", account = "acc-2", mailbox = "mb-arch"),
                ui = unreadView(account = "acc-1"),
                folderTrusted = true,
                inViewCount = 1,
            ),
        )
    }

    @Test
    fun `no account on screen names nothing`() {
        assertNull(
            "with no current account the folder list cannot be trusted to be the row's",
            unreadRowFolder(
                email = row("1", account = "acc-1", mailbox = "mb-arch"),
                ui = unreadView(account = null),
                folderTrusted = true,
                inViewCount = 1,
            ),
        )
    }

    @Test
    fun `an absent, blank or unknown folder names nothing`() {
        val ui = unreadView()
        assertNull(
            "no mailboxId: nothing to name",
            unreadRowFolder(row("1", account = "acc-1", mailbox = null), ui, folderTrusted = true, inViewCount = 1),
        )
        assertNull(
            "a blank mailboxId is not a folder either — it is what a row drawn from an index can carry",
            unreadRowFolder(row("2", account = "acc-1", mailbox = "   "), ui, folderTrusted = true, inViewCount = 1),
        )
        assertNull(
            "a folder the list has no entry for is an UNKNOWN folder, which is not the same thing " +
                "as a folder without a role: we stay quiet rather than guess",
            unreadRowFolder(row("3", account = "acc-1", mailbox = "mb-gone"), ui, folderTrusted = true, inViewCount = 1),
        )
    }
}
