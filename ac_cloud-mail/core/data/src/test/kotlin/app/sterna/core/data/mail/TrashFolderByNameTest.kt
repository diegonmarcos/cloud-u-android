package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which folder a JMAP delete falls back on when NO folder carries the `trash` role — the decision
 */
class TrashFolderByNameTest {

    private fun mailbox(id: String, name: String, parentId: String? = null) =
        Mailbox(id = id, name = name, role = null, parentId = parentId)

    private fun idFor(name: String): String? = trashFolderByName(listOf(mailbox("mb", name)))

    @Test fun `no role at all, and the bin is named Bin`() {
        assertEquals(
            "⛔ THE case. A JMAP server that sets no role leaves the account with no `trash`, and " +
                "delete() used to throw outright — the user could not get rid of a message at all",
            "mb-bin",
            trashFolderByName(listOf(mailbox("mb-in", "INBOX"), mailbox("mb-bin", "Bin"))),
        )
    }

    @Test fun `a top-level Corbeille beats a nested Trash`() {
        assertEquals(
            "a folder named Trash filed under another one is as likely to be someone's archive of " +
                "old bins as the account's own; the root one is the account's bin",
            "mb-root",
            trashFolderByName(
                listOf(
                    mailbox("mb-nested", "Trash", parentId = "mb-in"),
                    mailbox("mb-root", "Corbeille"),
                ),
            ),
        )
    }

    @Test fun `nothing that looks like a bin means no answer`() {
        assertNull(
            "null is what lets the caller CREATE one; answering with an unrelated folder would " +
                "move deleted mail into a folder the user reads",
            trashFolderByName(listOf(mailbox("mb-in", "INBOX"), mailbox("mb-w", "Work"))),
        )
    }

    @Test fun `the name is matched whatever its case`() {
        assertEquals(
            "servers spell it as they please — DELETED ITEMS is Exchange's own spelling",
            "mb-x",
            trashFolderByName(listOf(mailbox("mb-x", "DELETED ITEMS"))),
        )
    }

    @Test fun `the spellings servers really create, not the ones English guesses`() {
        // The damage this is about: a German account whose bin is "Gelöschte Objekte" is seen as
        // having no bin at all, so the delete CREATES a second folder called "Trash" and the user's
        val missed = listOf(
            "Gelöschte Objekte", "Gelöschte Elemente", "Éléments supprimés", "Elementos eliminados",
            "Posta eliminata", "Elementi eliminati", "Prullenmand", "Verwijderde items",
            "Elementy usunięte", "Lixo", "Itens excluídos", "Itens eliminados",
            "Удалённые", "Удаленные",
        ).filter { idFor(it) == null }
        assertEquals(
            "a bin the server actually names this way must be FOUND, not duplicated by a new " +
                "\"Trash\" next to it",
            emptyList<String>(), missed,
        )
    }

    @Test fun `no folder the user reads may ever be taken for a bin`() {
        // The rule that a table can only lose to by GROWING: slip "sent" (or "archive", or "junk")
        // into the names and every other test here stays green while deleted mail is moved into
        // the user's Sent folder — visible, and undone by nothing. Run, name by name.
        val stolen = listOf(
            // inbox
            "INBOX", "Posteingang", "Bandeja de entrada", "Boîte de réception", "Входящие",
            // sent
            "Sent", "Sent Items", "Gesendete Objekte", "Éléments envoyés", "Posta inviata",
            "Verzonden items", "Elementy wysłane", "Itens enviados", "Отправленные",
            // archive
            "Archive", "Archives", "Archiv", "Archivio", "Archiwum", "Arquivo", "Архив",
            // drafts
            "Drafts", "Entwürfe", "Brouillons", "Bozze", "Concepten", "Rascunhos", "Черновики",
            // junk — a bin that is really the spam folder is filtered out of every list, and many
            // servers empty it on a timer: mail deleted there is mail destroyed on a schedule
            "Junk", "Spam", "Junk E-mail", "Courrier indésirable", "Posta indesiderata",
            "Correo no deseado", "Lixo eletrônico", "Спам",
        ).filter { idFor(it) != null }
        assertEquals(
            "these are folders the user reads, sends from, files into — a delete routed to any of " +
                "them is mail moved somewhere the user did not ask for, or (Junk/Spam) somewhere " +
                "the server purges on its own",
            emptyList<String>(), stolen,
        )
    }
}
