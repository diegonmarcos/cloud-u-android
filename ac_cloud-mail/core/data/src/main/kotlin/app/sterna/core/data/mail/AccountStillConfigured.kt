package app.sterna.core.data.mail

/** A sync walk found, mid-flight, that the account it writes for is no longer configured (#121). A
 *  [CancellationException] on purpose, so `fullQueryWriteThrough` climbs out without reconciling;
 *  `refreshAllInboxes` catches it by name, or `refreshing = true` stays on screen for ever. */
class AccountGoneException(val accountId: String) : kotlin.coroutines.cancellation.CancellationException(
    "account $accountId is no longer configured; abandoning what was still writing in its name (#121)",
)

/** The decision a sync makes before it writes: may rows still be tagged with [localAccountId]? A
 *  blank id is a programming error and throws loudly; an id no configured account owns throws
 *  [AccountGoneException]. An empty [configuredAccountIds] abandons everything, and `accounts()`
 *  also answers `emptyList()` on a decode failure — kept, since refusing a write loses nothing. */
fun checkAccountStillConfigured(localAccountId: String, configuredAccountIds: Collection<String>) {
    require(localAccountId.isNotBlank()) {
        "checkAccountStillConfigured() needs a real account id: a blank one is the caller's own " +
            "bug (#121), not a signed-out account, and must not be mistaken for one."
    }
    if (localAccountId !in configuredAccountIds) throw AccountGoneException(localAccountId)
}
