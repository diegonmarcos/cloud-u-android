package com.diegonmarcos.superapp.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The legacy device id migration: pure, the alias table comes from the declaration handed in. */
class AccountDeviceAliasTest {
    private fun b64(json: String) = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
    private val aliases = AccountDevice.aliases(b64("""{"device_aliases":{"old":"new-id"}}"""))

    @Test fun aliasesAreReadFromTheDeclaration() {
        assertEquals(mapOf("old" to "new-id"), aliases)
        assertEquals(emptyMap<String, String>(), AccountDevice.aliases(b64("{}")))
        assertEquals(emptyMap<String, String>(), AccountDevice.aliases("not base64 !"))
    }

    @Test fun legacyIdMigratesOnlyWhenTheVaultNoLongerListsItAndListsTheTarget() {
        assertEquals("new-id", AccountDevice.migrated("old", setOf("new-id", "DEFAULT"), aliases))
        assertNull(AccountDevice.migrated("old", setOf("old", "new-id"), aliases))   // old still exists: keep it
        assertNull(AccountDevice.migrated("old", setOf("other"), aliases))           // target not listed: keep it
        assertNull(AccountDevice.migrated("old", emptySet(), aliases))               // listing unknown: keep it
        assertNull(AccountDevice.migrated("fine", setOf("new-id"), aliases))         // not an aliased id
        assertNull(AccountDevice.migrated("", setOf("new-id"), aliases))
    }
}
