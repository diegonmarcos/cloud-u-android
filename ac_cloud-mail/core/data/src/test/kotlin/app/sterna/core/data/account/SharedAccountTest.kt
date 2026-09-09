package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure "is this account shared?" rule and the accounts-screen order it feeds (issue #31).
 */
class SharedAccountTest {

    private fun login() = StoredAccount(
        id = "login-uuid",
        server = "https://mail.example.org",
        username = "alex.rivera@example.org",
        jmapAccountId = "s",
    )

    private fun delegated(
        id: String = "jordan-uuid",
        name: String = "jordan.lee@example.org",
        loginId: String = "login-uuid",
    ) = StoredAccount(
        id = id,
        server = "https://mail.example.org",
        // A sub-account carries the LOGIN's address as username; its own is the account name.
        username = "alex.rivera@example.org",
        accountName = name,
        loginId = loginId,
        jmapAccountId = "u",
    )

    @Test fun aFullAccountIsNotShared() {
        assertFalse(login().isShared)
    }

    @Test fun aDelegatedSubAccountIsShared() {
        assertTrue(delegated().isShared)
    }

    @Test fun aBlankLoginIdNamesNoLoginSoItIsNotShared() {
        // Neither an absent nor an empty/whitespace loginId points at a login to borrow from.
        assertFalse(login().copy(loginId = null).isShared)
        assertFalse(login().copy(loginId = "").isShared)
        assertFalse(login().copy(loginId = "   ").isShared)
    }

    // ---- the rows of the accounts screen, in order (volet 3) ----
    //
    // Same fact the four `sharedLabelsUnder` tests carried before this batch (which delegate hangs
    // under which login, and that another login's delegates never do), now expressed in ROWS: a
    // shared mailbox has a row of its own, indented, instead of a mention in its login's subtitle.

    @Test fun aLoginOnItsOwnIsOneTopLevelRow() {
        assertEquals(
            listOf(AccountRowEntry(login(), underLogin = false)),
            StoredAccount.accountsScreenRows(listOf(login())),
        )
    }

    @Test fun delegatesFollowTheirLoginImmediately() {
        val jordan = delegated()
        val team = delegated("team-uuid", "team@example.org")

        assertEquals(
            listOf(
                AccountRowEntry(login(), underLogin = false),
                AccountRowEntry(jordan, underLogin = true),
                AccountRowEntry(team, underLogin = true),
            ),
            StoredAccount.accountsScreenRows(listOf(login(), jordan, team)),
        )
    }

    @Test fun eachDelegateHangsUnderItsOwnLoginWhateverTheStoredOrder() {
        // `all` is the order accounts were ADDED, so sam's login can land AFTER another login's
        // delegate. Grouping by position would then put ops under alex, and jordan under nobody.
        val sam = login().copy(id = "sam-uuid", username = "sam@example.org")
        val jordan = delegated()
        val ops = delegated("ops-uuid", "ops@example.org", loginId = "sam-uuid")
        val all = listOf(login(), jordan, ops, sam)

        assertEquals(
            listOf(
                AccountRowEntry(login(), underLogin = false),
                AccountRowEntry(jordan, underLogin = true),
                AccountRowEntry(sam, underLogin = false),
                AccountRowEntry(ops, underLogin = true),
            ),
            StoredAccount.accountsScreenRows(all),
        )
    }

    @Test fun anImportPendingAccountHasNoRowAtAll() {
        // The still-inert imported accounts have their own section above this list.
        val pending = login().copy(id = "pending-uuid", username = "new@example.org", importPending = true)

        assertEquals(
            listOf(
                AccountRowEntry(login(), underLogin = false),
                AccountRowEntry(delegated(), underLogin = true),
            ),
            StoredAccount.accountsScreenRows(listOf(login(), delegated(), pending)),
        )
    }

    @Test fun aDelegateWhoseLoginIsNotInTheListKeepsATopLevelRow() {
        // Dropping it would make the mailbox — and its own notification switch — unreachable.
        val orphan = delegated().copy(loginId = "gone-uuid")
        val sam = login().copy(id = "sam-uuid", username = "sam@example.org")

        assertEquals(
            listOf(
                AccountRowEntry(sam, underLogin = false),
                AccountRowEntry(orphan, underLogin = false),
            ),
            StoredAccount.accountsScreenRows(listOf(orphan, sam)),
        )
    }

    @Test fun aDelegateOfAnImportPendingLoginFallsBackToTopLevel() {
        // The login is not drawn here at all, so there is no row for the delegate to hang under.
        val pendingLogin = login().copy(importPending = true)

        assertEquals(
            listOf(AccountRowEntry(delegated(), underLogin = false)),
            StoredAccount.accountsScreenRows(listOf(pendingLogin, delegated())),
        )
    }

    @Test fun aDelegateWhoseLoginIsItselfADelegateFallsBackToTopLevel() {
        // A shared mailbox is never a parent: only a login opens a group, so a loginId naming
        // ANOTHER delegate hangs under nothing even though that delegate IS on screen — as its
        val jordan = delegated()
        val subOfJordan = delegated("nested-uuid", "nested@example.org", loginId = "jordan-uuid")

        assertEquals(
            listOf(
                AccountRowEntry(login(), underLogin = false),
                AccountRowEntry(jordan, underLogin = true),
                AccountRowEntry(subOfJordan, underLogin = false),
            ),
            StoredAccount.accountsScreenRows(listOf(login(), jordan, subOfJordan)),
        )
    }

    @Test fun anImportPendingDelegateHasNoRowAnywhere() {
        // Reachable: `AccountStore.importAccounts` gives the copy a fresh id and importPending,
        // but KEEPS its loginId — so an imported file can hold a delegate awaiting its sign-in.
        val pendingDelegate = delegated("pending-uuid", "pending@example.org").copy(importPending = true)

        assertEquals(
            listOf(
                AccountRowEntry(login(), underLogin = false),
                AccountRowEntry(delegated(), underLogin = true),
            ),
            StoredAccount.accountsScreenRows(listOf(login(), delegated(), pendingDelegate)),
        )
    }

    @Test fun noAccountsMeansNoRows() {
        assertEquals(emptyList<AccountRowEntry>(), StoredAccount.accountsScreenRows(emptyList()))
    }
}
