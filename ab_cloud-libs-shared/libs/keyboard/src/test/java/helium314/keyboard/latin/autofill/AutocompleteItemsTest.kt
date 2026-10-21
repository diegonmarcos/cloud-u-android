// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autofill

import com.diegonmarcos.superapp.autofill.AutofillAddress
import com.diegonmarcos.superapp.autofill.AutofillContact
import com.diegonmarcos.superapp.autofill.AutofillProfile
import com.diegonmarcos.superapp.autofill.AutofillSchemas
import com.diegonmarcos.superapp.autofill.AutofillSot
import com.diegonmarcos.superapp.autofill.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1077 items 7 and 8: the schemas come from one declaration; the list masks sensitive fields and needs unlock. */
class AutocompleteItemsTest {
    private val profile = AutofillProfile(
        id = 1, label = "Spain-A", isDefault = true,
        fields = mapOf("given_name" to "Testy", "bday" to "1990-01-01"),
        addresses = listOf(AutofillAddress(id = 1, profileId = 1, fields = mapOf("city" to "Sampletown", "postal_code" to "00000"))),
        contacts = listOf(AutofillContact(id = 1, profileId = 1, kind = "email", value = "testy@example.invalid", type = "personal")),
    )

    @Test fun theAccountTablesAreTheProvidersOwnColumns() {
        val tables = AutofillSchemas.account()
        assertEquals(AutofillSot.PATHS, tables.map { it.id.removePrefix("account.") })
        for (t in tables) {
            val path = t.id.removePrefix("account.")
            val expected = AutofillSot.columns(path).filterNot { it == AutofillSot.ID || it == AutofillSot.UPDATED_AT }
            assertEquals("columns of $path", expected, t.fields.map { it.key })
            assertTrue(t.store.endsWith("/$path"))
        }
    }

    @Test fun everySchemaOfTheFleetIsInTheOnePlace() {
        val ids = AutofillSchemas.all().map { it.id }
        for (id in listOf("account.profiles", "account.addresses", "account.contacts", "account.rules", "account.snippets",
            "account.identity-handoff", "vault.login", "vault.card", "vault.identity", "vault.identity-fields"))
            assertTrue("$id is declared", id in ids)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(AutofillSchemas.vault().all { t -> t.fields.all { it.sensitive } }) // the vault's fields are all secrets
        assertTrue(AutofillSchemas.identityHandoff().fields.all { it.sensitive })
        assertTrue(AutofillSchemas.account().flatMap { it.fields }.any { it.key == "bday" && it.sensitive })
    }

    @Test fun rowsComeFromProfilesContactsAddressesSnippetsThenTheVault() {
        val items = AutocompleteItems.build(listOf(profile), listOf(Snippet(1, "About me", "Hello there")))
        assertTrue(items.any { it.label == "Given name" && it.value == "Testy" && !it.sensitive })
        assertTrue(items.any { it.label.startsWith("Email") && it.value == "testy@example.invalid" })
        assertTrue(items.any { it.label == "City" && it.value == "Sampletown" })
        assertTrue(items.any { it.group == "Snippets" && it.label == "About me" })
        val vault = items.filter { it.inVault }
        assertEquals(AutofillSchemas.vault().map { it.title }, vault.map { it.label })
        assertTrue(items.takeLast(vault.size).all { it.inVault }) // the vault rows close the list
    }

    @Test fun sensitiveRowsAreMaskedUntilUnlocked() {
        val items = AutocompleteItems.build(listOf(profile), emptyList())
        val bday = items.first { it.label == "Bday" }
        assertTrue(bday.sensitive)
        assertEquals(AutocompleteItems.MASK, AutocompleteItems.shown(bday, unlocked = false))
        assertEquals("1990-01-01", AutocompleteItems.shown(bday, unlocked = true))
        val city = items.first { it.label == "City" }
        assertEquals("Sampletown", AutocompleteItems.shown(city, unlocked = false))
    }

    @Test fun aSensitiveRowNeedsTheUnlockToPasteOrCopyAndAVaultRowOpensTheVault() {
        val items = AutocompleteItems.build(listOf(profile), emptyList())
        val bday = items.first { it.label == "Bday" }
        assertEquals(AutocompleteItems.Access.NEEDS_UNLOCK, AutocompleteItems.access(bday, false))
        assertEquals(AutocompleteItems.Access.USE, AutocompleteItems.access(bday, true))
        assertEquals(AutocompleteItems.Access.USE, AutocompleteItems.access(items.first { it.label == "City" }, false))
        val vault = items.first { it.inVault }
        assertEquals(AutocompleteItems.Access.OPEN_VAULT, AutocompleteItems.access(vault, true))
        assertNull(vault.value)
        assertFalse(AutocompleteItems.shown(vault, true).contains("•"))
    }

    @Test fun theUnlockExpiresAndCanBeLocked() {
        AutocompleteUnlock.lock()
        assertFalse(AutocompleteUnlock.isUnlocked(1_000))
        AutocompleteUnlock.grant(1_000)
        assertTrue(AutocompleteUnlock.isUnlocked(1_000 + AutocompleteUnlock.TTL_MS - 1))
        assertFalse(AutocompleteUnlock.isUnlocked(1_000 + AutocompleteUnlock.TTL_MS))
        AutocompleteUnlock.grant(5_000)
        AutocompleteUnlock.lock()
        assertFalse(AutocompleteUnlock.isUnlocked(5_001))
    }
}
