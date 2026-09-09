package app.sterna.core.data.account

/** Which identities a Save must ALSO create on the server (`Identity/set`) — fixes #172, a
 *  Settings-added alias with no server identity was refused at send.
 * [serverIdentities] is what the CALLER just read, or null if it failed — never substitute
 *  `before.serverIdentities.isEmpty()`, stale on a fresh account (measured, S7 2026-08-31). */
fun identitiesToCreate(
    before: StoredAccount,
    edited: List<StoredIdentity>,
    serverIdentities: List<StoredIdentity>?,
): List<StoredIdentity> {
    if (before.protocol != MailProtocol.JMAP) return emptyList()
    // A delegated sub-account cannot run Identity/get on itself (#31); creating there would be irreversible (rule 1).
    if (before.isLinked) return emptyList()
    if (serverIdentities == null) return emptyList()
    val fold = { email: String -> email.trim().lowercase() }
    val stored = (before.identities + serverIdentities).map { fold(it.email) }
    // SettingsScreen fabricates a login-address row when there's no stored identity; it must count as known too.
    val fabricated = if (before.identities.isEmpty()) listOf(fold(before.username)) else emptyList()
    val known = (stored + fabricated).toSet()
    return edited
        .filter { plausibleIdentityAddress(it.email) && it.email.trim().lowercase() !in known }
        .distinctBy { it.email.trim().lowercase() }
}

/** Could this save create anything — asks [identitiesToCreate] "if the server held nothing,
 * would anything leave?" Without it every Save would open a JMAP session, even on IMAP. */
fun mayCreateIdentities(before: StoredAccount, edited: List<StoredIdentity>): Boolean =
    identitiesToCreate(before, edited, serverIdentities = emptyList()).isNotEmpty()

/** `something@something`, with no whitespace and exactly one `@` — see [identitiesToCreate]. */
private fun plausibleIdentityAddress(email: String): Boolean {
    val trimmed = email.trim()
    if (trimmed.isEmpty()) return false
    val parts = trimmed.split("@")
    if (parts.size != 2) return false
    return parts.all { part -> part.isNotEmpty() && part.none { it.isWhitespace() } }
}
