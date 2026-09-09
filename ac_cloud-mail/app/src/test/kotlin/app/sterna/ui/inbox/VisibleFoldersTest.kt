package app.sterna.ui.inbox

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which folders the screens show when an account asks for its SUBSCRIBED folders only (#174) — the
 */
class VisibleFoldersTest {

    private fun folder(
        id: String,
        role: String? = null,
        name: String = id,
        parentId: String? = null,
        subscribed: Boolean = true,
        lostClaim: Boolean = false,
    ) = Mailbox(
        id = id,
        name = name,
        role = role,
        parentId = parentId,
        isSubscribed = subscribed,
        lostRoleClaim = lostClaim,
    )

    private fun account(id: String, on: Boolean) = StoredAccount(
        id = id,
        server = "mail.example.test",
        username = "$id@example.test",
        showOnlySubscribedFolders = on,
    )

    // ── T1: off, the default every account starts on ─────────────────────────────────────────────

    @Test fun `with the setting off the list comes back untouched, unsubscribed folders and all`() {
        val folders = listOf(
            folder("old", subscribed = false),
            folder("inbox", role = "inbox"),
            folder("zulu", subscribed = false),
            folder("alpha"),
        )
        // The same list object, not a copy that happens to be equal: this is the path every
        // account is on by default, and it must be an identity, not a rebuild.
        assertSame(folders, visibleFolders(folders, false))
        assertEquals(
            "the setting is OFF and a folder is missing — the default hides nothing",
            listOf("old", "inbox", "zulu", "alpha"),
            visibleFolders(folders, false).map { it.id },
        )
    }

    // ── T2: on ───────────────────────────────────────────────────────────────────────────────────

    @Test fun `with the setting on an unsubscribed leaf goes, a subscribed one stays`() {
        val folders = listOf(
            folder("inbox", role = "inbox"),
            folder("kept"),
            folder("old", subscribed = false),
            folder("also-kept"),
        )
        assertEquals(
            listOf("inbox", "kept", "also-kept"),
            visibleFolders(folders, true).map { it.id },
        )
    }

    @Test fun `the order the server sent is kept, it is not a sort`() {
        val folders = listOf(
            folder("zulu"),
            folder("gone", subscribed = false),
            folder("alpha"),
            folder("inbox", role = "inbox"),
        )
        assertEquals(listOf("zulu", "alpha", "inbox"), visibleFolders(folders, true).map { it.id })
    }

    // ── T3: a folder with a role is never hidden ─────────────────────────────────────────────────

    @Test fun `an unsubscribed folder that carries a role stays, whatever the role is`() {
        // Stalwart answers `Sent` with isSubscribed false (probe of 2026-08-25) and the bench found
        // `Archive` unsubscribed on three personas. Hiding those is K-9's own defect
        // (thunderbird-android#2615): no Trash to delete into, no Sent to find sent mail in.
        val folders = listOf(
            folder("inbox", role = "inbox", subscribed = false),
            folder("trash", role = "trash", subscribed = false),
            folder("sent", role = "sent", subscribed = false),
            folder("drafts", role = "drafts", subscribed = false),
            folder("archive", role = "archive", subscribed = false),
            folder("junk", role = "junk", subscribed = false),
            folder("flagged", role = "flagged", subscribed = false),
            folder("templates", role = "templates", subscribed = false),
            folder("old", subscribed = false),
        )
        assertEquals(
            "a folder the server gave a role to must survive the filter — including a role this " +
                "app does not translate. Only the role-less unsubscribed folder goes.",
            listOf("inbox", "trash", "sent", "drafts", "archive", "junk", "flagged", "templates"),
            visibleFolders(folders, true).map { it.id },
        )
    }

    // ── T3b: a folder that CLAIMED a role and lost the election ──────────────────────────────────

    /**
     * The defect. An IMAP account with both a `Junk` and a `Spam`: only one can be THE junk
     */
    @Test fun `an unsubscribed folder that claimed a role and LOST it stays`() {
        val folders = listOf(
            folder("inbox", role = "inbox"),
            folder("Junk", name = "Junk", role = "junk", subscribed = false),
            folder("Spam", name = "Spam", subscribed = false, lostClaim = true),
            folder("Old", name = "Old", subscribed = false),
        )
        assertEquals(
            "the folder that lost the junk election must stay listed — it is unsubscribed, it has " +
                "no role by the time it gets here, it is no routing target, and it is already out " +
                "of search: hiding it puts the spam false positives out of the app's reach",
            listOf("inbox", "Junk", "Spam"),
            visibleFolders(folders, true).map { it.id },
        )
    }

    /** The claim guard and the ancestor guard compose, exactly like the role guard does: a losing
     *  folder pulls its whole unsubscribed line up with it, or the drawer draws it at the root and
     *  lies about the hierarchy. */
    @Test fun `a losing folder pulls its unsubscribed ancestors up with it`() {
        val folders = listOf(
            folder("inbox", role = "inbox"),
            folder("Shared", name = "Shared", subscribed = false),
            folder("Shared/team", name = "team", subscribed = false),
            folder("Shared/team/Spam", name = "Spam", subscribed = false, lostClaim = true),
            folder("Old", name = "Old", subscribed = false),
        )
        val visible = visibleFolders(folders, true)
        assertEquals(
            listOf("inbox", "Shared", "Shared/team", "Shared/team/Spam"),
            visible.map { it.id },
        )
        assertEquals(
            "with Shared/team dropped, mailboxTree reparents the losing Spam and the drawer lies " +
                "about where it lives",
            listOf("Shared/team/Spam" to 2),
            mailboxTree(visible, emptySet()).filter { it.mailbox.id == "Shared/team/Spam" }
                .map { it.mailbox.id to it.depth },
        )
    }

    /** A JMAP-shaped parent works the same way — the walk is [folderParentId]'s, not a path trick. */
    @Test fun `a losing child of a hidden JMAP parent keeps that parent`() {
        val folders = listOf(
            folder("mb1", name = "Inbox", role = "inbox"),
            folder("mb7", name = "Shared", subscribed = false),
            folder("mb8", name = "Spam", parentId = "mb7", subscribed = false, lostClaim = true),
            folder("mb9", name = "Old", subscribed = false),
        )
        assertEquals(listOf("mb1", "mb7", "mb8"), visibleFolders(folders, true).map { it.id })
    }

    /** With the box UNTICKED the list is handed back as it stands — the claim changes nothing
     *  about the default path, which is still an identity and not a rebuild. */
    @Test fun `with the setting off a losing folder changes nothing about the untouched list`() {
        val folders = listOf(
            folder("inbox", role = "inbox"),
            folder("Spam", name = "Spam", subscribed = false, lostClaim = true),
            folder("Old", name = "Old", subscribed = false),
        )
        assertSame(folders, visibleFolders(folders, false))
        assertEquals(listOf("inbox", "Spam", "Old"), visibleFolders(folders, false).map { it.id })
    }

    /**
     * The reprieve KEEPS the row, it does not rewrite it: the folder comes out of the filter with
     */
    @Test fun `the filter hands back the row it kept, role and all`() {
        val loser = folder("Spam", name = "Spam", subscribed = false, lostClaim = true)
        val visible = visibleFolders(listOf(loser), true)
        assertEquals(listOf("Spam"), visible.map { it.id })
        assertEquals(
            "a folder kept by its lost claim must still show as role-less, or it loses Rename and " +
                "Delete from its ⋮ menu",
            listOf<String?>(null),
            visible.map { it.role },
        )
    }

    // ── T4/T5: an ancestor of a visible folder is visible ────────────────────────────────────────

    @Test fun `a JMAP parent stays for its subscribed child, and keeps it as a child`() {
        val folders = listOf(
            folder("inbox", role = "inbox"),
            folder("pa", name = "ProjectA", subscribed = false),
            folder("done", name = "Done", parentId = "pa"),
            folder("old", name = "Old", subscribed = false),
        )
        val visible = visibleFolders(folders, true)
        assertEquals(listOf("inbox", "pa", "done"), visible.map { it.id })
        assertEquals(
            "with ProjectA dropped, mailboxTree reparents Done to the root and the drawer lies " +
                "about the hierarchy",
            listOf("done" to 1),
            mailboxTree(visible, emptySet()).filter { it.mailbox.id == "done" }
                .map { it.mailbox.id to it.depth },
        )
    }

    @Test fun `an IMAP parent stays for its subscribed child, by path prefix`() {
        val folders = listOf(
            folder("INBOX", role = "inbox"),
            folder("ProjectA", name = "ProjectA", subscribed = false),
            folder("ProjectA/Done", name = "Done"),
            folder("Old", name = "Old", subscribed = false),
        )
        val visible = visibleFolders(folders, true)
        assertEquals(listOf("INBOX", "ProjectA", "ProjectA/Done"), visible.map { it.id })
        assertEquals(
            listOf("ProjectA/Done" to 1),
            mailboxTree(visible, emptySet()).filter { it.mailbox.id == "ProjectA/Done" }
                .map { it.mailbox.id to it.depth },
        )
        val dotted = listOf(
            folder("INBOX", role = "inbox"),
            folder("INBOX.ProjectA", name = "ProjectA", subscribed = false),
            folder("INBOX.ProjectA.Done", name = "Done"),
        )
        assertEquals(
            listOf("INBOX", "INBOX.ProjectA", "INBOX.ProjectA.Done"),
            visibleFolders(dotted, true).map { it.id },
        )
    }

    @Test fun `a grandparent stays too — one level up is not enough`() {
        val jmap = listOf(
            folder("a", name = "A", subscribed = false),
            folder("b", name = "B", parentId = "a", subscribed = false),
            folder("c", name = "C", parentId = "b"),
        )
        assertEquals(listOf("a", "b", "c"), visibleFolders(jmap, true).map { it.id })
        val imap = listOf(
            folder("A", name = "A", subscribed = false),
            folder("A/B", name = "B", subscribed = false),
            folder("A/B/C", name = "C"),
        )
        assertEquals(listOf("A", "A/B", "A/B/C"), visibleFolders(imap, true).map { it.id })
    }

    @Test fun `a whole unsubscribed branch with nothing visible under it goes`() {
        val folders = listOf(
            folder("inbox", role = "inbox"),
            folder("a", name = "A", subscribed = false),
            folder("a/b", name = "B", subscribed = false),
            folder("a/b/c", name = "C", subscribed = false),
        )
        assertEquals(listOf("inbox"), visibleFolders(folders, true).map { it.id })
    }

    @Test fun `a role-bearing child pulls its unsubscribed parent up with it`() {
        // The role guard and the ancestor guard have to compose: an archive nested under a folder
        // nobody subscribes to still needs its parent, or it lands at the drawer's root.
        val folders = listOf(
            folder("shared", name = "Shared", subscribed = false),
            folder("shared/arch", name = "Archive", role = "archive", subscribed = false),
        )
        assertEquals(listOf("shared", "shared/arch"), visibleFolders(folders, true).map { it.id })
    }

    @Test fun `a parentId loop does not hang, and keeps what the walk can see`() {
        val folders = listOf(
            folder("inbox", role = "inbox"),
            folder("a", parentId = "b", subscribed = false),
            folder("b", parentId = "a"),
        )
        assertEquals(listOf("inbox", "a", "b"), visibleFolders(folders, true).map { it.id })
    }

    @Test fun `an empty list stays empty either way`() {
        assertEquals(emptyList<String>(), visibleFolders(emptyList(), true).map { it.id })
        assertEquals(emptyList<String>(), visibleFolders(emptyList(), false).map { it.id })
    }

    // ── the boolean is the OPEN account's, not some account's ────────────────────────────────────

    @Test fun `the setting is read off the account named, and only that one`() {
        val accounts = listOf(account("a1", on = false), account("a2", on = true))
        assertFalse(showOnlySubscribedFor("a1", accounts))
        assertTrue(showOnlySubscribedFor("a2", accounts))
        assertFalse(
            "an account id that resolves to nothing must not hide folders — the one answer that " +
                "makes mail vanish with nobody having asked",
            showOnlySubscribedFor("gone", accounts),
        )
        assertFalse(showOnlySubscribedFor(null, accounts))
        assertFalse(showOnlySubscribedFor("a2", emptyList()))
    }
}
