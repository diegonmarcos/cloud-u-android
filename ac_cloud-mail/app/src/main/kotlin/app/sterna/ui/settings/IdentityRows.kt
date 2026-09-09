package app.sterna.ui.settings

import app.sterna.core.data.account.StoredIdentity
import app.sterna.ui.compose.SIGNATURE_DELIMITER
import app.sterna.ui.compose.signatureBlock

/** Display rules for the Identities editor. Presentation only: nothing here is persisted. */

/** One row of the editor, in display order: the server group first, then the manual group.
 *  [aliasIds] carries the other ids that may point at the same row — a server identity edited
 *  through a manual override can be recorded under either id, and both must match the default. */
internal data class IdentityRowRef(val rowId: String, val aliasIds: List<String> = emptyList())

/** The row left expanded when the editor is built: the default sender, falling back to the first row
 *  when none is set or it points at a row that is gone. Null only when there is no row at all. */
internal fun initialExpandedIdentityId(
    rows: List<IdentityRowRef>,
    defaultIdentityId: String?,
): String? {
    if (rows.isEmpty()) return null
    val default = defaultIdentityId?.takeIf { it.isNotBlank() }
    val match = default?.let { id -> rows.firstOrNull { it.rowId == id || id in it.aliasIds } }
    return (match ?: rows.first()).rowId
}

/** What a collapsed row reports about its signature. */
internal enum class SignatureState { HTML, TEXT, NONE }

/** An imported HTML signature always keeps a flattened text version alongside it, so the HTML half
 *  decides first: a row with both is an HTML signature, not a text one. */
internal fun signatureStateOf(signature: String, signatureHtml: String): SignatureState = when {
    signatureHtml.isNotBlank() -> SignatureState.HTML
    signature.isNotBlank() -> SignatureState.TEXT
    else -> SignatureState.NONE
}

/**
 * What a COLLAPSED identity row reports about its signatures (#206): the state of the one composing
 * would pre-select.
 *
 * Read through [StoredIdentity.defaultSignature] rather than off the legacy `signature` pair, or a row
 * whose named signatures have been edited would keep describing the pre-#206 text underneath them —
 * the header would say "plain text" while the default is HTML.
 */
internal fun signatureStateOf(identity: StoredIdentity): SignatureState =
    identity.defaultSignature()?.let { signatureStateOf(it.text, it.html) } ?: SignatureState.NONE

/** What the composer will actually put in the message for [signature]; null when a blank signature
 * adds nothing. The delimiter is never stored in the field, only added when the body is built
 *  (#90), so the preview is derived from [signatureBlock] itself, minus its leading blank line. */
internal fun signaturePreview(signature: String, delimiter: Boolean): String? =
    signatureBlock(signature, delimiter).trimStart('\n').takeIf { it.isNotEmpty() }

/** Whether [signature] already opens with a delimiter line of its own, which the app's own delimiter
 *  would be stacked on top of (#90). Reported, never removed. Read off the trimmed signature,
 *  exactly as [signatureBlock] builds it, so a delimiter typed under a blank line is caught too. */
internal fun signatureHasOwnDelimiter(signature: String, delimiter: Boolean): Boolean {
    if (!delimiter) return false
    val first = signature.trim().lineSequence().firstOrNull() ?: return false
    return first.trimEnd() == SIGNATURE_DELIMITER.trimEnd()
}

/** Expand or collapse [rowId]; the set is the whole state, and it is never saved. */
internal fun Set<String>.toggleIdentityRow(rowId: String): Set<String> =
    if (rowId in this) this - rowId else this + rowId
