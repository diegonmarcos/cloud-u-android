package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The addresses that ARE the user on one account — the list every "is this me?" refusal is
 */
class AccountAddressesTest {

    private fun id(email: String, name: String = "Alex") =
        StoredIdentity(id = email, name = name, email = email)

    @Test fun `every identity contributes its ADDRESS, not its display name`() {
        val addresses = accountAddresses(
            listOf(id("alex.rivera@masto.top", name = "Alex Rivera"), id("alex@alias.example")),
            username = "alex.rivera@masto.top",
        )
        assertTrue(
            "an alias the account can send as must be in the list — it is just as much the user " +
                "as the login is, and it is the address a rule would be written against",
            "alex@alias.example" in addresses,
        )
        assertTrue("…and so is the first identity", "alex.rivera@masto.top" in addresses)
        assertTrue(
            "a display name is not an address and must never stand in for one: it can never " +
                "equal what a From: header carries, so the list silently stops matching anything",
            addresses.none { it == "Alex Rivera" || it == "Alex" },
        )
    }

    @Test fun `the login is there even when no identity names it`() {
        // A linked sub-account resolves its identities through its LOGIN (issue #31), and an
        // account whose identity list has not synced yet has only its login to go on.
        assertEquals(
            listOf("shared@example.test", "login@example.test"),
            accountAddresses(listOf(id("shared@example.test")), username = "login@example.test"),
        )
    }

    @Test fun `an account with no identities at all still knows its login`() {
        assertEquals(listOf("login@example.test"), accountAddresses(emptyList(), "login@example.test"))
    }

    @Test fun `no account at all is an empty list, not a list holding nothing`() {
        // Null username = no credentials. The list must be empty rather than contain a blank
        // string: blockableSender compares addresses, and "" would match nothing anyway, but a
        // list that is empty says "cannot tell who I am" where a list of one lies about it.
        assertEquals(emptyList<String>(), accountAddresses(emptyList(), username = null))
    }
}
