package app.sterna.ui.inbox

import androidx.paging.PagingData
import androidx.paging.TerminalSeparatorType
import androidx.paging.filter
import androidx.paging.insertHeaderItem
import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.InboxRow
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant

/*
 * The drafts this phone holds and the server has not got, at the top of the Drafts folder (#95).
 * No state filter: a `STAGING` row is listed like any other. What a local row does not get is an
 * action — see [isLocalDraftRow].
 */

/** The role a folder must carry for its list to show the phone's own drafts. */
private const val DRAFTS_ROLE = "drafts"

/** The two-line preview of a draft body is cut here — the row shows at most two lines anyway. */
private const val PREVIEW_CHARS = 200

/** What the phone's own drafts contribute: the rows to draw on top, and the server ids they stand in
 * for. The two travel together — a local row shown without hiding the draft it replaces is the
 *  same draft twice, and the copy the user reaches for is the one the upload is about to destroy. */
internal data class LocalDraftRows(
    val rows: List<InboxRow> = emptyList(),
    val replacedServerIds: Set<String> = emptySet(),
)

/** True when [id] names a row of `local_drafts`, not a message a server knows about. The one
 *  predicate every gesture site asks, and it neutralises rather than routes: no action path knows
 *  this table, so a swipe announces "deleted" over a row that never moved. Only the tap acts (#95). */
internal fun isLocalDraftRow(id: String?): Boolean = id != null && id.startsWith(LOCAL_DRAFT_ID_PREFIX)

/** Whether the list on screen is the one place the phone's own drafts belong: this account's Drafts
 *  folder, funnel off. Answered from the folder-role map, never a [PageKey] member, each of which
 * rebuilds the pager. [unreadOnly] refuses: a local row is read by construction. */
internal fun showsLocalDrafts(
    accountId: String?,
    mailboxId: String?,
    roles: Map<Pair<String, String>, String>,
    unreadOnly: Boolean,
): Boolean {
    if (accountId == null || mailboxId == null || unreadOnly) return false
    return roles[accountId to mailboxId] == DRAFTS_ROLE
}

/** Whether the row drawn for [id] answers to a swipe at all. The guard sits on `gesturesEnabled`,
 *  before the gesture runs: the refusal inside `performSwipe` is a belt, `commitSwipe` playing the
 *  take-off arc first, so a swipeable local draft is destroyed on screen with no Undo (#126). */
internal fun rowGesturesEnabled(id: String?, selectionActive: Boolean): Boolean =
    !selectionActive && !isLocalDraftRow(id)

/** Whether the row drawn for [id] gets a star. `toggleFlag` over a local id does nothing on IMAP and
 *  fails on JMAP, so it gets none: an affordance that cannot act is a lie (WYSIWYG). */
internal fun showsFavouriteStar(id: String?): Boolean = !isLocalDraftRow(id)

/** The keys "Select all" may take: the folder's own, minus the server rows a local draft stands in
 * for and which are therefore not on screen. The destructive half of #126, re-opened by our own
 *  hiding: `selectableIds` pages `emails`, where a replaced server draft is still filed. */
internal fun selectableKeysMinusHidden(folderKeys: List<EmailKey>, replacedServerIds: Set<String>): List<EmailKey> =
    if (replacedServerIds.isEmpty()) folderKeys else folderKeys.filter { it.emailId !in replacedServerIds }

/** What `local_drafts` contributes to the list currently selected — nothing outside the Drafts
 * folder of the account those rows belong to. The scope is deduped before the store is
 *  subscribed: the role map re-emits for unrelated reasons, each re-subscribing the DAO. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun localDraftRowsFlow(
    currentAccountId: Flow<String?>,
    selectedMailboxId: Flow<String?>,
    folderRoles: Flow<Map<Pair<String, String>, String>>,
    unreadOnly: Flow<Boolean>,
    localDrafts: (String) -> Flow<List<LocalDraftEntity>>,
): Flow<LocalDraftRows> =
    combine(currentAccountId, selectedMailboxId, folderRoles, unreadOnly) { accountId, mailboxId, roles, unread ->
        if (showsLocalDrafts(accountId, mailboxId, roles, unread)) accountId!! to mailboxId!! else null
    }
        .distinctUntilChanged()
        .flatMapLatest { scope ->
            if (scope == null) {
                flowOf(LocalDraftRows())
            } else {
                localDrafts(scope.first).map { drafts ->
                    LocalDraftRows(
                        rows = drafts.map { it.toInboxRow(scope.second) },
                        replacedServerIds = replacedServerIds(drafts),
                    )
                }
            }
        }

/** The server drafts the given local rows stand in for. A draft reopened and re-saved offline
 *  carries [LocalDraftEntity.replacesEmailId] and leaves the server copy where it is until the
 *  upload lands, so both on screen is the same draft twice. */
internal fun replacedServerIds(drafts: List<LocalDraftEntity>): Set<String> =
    drafts.mapNotNullTo(mutableSetOf()) { it.replacesEmailId }

/** True when [row] is the server copy a local draft already stands in for, i.e. must not be drawn.
 * Only a row that stands for itself: conversation view is the default, and hiding a group's
 *  representative hides its conversation. A draft shown twice is recoverable; a lost one is not. */
internal fun hidesServerRow(row: InboxRow, replaced: Set<String>): Boolean =
    row.threadCount == 1 && row.email.id in replaced

/** The order local rows go to `insertHeaderItem`: each call prepends one item, so a newest-first
 *  list applied front to back would leave the oldest draft on top. */
internal fun headerInsertionOrder(rows: List<InboxRow>): List<InboxRow> = rows.reversed()

/** The paged list as the Drafts folder must read it. Applied strictly downstream of `cachedIn`,
 * in a property only `InboxScreen` collects, or a local id lands in the reader's pager. The
 *  caller combines, never maps: the Room `PagingSource` does not observe `local_drafts`. */
internal fun withLocalDrafts(paged: PagingData<InboxRow>, local: LocalDraftRows): PagingData<InboxRow> {
    var data = paged.filter { row -> !hidesServerRow(row, local.replacedServerIds) }
    for (row in headerInsertionOrder(local.rows)) {
        data = data.insertHeaderItem(terminalSeparatorType = TerminalSeparatorType.SOURCE_COMPLETE, item = row)
    }
    return data
}

/** One row of `local_drafts` as the list draws it. `$draft` gets the "(Draft)" chip, `$seen` keeps
 *  the row out of bold, threadCount 1 makes it unexpandable — it is in no known conversation. */
internal fun LocalDraftEntity.toInboxRow(mailboxId: String): InboxRow = InboxRow(
    email = Email(
        id = id,
        accountId = accountId,
        mailboxId = mailboxId,
        subject = subject,
        preview = localDraftPreview(textBody),
        receivedAt = Instant.ofEpochMilli(updatedAtMillis).toString(),
        to = localDraftAddresses(toAddresses),
        cc = localDraftAddresses(cc),
        bcc = localDraftAddresses(bcc),
        keywords = mapOf("\$draft" to true, "\$seen" to true),
    ),
    threadCount = 1,
    unread = false,
)

/** A comma-separated address column as the list shows it, blanks dropped. */
private fun localDraftAddresses(column: String?): List<EmailAddress> =
    column.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { EmailAddress(email = it) }

/** The preview of a draft's body: whitespace collapsed, cut to [PREVIEW_CHARS]. */
private fun localDraftPreview(textBody: String): String =
    textBody.replace(Regex("""\s+"""), " ").trim().take(PREVIEW_CHARS)
