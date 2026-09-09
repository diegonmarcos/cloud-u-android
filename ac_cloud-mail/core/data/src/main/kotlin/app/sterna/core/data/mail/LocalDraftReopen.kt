package app.sterna.core.data.mail

import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.data.db.LocalDraftState
import app.sterna.core.data.text.Block
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import app.sterna.core.data.text.richBodyFrom
import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/*
 * Reopening a draft the phone holds and the server has not (#95), without asking a server about a
 * draft it has never heard of.
 */

enum class DraftOpenRoute {
    NONE,

    /** A row of `local_drafts`: this phone is the only place the text exists. */
    LOCAL,

    SERVER,
}

/** A decision, not a null check: on `draftId != null` a local id takes the SERVER path and fails. */
fun draftOpenRoute(draftId: String?): DraftOpenRoute = when {
    draftId == null -> DraftOpenRoute.NONE
    draftId.startsWith(LOCAL_DRAFT_ID_PREFIX) -> DraftOpenRoute.LOCAL
    else -> DraftOpenRoute.SERVER
}

data class LocalDraftPrefill(
    /** The row's own id, never the server draft it stands in for: `localDraftTarget` re-saves onto it. */
    val editingDraftId: String,
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val body: String,
    val bodyRanges: Map<Inline, List<Span>> = emptyMap(),
    /** The list blocks [body] carries (#131). Dropped here, the next save stores the removal. */
    val bodyBlocks: List<Block> = emptyList(),
    val bodyLinks: List<Link> = emptyList(),
    /** Reveal the Cc/Bcc row, so a draft that carried them does not hide them on reopen. */
    val expand: Boolean,
    val fromEmail: String?,
    val inReplyTo: List<String>,
    val references: List<String>,
    val attachments: List<EmailBodyPart>,
    /** A lossy reading of the message it replaces, carried so a re-save cannot launder it (#63). */
    val bodyIsLossy: Boolean,
    /** Never "the draft being edited": consuming the row alone unmasks it, pre-edit (#95 × #69). */
    val replacedServerDraftId: String?,
    /** The numbering [replacedServerDraftId] was named under, frozen at the write (#99). */
    val replacedServerDraftUidValidity: Long?,
)

/**
 * Read off the row and nothing else — the id names a row no server has heard of. `htmlBody` is read
 * first (#131); `requestReceipt` is not projected (stated debt D1).
 */
fun localDraftPrefill(row: LocalDraftEntity): LocalDraftPrefill {
    val cc = localDraftAddresses(row.cc)
    val bcc = localDraftAddresses(row.bcc)
    // Both halves out of one answer, or two disagreeing columns open spans over the wrong letters.
    val rich: RichBody = richBodyFrom(row.htmlBody) { row.textBody }
    return LocalDraftPrefill(
        editingDraftId = row.id,
        to = localDraftAddresses(row.toAddresses).joinToString(", "),
        cc = cc.joinToString(", "),
        bcc = bcc.joinToString(", "),
        subject = row.subject,
        body = rich.text,
        bodyRanges = rich.ranges,
        bodyBlocks = rich.blocks,
        bodyLinks = rich.links,
        expand = cc.isNotEmpty() || bcc.isNotEmpty(),
        fromEmail = row.fromEmail,
        inReplyTo = localDraftMessageIds(row.inReplyTo),
        references = localDraftMessageIds(row.references),
        attachments = localDraftUploadAttachments(row.attachmentsJson),
        bodyIsLossy = row.bodyIsLossy,
        replacedServerDraftId = row.replacesEmailId,
        replacedServerDraftUidValidity = row.replacesUidValidity,
    )
}

private fun localDraftMessageIds(column: String?): List<String> =
    column.orEmpty().split(" ").map { it.trim() }.filter { it.isNotEmpty() }

/** Three answers (#95): a GONE row was consumed or thrown away, a BUSY one is whole and openable. */
sealed interface LocalDraftEdit {
    data class Taken(val row: LocalDraftEntity) : LocalDraftEdit

    object Gone : LocalDraftEdit

    /** Someone else owns it: releasing a row never held hands their draft to the upload worker. */
    object Busy : LocalDraftEdit
}

/**
 * Everything but the two with a real, concurrent owner: UPLOADING belongs to the worker, STAGING
 */
fun localDraftMayBeTakenForEdit(state: LocalDraftState): Boolean = when (state) {
    LocalDraftState.PENDING, LocalDraftState.EDITING -> true
    LocalDraftState.STAGING, LocalDraftState.UPLOADING -> false
}

/**
 * Nothing but [giveLocalDraftEditBack] ever unsets `EDITING`, the startup sweep included. A refused
 * row is answered [LocalDraftEdit.Busy] and not written at all.
 */
internal suspend fun takeLocalDraftEdit(
    id: String,
    load: suspend (String) -> LocalDraftEntity?,
    setState: suspend (LocalDraftState) -> Unit,
): LocalDraftEdit {
    val row = load(id) ?: return LocalDraftEdit.Gone
    if (!localDraftMayBeTakenForEdit(row.state)) return LocalDraftEdit.Busy
    setState(LocalDraftState.EDITING)
    return LocalDraftEdit.Taken(row)
}

/**
 * The scope can die after Room wrote `EDITING` and before the caller resumed (#95), leaving the row
 */
suspend fun takeLocalDraftEditOrGiveItBack(
    id: String,
    take: suspend (String) -> LocalDraftEdit,
    arm: (LocalDraftEntity) -> Unit,
    giveBack: suspend (String) -> Unit,
): LocalDraftEdit {
    val lease = try {
        take(id)
    } catch (cancelled: CancellationException) {
        // The row may already be EDITING with nobody left to remember it.
        withContext(NonCancellable) { giveBack(id) }
        throw cancelled
    }
    // Nothing suspends before [arm], so the id cannot fail to be remembered.
    if (lease is LocalDraftEdit.Taken) arm(lease.row)
    return lease
}

/**
 * The only way out of [LocalDraftState.EDITING], since a cold start cannot tell a row a restored
 */
internal suspend fun giveLocalDraftEditBack(
    id: String,
    nowMillis: Long,
    load: suspend (String) -> LocalDraftEntity?,
    setState: suspend (LocalDraftState) -> Unit,
    schedule: suspend (Long) -> Unit,
) {
    val row = load(id) ?: return
    if (row.state != LocalDraftState.EDITING) return
    setState(LocalDraftState.PENDING)
    schedule(localDraftScheduleDelayMillis(row.notBeforeMillis, nowMillis))
}

/**
 * Never one of this phone's own ids (#95), which the delivery worker would destroy under a name no
 * server issued. The local row is consumed on the phone instead, by [commitThenConsumeLocalDraft].
 */
fun sendDraftEmailId(draftEmailId: String?): String? = serverDraftReplacedBy(draftEmailId)

/**
 * The id and the IMAP numbering it was named under, one answer and never two decisions (#95 × #99).
 * Crossed, they delete another message of Drafts, or nothing while the app believes they did.
 */
data class SendDraftTarget(
    val emailId: String?,
    val uidValidity: Long?,
)

/**
 * Reopened from a row, [editingDraftId] is the row's id, for which [sendDraftEmailId] answers null
 */
fun sendDraftTarget(
    editingDraftId: String?,
    editingDraftUidValidity: Long?,
    replacedServerDraftId: String?,
    replacedServerDraftUidValidity: Long?,
): SendDraftTarget {
    sendDraftEmailId(editingDraftId)?.let { return SendDraftTarget(it, editingDraftUidValidity) }
    val replaced = sendDraftEmailId(replacedServerDraftId)
        ?: return SendDraftTarget(null, null)
    return SendDraftTarget(replaced, replacedServerDraftUidValidity)
}

/**
 * Withheld whole when the composer cannot show the message reproduces the draft (#63). The scheduled
 * table has no column for that verdict, so the id carries it: an id means "destroy authorised".
 */
fun SendDraftTarget.unlessBodyIsLossy(bodyIsLossy: Boolean): SendDraftTarget =
    if (bodyIsLossy) SendDraftTarget(null, null) else this

/**
 * Withheld whole when the draft was read under one account and the message is written under another
 */
fun SendDraftTarget.unlessDraftBelongsElsewhere(
    draftAccountId: String?,
    writingAccountId: String?,
): SendDraftTarget =
    if (draftAccountId != null && draftAccountId != writingAccountId) SendDraftTarget(null, null)
    else this

/**
 * Not `serverDraftAccountId ?: localRowAccountId`: the two are mutually exclusive today, and a `?:`
 * would be right by that coincidence alone the day one route arms both.
 */
fun carriedDraftAccountId(
    editingDraftId: String?,
    serverDraftAccountId: String?,
    localRowAccountId: String?,
): String? =
    if (sendDraftEmailId(editingDraftId) != null) serverDraftAccountId else localRowAccountId

/** The queued outbox row (#70) and the phone's own draft row (#95), decided together but apart. */
data class AbandonPlan(
    val localDraftToRelease: String?,
    val outboxToRelease: Long?,
    /** The outbox row to PARK instead. Never the same row as [outboxToRelease]: one row, one door. */
    val outboxToPark: Long?,
)

/**
 * [sending] gives back nothing: releasing the local row from under a send in flight has the upload
 */
fun abandonPlan(
    sending: Boolean,
    emptying: Boolean,
    parkOutbox: Boolean,
    localDraftId: String?,
    outboxId: Long?,
): AbandonPlan {
    if (sending) return AbandonPlan(null, null, null)
    val release = if (parkOutbox) null else outboxId
    val park = if (parkOutbox) outboxId else null
    if (emptying) return AbandonPlan(null, release, park)
    return AbandonPlan(localDraftId, release, park)
}

/**
 * A save closes the composer without passing through `abandon`, having rewritten the row itself —
 */
fun localDraftLeaseAfterSave(lease: String?, saved: Boolean): String? =
    if (saved) lease else null

/**
 * The row is the only copy of the text and [commit] creates the other one (#95), so consumed first,
 */
suspend fun <T> commitThenConsumeLocalDraft(
    localDraftId: String?,
    commit: suspend () -> T,
    consume: suspend (String) -> Unit,
): T {
    // Read before the shelter: nothing is committed yet, so a screen already gone takes nothing.
    coroutineContext.ensureActive()
    return withContext(NonCancellable) {
        val committed = commit()
        if (localDraftId != null) consume(localDraftId)
        committed
    }
}
