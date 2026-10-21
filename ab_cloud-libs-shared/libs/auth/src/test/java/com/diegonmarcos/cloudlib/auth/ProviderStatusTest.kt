package com.diegonmarcos.cloudlib.auth

import com.diegonmarcos.cloudlib.auth.ProviderStatus.Probe
import com.diegonmarcos.cloudlib.auth.ProviderStatus.Signals
import com.diegonmarcos.cloudlib.auth.ProviderStatus.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

/** #1083 The four-provider status rows: pure logic over the REAL declaration (build.json::auth.sign_in.providers). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProviderStatusTest {

    private fun rows(s: Signals = Signals()) = ProviderStatus.rows(SignIn.providers, s).associateBy { it.family }

    @Test fun `exactly four rows, in the declared order, each with a Sign in way`() {
        val r = ProviderStatus.rows(SignIn.providers, Signals())
        assertEquals(listOf("authelia", "google", "github", "cloudflare"), r.map { it.family })
        assertEquals(listOf("Authelia", "Google", "GitHub", "Cloudflare Access"), r.map { it.label })
        r.forEach { assertTrue("${it.family}: no way to sign in", it.ways.isNotEmpty()) }
        assertEquals("Authelia keeps both its ways in one row", 2, r.first().ways.size)
    }

    @Test fun `signed out until something proves otherwise, and 'checking' until probed`() {
        val r = rows()
        r.values.forEach { assertEquals(State.SIGNED_OUT, it.state) }
        assertEquals("checking…", r.getValue("google").detail)
        val probed = rows(Signals(probes = r.keys.associateWith { Probe(true) }))
        probed.values.forEach { assertEquals("not signed in", it.detail) }
    }

    @Test fun `a stored Authelia bearer, a recorded identity and an active tunnel are signed in`() {
        val r = rows(Signals(autheliaBearer = true, signedIn = mapOf("github" to "diegonmarcos"), vpnActive = true))
        assertEquals(State.SIGNED_IN, r.getValue("authelia").state)
        assertEquals("signed in as diegonmarcos", r.getValue("github").detail)
        assertEquals(State.SIGNED_IN, r.getValue("cloudflare").state)
        assertEquals("a bearer on this phone says nothing about Google", State.SIGNED_OUT, r.getValue("google").state)
    }

    @Test fun `a vpn does not sign anyone in but Cloudflare`() {
        val r = rows(Signals(vpnActive = true))
        assertEquals(State.SIGNED_OUT, r.getValue("authelia").state)
        assertEquals(State.SIGNED_OUT, r.getValue("github").state)
    }

    @Test fun `a probe that fails is unreachable, a server-side off switch is disabled`() {
        val r = rows(Signals(probes = mapOf("google" to Probe(false), "github" to Probe(true, enabled = false), "authelia" to Probe(true), "cloudflare" to Probe(true))))
        assertEquals(State.UNREACHABLE, r.getValue("google").state)
        assertEquals(State.DISABLED, r.getValue("github").state)
        assertEquals(State.SIGNED_OUT, r.getValue("authelia").state)
        // being signed in wins over a failed probe: the phone holds the proof
        val both = rows(Signals(signedIn = mapOf("google" to "me@diegonmarcos.com"), probes = mapOf("google" to Probe(false))))
        assertEquals(State.SIGNED_IN, both.getValue("google").state)
    }

    @Test fun `probe documents parse - reachable on 2xx and 3xx, enabled read from the broker health body`() {
        assertEquals(Probe(true, true), ProviderStatus.parseProbe(200, """{"enabled": true, "provider": "google"}"""))
        assertEquals(Probe(true, false), ProviderStatus.parseProbe(200, """{"enabled": false}"""))
        assertEquals(Probe(true, null), ProviderStatus.parseProbe(200, """{"status":"OK"}"""))
        assertEquals(Probe(true, null), ProviderStatus.parseProbe(302, ""))
        assertEquals(Probe(false), ProviderStatus.parseProbe(404, """{"enabled": true}"""))
        assertEquals(Probe(false), ProviderStatus.parseProbe(502, ""))
        assertNull(ProviderStatus.parseProbe(200, "not json").enabled)
    }

    @Test fun `a plain-http or blank probe url is never fetched`() {
        assertFalse(ProviderStatus.probe("").reachable)
        assertFalse(ProviderStatus.probe("http://127.0.0.1:9/never").reachable)
    }

    @Test fun `a provider with nothing to start from is not configured, not signed out`() {
        val dead = SignIn.providers.filter { it.id == "github" }.map { it.copy(clientId = "") }
        val r = ProviderStatus.rows(dead, Signals()).single()
        assertEquals(State.NOT_CONFIGURED, r.state)
    }
}
