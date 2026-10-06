package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserNavPolicy
import com.diegonmarcos.superapp.browser.BrowserNavPolicy.Decision
import com.diegonmarcos.superapp.browser.BrowserTab
import com.diegonmarcos.superapp.browser.BrowserTabStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #893 Google sign-in stuck in Cloud Browser, as behaviour. Root causes pinned here:
 *  - ERR_CACHE_MISS on accounts.google.com/gsi/transform: a POST result was committed as the tab's url and
 *    saved in its WebView state, then replayed from the cache on the next rebuild;
 *  - a WebView user agent carries `; wv)` / `Version/x.x`, which Google refuses (disallowed_useragent);
 *  - app links (intent:) must not leave the browser, accounts.google.com must load untouched.
 */
class BrowserSignInTest {

    private val wvUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UD1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 Mobile Safari/537.36"

    @Test
    fun `a POST result page is never committed as a tab's url`() {
        val tab = BrowserTab("https://example.com/app", "App", 1L, id = "t1")
        val after = BrowserTabStore.commit(listOf(tab), "t1", "https://accounts.google.com/gsi/transform", "Redirecting")
        assertEquals("https://example.com/app", after.single().url)
        assertFalse(BrowserTabStore.shouldCommit("https://accounts.google.com/gsi/transform"))
        assertFalse(BrowserTabStore.shouldCommit("https://accounts.google.com/signin/oauth/consent?x=1"))
        assertFalse(BrowserTabStore.shouldCommit("https://accounts.google.com/o/oauth2/v2/auth?client_id=1"))
        assertTrue(BrowserTabStore.shouldCommit("https://www.google.com/search?q=a"))
        assertTrue(BrowserTabStore.shouldCommit("https://myaccount.google.com/"))
    }

    @Test
    fun `form post results are recognised, ordinary pages are not`() {
        assertTrue(BrowserNavPolicy.isFormPostResult("https://accounts.google.com/gsi/transform"))
        assertTrue(BrowserNavPolicy.isFormPostResult("HTTPS://ACCOUNTS.GOOGLE.COM/v3/signin/challenge/pwd"))
        assertFalse(BrowserNavPolicy.isFormPostResult("https://accounts.google.com.evil.example/gsi/transform"))
        assertFalse(BrowserNavPolicy.isFormPostResult("https://example.com/gsi/transform"))
        assertFalse(BrowserNavPolicy.isFormPostResult(null))
    }

    @Test
    fun `the user agent loses the wv token and the Version marker, nothing else`() {
        assertTrue(BrowserNavPolicy.hasWebViewToken(wvUa))
        val clean = BrowserNavPolicy.cleanUserAgent(wvUa)
        assertFalse(clean, BrowserNavPolicy.hasWebViewToken(clean))
        assertFalse(clean.contains("; wv)"))
        assertFalse(clean.contains("Version/"))
        assertTrue(clean.contains("Chrome/124.0.0.0") && clean.contains("Android 14"))
        val configured = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        assertEquals(configured, BrowserNavPolicy.cleanUserAgent(configured))
    }

    @Test
    fun `web pages load, accounts google included, and app links never leave the browser`() {
        assertSame(Decision.Load, BrowserNavPolicy.decide("https://accounts.google.com/o/oauth2/auth?client_id=1"))
        assertSame(Decision.Load, BrowserNavPolicy.decide("https://accounts.google.com/gsi/transform"))
        assertSame(Decision.Load, BrowserNavPolicy.decide("http://localhost:8000"))
        assertSame(Decision.Block, BrowserNavPolicy.decide("market://details?id=com.google.android.gms"))
        assertSame(Decision.Block, BrowserNavPolicy.decide("intent://scan/#Intent;scheme=zxing;package=com.x;end"))
        assertEquals(Decision.Redirect("https://example.com/fallback?a=1"),
            BrowserNavPolicy.decide("intent://x#Intent;scheme=app;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback%3Fa%3D1;end"))
    }
}
