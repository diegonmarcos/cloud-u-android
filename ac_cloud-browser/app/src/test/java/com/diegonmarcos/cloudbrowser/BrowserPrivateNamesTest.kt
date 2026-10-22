package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.PrivateNames
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * The private names (termux.app, nix.app, emulated.app, cloud.app): exact names, resolved through the c3-webserver's
 * own loopback DNS and mapped to a port from the SAME declaration, only inside this browser.
 *
 *   N1 the shipped config carries the map of ac_cloud-c3-webserver/build.json::serving, nothing typed here
 *   N2 only the four exact names match: no subdomain, suffix, explicit port, userinfo or look-alike
 *   N3 the rewrite keeps path, query and fragment and lands on http://<loopback>:<declared port>
 *   N4 a non-loopback answer, a refusal, a wrong id and silence are all refusals, never a fall-through to the system
 *   N5 the wire: the query is a well-formed A/IN question and a real loopback UDP server resolves a name end to end
 *   N6 the shared library names nothing (#209) and the pins are the names
 *   N7 the resolver ladder: owned names from the declaration (no DNS), DNS for the rest, the WebServer service
 *      started once when the DNS is silent or the port refuses, a bounded retry, then the error
 *   N8 the baked declaration owns the four names on loopback and names the keep-alive service; a bad row is dropped
 */
class BrowserPrivateNamesTest {

    private fun json(rel: String) = JSONObject(File(rel).also { assertTrue("${it.absolutePath} missing", it.exists()) }.readText())
    private fun shipped(): BrowserConfig = BrowserConfig.parse(json("../build.json").getJSONObject("ui").getJSONObject("browser").toString())

    /**
     * What app/build.gradle bakes into the config (BuildConfig.UI_BROWSER_CONFIG_B64): the serving block of the web server's
     * build.json, each port resolved there from its owner's build.json. Read from the bake itself, so this test cannot agree
     * with a second reading of the declaration while the shipped config says something else.
     */
    private fun baked(): PrivateNames = BrowserConfig.parseBase64(BuildConfig.UI_BROWSER_CONFIG_B64).privateNames

    private val names = baked()

    @Test
    fun `N1 the declared map is the owner's four names on his four ports`() {
        assertTrue(names.enabled)
        assertEquals(mapOf("termux.app" to 8000, "nix.app" to 8001, "emulated.app" to 8002, "cloud.app" to 8003), names.ports)
        assertEquals("127.0.0.1", names.dnsHost)
        assertEquals(5353, names.dnsPort)
        // the gradle bake (BuildConfig.UI_BROWSER_CONFIG_B64) is this same parse of the same block
        val viaConfig = BrowserConfig.parse(JSONObject().put("private_names", JSONObject()
            .put("dns", JSONObject().put("host", names.dnsHost).put("port", names.dnsPort))
            .put("names", JSONObject(names.ports as Map<*, *>))).toString()).privateNames
        assertEquals(names.ports, viaConfig.ports)
    }

    @Test
    fun `N2 only the four exact names are private`() {
        for (u in listOf("http://termux.app", "https://termux.app/", "http://NIX.app/a/b?c=1#d", "emulated.app".let { "http://$it" }, "http://cloud.app?x=1")) assertTrue(u, names.matches(u))
        for (u in listOf(
            "http://www.termux.app", "http://termux.app.evil.com/", "http://xtermux.app", "http://termux.app:8080/", "http://user@termux.app/",
            "http://termux.application/", "http://app/", "http://example.com/termux.app", "http://127.0.0.1:8000/", "http://localhost:8000/",
            "termux.app", "javascript:alert(1)", "",
        )) assertFalse(u, names.matches(u))
    }

    @Test
    fun `N3 the rewrite keeps the rest of the url and lands on the declared port`() {
        assertEquals("http://127.0.0.1:8002/", PrivateNames.rewrite("http://emulated.app", "127.0.0.1", 8002))
        assertEquals("http://127.0.0.1:8002/docs/a%20b.md?raw#top", PrivateNames.rewrite("https://emulated.app/docs/a%20b.md?raw#top", "127.0.0.1", 8002))
        assertEquals("http://127.0.0.1:8003/?n=5", PrivateNames.rewrite("http://cloud.app?n=5", "127.0.0.1", 8003))
        val ok = names.resolve("https://nix.app/x", lookup = { "127.0.0.1" }) as PrivateNames.Result.Ok
        assertEquals("http://127.0.0.1:8001/x", ok.url)
        assertNull("a URL that is not private is not this class's business", names.resolve("https://example.com/", lookup = { error("must not be asked") }))
    }

    @Test
    fun `N4 anything but a loopback answer is a refusal, never the system resolver`() {
        val bad = names.resolve("http://termux.app", lookup = { "93.184.216.34" }) as PrivateNames.Result.Fail
        assertTrue(bad.why, bad.why.contains("not a loopback"))
        assertTrue(names.resolve("http://termux.app", lookup = { null }) is PrivateNames.Result.Fail)
        assertTrue((names.resolve("http://termux.app", lookup = { null }) as PrivateNames.Result.Fail).why.contains("did not answer"))
        assertTrue(PrivateNames.isLoopbackAddress("127.0.0.1") && PrivateNames.isLoopbackAddress("127.12.0.9"))
        for (ip in listOf("128.0.0.1", "10.0.0.1", "0.0.0.0", "127.0.0", "127.0.0.256", "localhost", "::1", "")) assertFalse(ip, PrivateNames.isLoopbackAddress(ip))
        // the server must be on loopback itself: a configured public address disables the whole feature
        assertFalse(PrivateNames("10.1.2.3", 5353, mapOf("a.app" to 8000)).enabled)
        assertFalse(PrivateNames("127.0.0.1", 53, mapOf("a.app" to 8000)).enabled)
        assertFalse(PrivateNames("127.0.0.1", 5353, emptyMap()).enabled)
        assertNull(PrivateNames.lookupA("10.1.2.3", 5353, "termux.app", 100))
        val page = PrivateNames.errorPage(PrivateNames.Result.Fail("termux.app", "no <answer>"))
        assertTrue(page.contains("termux.app") && page.contains("no &lt;answer&gt;") && !page.contains("<answer>"))
    }

    private fun answerFor(q: ByteArray, ip: ByteArray, id: Int = ((q[0].toInt() and 0xFF) shl 8) or (q[1].toInt() and 0xFF), rcode: Int = 0): ByteArray {
        val qEnd = q.size
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8); out.write(id and 0xFF)
        out.write(byteArrayOf(0x84.toByte(), rcode.toByte(), 0, 1, 0, if (rcode == 0) 1 else 0, 0, 0, 0, 0))
        out.write(q, 12, qEnd - 12)
        if (rcode == 0) { out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 30, 0, 4)); out.write(ip) }
        return out.toByteArray()
    }

    @Test
    fun `N5 the wire - a well-formed question, a parsed answer, and a real loopback server end to end`() {
        val q = PrivateNames.buildQuery(0x1234, "Emulated.App.")
        assertEquals(0x12, q[0].toInt()); assertEquals(0x34, q[1].toInt())
        assertEquals(1, q[5].toInt()); assertEquals(0, q[7].toInt())
        assertEquals("\u0008emulated\u0003app\u0000", String(q, 12, q.size - 16, Charsets.US_ASCII).lowercase())
        assertEquals(listOf(0, 1, 0, 1), q.takeLast(4).map { it.toInt() })
        val good = answerFor(q, byteArrayOf(127, 0, 0, 1))
        assertEquals("127.0.0.1", PrivateNames.parseAnswer(good, good.size, 0x1234))
        assertNull("wrong id", PrivateNames.parseAnswer(good, good.size, 0x1235))
        val refused = answerFor(q, byteArrayOf(127, 0, 0, 1), rcode = 5)
        assertNull("REFUSED", PrivateNames.parseAnswer(refused, refused.size, 0x1234))
        assertNull("truncated", PrivateNames.parseAnswer(good, good.size - 2, 0x1234))
        val asQuery = good.copyOf().also { it[2] = (it[2].toInt() and 0x7F).toByte() }
        assertNull("a query is not an answer", PrivateNames.parseAnswer(asQuery, asQuery.size, 0x1234))

        // a real UDP server on loopback, answering like the c3-webserver's
        val server = DatagramSocket(0, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val t = Thread {
            val buf = ByteArray(512); val p = DatagramPacket(buf, buf.size)
            server.receive(p)
            val reply = answerFor(buf.copyOf(p.length), byteArrayOf(127, 0, 0, 1))
            server.send(DatagramPacket(reply, reply.size, p.address, p.port))
        }.apply { isDaemon = true; start() }
        val live = PrivateNames("127.0.0.1", server.localPort.coerceAtLeast(1024), names.ports, 2000)
        val r = live.resolve("http://emulated.app/hello") as PrivateNames.Result.Ok
        assertEquals("http://127.0.0.1:8002/hello", r.url)
        t.join(3000); server.close()

        // and nothing listening: a quick refusal, not a hang
        val closed = DatagramSocket(0, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).also { it.close() }
        val dead = PrivateNames("127.0.0.1", closed.localPort.coerceAtLeast(1024), names.ports, 300)
        val t0 = System.nanoTime()
        assertTrue(dead.resolve("http://cloud.app") is PrivateNames.Result.Fail)
        assertTrue("a dead server must fail fast", (System.nanoTime() - t0) / 1_000_000 < 3000)
    }

    @Test
    fun `N6 the library names nothing and the pins are the names`() {
        assertFalse(BrowserConfig.DEFAULT.privateNames.enabled)
        assertTrue(BrowserConfig.DEFAULT.privateNames.ports.isEmpty())
        assertFalse(PrivateNames.NONE.matches("http://termux.app"))
        val lib = File("../../ab_cloud-libs-shared/libs/browser/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        for (n in listOf("termux.app", "nix.app", "emulated.app", "cloud.app")) assertFalse("$n is typed in the shared library", lib.contains("\"$n\""))
        assertEquals(listOf("http://termux.app", "http://nix.app", "http://emulated.app", "http://cloud.app"), shipped().defaultPinnedTabs.takeLast(4))
        for ((url, _) in shipped().defaultPinnedTitles) assertNotNull(url, names.nameOf(url))
        // the config the browser ships has no private_names of its own: the gradle bake adds the one declaration
        assertFalse(json("../build.json").getJSONObject("ui").getJSONObject("browser").has("private_names"))
    }

    private val svc = PrivateNames.ServiceTarget("com.diegonmarcos.cloudwebserver", "com.diegonmarcos.cloudwebserver.KeepAliveService")
    private fun owned() = PrivateNames(names.dnsHost, names.dnsPort, names.ports, owned = names.ports.keys.associateWith { "127.0.0.1" }, service = svc)
    private fun dynamic(service: PrivateNames.ServiceTarget? = svc) = PrivateNames(names.dnsHost, names.dnsPort, names.ports, service = service)

    @Test
    fun `N7 an owned name never asks DNS and a running server loads at once`() {
        val ev = mutableListOf<String>()
        val r = owned().ladder("http://emulated.app/x?y#z", lookup = { ev += "dns"; error("DNS must not be asked for an owned name") },
            serverUp = { ip, port -> ev += "up:$ip:$port"; true }, start = { ev += "start"; true }, sleep = { ev += "sleep" }) as PrivateNames.Result.Ok
        assertEquals("http://127.0.0.1:8002/x?y#z", r.url)
        assertEquals(listOf("up:127.0.0.1:8002"), ev)
    }

    @Test
    fun `N7 a stopped server is started once, waited for, then the page loads`() {
        val ev = mutableListOf<String>()
        var polls = 0
        val r = owned().ladder("http://emulated.app", lookup = { error("no DNS") },
            serverUp = { _, _ -> ev += "up"; ++polls > 3 }, start = { ev += "start"; true }, sleep = { ev += "sleep" })
        assertTrue(r is PrivateNames.Result.Ok)
        assertEquals("the service is started exactly once, before any wait", 1, ev.count { it == "start" })
        assertEquals(listOf("up", "start", "sleep", "up", "sleep", "up", "sleep", "up"), ev)
    }

    @Test
    fun `N7 a server that never comes up is a bounded retry and then the error`() {
        var starts = 0; var sleeps = 0
        val r = owned().ladder("http://cloud.app", serverUp = { _, _ -> false }, start = { starts++; true }, attempts = 5, sleep = { sleeps++ })
        assertTrue(r is PrivateNames.Result.Fail)
        assertTrue((r as PrivateNames.Result.Fail).why.contains("not running"))
        assertEquals(1, starts); assertEquals(5, sleeps)
    }

    @Test
    fun `N7 a dynamic name asks DNS, starts the service when it is silent, retries, then loads`() {
        val ev = mutableListOf<String>()
        var asked = 0
        val r = dynamic().ladder("http://nix.app/a", lookup = { ev += "dns"; if (++asked >= 3) "127.0.0.1" else null },
            serverUp = { _, _ -> ev += "up"; true }, start = { ev += "start"; true }, sleep = { ev += "sleep" }) as PrivateNames.Result.Ok
        assertEquals("http://127.0.0.1:8001/a", r.url)
        assertEquals(listOf("dns", "start", "sleep", "dns", "sleep", "dns", "up"), ev)
    }

    @Test
    fun `N7 DNS that never answers fails after one start and a bounded retry, never to the system`() {
        var starts = 0; var asks = 0
        val r = dynamic().ladder("http://termux.app", lookup = { asks++; null }, serverUp = { _, _ -> true }, start = { starts++; true }, attempts = 4, sleep = {})
        val fail = r as PrivateNames.Result.Fail
        assertTrue(fail.why, fail.why.contains("did not answer"))
        assertEquals(1, starts); assertEquals(1 + 4, asks)
    }

    @Test
    fun `N7 without a declared service nothing is started and a non-loopback answer is final`() {
        var starts = 0
        val none = dynamic(service = null).ladder("http://termux.app", lookup = { null }, serverUp = { _, _ -> true }, start = { starts++; true }, sleep = { error("no wait") })
        assertTrue(none is PrivateNames.Result.Fail); assertEquals(0, starts)
        val bad = dynamic().ladder("http://termux.app", lookup = { "93.184.216.34" }, serverUp = { _, _ -> true }, start = { starts++; true }, sleep = {})
        assertTrue((bad as PrivateNames.Result.Fail).why.contains("not a loopback")); assertEquals("a wrong answer is not a silent DNS", 0, starts)
        assertNull(owned().ladder("https://example.com/", lookup = { error("no") }))
    }

    @Test
    fun `N8 the baked declaration owns the four names on loopback and names the keep-alive service`() {
        assertEquals(names.ports.keys, names.owned.keys)
        for (ip in names.owned.values) assertEquals("127.0.0.1", ip)
        assertEquals(PrivateNames.ServiceTarget("com.diegonmarcos.cloudwebserver", "com.diegonmarcos.cloudwebserver.KeepAliveService"), names.service)
        assertEquals("no wildcard: a subdomain of an owned name is not private", false, names.matches("http://www.emulated.app/"))
        val parsed = PrivateNames.parse(JSONObject()
            .put("dns", JSONObject().put("host", "127.0.0.1").put("port", 5353))
            .put("names", JSONObject().put("a.app", 8000).put("b.app", 8001))
            .put("owned", JSONObject().put("a.app", "127.0.0.1").put("b.app", "93.184.216.34").put("c.app", "127.0.0.1")))
        assertEquals(mapOf("a.app" to "127.0.0.1"), parsed.owned)
        assertNull(parsed.service)
    }
}
