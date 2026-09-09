package app.sterna.core.data.account

/** What a pruned shared mailbox leaves behind, so re-granting doesn't hand back a fresh
 *  account. Keyed `loginId -> pruned records`; COMFORT state outside the account blob's gate. */

/** The account list and the leftover memory, as [reconciledAccounts] computed them together. */
internal data class ReconciledAccounts(
    val accounts: List<StoredAccount>,
    val memory: Map<String, List<StoredAccount>>,
)

/** Files every [pruned] record under its login (records missing loginId/jmapAccountId are
 *  IGNORED); a deposit already present REPLACES it. */
internal fun rememberPrunedLinked(
    memory: Map<String, List<StoredAccount>>,
    pruned: List<StoredAccount>,
): Map<String, List<StoredAccount>> {
    var result = memory
    pruned.forEach { record ->
        val loginId = record.loginId ?: return@forEach
        val jmapAccountId = record.jmapAccountId ?: return@forEach
        val kept = result[loginId].orEmpty().filterNot { it.jmapAccountId == jmapAccountId }
        result = result + (loginId to (kept + record))
    }
    return result
}

/** The remembered record for a (login, server account) pair, or null when nothing was filed. */
internal fun recallLinked(
    memory: Map<String, List<StoredAccount>>,
    loginId: String,
    jmapAccountId: String,
): StoredAccount? = memory[loginId]?.firstOrNull { it.jmapAccountId == jmapAccountId }

/** Drops one (login, server account) entry; a sub-map left empty is REMOVED with it, or a
 *  ghost key would grow forever. */
internal fun forgetLinked(
    memory: Map<String, List<StoredAccount>>,
    loginId: String,
    jmapAccountId: String,
): Map<String, List<StoredAccount>> {
    val kept = memory[loginId]?.filterNot { it.jmapAccountId == jmapAccountId } ?: return memory
    return if (kept.isEmpty()) memory - loginId else memory + (loginId to kept)
}

/** Forgets what an explicit REMOVAL made meaningless: a removed LOGIN takes its whole sub-map,
 *  a removed SUB-ACCOUNT takes only its own entry. */
internal fun forgetRemovedLinked(
    memory: Map<String, List<StoredAccount>>,
    removed: List<StoredAccount>,
): Map<String, List<StoredAccount>> {
    var result = memory
    removed.forEach { record ->
        val loginId = record.loginId
        if (loginId == null) {
            result = result - record.id
        } else {
            val jmapAccountId = record.jmapAccountId ?: return@forEach
            result = forgetLinked(result, loginId, jmapAccountId)
        }
    }
    return result
}

/** The [StoredAccount] for a newly granted share, wearing [remembered]'s settings if seen
 * before. RESET list, not KEEP list: named fields come FRESH, else BY DEFAULT (else a new field is silently lost). */
internal fun mintLinkedAccount(
    login: StoredAccount,
    sub: DiscoveredMailAccount,
    loginId: String,
    remembered: StoredAccount?,
    newId: String,
): StoredAccount {
    val fresh = StoredAccount(
        id = newId,
        server = login.server,
        username = login.username,
        accountName = sub.name,
        loginId = loginId,
        jmapAccountId = sub.jmapAccountId,
        protocol = login.protocol,
        authType = login.authType,
    )
    return remembered?.copy(
        id = fresh.id,
        server = fresh.server,
        username = fresh.username,
        accountName = fresh.accountName,
        loginId = fresh.loginId,
        jmapAccountId = fresh.jmapAccountId,
        protocol = fresh.protocol,
        authType = fresh.authType,
        inboxId = fresh.inboxId,
        inboxName = fresh.inboxName,
        unread = fresh.unread,
        serverIdentities = fresh.serverIdentities,
        importPending = fresh.importPending,
        oauthAccessToken = fresh.oauthAccessToken,
        oauthAccessExpiresAt = fresh.oauthAccessExpiresAt,
        oauthTokenEndpoint = fresh.oauthTokenEndpoint,
        oauthClientId = fresh.oauthClientId,
    ) ?: fresh
}

/** Applies [diff] to [list], carrying the memory across. A minted record is APPENDED, never
 *  slotted back (restored seniority could outrank a live one); memory is CONSUMED on recall. */
internal fun reconciledAccounts(
    list: List<StoredAccount>,
    login: StoredAccount,
    loginId: String,
    diff: LinkedAccountsDiff,
    memory: Map<String, List<StoredAccount>>,
    mintId: () -> String,
): ReconciledAccounts {
    val updated = list.toMutableList()
    var carried = memory
    // Pin the login's own JMAP account id on first discovery so later reconciles tell it apart.
    diff.pinPrimaryId?.let { pin ->
        updated[updated.indexOfFirst { it.id == loginId }] = login.copy(jmapAccountId = pin)
    }
    diff.toAdd.forEach { sub ->
        val remembered = recallLinked(carried, loginId, sub.jmapAccountId)
        updated += mintLinkedAccount(login, sub, loginId, remembered, mintId())
        if (remembered != null) carried = forgetLinked(carried, loginId, sub.jmapAccountId)
    }
    if (diff.prunedIds.isNotEmpty()) {
        carried = rememberPrunedLinked(carried, list.filter { it.id in diff.prunedIds })
        updated.removeAll { it.id in diff.prunedIds }
    }
    return ReconciledAccounts(updated, carried)
}
