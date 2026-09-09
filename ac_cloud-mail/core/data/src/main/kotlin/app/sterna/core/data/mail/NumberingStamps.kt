package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.db.RowNumbering

/** The IMAP numbering each of [emailIds] was read under, off the cached rows [read] hands back: the
 *  stamp both #99 guards oppose, not the folder-level record. JMAP answers an empty map. The read is
 *  chunked because SQLite refuses past 999 bound variables below Android 12 and this sits outside any
 *  `runCatching`, so an unbounded `IN (...)` would crash the delete. Account scope lives in [read]. */
internal suspend fun numberingStampsOfRows(
    credentials: AccountCredentials,
    emailIds: List<String>,
    read: suspend (List<String>) -> List<RowNumbering>,
): Map<String, Long?> {
    if (credentials.protocol != MailProtocol.IMAP || emailIds.isEmpty()) return emptyMap()
    val rows = byIdsChunked(emailIds) { chunk -> read(chunk) }
    return rows.associate { it.id to it.uidValidity }
}
