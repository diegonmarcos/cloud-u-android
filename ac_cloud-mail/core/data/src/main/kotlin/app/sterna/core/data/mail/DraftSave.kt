package app.sterna.core.data.mail

import app.sterna.core.data.db.OutboxState
import app.sterna.core.imap.ImapAddress
import kotlin.coroutines.cancellation.CancellationException

/**
 * [ORIGINAL_KEPT] left the original on the server, the replacement not having been shown to
 * reproduce it: a duplicate in Drafts is recoverable, a destroyed original is not (#63).
 */
enum class DraftSaveOutcome {
    SAVED,
    ORIGINAL_KEPT,

    /** Written locally, upload not yet through (#95). Not an error; the composer must not show one. */
    KEPT_ON_DEVICE,
}

fun draftSaveNeedsNotice(outcome: DraftSaveOutcome): Boolean = outcome == DraftSaveOutcome.ORIGINAL_KEPT

/**
 * Whether a save's [outcome] permits `deleteOutbox` on the queued row (#70 × #95). A KEPT_ON_DEVICE
 * draft is on no screen: consuming its row trades the last visible copy, and its files, for none.
 */
fun draftSaveConsumesTheQueuedRow(outcome: DraftSaveOutcome): Boolean =
    outcome != DraftSaveOutcome.KEPT_ON_DEVICE

/**
 * The state a surviving edited outbox row is parked in, or null when the save consumed it. Not
 * EDITING (filtered off screen and badge alike) nor FAILED (a banner nothing left can clear).
 */
fun draftSaveParksTheQueuedRowAs(outcome: DraftSaveOutcome): OutboxState? =
    if (draftSaveConsumesTheQueuedRow(outcome)) null else OutboxState.KEPT_AS_DRAFT

/**
 * Whether a re-saved draft reproduced the one it replaces, i.e. whether the original may be
 * destroyed. [replacementWentOut] carries no default: `= true` would arm the defect for new callers.
 */
internal fun draftReplacementIsFaithful(
    replacementWentOut: Boolean,
    attachmentsIn: Int,
    attachmentsCarried: Int,
    bodyIsLossy: Boolean,
    addressingIsCarried: Boolean,
    receiptRequestIsCarried: Boolean,
): Boolean = replacementWentOut &&
    !bodyIsLossy &&
    attachmentsCarried == attachmentsIn &&
    addressingIsCarried &&
    receiptRequestIsCarried

/**
 * Whether reopening a saved draft lost something the server copy still holds; `true` keeps the
 */
fun reopenedDraftIsLossy(
    hasHtmlBody: Boolean,
    inlineImageCount: Int,
    calendarPartCount: Int,
    receiptHeader: String?,
): Boolean = hasHtmlBody ||
    inlineImageCount > 0 ||
    calendarPartCount > 0 ||
    !receiptHeader.isNullOrBlank()

/**
 * The draft id a send may destroy once delivered, or null (#63). `receiptRequestIsCarried = true` is
 * not a server read: a clean [bodyIsLossy] already carries the receipt leg of [reopenedDraftIsLossy].
 */
internal suspend fun replaceableDraftIdOrNull(
    draftEmailId: String?,
    attachmentCount: Int,
    bodyIsLossy: Boolean,
    addressingIsCarried: suspend (String) -> Boolean,
): String? {
    if (draftEmailId == null) return null
    val faithful = draftReplacementIsFaithful(
        replacementWentOut = true,
        attachmentsIn = attachmentCount,
        attachmentsCarried = attachmentCount,
        bodyIsLossy = bodyIsLossy,
        addressingIsCarried = addressingIsCarried(draftEmailId),
        receiptRequestIsCarried = true,
    )
    return if (faithful) draftEmailId else null
}

/**
 * Whether [replacement] provably carries the addressing it replaces. `original == null` is an
 * unknown, not an empty addressing, and answers false; inclusion one way only, trimmed and folded.
 */
internal fun draftAddressingIsCarried(original: Set<String>?, replacement: Set<String>): Boolean {
    if (original == null) return false
    val carried = normalisedAddresses(replacement)
    return normalisedAddresses(original).all { it in carried }
}

/**
 * The addressing a replacement offers as proof: Cc and Bcc, since `BlindCopies.WRITTEN` writes a
 * real `Bcc:` into the APPENDed copy. An empty [bcc] fails the check above, sparing the server copy.
 */
internal fun draftReplacementAddressing(cc: List<String>, bcc: List<String>): Set<String> =
    (cc + bcc).toSet()

internal suspend fun draftAddressingIsCarried(
    replacesEmailId: String?,
    replacement: Set<String>,
    originalAddressing: suspend (String) -> Set<String>?,
): Boolean {
    // No original means nothing will be destroyed, so there is nothing to prove.
    if (replacesEmailId == null) return true
    val original = try {
        originalAddressing(replacesEmailId)
    } catch (cancelled: CancellationException) {
        // Not a failed read: rethrown, or a cancelled save would pass for an unknown addressing.
        throw cancelled
    } catch (failure: Throwable) {
        null
    }
    return draftAddressingIsCarried(original, replacement)
}

/**
 * Whether [replacement] provably carries the read-receipt request it replaces (RFC 8098's
 * `Disposition-Notification-To`). The same unknown rule as [draftAddressingIsCarried].
 */
internal fun draftReceiptRequestIsCarried(original: Boolean?, replacement: Boolean): Boolean =
    replacement || original == false

/** The twin of the [draftAddressingIsCarried] overload above, which documents the two catches. */
internal suspend fun draftReceiptRequestIsCarried(
    replacesEmailId: String?,
    replacement: Boolean,
    originalRequested: suspend (String) -> Boolean?,
): Boolean {
    if (replacesEmailId == null) return true
    if (replacement) return true
    val original = try {
        originalRequested(replacesEmailId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        null
    }
    return draftReceiptRequestIsCarried(original, replacement)
}

/**
 * `null` when an entry is present but yields no address: unreadable is an unknown, never an entry
 * left out — filtering shrinks the set the destroy decision reads, to empty if it was the only Cc.
 */
internal fun copiedAddressesOrNull(entries: List<ImapAddress>): Set<String>? {
    val addresses = entries.map { it.email?.trim().orEmpty() }
    if (addresses.any { it.isEmpty() }) return null
    return addresses.toSet()
}

private fun normalisedAddresses(addresses: Set<String>): Set<String> =
    addresses.mapNotNull { it.trim().lowercase().ifEmpty { null } }.toSet()
