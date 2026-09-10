package app.sterna.ui.settings

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity

/** What a Save from the account editor decides. Shared so the bottom button and the Save offered by
 *  the exit dialog (#34) cannot drift apart. */

/** The identity-side fields a Save writes back, derived from the editor's live rows. */
internal data class AccountSaveFields(
    /** The MANUAL list to persist: blank rows dropped, old-fold pollution healed. */
    val identities: List<StoredIdentity>,
    /** The default sender, or null when it no longer points at anything. */
    val defaultIdentityId: String?,
    /** Legacy account-level signature, mirrored from the first manual identity — from that
     *  identity's DEFAULT named signature (#206), not from whatever its pre-edit legacy field held. */
    val signature: String,
)

/** Persist only the manual list — server identities re-merge live on every read — dropping rows left
 *  without an address. The default sender is kept only while it still points at a surviving manual
 *  row or a server identity, so it degrades to "the first one" rather than dangling. */
internal fun accountSaveFields(
    edited: List<StoredIdentity>,
    serverIdentities: List<StoredIdentity>,
    defaultIdentityId: String?,
): AccountSaveFields {
    // Mirrored BEFORE the heal and the account-level copy below, because this is the one funnel both
    // identity groups reach Save through — the server-identity override and the purely-manual row
    // alike. Doing it in the editor's two onChange callbacks instead would be two chances to write
    // the mirror and, as #206 showed, two chances to forget it in the next box someone adds.
    val clean = StoredAccount.normalizeManualIdentities(
        edited.filter { it.email.isNotBlank() }.map { it.withLegacyMirror() },
        serverIdentities,
    )
    val serverIds = serverIdentities.map { it.id }
    val keptDefault = defaultIdentityId?.takeIf { id ->
        clean.any { it.id == id } || id in serverIds
    }
    return AccountSaveFields(
        identities = clean,
        defaultIdentityId = keptDefault,
        signature = clean.firstOrNull()?.signature ?: "",
    )
}

/** Whether the editor holds enough to write an account at all. Gates the bottom button and, since
 *  #34, the dialog's Save too: leaving through the dialog must never write a half-filled account the
 *  button itself would have refused. Ports come straight from the text fields, so a non-numeric one
 *  counts as missing. */
internal fun canSaveAccount(
    username: String,
    isImap: Boolean,
    server: String,
    imapHost: String,
    imapPort: String,
    smtpHost: String,
    smtpPort: String,
): Boolean = username.isNotBlank() && if (isImap) {
    imapHost.isNotBlank() && imapPort.toIntOrNull() != null &&
        smtpHost.isNotBlank() && smtpPort.toIntOrNull() != null
} else {
    server.isNotBlank()
}
