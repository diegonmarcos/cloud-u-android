package app.sterna.core.data.mail

import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.data.db.LocalDraftState
import app.sterna.core.data.db.OutboxAttachment
import app.sterna.core.data.db.OutboxAttachments
import app.sterna.core.data.db.newLocalDraftId
import app.sterna.core.imap.OutgoingAttachment
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/*
 * Saving a draft on the phone first (#95): one path, online or off. There is deliberately no
 * "network, else local" branch — two paths would not make the text durable before the attempt.
 */

internal data class LocalDraftTarget(val id: String, val existing: LocalDraftEntity?)

/**
 * Found by both keys: a [LOCAL_DRAFT_ID_PREFIX] id re-saves onto its own row, a server id is looked
 */
internal suspend fun localDraftTarget(
    replacesEmailId: String?,
    byId: suspend (String) -> LocalDraftEntity?,
    forServerDraft: suspend (String) -> LocalDraftEntity?,
    mint: () -> String = ::newLocalDraftId,
): LocalDraftTarget {
    if (replacesEmailId == null) return LocalDraftTarget(mint(), null)
    if (replacesEmailId.startsWith(LOCAL_DRAFT_ID_PREFIX)) {
        return LocalDraftTarget(replacesEmailId, byId(replacesEmailId))
    }
    val existing = forServerDraft(replacesEmailId)
    return LocalDraftTarget(existing?.id ?: mint(), existing)
}

/**
 * The server draft a save replaces, or null. A local id handed on as `replacesEmailId` reaches
 * `destroyDraft` under a name no server issued — on IMAP a number, which always names something.
 */
internal fun serverDraftReplacedBy(replacesEmailId: String?): String? =
    replacesEmailId?.takeUnless { it.startsWith(LOCAL_DRAFT_ID_PREFIX) }

/**
 * A fresh `Message-ID` every save, whatever [existing] carries: it names the version of the text, and
 */
internal fun localDraftMessageId(
    @Suppress("UNUSED_PARAMETER") existing: LocalDraftEntity?,
    mint: () -> String,
): String = mint()

internal fun newMessageId(username: String, unique: String = java.util.UUID.randomUUID().toString()): String =
    "$unique@${username.substringAfter('@', "localhost")}"

/**
 * Frozen here because this is the only moment it is true: a UID names a message inside one numbering
 * (#99), and a row designed to wait weeks can outlive the numbering it was written under.
 */
internal suspend fun localDraftUidValidity(
    replacesEmailId: String?,
    recorded: suspend (mailboxId: String) -> Long?,
): Long? {
    val mailboxId = replacesEmailId?.let { ImapMailService.mailboxOf(it) } ?: return null
    return recorded(mailboxId)
}

/**
 * A body the composer says it lost never writes over one already stored: a composer back from an
 */
internal fun localDraftBodyToWrite(
    body: String,
    existingBody: String?,
    composerBodyWasLost: Boolean,
    /** The composer's own `body.text`, not [body], which since #131 is derived (`toPlainText`). */
    typedBodyIsBlank: Boolean = body.isBlank(),
): String =
    if (localDraftKeepsTheStoredBody(typedBodyIsBlank, existingBody, composerBodyWasLost)) {
        existingBody.orEmpty()
    } else {
        body
    }

/**
 * The three terms as one decision, so the HTML part cannot answer differently from its text. The
 * `orEmpty()` above is exact and not a default: this is only true when [existingBody] is non-blank.
 */
internal fun localDraftKeepsTheStoredBody(
    typedBodyIsBlank: Boolean,
    existingBody: String?,
    composerBodyWasLost: Boolean,
): Boolean = composerBodyWasLost && typedBodyIsBlank && !existingBody.isNullOrBlank()

/**
 * The HTML follows the text, always (#131): last week's text beside the empty HTML of a composer
 * that lost everything reopens blank, `richBodyFrom` taking the HTML first. [html] `null` included.
 */
internal fun localDraftHtmlToWrite(
    html: String?,
    existingHtml: String?,
    body: String,
    existingBody: String?,
    composerBodyWasLost: Boolean,
    /** As in [localDraftBodyToWrite], and it must be the SAME answer: one condition, two columns. */
    typedBodyIsBlank: Boolean = body.isBlank(),
): String? =
    if (localDraftKeepsTheStoredBody(typedBodyIsBlank, existingBody, composerBodyWasLost)) {
        existingHtml
    } else {
        html
    }

/**
 * [existing]'s id and creation time survive; its `Message-ID` does not (see [localDraftMessageId]).
 */
@Suppress("LongParameterList")
internal fun localDraftRow(
    accountId: String,
    id: String,
    messageId: String,
    to: List<String>,
    cc: List<String>,
    bcc: List<String>,
    subject: String,
    body: String,
    html: String? = null,
    fromName: String? = null,
    fromEmail: String? = null,
    inReplyTo: List<String> = emptyList(),
    references: List<String> = emptyList(),
    replacesEmailId: String? = null,
    replacesUidValidity: Long? = null,
    bodyIsLossy: Boolean = false,
    /** Not [bodyIsLossy]. No default: `true` restores the silent refusal, `false` deletes drafts. */
    composerBodyWasLost: Boolean,
    typedBodyIsBlank: Boolean = body.isBlank(),
    requestReceipt: Boolean = false,
    existing: LocalDraftEntity? = null,
    nowMillis: Long,
): LocalDraftEntity = LocalDraftEntity(
    accountId = accountId,
    id = id,
    messageId = messageId,
    toAddresses = joinAddresses(to).orEmpty(),
    cc = joinAddresses(cc),
    bcc = joinAddresses(bcc),
    subject = subject,
    textBody = localDraftBodyToWrite(body, existing?.textBody, composerBodyWasLost, typedBodyIsBlank),
    htmlBody = localDraftHtmlToWrite(
        html, existing?.htmlBody, body, existing?.textBody, composerBodyWasLost, typedBodyIsBlank,
    ),
    fromName = fromName,
    fromEmail = fromEmail,
    inReplyTo = inReplyTo.joinToString(" ").ifBlank { null },
    references = references.joinToString(" ").ifBlank { null },
    // Filled in by [saveDraftLocalFirst] once the files are staged.
    attachmentsJson = "[]",
    // Written at the row's creation and never again (#95): a re-save from the phone's own store
    // arrives with both null and would rub them out, unmasking the original for good. The two move
    // together or not at all — that pairing is #99.
    replacesEmailId = if (existing == null) replacesEmailId else existing.replacesEmailId,
    replacesUidValidity = if (existing == null) replacesUidValidity else existing.replacesUidValidity,
    bodyIsLossy = bodyIsLossy,
    requestReceipt = requestReceipt,
    createdAtMillis = existing?.createdAtMillis ?: nowMillis,
    updatedAtMillis = nowMillis,
    notBeforeMillis = nowMillis,
    state = LocalDraftState.PENDING,
).also { requireSingleLineAddresses(localDraftHeaderAddresses(it)) }

/** In the form the wire will see it, so a newline the join strips is not read as an injection. */
internal fun localDraftHeaderAddresses(row: LocalDraftEntity): List<String> =
    localDraftAddresses(row.toAddresses) + localDraftAddresses(row.cc) + localDraftAddresses(row.bcc)

private fun joinAddresses(addresses: List<String>): String? =
    addresses.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",").ifEmpty { null }

internal fun localDraftAddresses(column: String?): List<String> =
    column.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Built from the row and not from the composer's arguments, so the `Message-ID` on the wire cannot
 */
internal fun localDraftOutgoing(
    row: LocalDraftEntity,
    loginAddress: String,
    attachments: List<OutgoingAttachment>,
    nowMillis: Long,
): OutgoingMessage {
    val from = formatFromAddress(row.fromName, row.fromEmail) ?: loginAddress
    val to = localDraftAddresses(row.toAddresses).map(::asGroupWhenItCannotBeAnAddrSpec)
    val cc = localDraftAddresses(row.cc).map(::asGroupWhenItCannotBeAnAddrSpec)
    val bcc = localDraftAddresses(row.bcc).map(::asGroupWhenItCannotBeAnAddrSpec)
    requireSingleLineAddresses(listOf(from) + to + cc + bcc)
    return OutgoingMessage(
        from = from,
        to = to,
        cc = cc,
        bcc = bcc,
        subject = row.subject,
        body = row.textBody,
        html = row.htmlBody,
        inReplyTo = row.inReplyTo?.split(" ")?.firstOrNull { it.isNotBlank() },
        references = row.references,
        messageId = row.messageId,
        dateMillis = nowMillis,
        attachments = attachments,
        requestReceipt = row.requestReceipt,
    )
}

/**
 * A token that cannot be an `addr-spec` goes out as RFC 5322 group syntax naming nobody, so
 */
private fun asGroupWhenItCannotBeAnAddrSpec(token: String): String {
    val name = token.removeSuffix(":")
    return if (name.isNotBlank() && '@' !in name && ':' !in name && ';' !in name) "$name:;" else token
}

internal fun localDraftDirName(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

/**
 * The directory under `filesDir` holding every local draft's staged bytes. A constant because two
 * literals would be one rename away from a purge that sweeps an empty directory and reports success.
 */
const val LOCAL_DRAFT_FILES_DIR: String = "local-drafts"

/**
 * Refuses rather than shortens (#70): a part whose bytes cannot be read throws, where staging fewer
 */
internal fun stageLocalDraftAttachments(dir: File, attachments: List<EmailBodyPart>): List<OutboxAttachment> {
    if (attachments.isEmpty()) return emptyList()
    dir.mkdirs()
    return attachments.mapNotNull { part ->
        when {
            part.blobId != null -> OutboxAttachment(
                kind = OutboxAttachments.KIND_JMAP_BLOB,
                blobId = part.blobId, type = part.type, name = part.name, size = part.size,
                cid = part.cid, disposition = part.disposition,
            )
            part.partId != null -> {
                val source = part.partId!!
                val bytes = runCatching { File(source).readBytes() }.getOrNull()
                    ?: error("Couldn't read the attachment ${part.name ?: source} to keep it with the draft.")
                val safe = (part.name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
                // Unique name, so two inline images sharing a file name don't overwrite each other.
                val dest = File(dir, "${System.nanoTime()}-$safe").apply { writeBytes(bytes) }
                OutboxAttachment(
                    kind = OutboxAttachments.KIND_IMAP_FILE,
                    path = dest.absolutePath, type = part.type, name = part.name, size = bytes.size.toLong(),
                    cid = part.cid, disposition = part.disposition,
                )
            }
            else -> null
        }
    }
}

/**
 * The text at once, and nothing of [existing] taken away. A first save goes down in
 */
internal fun localDraftStagingRow(row: LocalDraftEntity, existing: LocalDraftEntity?): LocalDraftEntity =
    if (existing == null) {
        row.copy(state = LocalDraftState.STAGING)
    } else {
        row.copy(attachmentsJson = existing.attachmentsJson, state = existing.state)
    }

/**
 * The order is the whole fix: [upsert] through [localDraftStagingRow], [stage], [upsert] again
 */
@Suppress("LongParameterList")
internal suspend fun saveDraftLocalFirst(
    row: LocalDraftEntity,
    existing: LocalDraftEntity?,
    nowMillis: Long,
    upsert: suspend (LocalDraftEntity) -> Unit,
    stage: suspend (LocalDraftEntity) -> List<OutboxAttachment>,
    discard: suspend (LocalDraftEntity) -> Unit,
    upload: suspend (LocalDraftEntity) -> DraftSaveOutcome,
    record: suspend (LocalDraftEntity, LocalDraftAttempt) -> Unit,
    schedule: (LocalDraftUploadJob) -> Unit,
): DraftSaveOutcome {
    upsert(localDraftStagingRow(row, existing))
    // NonCancellable: run from an already-cancelled coroutine, `discardLocalDraft` swallows the Room
    // delete while the file delete goes through — a STAGING row saying "[]" whose bytes are gone.
    val staged = stageOrRollback(
        rollback = { withContext(NonCancellable) { if (existing == null) discard(row) } },
    ) { stage(row) }
    val durable = row.copy(
        attachmentsJson = OutboxAttachments.encode(staged),
        state = LocalDraftState.PENDING,
    )
    // NonCancellable, and this is the write the whole volet rests on: [stage] does not suspend, so a
    // cancellation is first seen here. Cut here, a first save leaves a STAGING row saying "[]" with
    // orphaned bytes; a re-save leaves the new text under the old row's EDITING lease.
    withContext(NonCancellable) { upsert(durable) }
    val outcome = try {
        upload(durable)
    } catch (cancelled: CancellationException) {
        // Booked before the stop is let through: past here every suspension point throws.
        schedule(LocalDraftUploadJob(durable.accountId, durable.id, 0L))
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
        val attempt = localDraftAttemptAfterFailure(durable, failure, nowMillis)
        // Booked before it is written down: [record] suspends, so a booking after it may not run.
        schedule(
            LocalDraftUploadJob(
                durable.accountId,
                durable.id,
                localDraftScheduleDelayMillis(attempt.notBeforeMillis, nowMillis),
            ),
        )
        record(durable, attempt)
        return DraftSaveOutcome.KEPT_ON_DEVICE
    }
    // NonCancellable: the draft is on the server and the original destroyed. A stop here leaves a
    // row describing bytes `discardLocalDraft` deletes anyway, which the worker re-uploads amputated.
    withContext(NonCancellable) { discard(durable) }
    return outcome
}
