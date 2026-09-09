package app.sterna.ui

/**
 * Which top-level screen the app opens on. Two facts in, one [RootState] out — and nothing else,
 */
object RootRoute {

    /**
     * THE ORDER OF THE TWO TESTS IS THE DECISION, not a formality.
     */
    fun resolve(currentAccountId: String?, accountsUnreadable: Boolean): RootState = when {
        currentAccountId != null -> RootState.Authenticated(currentAccountId)
        accountsUnreadable -> RootState.AccountsUnreadable
        else -> RootState.NeedAccount
    }
}
