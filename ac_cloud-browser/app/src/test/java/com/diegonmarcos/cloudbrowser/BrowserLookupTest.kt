package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.cloudbrowser.provider.BrowserLookup
import com.diegonmarcos.superapp.browser.BrowserBookmark
import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.BrowserTab
import com.diegonmarcos.superapp.browser.BrowserVisit
import com.diegonmarcos.superapp.browser.PrivateSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lookup the SuperApp's search runs against this browser (provider/BrowserLookup): who may
 * ask, what comes back, that an incognito page never does, and the web row's URL-or-search call.
 */
class BrowserLookupTest {

    private val now = 10L * 86_400_000L
    private val engine = BrowserConfig.QWANT

    // ── who may ask ────────────────────────────────────────────────────────────

    @Test fun `only a holder of the signature permission or the browser itself is served`() {
        assertTrue(BrowserLookup.allowed(callingUid = 10_123, myUid = 10_123, callerHoldsPermission = false))
        assertTrue(BrowserLookup.allowed(callingUid = 10_200, myUid = 10_123, callerHoldsPermission = true))
        assertFalse(BrowserLookup.allowed(callingUid = 10_200, myUid = 10_123, callerHoldsPermission = false))
    }

    @Test fun `the provider is exported only behind the fleet's signature permission and re-checks it`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val tag = manifest.substringAfter(".provider.BrowserLookupProvider").substringBefore(">")
        assertTrue(tag.contains("android:permission=\"${BrowserLookup.PERMISSION}\""))
        assertTrue(tag.contains("android:exported=\"true\""))
        assertTrue(tag.contains("\${applicationId}.${BrowserLookup.AUTHORITY_SUFFIX}"))
        // The permission itself is signature-level: defined once, by libs:core, for every fleet app.
        val core = File("../../ab_cloud-libs-shared/libs/core/src/main/AndroidManifest.xml").readText()
        val def = Regex("<permission\\s+android:name=\"${Regex.escape(BrowserLookup.PERMISSION)}\"[^>]*>").find(core)?.value.orEmpty()
        assertTrue(def.contains("android:protectionLevel=\"signature\""))
        // The provider refuses before it reads anything, and it writes nothing.
        val src = File("src/main/java/com/diegonmarcos/cloudbrowser/provider/BrowserLookupProvider.kt").readText()
        assertTrue(src.indexOf("BrowserLookup.allowed(") < src.indexOf("BrowserHistory(ctx)"))
        assertTrue(src.contains("throw SecurityException"))
        for (no in listOf("CookieManager", "Autofill", "edit()", "getSharedPreferences")) assertFalse(no, src.contains(no))
    }

    @Test fun `the limit is small and bounded, the query trimmed and capped`() {
        assertEquals(20, BrowserLookup.limit(null))
        assertEquals(20, BrowserLookup.limit("x"))
        assertEquals(1, BrowserLookup.limit("0"))
        assertEquals(BrowserLookup.MAX_LIMIT, BrowserLookup.limit("100000"))
        assertEquals(7, BrowserLookup.limit(" 7 "))
        assertEquals("cats", BrowserLookup.query("  cats "))
        assertEquals(BrowserLookup.MAX_QUERY, BrowserLookup.query("a".repeat(5000)).length)
    }

    // ── favourites and history ─────────────────────────────────────────────────

    @Test fun `favourites match title or url, carry when they were added, and respect the limit`() {
        val favs = listOf(
            BrowserBookmark("https://git.diegonmarcos.com", "Gitea", "Public", 5L),
            BrowserBookmark("https://news.example", "News", "", 6L),
            BrowserBookmark("https://gitlab.com", "GitLab", "", 7L),
        )
        val rows = BrowserLookup.favourites(favs, "git", 20)
        assertEquals(setOf("https://git.diegonmarcos.com", "https://gitlab.com"), rows.map { it.url }.toSet())
        assertTrue(rows.all { it.kind == BrowserLookup.KIND_FAVOURITE })
        assertEquals(5L, rows.first { it.title == "Gitea" }.time)
        assertEquals(1, BrowserLookup.favourites(favs, "git", 1).size)
        assertTrue(BrowserLookup.favourites(favs, "  ", 20).isEmpty())
    }

    @Test fun `history matches, ranks like the address bar, and carries the last visit`() {
        val visits = listOf(
            BrowserVisit("https://wiki.example/cats", "Cats", now - 1_000),
            BrowserVisit("https://cats.example/", "Home", now - 5 * 86_400_000L),
            BrowserVisit("https://dogs.example/", "Dogs", now),
        )
        val rows = BrowserLookup.history(visits, emptyList(), "cats", 20, now)
        // A title that starts with the word, visited a second ago, beats a host that does, five days old.
        assertEquals(listOf("https://wiki.example/cats", "https://cats.example/"), rows.map { it.url })
        assertEquals(now - 1_000, rows.first().time)
        assertTrue(rows.all { it.kind == BrowserLookup.KIND_HISTORY })
    }

    // ── incognito never leaves ────────────────────────────────────────────────

    @Test fun `a page open in a private tab is never returned, even if it was visited normally before`() {
        val visits = listOf(
            BrowserVisit("https://secret.example/", "Secret", now),
            BrowserVisit("https://public.example/secret-recipes", "Recipes", now),
        )
        val tabs = listOf(
            BrowserTab("https://secret.example/", "Secret", now, id = "p", isPrivate = true),
            BrowserTab("https://public.example/secret-recipes", "Recipes", now, id = "n", isPrivate = false),
        )
        val rows = BrowserLookup.history(visits, tabs, "secret", 20, now)
        assertEquals(listOf("https://public.example/secret-recipes"), rows.map { it.url })
    }

    @Test fun `a private tab's visit is never recorded, so the history the provider reads has none`() {
        val session = PrivateSession()
        val private = BrowserTab("https://incognito.example/", "x", now, id = "p", isPrivate = true)
        val normal = BrowserTab("https://normal.example/", "y", now, id = "n")
        assertFalse(session.visit(private, "https://incognito.example"))
        assertTrue(session.visit(normal, "https://normal.example"))
    }

    @Test fun `open tabs are never served, normal or private`() {
        val tabs = listOf(BrowserTab("https://open.example/", "Open", now, id = "n"))
        assertTrue(BrowserLookup.history(emptyList(), tabs, "open", 20, now).isEmpty())
    }

    // ── the web row ───────────────────────────────────────────────────────────

    @Test fun `a URL or a domain opens directly`() {
        val url = BrowserLookup.web("example.com/path", engine)!!
        assertEquals(BrowserLookup.KIND_URL, url.kind)
        assertEquals("https://example.com/path", url.url)
        assertEquals("http://localhost:8000", BrowserLookup.web("localhost:8000", engine)!!.url)
        assertEquals("https://a.b/c", BrowserLookup.web("https://a.b/c", engine)!!.url)
    }

    @Test fun `anything else searches the default engine`() {
        val q = BrowserLookup.web("best cats 2.5", engine)!!
        assertEquals(BrowserLookup.KIND_SEARCH, q.kind)
        assertEquals(engine.template.replace("{q}", "best+cats+2.5"), q.url)
        assertEquals(engine.label, q.title)
        assertEquals(BrowserLookup.KIND_SEARCH, BrowserLookup.web("2.5", engine)!!.kind)
        assertNull(BrowserLookup.web("   ", engine))
    }
}
