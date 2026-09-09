package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The startup routing, EXECUTED. `RootViewModel` needs an `Application`, the account store and
 */
class RootRouteTest {

    @Test fun `a signed-in account opens its mailbox`() {
        assertEquals(
            RootState.Authenticated(ACCOUNT),
            RootRoute.resolve(currentAccountId = ACCOUNT, accountsUnreadable = false),
        )
    }

    @Test fun `no account and a readable store is a normal first run`() {
        assertEquals(
            "a fresh install, or one signed out, must reach the welcome and the connect screen — " +
                "this is the only path on which anyone ever creates a first account",
            RootState.NeedAccount,
            RootRoute.resolve(currentAccountId = null, accountsUnreadable = false),
        )
    }

    @Test fun `no account and an unreadable store is the alert, never the add-account invitation`() {
        assertEquals(
            "routed to NeedAccount, so the user is invited to add an account the store will " +
                "refuse to save: it appears to work and is gone at the next launch",
            RootState.AccountsUnreadable,
            RootRoute.resolve(currentAccountId = null, accountsUnreadable = true),
        )
    }

    /**
     * THE ORDER OF THE TWO TESTS, pinned by the one input pair that can tell them apart.
     */
    @Test fun `an account that IS signed in wins over the unreadable flag`() {
        assertEquals(
            "the unreadable flag is tested before the current account: a signed-in user is thrown " +
                "onto the alert screen",
            RootState.Authenticated(ACCOUNT),
            RootRoute.resolve(currentAccountId = ACCOUNT, accountsUnreadable = true),
        )
    }

    private companion object {
        const val ACCOUNT = "acc-jordan"
    }
}
