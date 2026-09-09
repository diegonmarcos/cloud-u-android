package app.sterna.core.imap

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

    /**
     * The advanced-search criteria an IMAP `SEARCH` can express server-side (RFC 3501 §6.4.4). The
     */
data class ImapSearchCriteria(
    val text: String = "",
    val from: String = "",
    val recipient: String = "",
    val subject: String = "",
    val flagged: Boolean = false,
    val afterMillis: Long? = null,
    val beforeMillis: Long? = null,
)

    /** A built `SEARCH` argument list: the search keys, plus whether any value carries a non-ASCII
     *  character and therefore needs a `CHARSET UTF-8` declaration. */
data class ImapSearchCommand(val keys: String, val needsUtf8: Boolean) {
    /** The arguments as they go on the wire, after `UID SEARCH `. */
    fun arguments(declareUtf8: Boolean = needsUtf8): String =
        if (declareUtf8) "CHARSET UTF-8 $keys" else keys
}

/** IMAP date-text: `1-Jun-2026`. English month names, never the device locale's. */
private val IMAP_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH)

    /**
     * Build the `UID SEARCH` key list for [criteria] — pure, so the exact bytes we put on the wire are
     */
fun buildImapSearch(criteria: ImapSearchCriteria): ImapSearchCommand {
    val keys = mutableListOf<String>()
    val from = criteria.from.trim()
    val recipient = criteria.recipient.trim()
    val subject = criteria.subject.trim()
    val text = criteria.text.trim()

    if (criteria.flagged) keys += "FLAGGED"
    if (from.isNotEmpty()) keys += "FROM ${quote(from)}"
    if (recipient.isNotEmpty()) keys += "OR TO ${quote(recipient)} CC ${quote(recipient)}"
    if (subject.isNotEmpty()) keys += "SUBJECT ${quote(subject)}"
    criteria.afterMillis?.let { keys += "SINCE ${imapDay(it)}" }
    criteria.beforeMillis?.let { keys += "BEFORE ${imapDay(it, plusDays = 1)}" }
    // Full-text last: it is the most expensive key, and servers evaluate left to right.
    if (text.isNotEmpty()) keys += "TEXT ${quote(text)}"

    val needsUtf8 = listOf(text, from, recipient, subject)
        .any { value -> value.any { it.code > 127 } }
    return ImapSearchCommand(keys = keys.joinToString(" ").ifEmpty { "ALL" }, needsUtf8 = needsUtf8)
}

private fun imapDay(millis: Long, plusDays: Long = 0): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().plusDays(plusDays).format(IMAP_DATE)

    /**
     * One folder's matches. [mailbox] is the folder the UIDs belong to — UIDs mean nothing without it.
     */
data class ImapFolderHits(
    val mailbox: String,
    val messages: List<ImapMessage>,
    val incomplete: Boolean = false,
        /**
         * The folder's UIDVALIDITY as the SELECT of this walk stated it, or 0 when it stated none.
         */
    val uidValidity: Long = 0L,
)

/** How many UIDs are fetched per `UID FETCH` while walking candidates for the attachment filter. */
private const val SEARCH_FETCH_BATCH = 50

    /** Ceiling on how many candidate messages one folder is fetched-and-filtered for when the
     *  attachment filter is on. Without it a broad query on a huge folder would pull every matching
     *  envelope over a mobile link looking for the few with a file attached. */
private const val ATTACHMENT_SCAN_CAP = 1_000

    /**
     * Search [mailboxes] one after another on THIS session and return each folder's matches, newest UID
     */
fun ImapSession.searchFolders(
    mailboxes: List<String>,
    command: ImapSearchCommand,
    requireAttachment: Boolean,
    limit: Int,
    stillWanted: () -> Boolean = { true },
    onFolderError: (mailbox: String, error: Throwable) -> Unit = { _, _ -> },
): List<ImapFolderHits> {
    if (limit <= 0) return emptyList()
    val hits = mutableListOf<ImapFolderHits>()
    for (mailbox in mailboxes) {
        if (!stillWanted()) break
            // What the SELECT states about this folder's numbering, kept instead of dropped: see
            // [ImapFolderHits.uidValidity]. 0 for a folder whose SELECT failed.
        var observed = 0L
        val scan = try {
            observed = select(mailbox).uidValidity
            searchOneFolder(command, requireAttachment, limit)
        } catch (t: Throwable) {
            onFolderError(mailbox, t)
            hits += ImapFolderHits(mailbox, emptyList(), incomplete = true)
            continue
        }
        if (scan.messages.isNotEmpty() || scan.truncated) {
            hits += ImapFolderHits(mailbox, scan.messages, incomplete = scan.truncated, uidValidity = observed)
        }
    }
    return hits
}

/** One folder's scan: what it kept, and whether it stopped looking with candidates left over. */
private class FolderScan(val messages: List<ImapMessage>, val truncated: Boolean)

/** Search the ALREADY SELECTed folder and fetch up to [limit] matching envelopes, newest first. */
private fun ImapSession.searchOneFolder(
    command: ImapSearchCommand,
    requireAttachment: Boolean,
    limit: Int,
): FolderScan {
    val uids = searchUids(command).sortedDescending()
    if (uids.isEmpty()) return FolderScan(emptyList(), truncated = false)
    // No local filter: the newest [limit] UIDs are exactly the answer, in one FETCH.
    if (!requireAttachment) {
        return FolderScan(fetchUids(uids.take(limit)).sortedByDescending { it.uid }, truncated = false)
    }

    val kept = mutableListOf<ImapMessage>()
    var examined = 0
    for (chunk in uids.chunked(SEARCH_FETCH_BATCH)) {
        if (kept.size >= limit || examined >= ATTACHMENT_SCAN_CAP) break
        examined += chunk.size
        kept += fetchUids(chunk).sortedByDescending { it.uid }.filter { it.hasAttachment }
    }
    // Stopping with candidates left AND the cap unfilled means the scan cap cut the walk short:
    // the folder may hold matches nobody looked at, so the answer is not a total.
    return FolderScan(kept.take(limit), truncated = examined < uids.size && kept.size < limit)
}
