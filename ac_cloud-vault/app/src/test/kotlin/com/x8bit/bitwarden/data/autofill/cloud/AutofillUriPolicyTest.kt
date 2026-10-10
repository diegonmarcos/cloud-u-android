package com.x8bit.bitwarden.data.autofill.cloud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AutofillUriPolicyTest {

    private val site = "https://login.example.com"

    @Test
    fun `a known browser's web domain is used as reported`() {
        val d = AutofillUriPolicy.decide(site, "com.android.chrome", isKnownBrowser = true, isFleetSigned = false)
        assertEquals(AutofillUriPolicy.Decision.Use(site), d)
    }

    @Test
    fun `Cloud Browser's web domain is used as reported`() {
        val d = AutofillUriPolicy.decide(site, CLOUD_BROWSER_PACKAGE, isKnownBrowser = false, isFleetSigned = false)
        assertEquals(AutofillUriPolicy.Decision.Use(site), d)
    }

    @Test
    fun `a fleet app signed with the vault's key is trusted with its WebView's domain`() {
        val d = AutofillUriPolicy.decide(site, "com.diegonmarcos.cloudmail", isKnownBrowser = false, isFleetSigned = true)
        assertEquals(AutofillUriPolicy.Decision.Use(site), d)
    }

    @Test
    fun `any other app claiming a web domain must prove it with Digital Asset Links`() {
        val d = AutofillUriPolicy.decide(site, "com.unknown.app", isKnownBrowser = false, isFleetSigned = false)
        assertEquals(
            AutofillUriPolicy.Decision.VerifyAssetLinks(
                website = site,
                host = "login.example.com",
                packageName = "com.unknown.app",
                fallback = "androidapp://com.unknown.app",
            ),
            d,
        )
    }

    @Test
    fun `an unverified app's claimed website is never what a save writes`() {
        assertEquals(
            "androidapp://com.unknown.app",
            AutofillUriPolicy.decideForSave(site, "com.unknown.app", isKnownBrowser = false, isFleetSigned = false),
        )
        assertEquals(
            site,
            AutofillUriPolicy.decideForSave(site, "com.android.chrome", isKnownBrowser = true, isFleetSigned = false),
        )
    }

    @Test
    fun `a native app is matched by its package`() {
        val app = "androidapp://com.example.app"
        assertEquals(
            AutofillUriPolicy.Decision.Use(app),
            AutofillUriPolicy.decide(app, "com.example.app", isKnownBrowser = false, isFleetSigned = false),
        )
    }

    @Test
    fun `no package and no host means nothing to match`() {
        assertEquals(
            AutofillUriPolicy.Decision.Use(null),
            AutofillUriPolicy.decide("https://", null, isKnownBrowser = false, isFleetSigned = false),
        )
    }

    @Test
    fun `isAppUri tells app URIs from web URIs`() {
        assertEquals(true, AutofillUriPolicy.isAppUri("androidapp://com.example.app"))
        assertEquals(false, AutofillUriPolicy.isAppUri(site))
        assertEquals("androidapp://com.example.app", AutofillUriPolicy.appUri("com.example.app"))
    }

    @Test
    fun `hostOf lower-cases and refuses app URIs`() {
        assertEquals("login.example.com", AutofillUriPolicy.hostOf("https://LOGIN.Example.com/path"))
        assertNull(AutofillUriPolicy.hostOf("androidapp://com.example.app"))
        assertNull(AutofillUriPolicy.hostOf(null))
    }
}
