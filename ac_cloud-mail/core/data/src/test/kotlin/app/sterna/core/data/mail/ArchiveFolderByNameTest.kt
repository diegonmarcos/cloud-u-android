package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which folder a JMAP archive falls back on when NO folder carries the `archive` role — the
 */
class ArchiveFolderByNameTest {

    private fun mailbox(id: String, name: String, parentId: String? = null) =
        Mailbox(id = id, name = name, role = null, parentId = parentId)

    @Test fun `no role at all, and the archive is named Archive`() {
        assertEquals(
            "⛔ THE case the bench hit on 2026-08-25: Stalwart answers Archive with role null, so " +
                "the Archive button files by name — and the folder lists must know it does",
            "mb-arch",
            archiveFolderByName(listOf(mailbox("mb-in", "INBOX"), mailbox("mb-arch", "Archive"))),
        )
    }

    @Test fun `a top-level Archiv beats a nested Archives`() {
        assertEquals(
            "a folder named Archives filed under another one is as likely to be someone's stash of " +
                "old archives as the account's own; the root one is the account's archive",
            "mb-root",
            archiveFolderByName(
                listOf(
                    mailbox("mb-nested", "Archives", parentId = "mb-in"),
                    mailbox("mb-root", "Archiv"),
                ),
            ),
        )
    }

    @Test fun `nothing that looks like an archive means no answer`() {
        assertNull(
            "null is what lets the caller CREATE one; answering with an unrelated folder would " +
                "file archived mail into a folder that means something else to the user",
            archiveFolderByName(listOf(mailbox("mb-in", "INBOX"), mailbox("mb-w", "Projet 2019"))),
        )
    }

    @Test fun `the name is matched whatever its case, and beyond English`() {
        assertEquals("mb-x", archiveFolderByName(listOf(mailbox("mb-x", "ARCHIVE"))))
        assertEquals(
            "a Russian account's Архив is the folder the Archive button already files into",
            "mb-ru",
            archiveFolderByName(listOf(mailbox("mb-ru", "Архив"))),
        )
    }
}
