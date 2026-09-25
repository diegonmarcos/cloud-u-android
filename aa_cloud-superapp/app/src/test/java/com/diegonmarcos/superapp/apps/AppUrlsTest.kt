package com.diegonmarcos.superapp.apps

import android.app.Application
import com.diegonmarcos.superapp.launcher.AppUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #572 — AppUrls derives every URL from the declaration it is handed. Each test
 * hands it a declaration of its own making (never the shipped build.json) and
 * asserts what comes out; Robolectric because the parse is org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppUrlsTest {
    private val cfg = """
        {"public_scheme":"https://","private_scheme":"http://",
         "website_meta_keys":["website","homepage"],
         "installer_pages":{"com.android.vending":{"label":"Play page","url":"https://play.example/d?id={pkg}"}}}
    """.trimIndent()
    private val ext = """
        [{"id":"a","hub_package":"p.a","service":"svc-pub","forks":{}},
         {"id":"b","hub_package":"p.b","service":"svc-priv","forks":{"f":"p.b.fork"}},
         {"id":"c","hub_package":"p.c","forks":{}},
         {"id":"d","hub_package":"p.d","service":"gone","forks":{}}]
    """.trimIndent()
    private val pub = """[{"name":"svc-pub","public_url":"a.example.org/x","private_dns":"a.app:80"}]"""
    private val priv = """[{"name":"svc-priv","private_dns":"b.app:90"}]"""

    private fun ours(pkg: String) = AppUrls.ours(pkg, ext, cfg, pub, priv).map { it.label to it.url }

    @Test fun `a public service gives its public and private URL, public first`() =
        assertEquals(listOf("Public" to "https://a.example.org/x", "Private" to "http://a.app:80"), ours("p.a"))

    @Test fun `a private-only service gives just the private URL, and a fork package resolves too`() =
        assertEquals(listOf("Private" to "http://b.app:90"), ours("p.b.fork"))

    @Test fun `no service, an unlisted service or an unknown package draws nothing`() {
        assertTrue(ours("p.c").isEmpty())
        assertTrue(ours("p.d").isEmpty())
        assertTrue(ours("not.declared").isEmpty())
    }

    @Test fun `the app's own meta-data website wins over the store page`() {
        val l = AppUrls.website("x.y", cfg, { k -> if (k == "homepage") "https://vendor.example/" else null }, "com.android.vending")!!
        assertEquals("Website" to "https://vendor.example/", l.label to l.url)
    }

    @Test fun `a meta-data value that is not a web URL is ignored, the installer page answers`() {
        val l = AppUrls.website("x.y", cfg, { "javascript:alert(1)" }, "com.android.vending")!!
        assertEquals("Play page" to "https://play.example/d?id=x.y", l.label to l.url)
    }

    @Test fun `nothing declared and no known installer means no row`() {
        assertNull(AppUrls.website("x.y", cfg, { null }, "some.unknown.store"))
        assertNull(AppUrls.website("x.y", cfg, { null }, null))
    }
}
