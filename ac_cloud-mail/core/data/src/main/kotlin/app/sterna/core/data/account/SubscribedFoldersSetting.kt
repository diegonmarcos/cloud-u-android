package app.sterna.core.data.account

/** The two decisions behind the "show only subscribed folders" switch (#174), kept out of [AccountStore] for a JVM
 *  test. */

/** [accounts] with the choice on the ONE record [id] names — the `it.id == id` guard is the whole function. */
fun withShowOnlySubscribedFolders(
    accounts: List<StoredAccount>,
    id: String,
    enabled: Boolean,
): List<StoredAccount> =
    accounts.map { if (it.id == id) it.copy(showOnlySubscribedFolders = enabled) else it }

/** What the folder lists do for [account] — false when unresolved, FALSE on purpose. */
fun showOnlySubscribedFoldersOf(account: StoredAccount?): Boolean =
    account?.showOnlySubscribedFolders ?: false

/** The folder sync owed to the server, or null; carries the VALUE decided on, not a bare "refresh". */
data class FolderSubscriptionSync(val accountId: String, val onlySubscribed: Boolean)

/** What moving [accountId]'s switch owes: a listing asking about subscription when ON, nothing when OFF. */
fun subscriptionSyncOnToggle(accountId: String, enabled: Boolean): FolderSubscriptionSync? =
    if (enabled) FolderSubscriptionSync(accountId, onlySubscribed = true) else null
