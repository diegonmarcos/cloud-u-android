package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers the default-identity resolver + backward-compatible deserialization (#78). */
class StoredAccountDefaultIdentityTest {

    private fun identity(id: String, email: String) =
        StoredIdentity(id = id, name = id, email = email)

    private fun account(
        identities: List<StoredIdentity>,
        defaultIdentityId: String? = null,
    ) = StoredAccount(
        id = "acc",
        server = "example.test",
        username = "user@example.test",
        identities = identities,
        defaultIdentityId = defaultIdentityId,
    )

    @Test fun default_matchesStoredId() {
        val work = identity("work", "work@example.test")
        val personal = identity("personal", "me@example.test")
        val acc = account(listOf(work, personal), defaultIdentityId = "personal")

        assertEquals(personal, acc.defaultIdentity())
    }

    @Test fun default_fallsBackToFirst_whenIdMissing() {
        // Stored default no longer exists among current identities (e.g. server refresh dropped it).
        val work = identity("work", "work@example.test")
        val personal = identity("personal", "me@example.test")
        val acc = account(listOf(work, personal), defaultIdentityId = "gone")

        assertEquals(work, acc.defaultIdentity())
    }

    @Test fun default_fallsBackToFirst_whenUnset() {
        // Neither address is the account's own, so there is nothing better than "the first".
        val work = identity("work", "work@example.test")
        val personal = identity("personal", "me@example.test")
        val acc = account(listOf(work, personal), defaultIdentityId = null)

        assertEquals(work, acc.defaultIdentity())
    }

    @Test fun addingAnIdentity_doesNotStealThePreselectedSender() {
        // The bug (#78): manual identities come first in resolvedIdentities(), so a freshly added
        // one used to land at the head of the list and became the pre-selected sender in silence.
        val added = identity("added", "new@example.test")
        val own = identity("own", "user@example.test")
        val acc = account(listOf(added, own), defaultIdentityId = null)

        assertEquals(own, acc.defaultIdentity())
    }

    @Test fun manualIdentity_doesNotOutrankTheServerIdentityThatIsTheAccount() {
        // Same thing across the two groups: the account's own address is discovered from the
        // server, and a manual identity added later must not displace it.
        val added = identity("added", "new@example.test")
        val own = identity("own", "user@example.test")
        val acc = StoredAccount(
            id = "acc",
            server = "example.test",
            username = "user@example.test",
            identities = listOf(added),
            serverIdentities = listOf(own),
        )

        assertEquals("own", acc.defaultIdentity()?.id)
    }

    @Test fun accountAddressMatch_ignoresCaseAndSpacing() {
        val added = identity("added", "new@example.test")
        val own = identity("own", "  User@Example.TEST ")
        val acc = account(listOf(added, own), defaultIdentityId = null)

        assertEquals(own, acc.defaultIdentity())
    }

    @Test fun default_fallsBackToFirst_whenNoIdentityIsTheAccountAddress() {
        val alias = identity("alias", "alias@example.test")
        val other = identity("other", "other@example.test")
        val acc = account(listOf(alias, other), defaultIdentityId = null)

        assertEquals(alias, acc.defaultIdentity())
    }

    @Test fun explicitDefault_stillWinsOverTheAccountAddress() {
        val chosen = identity("chosen", "alias@example.test")
        val own = identity("own", "user@example.test")
        val acc = account(listOf(chosen, own), defaultIdentityId = "chosen")

        assertEquals(chosen, acc.defaultIdentity())
    }

    @Test fun linkedSubAccount_ignoresTheLoginAddress() {
        // A shared mailbox's username IS the login's address (#31), so it must not be read as
        // "the account's own address" — that would pre-select the login as the sender.
        val ownAddress = identity("shared", "team@example.test")
        val loginAddress = identity("login", "user@example.test")
        val acc = StoredAccount(
            id = "sub",
            server = "example.test",
            username = "user@example.test", // the LOGIN's address, not this account's
            loginId = "login-acc",
            identities = listOf(ownAddress, loginAddress),
        )

        assertEquals(ownAddress, acc.defaultIdentity())
    }

    @Test fun oldSavedAccount_loadsWithNullDefault() {
        // A record serialized before this feature existed has no defaultIdentityId key.
        val legacyJson = """
            {"id":"acc","server":"example.test","username":"user@example.test",
             "identities":[{"id":"work","name":"Work","email":"work@example.test"}]}
        """.trimIndent()
        val account = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(StoredAccount.serializer(), legacyJson)

        assertNull(account.defaultIdentityId)
        assertEquals("work", account.defaultIdentity()?.id)
    }
}
