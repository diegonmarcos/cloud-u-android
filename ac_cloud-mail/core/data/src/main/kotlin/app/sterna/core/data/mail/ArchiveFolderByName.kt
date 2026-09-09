package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Mailbox

/** Lowercased folder names that denote an archive: the names servers carry across Sterna's
 *  locales, never the app's own labels. One list for both questions — where the Archive button
 *  files mail, and whether the folder lists may hide it (#174). `lowercase()` with no argument. */
val ARCHIVE_FOLDER_NAMES = listOf(
    "archive", "archives", "archived",
    "archivé", "archivés", "archiv", "archivio",
    "arquivo", "arquivos", "archief", "archiwum", "архив",
)

/** The account's archive folder recognised by name, top-level preferred; null sends the caller to
 *  create one. [mailboxes] must be that account's own folders, never the global cache. JMAP only. */
fun archiveFolderByName(mailboxes: List<Mailbox>): String? =
    mailboxes
        .filter { it.name.lowercase() in ARCHIVE_FOLDER_NAMES }
        .minByOrNull { if (it.parentId == null) 0 else 1 }
        ?.id
