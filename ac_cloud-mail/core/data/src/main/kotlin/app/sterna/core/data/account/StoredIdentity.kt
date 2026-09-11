package app.sterna.core.data.account

import app.sterna.core.data.text.htmlToText
import app.sterna.core.data.text.looksLikeHtml
import kotlinx.serialization.Serializable

/** A sending identity: "from" name + address + signature. Stored twice — [signature] plain
 *  text, [signatureHtml] optional HTML — as JSON (not Room), so new fields default cleanly. */
@Serializable
data class StoredIdentity(
    val id: String,
    val name: String,
    val email: String,
    val signature: String = "",
    /** Rich version of [signature], sent as-is in the html alternative. Empty = plain text only. */
    val signatureHtml: String = "",
    /** The identity's NAMED signatures (#206). Empty on every record written before #206, which is why
     *  nothing reads this field directly — see [resolvedSignatures]. */
    val signatures: List<StoredSignature> = emptyList(),
    /** Which of [signatures] the composer pre-selects, keyed by [StoredSignature.id]; an unmatched id
     *  degrades via [defaultSignature], exactly as [StoredAccount.defaultIdentityId] does. */
    val defaultSignatureId: String? = null,
) {
    /** "Name <email>" or just the address when unnamed. */
    fun display(): String = if (name.isBlank()) email else "$name <$email>"

    /**
     * This identity's signatures, MIGRATING the pre-#206 single one when the new list is empty (#206).
     *
     * The legacy [signature]/[signatureHtml] pair is never cleared by this — it is read, not moved —
     * so an install that rolls back to a build without [signatures] still finds the owner's signature
     * where it has always been. That is the whole reason migration happens on READ rather than as a
     * one-shot rewrite of the store: a rewrite has exactly one chance to be correct, and a downgrade
     * afterwards silently loses the signature.
     *
     * A fresh install has no legacy signature either, so the flattened pair is blank and the list is
     * EMPTY — not a list holding one empty signature, which would show the owner a nameless blank row
     * in every picker and put a bare delimiter into every message they send.
     */
    fun resolvedSignatures(): List<StoredSignature> {
        if (signatures.isNotEmpty()) return signatures
        // Legacy raw HTML may still be sitting in the plain-text field; split it the way every other
        // reader of these two fields does, so the migrated entry is a correct (text, html) pair.
        val split = withSplitSignature()
        if (split.signature.isBlank() && split.signatureHtml.isBlank()) return emptyList()
        return listOf(
            StoredSignature(
                id = StoredSignature.MIGRATED_ID,
                name = "",
                text = split.signature,
                html = split.signatureHtml,
            ),
        )
    }

    /**
     * The signature the composer opens with: the [defaultSignatureId] match, else the first.
     *
     * The same two-step degradation as [StoredAccount.defaultIdentity], and for the same reason: the
     * stored id names a row that can be deleted or replaced by a server refresh, and a composer that
     * answered "none" in that case would silently drop the signature off outgoing mail.
     */
    fun defaultSignature(): StoredSignature? {
        val all = resolvedSignatures()
        return all.firstOrNull { it.id == defaultSignatureId } ?: all.firstOrNull()
    }

    /**
     * The legacy [signature]/[signatureHtml] pair refreshed from the DEFAULT named signature (#206).
     *
     * [resolvedSignatures] promises that the legacy pair is read and never cleared, "so an install
     * that rolls back to a build without [signatures] still finds the owner's signature where it has
     * always been". Migration alone keeps that promise only until the first edit: the editor writes
     * [signatures], nothing writes the pair, and from then on the pair holds the signature the owner
     * REPLACED. A rollback, a backup taken afterwards, and the account-level mirror in
     * `accountSaveFields` then all quietly serve the old signature — the newer half is kept, the older
     * half rots, and nothing on screen says so.
     *
     * Mirroring at save keeps both halves saying the same thing. Idempotent where nothing has been
     * edited: for an identity with no [signatures] of its own, the default IS the migrated pair, so
     * copying it back changes nothing. An identity with no signature at all is returned untouched
     * rather than blanked, which is the same "degrade, never destroy" rule [defaultSignature] follows.
     */
    fun withLegacyMirror(): StoredIdentity {
        val default = defaultSignature() ?: return this
        return copy(signature = default.text, signatureHtml = default.html)
    }

    /** Splits a legacy signature holding raw HTML in [signature] (pre-1.3.13 "Import HTML")
     *  into [signatureHtml] + flattened text. Idempotent otherwise. */
    fun withSplitSignature(): StoredIdentity =
        if (signatureHtml.isBlank() && looksLikeHtml(signature)) {
            // Link targets kept, for [StoredSignature.of]'s reason: this is the text/plain half.
            copy(signature = htmlToText(signature, keepLinkTargets = true), signatureHtml = signature)
        } else {
            this
        }
}

/** Every address that IS the user on this account, plus its login. [username] is added even
 *  when identities name it: a linked sub-account resolves identities through its LOGIN (#31). */
fun accountAddresses(identities: List<StoredIdentity>, username: String?): List<String> =
    identities.map { it.email } + listOfNotNull(username)
