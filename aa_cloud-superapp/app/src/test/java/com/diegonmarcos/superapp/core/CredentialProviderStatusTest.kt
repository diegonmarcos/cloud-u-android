package com.diegonmarcos.superapp.core

import com.diegonmarcos.superapp.core.CredentialProviderStatus as C
import com.diegonmarcos.superapp.core.CredentialProviderStatus.State
import com.diegonmarcos.superapp.core.CredentialProviderStatus.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Is Cloud Vault the DEFAULT provider: the parse, the three states, the intent per API level, the hidden row. */
class CredentialProviderStatusTest {
    private val cred = "${C.VAULT_PACKAGE}/${C.CREDENTIAL_SERVICE}"
    private val fill = "${C.VAULT_PACKAGE}/${C.AUTOFILL_SERVICE}"
    private val other = "com.other.pm/com.other.pm.CredService"

    @Test fun `component present, absent and malformed`() {
        assertEquals(listOf(C.Component("a.b", "a.b.C")), C.parseComponents("a.b/a.b.C"))
        assertEquals(listOf(C.Component("a.b", "a.b.C")), C.parseComponents("a.b/.C"))
        assertEquals(2, C.parseComponents("a.b/a.b.C:x.y/x.y.Z").size)
        assertTrue(C.parseComponents(null).isEmpty())
        assertTrue(C.parseComponents("").isEmpty())
        for (bad in listOf("garbage", "/cls", "pkg/", "a/b/c", "a b/c", ":::")) assertTrue(bad, C.parseComponents(bad).isEmpty())
        // a malformed entry never hides a good neighbour
        assertEquals(1, C.parseComponents("junk:a.b/a.b.C").size)
    }

    @Test fun `credential state is Default, Enabled-not-default, Off`() {
        assertEquals(State.DEFAULT, C.credentialState(cred, "$other:$cred"))
        assertEquals(State.ENABLED, C.credentialState(other, "$other:$cred"))
        assertEquals(State.ENABLED, C.credentialState("", cred))
        assertEquals(State.OFF, C.credentialState(other, other))
        assertEquals(State.OFF, C.credentialState("", ""))
        assertEquals(State.OFF, C.credentialState("garbage", "::"))
    }

    @Test fun `autofill state is Default or Off`() {
        assertEquals(State.DEFAULT, C.autofillState(fill))
        assertEquals(State.OFF, C.autofillState(""))
        assertEquals(State.OFF, C.autofillState("com.other/com.other.Fill"))
        assertEquals(State.OFF, C.autofillState("not a component"))
    }

    @Test fun `unreadable is unknown, never a guess`() {
        assertEquals(State.UNKNOWN, C.credentialState(null, null))
        assertEquals(State.UNKNOWN, C.credentialState(null, other))
        assertEquals(State.DEFAULT, C.credentialState(cred, null))
        assertEquals(State.UNKNOWN, C.autofillState(null))
        assertEquals(State.UNKNOWN, C.statusOf(34, null, null, fill).overall)
    }

    @Test fun `overall three-state and only Default completes`() {
        assertEquals(State.DEFAULT, C.statusOf(34, cred, cred, fill).overall)
        assertTrue(C.statusOf(34, cred, cred, fill).complete)
        assertEquals(State.ENABLED, C.statusOf(34, other, "$other:$cred", fill).overall)
        assertFalse(C.statusOf(34, other, "$other:$cred", fill).complete)
        assertEquals(State.ENABLED, C.statusOf(34, cred, cred, "").overall)
        assertEquals(State.OFF, C.statusOf(34, "", "", "").overall)
        assertFalse(C.statusOf(34, "", "", "").complete)
    }

    @Test fun `below API 34 only autofill counts`() {
        val s = C.statusOf(33, null, null, fill)
        assertNull(s.credential)
        assertTrue(s.complete)
        assertEquals(Step.AUTOFILL, C.statusOf(33, null, null, "").nextStep)
    }

    @Test fun `next step is credential first, then autofill`() {
        assertEquals(Step.CREDENTIAL, C.statusOf(34, other, other, fill).nextStep)
        assertEquals(Step.AUTOFILL, C.statusOf(34, cred, cred, "").nextStep)
    }

    @Test fun `intent selection per API level`() {
        val uri = "package:${C.VAULT_PACKAGE}"
        assertEquals(C.IntentSpec(C.ACTION_CREDENTIAL_PROVIDER, uri), C.intentCandidates(Step.CREDENTIAL, 34, "Google").first())
        assertEquals(C.IntentSpec(C.ACTION_REQUEST_SET_AUTOFILL_SERVICE, uri), C.intentCandidates(Step.CREDENTIAL, 33, "Google").first())
        assertEquals(C.IntentSpec(C.ACTION_REQUEST_SET_AUTOFILL_SERVICE, uri), C.intentCandidates(Step.AUTOFILL, 36, "Google").first())
        assertEquals(C.IntentSpec(C.ACTION_SETTINGS), C.intentCandidates(Step.AUTOFILL, 30, "Google").last())
        assertEquals(C.IntentSpec(C.ACTION_SETTINGS), C.intentCandidates(Step.CREDENTIAL, 34, "Google").last())
    }

    @Test fun `Samsung gets the General management fallback before general settings`() {
        val c = C.intentCandidates(Step.CREDENTIAL, 34, "samsung")
        assertEquals("com.android.settings" to C.SAMSUNG_GENERAL_MANAGEMENT, c[c.size - 2].component)
        assertTrue(C.intentCandidates(Step.CREDENTIAL, 34, "Google").none { it.component != null })
    }

    @Test fun `row is hidden when Vault is not installed`() {
        assertFalse(C.rowVisible(false))
        assertTrue(C.rowVisible(true))
    }
}
