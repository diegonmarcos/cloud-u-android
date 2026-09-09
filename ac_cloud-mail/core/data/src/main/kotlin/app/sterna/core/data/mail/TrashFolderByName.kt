package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Mailbox

/**
 * The names SERVERS carry, never the app's own labels. It may never grow into a folder the user
 * reads — a name that also denotes an inbox, archive or spam turns "delete" into "move elsewhere".
 */
val TRASH_FOLDER_NAMES = listOf(
    "trash", "deleted", "deleted items", "deleted messages", "bin", // en (+ en-GB)
    "papierkorb", "gelöschte objekte", "gelöschte elemente", // de
    "papelera", "elementos eliminados", // es
    "corbeille", "éléments supprimés", // fr
    "cestino", "posta eliminata", "elementi eliminati", // it
    "prullenbak", "prullenmand", "verwijderde items", // nl
    "kosz", "elementy usunięte", // pl
    "lixeira", "lixo", "itens excluídos", "itens eliminados", // pt
    "корзина", "удалённые", "удаленные", // ru
    "çöp", "削除済み", "papperskorg", "koš", // beyond the app's locales, common on servers
)

/**
 * Top-level preferred; null lets the caller create a bin rather than move deleted mail into a folder
 * the user reads. JMAP only. [mailboxes] must be THE ACCOUNT'S OWN folders, never the global cache:
 * a delete would otherwise aim at a folder id another account has no row for, and no-op (#31).
 */
fun trashFolderByName(mailboxes: List<Mailbox>): String? =
    mailboxes
        .filter { it.name.lowercase() in TRASH_FOLDER_NAMES }
        .minByOrNull { if (it.parentId == null) 0 else 1 }
        ?.id
