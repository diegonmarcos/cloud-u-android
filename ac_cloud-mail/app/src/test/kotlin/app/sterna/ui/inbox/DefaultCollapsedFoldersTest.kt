package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a folder NOBODY has decided anything about does on the first run, EXECUTED
 */
class DefaultCollapsedFoldersTest {

    private fun mailbox(
        id: String,
        role: String? = null,
        name: String = id,
        parentId: String? = null,
        lostRoleClaim: Boolean = false,
    ) = Mailbox(id = id, name = name, role = role, parentId = parentId, lostRoleClaim = lostRoleClaim)

    @Test fun `a folder of the user's with something under it starts folded, a leaf does not`() {
        val folders = listOf(
            mailbox("Travail"),
            mailbox("Travail/Client A"),
            mailbox("Perso"),
        )
        assertEquals(
            "a user folder with children must start folded, and a folder with nothing under it " +
                "must not: the drawer draws no chevron on a leaf, so folding it would hide nothing " +
                "and could never be undone from the screen.",
            setOf("Travail"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    /**
     * The mutation this test exists for: `folderRank(role) == 6` in place of `role == null`.
     */
    @Test fun `no folder carrying a role starts folded, not the six the app names and not 'all'`() {
        val folders = listOf(
            mailbox("in", role = "inbox"), mailbox("in/x"),
            mailbox("dr", role = "drafts"), mailbox("dr/x"),
            mailbox("se", role = "sent"), mailbox("se/x"),
            mailbox("tr", role = "trash"), mailbox("tr/x"),
            mailbox("ju", role = "junk"), mailbox("ju/x"),
            mailbox("ar", role = "archive"), mailbox("ar/x"),
            mailbox("al", role = "all"), mailbox("al/x"),
            mailbox("Travail"), mailbox("Travail/Client A"),
        )
        assertEquals(
            "a folder the server gave a role must never start folded, whatever its children — and " +
                "that includes the roles this app does not translate ('all' here), which " +
                "folderRank lumps in with the user's own folders. Only the user folder in this " +
                "list may come back. If Travail alone is missing, the rule stopped folding " +
                "anything at all.",
            setOf("Travail"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    /**
     * The child that carries the role, which no other case here has: the rule reads the role of
     */
    @Test fun `a user folder whose only child carries a role still starts folded`() {
        val folders = listOf(
            mailbox("Perso"),
            mailbox("Perso/Archive", role = "archive"),
        )
        assertEquals(
            "Perso must fold on the first run even though the only thing under it is a folder the " +
                "server gave the archive role. The role guard applies to the folder being folded, " +
                "not to its children — and the assumed consequence is that Perso/Archive is off " +
                "screen until the user opens Perso, with Perso's badge carrying its unread.",
            setOf("Perso"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    /**
     * Stalwart's `Archive`, MEASURED on the bench on 2026-08-25 and written into
     */
    @Test fun `an archive the server left roleless is not folded — the app files mail into it`() {
        val folders = listOf(
            mailbox("Archive"),
            mailbox("Archive/2024"),
            mailbox("Travail"),
            mailbox("Travail/Client A"),
        )
        assertEquals(
            "a folder one of this app's actions can file mail into BY NAME must not fold itself " +
                "on the first run: on Stalwart the Archive arrives role-null, and folding it hides " +
                "the destination of the Archive button behind a chevron nobody asked for. Travail " +
                "is the control — if it is missing too, the rule stopped folding anything.",
            setOf("Travail"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    /**
     * The loser of an IMAP role election (`ImapClient.assignRoles`): the server DID advertise a
     */
    @Test fun `a folder that claimed a role and lost the election is not folded either`() {
        val folders = listOf(
            mailbox("Spam", lostRoleClaim = true),
            mailbox("Spam/Faux positifs"),
            mailbox("Travail"),
            mailbox("Travail/Client A"),
        )
        assertEquals(
            "an account with both a Junk and a Spam has one of them reaching here with role null " +
                "and lostRoleClaim true — the server named a role on it. Folding it puts the " +
                "folder holding the spam false positives behind a chevron on the first launch. " +
                "Travail is the control.",
            setOf("Travail"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    /**
     * The IMAP shape the whole guard is built around: ids that ARE paths, everything hanging
     * under the Inbox. Folding the Inbox would leave the drawer showing one single line.
     */
    @Test fun `an IMAP tree under the Inbox leaves the Inbox open, both delimiters`() {
        val slash = listOf(
            mailbox("INBOX", role = "inbox"),
            mailbox("INBOX/Travail"),
            mailbox("INBOX/Travail/Client A"),
        )
        assertEquals(
            "on an INBOX/ namespace the whole tree hangs under the Inbox: folding it would leave " +
                "the drawer with a single line and no way to see anything else.",
            setOf("INBOX/Travail"),
            defaultCollapsedFolderIds(slash, rowsBadgeUnread = true),
        )
        val dotted = listOf(
            mailbox("INBOX", role = "inbox"),
            mailbox("INBOX.Travail"),
            mailbox("INBOX.Travail.Client A"),
        )
        assertEquals(
            "the same account on Dovecot, whose delimiter is '.' — the other branch of " +
                "folderParentId. A rule that only knows '/' folds nothing at all here.",
            setOf("INBOX.Travail"),
            defaultCollapsedFolderIds(dotted, rowsBadgeUnread = true),
        )
    }

    @Test fun `nesting declared by the JMAP parentId counts too`() {
        val folders = listOf(
            mailbox("m1", name = "Travail"),
            mailbox("m2", name = "Client A", parentId = "m1"),
        )
        assertEquals(
            "JMAP ids carry no path: filiation comes from parentId, and a rule that only splits " +
                "ids on a delimiter folds nothing on a JMAP account.",
            setOf("m1"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    /**
     * The grandchild, pinned to what [folderParentId] actually answers rather than to what the
     */
    @Test fun `a folder whose only descendant skips a level is nobody's parent`() {
        val folders = listOf(
            mailbox("Perso"),
            mailbox("Perso/Projets/Client A"),
        )
        assertEquals(
            "with the intermediate folder absent, mailboxTree draws the deep folder at the top " +
                "level: Perso has no chevron, so it must not start folded.",
            emptySet<String>(),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    @Test(timeout = 5_000) fun `a parentId pointing back down its own chain does not spin`() {
        val folders = listOf(
            mailbox("a", parentId = "b"),
            mailbox("b", parentId = "a"),
        )
        assertEquals(
            "a cycle is a server answer, not an impossibility. Each of the two names the other as " +
                "its parent, so both are parents; what must not happen is a walk that never ends.",
            setOf("a", "b"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    /**
     * V5, and the ONLY thing it changes here: an account whose drawer rows carry no unread count
     */
    @Test fun `rows that cannot badge unread fold nothing by default, and the flag changes nothing else`() {
        val folders = listOf(
            mailbox("INBOX", role = "inbox"),
            mailbox("INBOX/Travail"),
            mailbox("INBOX/Travail/Client A"),
            mailbox("Perso"),
            mailbox("Perso/Archive", role = "archive"),
        )
        assertEquals(
            "the account whose rows CAN badge unread must keep the shipped default: every folder " +
                "of the user's that has children starts folded. If this half is empty the guard " +
                "is unconditional and the feature is dead on every account.",
            setOf("INBOX/Travail", "Perso"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
        assertEquals(
            "on an account whose folder rows carry no unread count, NOTHING may start folded. " +
                "Folding INBOX/Travail and Perso here hides whatever arrives under them behind a " +
                "chevron nobody touched, and no row anywhere says so — the badge that makes the " +
                "default honest does not exist on this account (measured on the bench, IMAP, " +
                "2026-09-01).",
            emptySet<String>(),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = false),
        )
    }

    /**
     * The role guard is not what V5 touches, seen from the other side: with no badge available the
     */
    @Test fun `a badge-less account folds nothing even where the role guard would have allowed it`() {
        val folders = listOf(mailbox("Travail"), mailbox("Travail/Client A"))
        assertEquals(
            "Travail is the plainest user folder with a child there is — the case at the top of " +
                "this file folds it. With no badge on its row it must not fold.",
            emptySet<String>(),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = false),
        )
    }

    @Test fun `an account with no folders folds nothing`() {
        assertEquals(emptySet<String>(), defaultCollapsedFolderIds(emptyList(), rowsBadgeUnread = true))
    }
}
