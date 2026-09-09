package app.sterna.core.data.account

/**
 * Drops every account's OpenPGP signing key id and public key. A key id names a key inside
 */
fun withoutPgpSignKeys(accounts: List<StoredAccount>): List<StoredAccount> =
    accounts.map { it.copy(pgpSignKeyId = 0L, pgpPublicKey = "") }
