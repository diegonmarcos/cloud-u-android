package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHICH folder a list row is judged by.
 */
class RowFolderRoleTest {

    private fun row(id: String, account: String?, mailbox: String?) =
        Email(id = id, accountId = account, mailboxId = mailbox)

    /** A view showing [selected], among folders [folders] — the only two fields this reads. */
    private fun view(selected: String?, folders: List<Mailbox>) = MailUi(
        accountName = "a",
        mailboxName = "m",
        unreadCount = 0,
        selectedMailboxId = selected,
        unified = selected == null,
        mailboxes = folders,
        refreshing = false,
        error = null,
    )

    private fun folder(id: String, role: String? = null) = Mailbox(id = id, name = id, role = role)

    private val trashView = view("t", listOf(folder("i", "inbox"), folder("t", "trash")))

    // -- the row's own folder wins -----------------------------------------------------------

    @Test
    fun `a row filed in Archive reads archive even when the view is elsewhere`() {
        val role = rowFolderRole(
            email = row("1", account = "a", mailbox = "arch"),
            ui = trashView,
            roles = mapOf(("a" to "arch") to "archive", ("a" to "t") to "trash"),
            folderTrusted = true,
        )
        assertEquals(
            "the row is in the Archive; judging it by the folder on screen would offer to DESTROY " +
                "it. Role answered was: $role",
            "archive", role,
        )
    }

    @Test
    fun `the same folder id under two accounts answers each account's own role`() {
        // Codeberg #121/#31: servers number mailboxes per account, so "5" is a different folder
        // under a different account — routinely the Inbox of one and the Trash of the other. A
        // bare-id lookup hands account A's role to account B's message and offers to destroy it.
        val roles = mapOf(("a" to "5") to "trash", ("b" to "5") to "inbox")
        val ui = view("5", listOf(folder("5", "trash")))
        assertEquals("A's folder 5 is its Trash", "trash", rowFolderRole(row("1", "a", "5"), ui, roles, folderTrusted = true))
        assertEquals(
            "B's folder 5 is B's INBOX, whatever A calls its own folder 5 — and whatever folder is " +
                "on screen",
            "inbox", rowFolderRole(row("2", "b", "5"), ui, roles, folderTrusted = true),
        )
    }

    @Test
    fun `a row in a role-less user folder falls back rather than inventing a role`() {
        // "Projets" has no role, so the map holds no entry for it. In a view that has no role
        // either — the unified inbox, the unread scope — that is null, i.e. "nothing special", and
        // every affordance keeps its ordinary meaning.
        val ui = view(null, listOf(folder("i", "inbox")))
        assertEquals(null, rowFolderRole(row("1", "a", "projets"), ui, mapOf(("a" to "i") to "inbox"), folderTrusted = true))
    }

    // -- the fallback: a row whose folder cannot be believed ----------------------------------

    @Test
    fun `a row with no folder on it reads the view's role`() {
        // A row drawn from the search index carries a mailboxId frozen at crawl time, which may be
        // empty (SelectionTargets.folderTrusted). Blank is as absent as null — not through a guard
        // of its own (one was written and deleted: a mutation removing it survived, because a blank
        // id has no entry in the role map either) but through the same single fallback.
        val roles = mapOf(("a" to "t") to "trash", ("a" to "arch") to "archive")
        assertEquals("trash", rowFolderRole(row("1", "a", null), trashView, roles, folderTrusted = true))
        assertEquals("trash", rowFolderRole(row("1", "a", ""), trashView, roles, folderTrusted = true))
        assertEquals("trash", rowFolderRole(row("1", "a", "   "), trashView, roles, folderTrusted = true))
    }

    @Test
    fun `a row with no account on it reads the view's role`() {
        assertEquals("trash", rowFolderRole(row("1", null, "t"), trashView, mapOf(("a" to "t") to "trash"), folderTrusted = true))
    }

    @Test
    fun `an empty role map still answers the view's role`() {
        // folderRoles is a WhileSubscribed StateFlow seeded with emptyMap, so this is the state of
        // the first frames of every screen. Answering null there would take "Delete permanently"
        // off the Trash for as long as the Room query takes.
        assertEquals(
            "an unresolved lookup must degrade to what shipped before, not to \"no role\"",
            "trash", rowFolderRole(row("1", "a", "t"), trashView, emptyMap(), folderTrusted = true),
        )
    }

    // -- a search hit's folder is not evidence of anything ------------------------------------

    @Test
    fun `a search hit keeps the view's role however confident its own folder looks`() {
        // The reported walk, and it costs mail: a message is archived and indexed; it is then moved
        // to "Projets" from the webmail. The FTS row still says `archive` — SelectionTargets puts it
        val inboxView = view("i", listOf(folder("i", "inbox"), folder("arch", "archive")))
        val roles = mapOf(("a" to "i") to "inbox", ("a" to "arch") to "archive")
        val staleHit = row("1", "a", "arch")
        assertEquals(
            "a search hit must not be judged by the folder the crawl froze into it",
            "inbox", rowFolderRole(staleHit, inboxView, roles, folderTrusted = false),
        )
        assertEquals(
            "the very same row IN THE BROWSE LIST is trusted — that is the whole difference",
            "archive", rowFolderRole(staleHit, inboxView, roles, folderTrusted = true),
        )
    }

    @Test
    fun `a search hit of another account cannot claim a role in the unified view`() {
        // The second damage of the same hole. In the unified list an untrusted hit of account B
        // filed in B's Archive would read `archive`, and the unarchive path then picks its
        val unified = view(null, listOf(folder("i-a", "inbox")))
        val roles = mapOf(("b" to "arch-b") to "archive", ("a" to "i-a") to "inbox")
        assertEquals(null, rowFolderRole(row("1", "b", "arch-b"), unified, roles, folderTrusted = false))
    }

    @Test
    fun `an untrusted row is refused before the map is consulted at all`() {
        // Not "the lookup happens to miss": the refusal comes first. A hit whose folder IS in the
        // map, and whose role is the Trash, must still read the view — otherwise a stale index row
        // turns a swipe into a permanent destroy.
        val archiveView = view("arch", listOf(folder("arch", "archive"), folder("t", "trash")))
        val roles = mapOf(("a" to "t") to "trash", ("a" to "arch") to "archive")
        assertEquals("archive", rowFolderRole(row("1", "a", "t"), archiveView, roles, folderTrusted = false))
    }

    // -- non-regression on the two views that exist today -------------------------------------

    @Test
    fun `in a plain folder every row reads the folder on screen`() {
        val ui = view("t", listOf(folder("i", "inbox"), folder("t", "trash")))
        val roles = mapOf(("a" to "i") to "inbox", ("a" to "t") to "trash")
        val rows = listOf(row("1", "a", "t"), row("2", "a", "t"), row("3", "a", null))
        assertEquals(
            "a Trash folder's rows are all in the Trash, so nothing about the Trash view changes",
            listOf("trash", "trash", "trash"),
            rows.map { rowFolderRole(it, ui, roles, folderTrusted = true) },
        )
    }

    @Test
    fun `in the unified inbox every row reads inbox`() {
        // The unified view selects no folder, so its own role is null and the pre-change answer was
        // null for every row. Every row in it IS in an inbox, so the new answer is "inbox" — which
        // is not the Trash, not the Archive and not Drafts, so no affordance changes.
        val ui = view(null, emptyList())
        val roles = mapOf(("a" to "i-a") to "inbox", ("b" to "i-b") to "inbox")
        val rows = listOf(row("1", "a", "i-a"), row("2", "b", "i-b"))
        assertEquals(listOf("inbox", "inbox"), rows.map { rowFolderRole(it, ui, roles, folderTrusted = true) })
    }
}
