package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.PasskeySupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Passkeys in WebView: the feature gate, the fallback trigger, the secure-browser launch. */
class PasskeySupportTest {

    // Google's page when the browser has no WebAuthn (visible text only).
    private val googleUnsupported = """
        Verify to continue as me@diegonmarcos.com
        Your account uses a passkey for two-factor authentication, but the browser you're using does not support
        passkeys. Try a different browser or device.
    """.trimIndent()

    @Test fun `mode is FOR_BROWSER only when the WebView has the feature`() {
        assertEquals(PasskeySupport.Mode.FOR_BROWSER, PasskeySupport.modeFor(true))
        assertNull(PasskeySupport.modeFor(false))
        assertNull(PasskeySupport.modeFor(true, enabled = false))
    }

    @Test fun `the Google does-not-support page triggers the offer`() {
        val u = "https://accounts.google.com/v3/signin/challenge/pk"
        assertTrue(PasskeySupport.isUnsupportedPage(u, googleUnsupported))
        assertTrue(PasskeySupport.shouldOfferSecureBrowser(u, googleUnsupported, false, PasskeySupport.Mode.FOR_BROWSER))
    }

    @Test fun `other pages and other hosts do not trigger it`() {
        assertFalse(PasskeySupport.isUnsupportedPage("https://accounts.google.com/signin", "Enter your password"))
        assertFalse(PasskeySupport.isUnsupportedPage("https://evil.example/", googleUnsupported))
        assertFalse(PasskeySupport.isUnsupportedPage("https://accounts.google.com.evil.example/", googleUnsupported))
        assertFalse(PasskeySupport.isUnsupportedPage("http://accounts.google.com/", googleUnsupported))
        assertFalse(PasskeySupport.shouldOfferSecureBrowser("https://example.com/", "hello", false, PasskeySupport.Mode.FOR_BROWSER))
    }

    @Test fun `a passkey challenge url only offers when WebAuthn is off`() {
        val u = "https://accounts.google.com/v3/signin/challenge/pk/presend"
        assertTrue(PasskeySupport.isPasskeyChallengeUrl(u))
        assertTrue(PasskeySupport.shouldOfferSecureBrowser(u, null, false, null))
        assertFalse(PasskeySupport.shouldOfferSecureBrowser(u, null, false, PasskeySupport.Mode.FOR_BROWSER))
        assertFalse(PasskeySupport.isPasskeyChallengeUrl("https://accounts.google.com/v3/signin/challenge/pwd"))
    }

    @Test fun `a WebAuthn call that fails fast offers, a user cancel does not`() {
        assertTrue(PasskeySupport.isWebAuthnFailure("NotSupportedError", 5))
        assertTrue(PasskeySupport.isWebAuthnFailure("SecurityError", 5000))
        assertTrue(PasskeySupport.isWebAuthnFailure("NotAllowedError", 50))
        assertFalse(PasskeySupport.isWebAuthnFailure("NotAllowedError", 8000))
        assertFalse(PasskeySupport.isWebAuthnFailure("AbortError", 10))
        assertTrue(PasskeySupport.shouldOfferSecureBrowser("https://example.com/login", null, true, PasskeySupport.Mode.FOR_BROWSER))
    }

    @Test fun `the secure browser is never us, and only https leaves`() {
        val own = "com.diegonmarcos.cloudbrowser"
        assertEquals("com.android.chrome", PasskeySupport.pickBrowserPackage(listOf(own, "com.android.chrome"), own, own))
        assertEquals("org.mozilla.firefox", PasskeySupport.pickBrowserPackage(listOf("com.android.chrome", "org.mozilla.firefox"), own, "org.mozilla.firefox"))
        assertNull(PasskeySupport.pickBrowserPackage(listOf(own), own, own))
        val l = PasskeySupport.secureBrowserLaunch("https://accounts.google.com/x", "com.android.chrome")!!
        assertEquals("https://accounts.google.com/x", l.url)
        assertEquals("com.android.chrome", l.browserPackage)
        assertTrue(l.customTab)
        assertNull(PasskeySupport.secureBrowserLaunch("http://accounts.google.com/x", null))
        assertNull(PasskeySupport.secureBrowserLaunch("intent://x#Intent;end", null))
    }
}
