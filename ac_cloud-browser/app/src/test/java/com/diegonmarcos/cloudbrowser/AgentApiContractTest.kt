package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.cloudbrowser.agentapi.AgentApiContract
import com.diegonmarcos.cloudbrowser.agentapi.AgentApiContract.FetchParsed
import com.diegonmarcos.cloudbrowser.agentapi.AgentApiContract.OpenParsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

/** #913 the fleet agent door to the browser: what it accepts, what it refuses, how a page becomes text, and that it stays read-only. */
class AgentApiContractTest {
    private fun refused(url: String?, max: Int = 0) = (AgentApiContract.fetchRequest(url, max) as FetchParsed.Refused).why

    @Test fun `open takes http and https with a host and a short group`() {
        val ok = AgentApiContract.openRequest(" https://www.wg-gesucht.de/x.123.html ", "  House   search ") as OpenParsed.Ok
        assertEquals("https://www.wg-gesucht.de/x.123.html", ok.url)
        assertEquals("House search", ok.group)
        assertEquals("", (AgentApiContract.openRequest("http://a.example/", null) as OpenParsed.Ok).group)
        assertTrue(AgentApiContract.openRequest("javascript:alert(1)", null) is OpenParsed.Refused)
        assertTrue(AgentApiContract.openRequest("file:///etc/passwd", null) is OpenParsed.Refused)
        assertTrue(AgentApiContract.openRequest(null, null) is OpenParsed.Refused)
        assertTrue(AgentApiContract.openRequest("https://a.example/", "g".repeat(41)) is OpenParsed.Refused)
        assertTrue(AgentApiContract.openRequest("https://a.example/", "g".repeat(40)) is OpenParsed.Ok)
    }

    @Test fun `fetch is https only and refuses local and numeric hosts`() {
        assertTrue(AgentApiContract.fetchRequest("https://www.wg-gesucht.de/a.1.html", 0) is FetchParsed.Ok)
        assertEquals(AgentApiContract.DEFAULT_MAX_CHARS, (AgentApiContract.fetchRequest("https://a.example/", 0) as FetchParsed.Ok).maxChars)
        assertEquals(500, (AgentApiContract.fetchRequest("https://a.example/", 500) as FetchParsed.Ok).maxChars)
        assertTrue(refused("http://a.example/").contains("https"))
        assertTrue(refused("https://127.0.0.1/").contains("IP"))
        assertTrue(refused("https://2130706433/").contains("IP"))
        assertTrue(refused("https://[::1]/").contains("IP"))
        assertTrue(refused("https://localhost/").isNotBlank())
        assertTrue(refused("https://printer.local/").contains("local"))
        assertTrue(refused("https://nas/").contains("single-label"))
        assertTrue(refused("https://user:pw@a.example/").contains("credentials"))
        assertTrue(refused("ftp://a.example/").contains("https"))
        assertTrue(refused(null).isNotBlank())
        assertTrue(refused("https://a.example/", -1).contains("max_chars"))
        assertTrue(refused("https://a.example/", AgentApiContract.MAX_MAX_CHARS + 1).contains("max_chars"))
        assertTrue(AgentApiContract.fetchRequest("https://a.example/", AgentApiContract.MAX_MAX_CHARS) is FetchParsed.Ok)
    }

    @Test fun `a name that resolves into a private range is refused`() {
        fun a(vararg b: Int) = InetAddress.getByAddress(ByteArray(b.size) { b[it].toByte() })
        assertNull(AgentApiContract.addressRefusal(a(93, 184, 216, 34)))
        assertNotNull(AgentApiContract.addressRefusal(a(127, 0, 0, 1)))
        assertNotNull(AgentApiContract.addressRefusal(a(10, 0, 0, 5)))
        assertNotNull(AgentApiContract.addressRefusal(a(192, 168, 1, 1)))
        assertNotNull(AgentApiContract.addressRefusal(a(172, 16, 0, 1)))
        assertNotNull(AgentApiContract.addressRefusal(a(169, 254, 1, 1)))
        assertNotNull(AgentApiContract.addressRefusal(a(100, 64, 0, 1)))
        assertNull(AgentApiContract.addressRefusal(a(100, 63, 0, 1)))
        assertNotNull(AgentApiContract.addressRefusal(a(0, 0, 0, 0)))
        assertNotNull(AgentApiContract.addressRefusal(InetAddress.getByName("fd00::1")))
        assertNull(AgentApiContract.addressRefusal(InetAddress.getByName("2001:db8::1")))
    }

    @Test fun `redirects resolve relative to the page`() {
        assertEquals("https://a.example/b", AgentApiContract.redirectTarget("https://a.example/x/y", "/b"))
        assertEquals("https://o.example/z", AgentApiContract.redirectTarget("https://a.example/", "https://o.example/z"))
        assertNull(AgentApiContract.redirectTarget("https://a.example/", ""))
        assertNull(AgentApiContract.redirectTarget("https://a.example/", null))
        // a redirect to http or to a private name is caught by the same per-hop check
        assertNotNull(AgentApiContract.hopRefusal(AgentApiContract.redirectTarget("https://a.example/", "http://a.example/")!!))
        assertNotNull(AgentApiContract.hopRefusal(AgentApiContract.redirectTarget("https://a.example/", "https://192.168.0.1/")!!))
    }

    @Test fun `the charset comes from the header or is utf-8`() {
        assertEquals("ISO-8859-1", AgentApiContract.charsetOf("text/html; charset=ISO-8859-1").name())
        assertEquals("UTF-8", AgentApiContract.charsetOf("text/html").name())
        assertEquals("UTF-8", AgentApiContract.charsetOf(null).name())
        assertEquals("UTF-8", AgentApiContract.charsetOf("text/html; charset=nonsense-9").name())
    }

    @Test fun `a page becomes its title and visible text`() {
        val html = """<html><head><title> Zimmer &amp; Küche &ndash; Berlin </title><style>p{color:red}</style></head>
            <body><!-- hidden --><script>var x = "secret";</script><h1>M&ouml;bliertes Zimmer</h1>
            <p>Miete:&nbsp;450&#8364;<br>Frei ab 01.11.</p><table><tr><td>Gr&#xF6;&szlig;e</td><td>14 m²</td></tr></table>
            <noscript>enable js</noscript></body></html>"""
        val e = AgentApiContract.extract(html, 10_000)
        assertEquals("Zimmer & Küche – Berlin", e.title)
        assertFalse(e.truncated)
        assertTrue(e.text.contains("Möbliertes Zimmer"))
        assertTrue(e.text.contains("Miete: 450€\nFrei ab 01.11."))
        assertTrue(e.text.contains("Größe 14 m²"))
        for (gone in listOf("secret", "hidden", "color:red", "enable js", "<", "Zimmer & Küche")) assertFalse(gone, e.text.contains(gone))
    }

    @Test fun `text is cut at the cap and says so`() {
        val e = AgentApiContract.extract("<p>" + "abc ".repeat(100) + "</p>", 50)
        assertEquals(50, e.text.length); assertTrue(e.truncated)
        assertFalse(AgentApiContract.extract("<p>short</p>", 5).truncated)
        assertEquals("short", AgentApiContract.extract("<p>short</p>", 5).text)
    }

    @Test fun `unknown and broken entities survive as written`() {
        assertEquals("&nope; a", AgentApiContract.unescape("&nope; a"))
        assertEquals("", AgentApiContract.unescape("&#xD800;"))
        assertEquals("A", AgentApiContract.unescape("&#65;"))
    }

    @Test fun `the door is read only and signature guarded`() {
        val dir = "src/main/java/com/diegonmarcos/cloudbrowser/agentapi/"
        val fetch = File(dir + "AgentFetchProvider.kt").readText()
        assertTrue(fetch.contains("requestMethod = \"GET\""))
        for (verb in listOf("\"POST\"", "\"PUT\"", "\"DELETE\"", "doOutput", "outputStream", "setRequestProperty(\"Cookie\"", "CookieManager"))
            assertFalse(verb, fetch.contains(verb))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (name in listOf(".agentapi.AgentOpenActivity", ".agentapi.AgentFetchProvider")) {
            val tag = manifest.substringAfter(name).substringBefore(">")
            assertTrue(name, tag.contains("android:permission=\"${AgentApiContract.PERMISSION}\""))
            assertTrue(name, tag.contains("android:exported=\"true\""))
        }
        assertTrue(manifest.contains(AgentApiContract.ACTION_OPEN))
        assertTrue(manifest.contains("\${applicationId}.${AgentApiContract.AUTHORITY_SUFFIX}"))
    }
}
