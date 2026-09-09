package app.sterna.core.data.account

import java.util.Base64

/** Everything decidable about [StoredAccount.pgpPublicKey], cached because a background
 *  sender (no screen) can't launch the provider's PendingIntent to read it. */

/** Exact text [StoredAccount.pgpPublicKey] stores: `Base64.getEncoder()`, no line breaks —
 *  not `getMimeEncoder()`, which folds and isn't what a header can carry. */
fun pgpPublicKeyCacheValue(minimizedKey: ByteArray?): String =
    if (minimizedKey == null || minimizedKey.isEmpty()) {
        ""
    } else {
        Base64.getEncoder().encodeToString(minimizedKey)
    }

/** Account whose public key must be fetched now, or null. Needs a signing key, empty cache,
 *  and [alreadyTried] not naming it — else a busy provider retries every re-arbitration. */
fun pgpPublicKeyBackfill(account: StoredAccount?, alreadyTried: Set<String>): String? =
    account
        ?.takeIf { it.pgpSignKeyId != 0L && it.pgpPublicKey.isEmpty() && it.id !in alreadyTried }
        ?.id

/** [accounts] with [id]'s key replaced by [publicKey], only while it still carries [signKeyId]
 *  — else the OLD key's bytes would land under a NEW id after a mid-flight key switch. */
fun withCachedPgpPublicKey(
    accounts: List<StoredAccount>,
    id: String,
    signKeyId: Long,
    publicKey: String,
): List<StoredAccount> = accounts.map {
    if (signKeyId != 0L && it.id == id && it.pgpSignKeyId == signKeyId) {
        it.copy(pgpPublicKey = publicKey)
    } else {
        it
    }
}
