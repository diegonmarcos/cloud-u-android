package com.x8bit.bitwarden.data.autofill.cloud

import com.x8bit.bitwarden.data.autofill.cloud.ProviderChecklist.Action
import com.x8bit.bitwarden.data.autofill.cloud.ProviderChecklist.ProviderProbe
import com.x8bit.bitwarden.data.autofill.cloud.ProviderChecklist.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderChecklistTest {

    private val pkg = "com.diegonmarcos.cloudvault"
    private val ourAutofill = "$pkg/com.x8bit.bitwarden.Autofill.AutofillService"
    private val ourCredential = "$pkg/com.x8bit.bitwarden.Autofill.CredentialProviderService"
    private val otherAutofill = "com.google.android.gms/com.google.android.gms.autofill.service.AutofillService"
    private val otherCredential = "com.google.android.gms/com.google.android.gms.credential.manager.CredentialProviderService"

    private fun probe(
        sdk: Int = 34,
        supported: Boolean? = true,
        own: Boolean? = null,
        autofill: String? = null,
        credEnabled: Boolean? = null,
        primary: String? = null,
        list: String? = null,
    ) = ProviderProbe(sdk, supported, own, autofill, credEnabled, primary, list)

    private fun List<ProviderChecklist.Item>.state(id: String) = first { it.id == id }.state

    @Test
    fun `everything set on Android 14 is four green rows and complete`() {
        val items = ProviderChecklist.items(
            probe(own = true, autofill = ourAutofill, credEnabled = true, primary = ourCredential, list = ourCredential),
            pkg,
        )
        assertEquals(4, items.size)
        assertTrue(items.all { it.state == State.OK }, items.toString())
        assertTrue(ProviderChecklist.isComplete(items))
        assertEquals(Action.NONE, ProviderChecklist.nextAction(items))
    }

    @Test
    fun `below Android 14 only the two autofill rows exist`() {
        val items = ProviderChecklist.items(probe(sdk = 33, own = true), pkg)
        assertEquals(
            listOf(ProviderChecklist.ID_AUTOFILL_SUPPORTED, ProviderChecklist.ID_AUTOFILL_SERVICE),
            items.map { it.id },
        )
        assertTrue(ProviderChecklist.isComplete(items))
    }

    @Test
    fun `another autofill service selected is red, names it, and opens the autofill picker`() {
        val items = ProviderChecklist.items(probe(sdk = 30, own = false, autofill = otherAutofill), pkg)
        val row = items.first { it.id == ProviderChecklist.ID_AUTOFILL_SERVICE }
        assertEquals(State.MISSING, row.state)
        assertTrue(row.detail.contains("com.google.android.gms"), row.detail)
        assertEquals(Action.SET_AUTOFILL_SERVICE, ProviderChecklist.nextAction(items))
        assertFalse(ProviderChecklist.isComplete(items))
    }

    @Test
    fun `hasEnabledAutofillServices is believed even when the secure setting is hidden`() {
        val items = ProviderChecklist.items(probe(sdk = 30, own = true, autofill = null), pkg)
        assertEquals(State.OK, items.state(ProviderChecklist.ID_AUTOFILL_SERVICE))
    }

    @Test
    fun `nothing readable is grey, never green`() {
        val items = ProviderChecklist.items(probe(supported = null, own = null, autofill = null), pkg)
        assertTrue(items.none { it.state == State.OK }, items.toString())
        assertFalse(ProviderChecklist.isComplete(items))
    }

    @Test
    fun `provider enabled but another preferred is green then red`() {
        val items = ProviderChecklist.items(
            probe(own = true, credEnabled = true, primary = otherCredential, list = "$otherCredential:$ourCredential"),
            pkg,
        )
        assertEquals(State.OK, items.state(ProviderChecklist.ID_CREDENTIAL_ENABLED))
        assertEquals(State.MISSING, items.state(ProviderChecklist.ID_CREDENTIAL_PREFERRED))
        assertEquals(Action.CREDENTIAL_PROVIDER_SETTINGS, ProviderChecklist.nextAction(items))
    }

    @Test
    fun `an unreadable preferred provider is grey and does not block completion`() {
        val items = ProviderChecklist.items(probe(own = true, credEnabled = true, primary = null), pkg)
        assertEquals(State.UNKNOWN, items.state(ProviderChecklist.ID_CREDENTIAL_PREFERRED))
        assertTrue(ProviderChecklist.isComplete(items))
    }

    @Test
    fun `without the CredentialManager answer the enabled row falls back to the settings list`() {
        val on = ProviderChecklist.items(probe(own = true, credEnabled = null, primary = "", list = ourCredential), pkg)
        assertEquals(State.OK, on.state(ProviderChecklist.ID_CREDENTIAL_ENABLED))
        val off = ProviderChecklist.items(probe(own = true, credEnabled = null, primary = "", list = ""), pkg)
        assertEquals(State.MISSING, off.state(ProviderChecklist.ID_CREDENTIAL_ENABLED))
    }

    @Test
    fun `autofill unsupported on the device offers no settings button for it`() {
        val items = ProviderChecklist.items(probe(sdk = 30, supported = false, own = false, autofill = ""), pkg)
        assertEquals(State.MISSING, items.state(ProviderChecklist.ID_AUTOFILL_SUPPORTED))
        assertTrue(items.all { it.action == Action.NONE })
    }

    @Test
    fun `a different service class in our package does not count as ours`() {
        val items = ProviderChecklist.items(probe(sdk = 30, own = null, autofill = "$pkg/.SomethingElse"), pkg)
        assertEquals(State.MISSING, items.state(ProviderChecklist.ID_AUTOFILL_SERVICE))
    }
}
