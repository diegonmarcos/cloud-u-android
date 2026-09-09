package app.sterna.ui.inbox

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.mail.CrossAccountMove
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.jmap.model.Mailbox
import java.text.Collator

/** True when the folder on screen has left the account's folder list, so the list falls back to
 *  the Inbox (#89). An empty list means "not known yet", never "the folder is gone". */
internal fun selectionIsGone(selectedMailboxId: String?, mailboxes: List<Mailbox>): Boolean {
    if (selectedMailboxId == null) return false
    if (mailboxes.isEmpty()) return false
    return mailboxes.none { it.id == selectedMailboxId }
}

/** True when the folder on screen is gone (#89) or hidden by "subscribed folders only" (#174).
 * Both lists are judged; elsewhere a hidden folder is still a real folder. */
internal fun selectionIsUnreachable(
    selectedMailboxId: String?,
    mailboxes: List<Mailbox>,
    showOnlySubscribed: Boolean,
): Boolean =
    selectionIsGone(selectedMailboxId, mailboxes) ||
        selectionIsGone(selectedMailboxId, visibleFolders(mailboxes, showOnlySubscribed))

/** The folder a tapped notification may switch the list to, or null when it is unreachable (#174).
 * [mailboxes] is the whole list, here and at `resolve`: it is the result that is judged. */
internal fun notificationFolderToShow(
    resolvedMailboxId: String?,
    mailboxes: List<Mailbox>,
    showOnlySubscribed: Boolean,
): String? {
    val target = resolvedMailboxId ?: return null
    if (selectionIsUnreachable(target, mailboxes, showOnlySubscribed)) return null
    return target
}

/** Lead order of the standard folders in a picker; a folder the user made shares last rank (#25). */
private fun roleRank(role: String?): Int = when (role) {
    "inbox" -> 0
    "drafts" -> 1
    "sent" -> 2
    "junk" -> 3
    "archive", "all" -> 4
    "trash" -> 5
    else -> 6
}

/** The folders a move-to-folder picker offers: [mailboxes] minus the current folder and what
 * [showOnlySubscribed] hides (#174), standard first then the user's own by path. Only the
 *  targets may be filtered, or a child whose parent is hidden loses a path segment (#109). */
internal fun moveTargets(
    mailboxes: List<Mailbox>,
    currentMailboxId: String?,
    showOnlySubscribed: Boolean = false,
    collator: Collator = Collator.getInstance(),
    ancestorsOf: (Mailbox, List<Mailbox>) -> List<String> = ::mailboxAncestors,
): List<Mailbox> =
    visibleFolders(mailboxes, showOnlySubscribed).filter { it.id != currentMailboxId }
        .map { it to (ancestorsOf(it, mailboxes) + it.name) }
        .sortedWith(
            compareBy<Pair<Mailbox, List<String>>> { roleRank(it.first.role) }
                .thenComparator { a, b -> comparePath(a.second, b.second, collator) },
        )
        .map { it.first }

/** Two folder paths compared segment by segment: joined, they would sort on the separator and
 * "Work_Notes" would land between "Work" and "Work/2026". [collator], not [String.compareTo]. */
private fun comparePath(a: List<String>, b: List<String>, collator: Collator): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val order = collator.compare(a[i], b[i])
        if (order != 0) return order
    }
    return a.size - b.size
}

/** The ancestors of [mailbox], outermost first — empty at the root. On IMAP read off the id,
 *  never resolved as mailboxes: a `\Noselect` parent is absent yet still contains its children. */
internal fun mailboxAncestors(mailbox: Mailbox, mailboxes: List<Mailbox>): List<String> {
    val (chain, _) = parentNameChain(mailbox, mailboxes)
    if (chain.isNotEmpty()) return chain
    val (parentPath, delimiter) = imapParentPath(mailbox) ?: return emptyList()
    return parentPath.split(delimiter).filter { it.isNotEmpty() }
}

/** The path a server-side rule must name to file mail into [mailbox] — a Sieve `fileinto` value,
 *  never translated, and whole: a leaf alone names the wrong folder as soon as two share it. Null
 *  when it cannot be named with certainty. */
internal fun mailboxFilePath(mailbox: Mailbox, mailboxes: List<Mailbox>): String? {
    val (chain, rooted) = parentNameChain(mailbox, mailboxes)
    if (!rooted) return null
    if (chain.isNotEmpty()) {
        val segments = chain + mailbox.name
        if (segments.any { JMAP_PATH_SEPARATOR in it }) return null
        return segments.joinToString(JMAP_PATH_SEPARATOR)
    }
    if (imapParentPath(mailbox) != null) return mailbox.id
    return mailbox.name
}

/** The line under a folder's name in a picker, so two folders sharing a leaf can be told apart
 *  (#109). Ancestors go through [stripBidiAndControls]: IMAP only ever filtered the leaf (#101). */
internal fun mailboxPathLabel(mailbox: Mailbox, mailboxes: List<Mailbox>): String? {
    val role = mailbox.role
    if (role != null && role in TRANSLATED_ROLES) return null
    val ancestors = mailboxAncestors(mailbox, mailboxes)
        .map(::stripBidiAndControls)
        .filter { it.isNotBlank() }
    if (ancestors.isEmpty()) return null
    return elideOutermost(ancestors)
}

/** Separator assumed between two segments of a JMAP mailbox path on the wire. Not established —
 *  JMAP has no path syntax — and unverified against a live server; single place to change. */
private const val JMAP_PATH_SEPARATOR = "/"

/** Separator between two ancestors on screen — spaced, so it cannot be read as part of a name. */
private const val PATH_LABEL_SEPARATOR = " / "

private const val ELLIPSIS = "…"

/** How much of a parent path a picker row shows before its outermost ancestors are dropped. */
private const val PATH_LABEL_MAX_CHARS = 40

/** Shorten a parent path from the outside in: the nearest ancestor tells one "Done" from another,
 *  so eliding at the end drops the segment the row exists to show (Compose's start elision does not
 *  exist here). A lone over-budget ancestor is cut in the middle, keeping both discriminants. */
private fun elideOutermost(ancestors: List<String>): String {
    var kept = ancestors
    while (kept.size > 1 &&
        kept.joinToString(PATH_LABEL_SEPARATOR).length + ELLIPSIS.length > PATH_LABEL_MAX_CHARS
    ) {
        kept = kept.drop(1)
    }
    val dropped = kept.size < ancestors.size
    val budget = if (dropped) PATH_LABEL_MAX_CHARS - ELLIPSIS.length else PATH_LABEL_MAX_CHARS
    val text = elideMiddle(kept.joinToString(PATH_LABEL_SEPARATOR), budget)
    return if (dropped) ELLIPSIS + text else text
}

/** [text] shortened to [maxChars] by taking out its middle, keeping both ends. */
private fun elideMiddle(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val kept = maxChars - ELLIPSIS.length
    return text.take(kept - kept / 2) + ELLIPSIS + text.takeLast(kept / 2)
}

/** The `parentId` chain of names above [mailbox], and whether the walk reached the root: it stops
 *  short on a missing parent or a loop, and a path cut halfway names another folder, silently. */
private fun parentNameChain(
    mailbox: Mailbox,
    mailboxes: List<Mailbox>,
): Pair<List<String>, Boolean> {
    val parentId = mailbox.parentId ?: return emptyList<String>() to true
    val byId = mailboxes.associateBy { it.id }
    val chain = ArrayDeque<String>()
    val seen = mutableSetOf(mailbox.id)
    var parent = byId[parentId]
    while (parent != null) {
        if (!seen.add(parent.id)) return chain.toList() to false
        chain.addFirst(parent.name)
        val next = parent.parentId ?: return chain.toList() to true
        parent = byId[next]
    }
    return chain.toList() to false
}

/** The parent path an IMAP id carries, with its delimiter — null when the id is not a path. The
 *  delimiter is read off the id, not guessed: "Foo/Bar" on a dot-delimited server would be cut. */
private fun imapParentPath(mailbox: Mailbox): Pair<String, Char>? {
    val id = mailbox.id
    val name = mailbox.name
    if (name.isEmpty() || id.length <= name.length + 1 || !id.endsWith(name)) return null
    val delimiter = id[id.length - name.length - 1]
    if (delimiter != '/' && delimiter != '.') return null
    return id.take(id.length - name.length - 1).takeIf { it.isNotEmpty() }?.let { it to delimiter }
}

/** The roles [mailboxDisplayName] answers with a localized label — keep the two in step. */
private val TRANSLATED_ROLES = setOf(
    "inbox", "archive", "drafts", "sent", "junk", "trash", "all", "flagged", "important",
)

/** Drop control characters and bidi overrides from a path segment about to be shown: IMAP filters
 *  the folder name (#101) but not the path. Duplicated — the original is internal to `:core:imap`. */
private fun stripBidiAndControls(s: String): String = s.filterNot { c ->
    val code = c.code
    (code in 0x00..0x08) || (code in 0x0B..0x1F) || (code in 0x7F..0x9F) ||
        (code in 0x202A..0x202E) || (code in 0x2066..0x2069) || code == 0x200F || code == 0x200E
}

/** One row of a move-to-folder picker, already painted: folder, displayed name, parent path. */
internal data class FolderPickerRow(val folder: Mailbox, val name: String, val path: String?)

/** The rows a move-to-folder picker still shows once [query] is typed (#182). It filters and
 * never reorders, as promised on the thread. Case is folded by `ignoreCase`, never by a
 *  default-locale fold: in Turkish "I" folds to "ı". Rows arrive painted, not raw. */
internal fun filterFolderRows(rows: List<FolderPickerRow>, query: String): List<FolderPickerRow> {
    if (query.isEmpty()) return rows
    return rows.filter { row ->
        row.name.contains(query, ignoreCase = true) ||
            row.path?.contains(query, ignoreCase = true) == true
    }
}

// ── a move into ANOTHER account's folder (#189) ───────────────────────────────────────────────

/** The account whose folders the picker lists: the one chosen on its account row, else the owner,
 *  so [moveTargets] is handed one account's list (#92). */
internal fun pickerAccount(chosen: String?, owner: String?): String? = chosen ?: owner

/** The folder the picker leaves out, but only while listing the owner's own folders: in another
 *  account that id names nothing, or a homonym (#92). */
internal fun pickerExcludedMailbox(chosen: String?, owner: String?, current: String?): String? =
    if (chosen == null || chosen == owner) current else null

/** What the account row records when [picked] is tapped: null for the owner, so picking the
 *  message's own account back is the state the picker opened in; the id of any other. */
internal fun pickerChoice(picked: String, owner: String?): String? = picked.takeIf { it != owner }

/** The account row's order: the owner first, then the others as the store lists them. */
internal fun pickerAccounts(accounts: List<StoredAccount>, owner: String?): List<StoredAccount> {
    val lead = accounts.firstOrNull { it.id == owner } ?: return accounts
    return listOf(lead) + accounts.filter { it.id != owner }
}

/** Whether the move leaves the message's account. False when none was chosen or it is the owner's:
 *  the gesture then takes the same-account path, Undo included. */
internal fun isCrossAccountMove(targetAccountId: String?, ownerAccountId: String?): Boolean =
    targetAccountId != null && targetAccountId != ownerAccountId

/** The one banner a multi-message move between accounts ends on. */
internal enum class CrossAccountOutcome { ALL_MOVED, SOME_COPIED_NOT_REMOVED, PARTLY_FAILED, ALL_FAILED }

/** Which banner: a copy whose original stayed wins over a failure, then a batch where nothing was
 * confirmed written on B, then a partial failure, then "all moved". The existential branch must
 * come after the total one, and [results] must be non-empty — `all {}` is true of nothing. */
internal fun crossAccountBanner(results: List<CrossAccountMove>, skipped: Int): CrossAccountOutcome = when {
    results.any { it is CrossAccountMove.CopiedNotRemoved } -> CrossAccountOutcome.SOME_COPIED_NOT_REMOVED
    results.isNotEmpty() && results.all { it is CrossAccountMove.Failed } -> CrossAccountOutcome.ALL_FAILED
    skipped > 0 || results.any { it is CrossAccountMove.Failed } -> CrossAccountOutcome.PARTLY_FAILED
    else -> CrossAccountOutcome.ALL_MOVED
}

/** Which keys of a cross-account move go back under the tick (#189): every key whose verdict wrote
 * nothing on B and whose row is still in [cached]. "Nothing on B", not "stayed on A" — a
 * CopiedNotRemoved handed back would be copied to B twice. Indexed on the verdict. */
internal fun crossAccountGiveBack(
    outcomes: Map<EmailKey, CrossAccountMove>,
    cached: Set<EmailKey>,
): Set<EmailKey> = outcomes
    .filterValues { it !is CrossAccountMove.Moved && it !is CrossAccountMove.CopiedNotRemoved }
    .keys.filterTo(LinkedHashSet()) { it in cached }

/** "Account › Folder", the way the banners name where a message went. */
internal fun crossAccountTargetLabel(account: String, folder: String): String = "$account › $folder"

/** The line one message's cross-account move leaves behind (#189), chosen by the verdict alone: a
 *  "Moved to B › Archive" over a [CrossAccountMove.CopiedNotRemoved] sends the user away from a
 *  message that never left A. A failure carries no throwable, so [online] is the caller's. */
@StringRes
internal fun crossAccountMessageRes(move: CrossAccountMove, online: Boolean): Int = when (move) {
    CrossAccountMove.Moved -> R.string.status_moved_to_account
    is CrossAccountMove.CopiedNotRemoved -> R.string.status_copied_not_removed
    is CrossAccountMove.Failed -> if (online) R.string.status_action_failed else R.string.status_action_offline
}

/** The plural a selection's banner counts with (#189); null for the two outcomes that are plain
 *  strings, neither `getQuantityString` nor `getString` accepting the other's id. */
@PluralsRes
internal fun crossAccountSelectionPlural(outcome: CrossAccountOutcome): Int? = when (outcome) {
    CrossAccountOutcome.ALL_MOVED -> R.plurals.status_selection_moved
    CrossAccountOutcome.SOME_COPIED_NOT_REMOVED -> R.plurals.status_selection_copied_not_removed
    CrossAccountOutcome.PARTLY_FAILED, CrossAccountOutcome.ALL_FAILED -> null
}

/** The plain line a selection's banner ends on when the batch did not go through whole — null for
 * the two that count. Two outcomes, two sentences, never one branch: "some messages couldn't be
 *  processed" over a batch where nothing moved claims part of it travelled, and there is no Undo. */
@StringRes
internal fun crossAccountSelectionFailedRes(outcome: CrossAccountOutcome): Int? = when (outcome) {
    CrossAccountOutcome.PARTLY_FAILED -> R.string.status_action_partly_failed
    CrossAccountOutcome.ALL_FAILED -> R.string.status_action_failed
    CrossAccountOutcome.ALL_MOVED, CrossAccountOutcome.SOME_COPIED_NOT_REMOVED -> null
}

/** The translated name of a folder with a role, or null for one called by its own name — the rule
 *  behind `mailboxDisplayName` (composition) and `mailboxLabel` (ViewModel). */
@StringRes
internal fun mailboxRoleNameRes(role: String?): Int? = when (role) {
    "inbox" -> R.string.folder_inbox
    "archive" -> R.string.folder_archive
    "drafts" -> R.string.folder_drafts
    "sent" -> R.string.folder_sent
    "junk" -> R.string.folder_junk
    "trash" -> R.string.folder_trash
    "all" -> R.string.folder_all
    "flagged" -> R.string.folder_flagged
    "important" -> R.string.folder_important
    else -> null
}

/** [mailboxDisplayName] outside composition — how the ViewModel's banners name a folder. */
internal fun mailboxLabel(context: Context, role: String?, name: String): String =
    mailboxRoleNameRes(role)?.let(context::getString) ?: name
