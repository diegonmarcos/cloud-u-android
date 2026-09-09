package app.sterna.core.data.account

import app.sterna.core.imap.OutgoingMime

/** Whether an outgoing message announces this account's OpenPGP key. Sent on EVERY send
 *  (Autocrypt §2.1); no `prefer-encrypt`; PGP mode of the message is NOT consulted, or a
 *  header carried only by encrypted mail would only reach people who already have the key. */
fun autocryptHeaderValue(account: StoredAccount?, fromAddress: String?): String? {
    if (account == null || !account.pgpEnabled) return null
    if (account.pgpSignKeyId == 0L || account.pgpPublicKey.isEmpty()) return null
    val from = fromAddress?.trim().orEmpty()
    if (from.isEmpty() || !sameMailbox(from, account.username)) return null
    // headerSafe is [OutgoingMime]'s CR/LF sink; nothing folds here, only the SMTP side does.
    return "addr=${OutgoingMime.headerSafe(from)}; keydata=${OutgoingMime.headerSafe(account.pgpPublicKey)}"
}

/** Whether [from] and [accountAddress] are the same mailbox — guards a delegated send: if
 *  they differ, announcing this key would make the correspondent encrypt to the wrong key. */
private fun sameMailbox(from: String, accountAddress: String): Boolean {
    val own = accountAddress.trim().lowercase()
    return own.isNotEmpty() && from.trim().lowercase() == own
}
