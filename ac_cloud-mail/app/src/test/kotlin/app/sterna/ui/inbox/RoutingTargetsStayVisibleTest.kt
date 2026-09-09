package app.sterna.ui.inbox

import app.sterna.core.data.mail.ARCHIVE_FOLDER_NAMES
import app.sterna.core.data.mail.TRASH_FOLDER_NAMES
import app.sterna.core.data.mail.archiveFolderByName
import app.sterna.core.data.mail.trashFolderByName
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The invariant: **a folder one of the app's actions can file mail into stays visible** under
 */
class RoutingTargetsStayVisibleTest {

    private fun folder(
        id: String,
        name: String = id,
        role: String? = null,
        parentId: String? = null,
        subscribed: Boolean = true,
    ) = Mailbox(id = id, name = name, role = role, parentId = parentId, isSubscribed = subscribed)

    // ── the symptom, as the bench played it ──────────────────────────────────────────────────────

    @Test fun `an unsubscribed roleless Archive stays in the lists`() {
        val folders = listOf(
            folder("Inbox", role = "inbox"),
            folder("Archive", name = "Archive", subscribed = false),
            folder("Vieux", name = "Vieux", subscribed = false),
        )
        assertEquals(
            "⛔ THE bench defect. Stalwart gives Archive no role and no subscription; the Archive " +
                "button files there anyway, so hiding it puts archived mail out of reach.",
            listOf("Inbox", "Archive"),
            visibleFolders(folders, true).map { it.id },
        )
    }

    @Test fun `an unsubscribed roleless Corbeille stays in the lists`() {
        val folders = listOf(
            folder("Inbox", role = "inbox"),
            folder("t", name = "Corbeille", subscribed = false),
        )
        assertEquals(
            "delete falls back on the bin BY NAME (trashFolderByName); a hidden bin is deleted " +
                "mail nobody can get back",
            listOf("Inbox", "t"),
            visibleFolders(folders, true).map { it.id },
        )
    }

    @Test fun `a cyrillic archive name stays in the lists`() {
        val folders = listOf(folder("Inbox", role = "inbox"), folder("ru", name = "Архив", subscribed = false))
        assertEquals(
            "the case that dies first when someone retypes the name table by hand: Архив is in " +
                "ARCHIVE_FOLDER_NAMES, so the app files there and must show it",
            listOf("Inbox", "ru"),
            visibleFolders(folders, true).map { it.id },
        )
    }

    // ── what does NOT become visible ─────────────────────────────────────────────────────────────

    @Test fun `an unsubscribed folder in neither table stays hidden`() {
        val folders = listOf(
            folder("Inbox", role = "inbox"),
            folder("Vieux", name = "Vieux", subscribed = false),
            folder("P19", name = "Projet 2019", subscribed = false),
            folder("Kept", name = "Kept"),
        )
        assertEquals(
            "the setting must still hide something. Nothing in the app files mail into Vieux or " +
                "Projet 2019 on the strength of their names.",
            listOf("Inbox", "Kept"),
            visibleFolders(folders, true).map { it.id },
        )
    }

    @Test fun `a folder that carries a role is kept by the role, whatever it is named`() {
        val folders = listOf(folder("s", name = "Vieux", role = "sent", subscribed = false))
        assertEquals(
            "the role guard is not replaced, it is added to: sent and drafts have no by-name " +
                "fallback and stay covered by role != null alone",
            listOf("s"),
            visibleFolders(folders, true).map { it.id },
        )
    }

    // ── the witness: ONE list of names, read by both sides ─────────────────────────────────────

    @Test fun `routing and visibility answer the same on every name the tables hold`() {
        val disagree = (ARCHIVE_FOLDER_NAMES + TRASH_FOLDER_NAMES).filter { name ->
            val mailbox = folder("mb", name = name, subscribed = false)
            // The routing side: the two elections `MailRepository` itself calls when the server
            // set no role (archiveMailboxId / trashMailboxId).
            val routesHere = archiveFolderByName(listOf(mailbox)) != null ||
                trashFolderByName(listOf(mailbox)) != null
            // The visibility side: what the screens are handed.
            val shown = visibleFolders(listOf(mailbox), true).map { it.id } == listOf("mb")
            routesHere != shown
        }
        assertEquals(
            "⛔ the two sides read ONE table or they drift: every name below is one the app files " +
                "mail into while refusing to show the folder (or the reverse).",
            emptyList<String>(),
            disagree,
        )
    }

    @Test fun `routing and visibility also agree that an ordinary folder is neither`() {
        val disagree = listOf("Vieux", "Projet 2019", "Factures", "INBOX", "Work").filter { name ->
            val mailbox = folder("mb", name = name, subscribed = false)
            val routesHere = archiveFolderByName(listOf(mailbox)) != null ||
                trashFolderByName(listOf(mailbox)) != null
            val shown = visibleFolders(listOf(mailbox), true).map { it.id } == listOf("mb")
            routesHere != shown
        }
        assertEquals(
            "the other direction: a fix that simply showed everything would pass the test above.",
            emptyList<String>(),
            disagree,
        )
    }

    // ── the tree still holds around a folder kept this way ───────────────────────────────────────

    @Test fun `an unsubscribed parent of a kept archive is kept too`() {
        val folders = listOf(
            folder("shared", name = "Équipe", subscribed = false),
            folder("shared/arch", name = "Archives", parentId = "shared", subscribed = false),
        )
        assertEquals(
            "rule 3 already says an ancestor of something visible is visible; drop the parent and " +
                "the drawer draws the child one level up, which is a lie about the hierarchy",
            listOf("shared", "shared/arch"),
            visibleFolders(folders, true).map { it.id },
        )
    }
}
