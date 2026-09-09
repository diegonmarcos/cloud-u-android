package app.sterna.core.data.account

/** WHICH ACCOUNT IS ALREADY THERE — the one comparison, so every route CALLS it instead of
 * re-deriving its own. [authType] is NOT part of the key; nothing here is persisted. */

/** The identity an account is recognised by: protocol + normalised endpoint + normalised username. */
data class AccountKey(
    val protocol: MailProtocol,
    val endpoint: String,
    val username: String,
)

/** Endpoint reduced to scheme+trailing-`/`-free, trimmed, lower-cased. A PATH IS KEPT:
 *  stripping it would merge two different JMAP servers under one name, overwriting a secret (rule 1). */
fun normalizeEndpoint(raw: String): String =
    raw.trim()
        .lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')
        .trim()

/** IMAP keys on the IMAP host (an IMAP account carries a blank `server`); everything else on `server`. */
fun accountEndpointOf(protocol: MailProtocol, server: String, imapHost: String): String =
    if (protocol == MailProtocol.IMAP) imapHost else server

/** The key these fields would be recognised by, or null if either half is blank — no guard,
 *  rather than one that could collide two half-filled accounts. */
fun accountKeyOf(
    protocol: MailProtocol,
    server: String,
    imapHost: String,
    username: String,
): AccountKey? {
    val endpoint = normalizeEndpoint(accountEndpointOf(protocol, server, imapHost))
    val user = username.trim().lowercase()
    if (endpoint.isBlank() || user.isBlank()) return null
    return AccountKey(protocol, endpoint, user)
}

/** The key of a stored account. */
fun accountKeyOf(a: StoredAccount): AccountKey? =
    accountKeyOf(a.protocol, a.server, a.imapHost, a.username)

/** The account already stored under [key], or null (including for a null key: no key, no match). */
fun resolveExisting(accounts: List<StoredAccount>, key: AccountKey?): StoredAccount? {
    if (key == null) return null
    return accounts.firstOrNull { accountKeyOf(it) == key }
}

/** The stored LOGIN recognised by [key]. A linked sub-account BORROWS its login's secret,
 *  so an add onto it would file the new token where nothing reads it. */
fun resolveExistingLogin(accounts: List<StoredAccount>, key: AccountKey?): StoredAccount? =
    resolveExisting(accounts.filter { it.loginId == null }, key)

/** First login recognised by one of [keys], in order — a route may offer an EXTRA key.
 * Not folded into [accountKeyOf] (one key per account); fixes #55's own duplicate, order matters. */
fun resolveExistingLoginAmong(accounts: List<StoredAccount>, keys: List<AccountKey?>): StoredAccount? =
    keys.firstNotNullOfOrNull { resolveExistingLogin(accounts, it) }

/** [existing] updated with what an add PROVED. `existing.copy(...)`, never `proven.copy(...)`,
 *  so untouched fields survive (rule 1); a password after OAuth DOES clear oauth* fields. */
fun refreshedWith(existing: StoredAccount, proven: StoredAccount): StoredAccount =
    existing.copy(
        server = proven.server,
        username = proven.username,
        protocol = proven.protocol,
        authType = proven.authType,
        oauthAccessToken = proven.oauthAccessToken,
        oauthAccessExpiresAt = proven.oauthAccessExpiresAt,
        oauthTokenEndpoint = proven.oauthTokenEndpoint,
        oauthClientId = proven.oauthClientId,
        imapHost = proven.imapHost,
        imapPort = proven.imapPort,
        imapSecurity = proven.imapSecurity,
        smtpHost = proven.smtpHost,
        smtpPort = proven.smtpPort,
        smtpSecurity = proven.smtpSecurity,
        importPending = false,
    )
