package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The drawer's own tree, RUN — it had no test at all until #174 came to filter what goes into it.
 */
class MailboxTreeTest {

    private fun mailbox(id: String, role: String? = null, name: String = id, parentId: String? = null) =
        Mailbox(id = id, name = name, role = role, parentId = parentId)

    private fun shape(nodes: List<MailboxNode>) =
        nodes.map { Triple(it.mailbox.id, it.depth, it.hasChildren) }

    @Test fun `a flat list comes out flat, standard folders first`() {
        // The standard folders are ordered by role — Inbox, Drafts, Sent, Trash, Junk, Archive —
        // and since #247 the custom ones are ordered BY NAME behind them, where they used to keep
        // whatever order the server had listed them in. "alpha" before "zulu" is that fix; see
        // DrawerFolderOrderTest for the owner's own list, which is what the change was for.
        val folders = listOf(
            mailbox("zulu"),
            mailbox("archive-1", role = "archive"),
            mailbox("trash-1", role = "trash"),
            mailbox("alpha"),
            mailbox("inbox-1", role = "inbox"),
            mailbox("junk-1", role = "junk"),
            mailbox("sent-1", role = "sent"),
            mailbox("drafts-1", role = "drafts"),
        )
        assertEquals(
            listOf(
                Triple("inbox-1", 0, false),
                Triple("drafts-1", 0, false),
                Triple("sent-1", 0, false),
                Triple("trash-1", 0, false),
                Triple("junk-1", 0, false),
                Triple("archive-1", 0, false),
                Triple("alpha", 0, false),
                Triple("zulu", 0, false),
            ),
            shape(mailboxTree(folders, emptySet())),
        )
    }

    @Test fun `the rank of every role the drawer knows, and of one it does not`() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 6, 6),
            listOf("inbox", "drafts", "sent", "trash", "junk", "archive", "all", "templates", null)
                .map { folderRank(it) },
        )
    }

    @Test fun `JMAP nesting follows parentId, depth first`() {
        val folders = listOf(
            mailbox("inbox-1", role = "inbox"),
            mailbox("pa", name = "ProjectA"),
            mailbox("done", name = "Done", parentId = "pa"),
            mailbox("old", name = "Old", parentId = "done"),
            mailbox("pb", name = "ProjectB"),
        )
        assertEquals(
            listOf(
                Triple("inbox-1", 0, false),
                Triple("pa", 0, true),
                Triple("done", 1, true),
                Triple("old", 2, false),
                Triple("pb", 0, false),
            ),
            shape(mailboxTree(folders, emptySet())),
        )
    }

    @Test fun `IMAP nesting follows the path delimiter in the id`() {
        val slash = listOf(
            mailbox("INBOX", role = "inbox"),
            mailbox("ProjectA", name = "ProjectA"),
            mailbox("ProjectA/Done", name = "Done"),
        )
        assertEquals(
            listOf(Triple("INBOX", 0, false), Triple("ProjectA", 0, true), Triple("ProjectA/Done", 1, false)),
            shape(mailboxTree(slash, emptySet())),
        )
        val dotted = listOf(
            mailbox("INBOX", role = "inbox"),
            mailbox("INBOX.ProjectA", name = "ProjectA"),
            mailbox("INBOX.ProjectA.Done", name = "Done"),
        )
        assertEquals(
            listOf(
                Triple("INBOX", 0, true),
                Triple("INBOX.ProjectA", 1, true),
                Triple("INBOX.ProjectA.Done", 2, false),
            ),
            shape(mailboxTree(dotted, emptySet())),
        )
    }

    @Test fun `a child whose parent is not in the list is shown at the top level`() {
        // Not dropped: a folder that cannot be placed must still be reachable, or mail is
        // unreachable through the drawer. Both the JMAP and the IMAP way of missing a parent.
        val folders = listOf(
            mailbox("inbox-1", role = "inbox"),
            mailbox("done", name = "Done", parentId = "nowhere"),
            mailbox("ProjectA/Done", name = "Done"),
        )
        assertEquals(
            listOf(Triple("inbox-1", 0, false), Triple("done", 0, false), Triple("ProjectA/Done", 0, false)),
            shape(mailboxTree(folders, emptySet())),
        )
    }

    @Test fun `a collapsed folder keeps its row and its chevron, and hides its descendants`() {
        val folders = listOf(
            mailbox("inbox-1", role = "inbox"),
            mailbox("pa", name = "ProjectA"),
            mailbox("done", name = "Done", parentId = "pa"),
            mailbox("old", name = "Old", parentId = "done"),
        )
        assertEquals(
            listOf(Triple("inbox-1", 0, false), Triple("pa", 0, true)),
            shape(mailboxTree(folders, setOf("pa"))),
        )
        assertEquals(
            listOf(Triple("inbox-1", 0, false), Triple("pa", 0, true), Triple("done", 1, true)),
            shape(mailboxTree(folders, setOf("done"))),
        )
    }

    @Test fun `a parentId loop neither hangs nor swallows the folders around it`() {
        // Two folders naming each other as parent: neither is a child of the root, so neither is
        // reachable and the walk must simply not run forever. What matters is the third row.
        val folders = listOf(
            mailbox("inbox-1", role = "inbox"),
            mailbox("a", parentId = "b"),
            mailbox("b", parentId = "a"),
        )
        assertEquals(listOf(Triple("inbox-1", 0, false)), shape(mailboxTree(folders, emptySet())))
    }

    @Test fun `an empty list yields an empty tree`() {
        assertEquals(emptyList<Triple<String, Int, Boolean>>(), shape(mailboxTree(emptyList(), emptySet())))
    }
}
