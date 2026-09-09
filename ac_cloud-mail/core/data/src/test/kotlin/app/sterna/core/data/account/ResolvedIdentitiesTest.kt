package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two-group identity model: manual [StoredAccount.identities] overriding server
 */
class ResolvedIdentitiesTest {

    private fun id(id: String, email: String, name: String = id, signature: String = "") =
        StoredIdentity(id = id, name = name, email = email, signature = signature)

    private fun account(
        manual: List<StoredIdentity> = emptyList(),
        server: List<StoredIdentity> = emptyList(),
        defaultIdentityId: String? = null,
    ) = StoredAccount(
        id = "acc",
        server = "example.test",
        username = "user@example.test",
        accountName = "User",
        signature = "legacy-sig",
        identities = manual,
        serverIdentities = server,
        defaultIdentityId = defaultIdentityId,
    )

    @Test fun manualOverridesServer_sameEmail_manualWins() {
        // Same address, differing case/whitespace: the manual entry must win (its name + signature).
        val server = id("srv", " Work@Example.test ", name = "Server Name", signature = "srv-sig")
        val manual = id("mine", "work@example.test", name = "My Name", signature = "my-sig")
        val resolved = account(manual = listOf(manual), server = listOf(server)).resolvedIdentities()

        assertEquals(1, resolved.size)
        assertEquals("mine", resolved[0].id)
        assertEquals("My Name", resolved[0].name)
        assertEquals("my-sig", resolved[0].signature)
    }

    @Test fun pristineServerIdentity_appears_thenSelfCorrects_whenServerDropsIt() {
        val serverAlias = id("srv-alias", "alias@example.test")
        val manual = id("mine", "me@example.test")

        val withAlias = account(manual = listOf(manual), server = listOf(serverAlias))
        assertTrue(withAlias.resolvedIdentities().any { it.email == "alias@example.test" })

        // Server later drops the alias; with no manual copy it must disappear (no stale freeze).
        val afterServerRefresh = withAlias.copy(serverIdentities = emptyList())
        assertFalse(afterServerRefresh.resolvedIdentities().any { it.email == "alias@example.test" })
        assertEquals(listOf("me@example.test"), afterServerRefresh.resolvedIdentities().map { it.email })
    }

    @Test fun savePersistsOnlyManual_serverAliasesNotFrozen() {
        // Simulate the editor's save: write back ONLY the manual list. Server identities must be
        // untouched, and a later server-side removal must self-correct (the fold-on-save bug fix).
        val serverAlias = id("srv-alias", "alias@example.test")
        val manual = id("mine", "me@example.test")
        val before = account(manual = listOf(manual), server = listOf(serverAlias))

        // What SettingsScreen.save() does: setIdentities(manualOnly) — never the merged list.
        val manualOnly = before.resolvedIdentities().filter { r -> r.id == "mine" }
        val afterSave = before.copy(identities = manualOnly)

        assertEquals(listOf("mine"), afterSave.identities.map { it.id })
        assertFalse(afterSave.identities.any { it.email == "alias@example.test" })
        // Alias still merges live while the server advertises it...
        assertTrue(afterSave.resolvedIdentities().any { it.email == "alias@example.test" })
        // ...and vanishes once the server drops it, since it was never frozen into identities.
        assertFalse(afterSave.copy(serverIdentities = emptyList()).resolvedIdentities()
            .any { it.email == "alias@example.test" })
    }

    @Test fun emptyBoth_fallsBackToSynthesizedDefault() {
        val resolved = account(manual = emptyList(), server = emptyList()).resolvedIdentities()

        assertEquals(1, resolved.size)
        assertEquals("default", resolved[0].id)
        assertEquals("user@example.test", resolved[0].email)
        assertEquals("User", resolved[0].name)
        assertEquals("legacy-sig", resolved[0].signature)
    }

    @Test fun resolved_manualFirst_thenServer() {
        val manual = id("mine", "me@example.test")
        val serverAlias = id("srv", "alias@example.test")
        val resolved = account(manual = listOf(manual), server = listOf(serverAlias)).resolvedIdentities()

        assertEquals(listOf("mine", "srv"), resolved.map { it.id })
    }

    @Test fun defaultIdentity_resolvesServerId_underManualFirstOrder() {
        val manual = id("mine", "me@example.test")
        val serverAlias = id("srv", "alias@example.test")
        val acc = account(manual = listOf(manual), server = listOf(serverAlias), defaultIdentityId = "srv")

        assertEquals("srv", acc.defaultIdentity()?.id)
    }

    @Test fun defaultIdentity_manualFirstIsFallback_whenUnset() {
        val manual = id("mine", "me@example.test")
        val serverAlias = id("srv", "alias@example.test")
        val acc = account(manual = listOf(manual), server = listOf(serverAlias), defaultIdentityId = null)

        assertEquals("mine", acc.defaultIdentity()?.id)
    }

    // --- normalizeManualIdentities: self-heal old merge-on-save fold pollution ---

    @Test fun normalize_collapsesByteIdenticalDuplicates() {
        // Three identical rows (the admin@masto.top pollution) collapse to one.
        val dup = id("a1", "admin@masto.top", name = "Admin", signature = "sig")
        val dup2 = id("a2", "admin@masto.top", name = "Admin", signature = "sig")
        val dup3 = id("a3", "admin@masto.top", name = "Admin", signature = "sig")
        val cleaned = StoredAccount.normalizeManualIdentities(listOf(dup, dup2, dup3), emptyList())

        assertEquals(1, cleaned.size)
        assertEquals("a1", cleaned[0].id) // first occurrence kept
    }

    @Test fun normalize_dropsManualCopyOfServerIdentity() {
        val server = id("srv", "admin@masto.top", name = "Admin", signature = "sig")
        val frozen = id("manual", "admin@masto.top", name = "Admin", signature = "sig")
        val cleaned = StoredAccount.normalizeManualIdentities(listOf(frozen), listOf(server))

        assertTrue(cleaned.isEmpty())
    }

    @Test fun normalize_keepsSameEmailButDifferentName() {
        // A genuine override (same address, different name) survives.
        val server = id("srv", "admin@masto.top", name = "Admin", signature = "sig")
        val override = id("manual", "admin@masto.top", name = "Custom Name", signature = "sig")
        val cleaned = StoredAccount.normalizeManualIdentities(listOf(override), listOf(server))

        assertEquals(listOf("manual"), cleaned.map { it.id })
    }

    @Test fun normalize_keepsDistinctIdentity_differentEmail() {
        val server = id("srv", "admin@masto.top", name = "Admin", signature = "sig")
        val distinct = id("manual", "me@example.test", name = "Me", signature = "")
        val cleaned = StoredAccount.normalizeManualIdentities(listOf(distinct), listOf(server))

        assertEquals(listOf("manual"), cleaned.map { it.id })
    }

    // --- #79: editing a server identity's name/signature via a manual override ---

    @Test fun serverSignatureEdit_persistedAsOverride_thatWins() {
        // The editor upserts an override (server id + email, edited signature). It must survive the
        // save-time normalize (it differs from the server value) and win in resolvedIdentities().
        val server = id("s", "admin@masto.top", name = "Admin", signature = "old-sig")
        val edited = id("s", "admin@masto.top", name = "Admin", signature = "new-sig")

        val cleaned = StoredAccount.normalizeManualIdentities(listOf(edited), listOf(server))
        assertEquals(listOf("s"), cleaned.map { it.id }) // override kept (signature differs)

        val acc = account(manual = cleaned, server = listOf(server))
        val resolved = acc.resolvedIdentities()
        assertEquals(1, resolved.size)
        assertEquals("new-sig", resolved[0].signature) // #79: the edited signature applies
    }

    @Test fun pristineServerIdentity_notPersisted_andSelfCorrects() {
        // An override identical to the server value (user opened but did not really change it) is a
        // frozen copy: dropped on save, so the server identity keeps self-correcting.
        val server = id("s", "admin@masto.top", name = "Admin", signature = "sig")
        val pristine = id("s", "admin@masto.top", name = "Admin", signature = "sig")

        val cleaned = StoredAccount.normalizeManualIdentities(listOf(pristine), listOf(server))
        assertTrue(cleaned.isEmpty()) // not persisted

        val saved = account(manual = cleaned, server = listOf(server))
        assertTrue(saved.resolvedIdentities().any { it.email == "admin@masto.top" })
        // Server later drops it: with no frozen manual copy it disappears.
        assertFalse(saved.copy(serverIdentities = emptyList()).resolvedIdentities()
            .any { it.email == "admin@masto.top" })
    }

    @Test fun purelyManualIdentity_unaffectedByNormalizeAndResolve() {
        val server = id("s", "admin@masto.top", name = "Admin", signature = "sig")
        val manual = id("mine", "me@example.test", name = "Me", signature = "my-sig")

        val cleaned = StoredAccount.normalizeManualIdentities(listOf(manual), listOf(server))
        assertEquals(listOf("mine"), cleaned.map { it.id })

        val resolved = account(manual = cleaned, server = listOf(server)).resolvedIdentities()
        assertEquals(listOf("me@example.test", "admin@masto.top"), resolved.map { it.email })
        assertEquals("my-sig", resolved.first { it.email == "me@example.test" }.signature)
    }

    @Test fun distinctServerIdentities_collapsesByteDuplicates_keepsDistinct() {
        // Server returns two byte-identical admin rows + one distinct address (the masto.top quirk).
        val dup1 = id("s1", "admin@masto.top", name = "Admin")
        val dup2 = id("s2", "Admin@Masto.top", name = "Admin")
        val distinct = id("s3", "emon@masto.top", name = "Emon")
        val display = StoredAccount.distinctServerIdentities(listOf(dup1, dup2, distinct))

        assertEquals(listOf("s1", "s3"), display.map { it.id }) // first dup kept, distinct kept
    }

    @Test fun defaultIdentity_serverOverriddenByManual_degradesToFirst() {
        // Default pointed at a server identity that a same-email manual entry now overrides:
        // the server id is deduped out, so it must degrade to the first resolved (the manual one).
        val server = id("srv", "work@example.test")
        val manual = id("mine", "work@example.test")
        val acc = account(manual = listOf(manual), server = listOf(server), defaultIdentityId = "srv")

        assertNull(acc.resolvedIdentities().firstOrNull { it.id == "srv" })
        assertEquals("mine", acc.defaultIdentity()?.id)
    }

    // --- issue #31: a linked sub-account's From ---
    //
    // A linked sub-account's [StoredAccount.username] is its LOGIN's address (the one that
    // authenticates), so the standalone fallback identity would be a silently wrong From. The

    private fun standalone() = StoredAccount(
        id = "login-uuid",
        server = "https://mail.example.org",
        username = "alex@example.org",
        accountName = "Alex",
        signature = "-- Alex",
    )

    private fun linked(accountName: String) = StoredAccount(
        id = "sub-uuid",
        server = "https://mail.example.org",
        username = "alex@example.org", // the login's address, NOT the sub-account's
        accountName = accountName,
        loginId = "login-uuid",
        jmapAccountId = "u",
    )

    @Test fun standaloneFallsBackToItsOwnUsername() {
        val identities = standalone().resolvedIdentities()

        assertEquals(1, identities.size)
        assertEquals("alex@example.org", identities[0].email)
        assertEquals("Alex", identities[0].name)
        assertEquals("-- Alex", identities[0].signature)
    }

    @Test fun linkedDerivesItsAddressFromTheSessionAccountName() {
        val identities = linked(accountName = "jordan@example.org").resolvedIdentities()

        assertEquals(1, identities.size)
        assertEquals("jordan@example.org", identities[0].email)
        // Never the login's address under the sub-account's name.
        assertTrue(identities.none { it.email == "alex@example.org" })
    }

    @Test fun linkedWithDisplayNameOnlyOffersNothingRatherThanAWrongFrom() {
        assertTrue(linked(accountName = "Jordan Lee").resolvedIdentities().isEmpty())
        assertTrue(linked(accountName = "").resolvedIdentities().isEmpty())
        // Not an address either: two @s or embedded spaces.
        assertTrue(linked(accountName = "a@b@c").resolvedIdentities().isEmpty())
        assertTrue(linked(accountName = "jordan lee@example.org").resolvedIdentities().isEmpty())
    }

    @Test fun linkedStoredIdentitiesStillWin() {
        val manual = StoredIdentity(id = "m1", name = "Jordan", email = "jordan@example.org")
        val identities = linked(accountName = "Jordan Lee").copy(identities = listOf(manual)).resolvedIdentities()

        assertEquals(listOf(manual), identities)
    }
}
