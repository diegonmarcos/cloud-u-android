package com.diegonmarcos.superapp.browser

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * An app's PRIVATE NAMES: a few exact host names that stand for servers on this phone's own loopback, answered by
 * a DNS server the phone's own app runs (cloud-c3-webserver: udp 127.0.0.1:5354), and resolved only here, inside
 * the browser's own page loads.
 *
 * Why this exists rather than the system resolver: Android cannot bind :53 without root and cannot be told to use
 * a loopback resolver, and `.app` is a real public TLD whose names (termux.app) are real websites. So nothing is
 * changed system-wide. The browser asks the app's server for the name's address, accepts ONLY a loopback answer,
 * and maps the port from the same declaration (DNS carries no port). Where the server does not answer, the page
 * says so; the name is NEVER handed to the system resolver, where it would load the public website.
 *
 * The declaration (ui.browser.private_names in the consuming app's baked config) is one block from
 * cloud-c3-webserver's build.json::serving; this class holds no name, address or port of its own. Exact names
 * only: `www.termux.app` and `termux.app.evil.com` are not private. Pure but for [lookup]'s one loopback datagram.
 *
 * THE LADDER ([ladder], what a page load runs). A name the fleet OWNS (declared `owned`: name -> loopback address,
 * serving.endpoints[].resolve = declared) is resolved straight from the declaration, so a stopped DNS server cannot
 * break it; a name that is not owned is asked of the DNS server ([resolve]). Whichever rung answered, the server the
 * name stands for must accept a connection; when the DNS did not answer, or the port refuses, the browser starts the
 * WebServer's keep-alive service ([service], explicit intent), waits briefly, retries, and only then fails.
 */
class PrivateNames(
    /** The DNS server's host: must be a loopback literal (checked by [enabled]). */
    val dnsHost: String,
    val dnsPort: Int,
    /** host name (lower case) -> port of the loopback server it stands for. */
    val ports: Map<String, Int>,
    val timeoutMs: Int = 400,
    /** host name (lower case) -> loopback address the declaration itself answers with (no DNS needed). */
    val owned: Map<String, String> = emptyMap(),
    /** The service that starts the DNS and the servers, by explicit intent; null = the browser cannot start it. */
    val service: ServiceTarget? = null,
) {
    /** Package and class of the keep-alive service (build.json serving.keepalive). */
    data class ServiceTarget(val pkg: String, val cls: String)

    /** Off unless the server is on loopback and at least one name is declared. */
    val enabled: Boolean get() = ports.isNotEmpty() && dnsPort in 1024..65535 && isLoopbackLiteral(dnsHost)

    /** The authority of [url] when it is one of the declared names, exactly (any scheme; an explicit port or userinfo makes it someone else's). */
    fun nameOf(url: String): String? {
        if (!enabled) return null
        val m = URL_RE.matchEntire(url.trim()) ?: return null
        val authority = m.groupValues[2].lowercase()
        return authority.takeIf { it in ports }
    }

    fun matches(url: String): Boolean = nameOf(url) != null

    sealed class Result {
        /** [url] is what the WebView should load: `http://<loopback ip>:<port>/<rest>`. */
        data class Ok(val name: String, val url: String) : Result()
        /** [silent]: the DNS server did not answer at all (worth starting the service and asking again). */
        data class Fail(val name: String, val why: String, val silent: Boolean = false) : Result()
    }

    /** Resolve [url] now (blocking up to [timeoutMs]); a non-private URL is not this class's business. */
    fun resolve(url: String, lookup: (String) -> String? = { lookupA(dnsHost, dnsPort, it, timeoutMs) }): Result? {
        val name = nameOf(url) ?: return null
        val ip = lookup(name) ?: return Result.Fail(name, "the private DNS server at $dnsHost:$dnsPort did not answer", silent = true)
        if (!isLoopbackAddress(ip)) return Result.Fail(name, "the private DNS server answered $ip, which is not a loopback address")
        return Result.Ok(name, rewrite(url, ip, ports.getValue(name)))
    }

    /** The declared address of an owned [name], or null (not owned, or not a loopback address). */
    fun ownedAddress(name: String): String? = owned[name]?.takeIf { isLoopbackAddress(it) }

    /**
     * The whole ladder for [url] (null when it is not a private name): owned declaration, else DNS; then the server
     * must accept a connection; on a silent DNS or a refused port [start] is called once, then [attempts] polls
     * [waitMs] apart. [lookup], [serverUp], [start] and [sleep] are the seams the tests drive.
     */
    fun ladder(
        url: String,
        lookup: (String) -> String? = { lookupA(dnsHost, dnsPort, it, timeoutMs) },
        serverUp: (String, Int) -> Boolean = { ip, port -> tcpOpen(ip, port) },
        start: () -> Boolean = { false },
        attempts: Int = ATTEMPTS,
        waitMs: Long = WAIT_MS,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Result? {
        val name = nameOf(url) ?: return null
        val port = ports.getValue(name)
        var started = false
        fun startOnce() { if (!started) { started = true; runCatching { start() } } }

        // Rung 1 (owned): the declaration answers. Rung 2 (dynamic): DNS, with one start + retries when it is silent.
        var r: Result = ownedAddress(name)?.let { Result.Ok(name, rewrite(url, it, port)) } ?: resolve(url, lookup)!!
        if (r is Result.Fail && r.silent && service != null) {
            startOnce()
            for (i in 0 until attempts) {
                sleep(waitMs)
                r = resolve(url, lookup)!!
                if (r is Result.Ok) break
            }
        }
        if (r !is Result.Ok) return r
        // The name resolved; the server it stands for must be there too (the DNS rung's answer is only an address).
        val ip = Regex("""^http://([^:/]+):""").find(r.url)?.groupValues?.get(1) ?: return r
        if (!serverUp(ip, port)) {
            if (service != null) {
                startOnce()
                for (i in 0 until attempts) {
                    sleep(waitMs)
                    if (serverUp(ip, port)) return r
                }
            }
            return Result.Fail(name, "the server for $name ($ip:$port) is not running" + if (service != null) " and starting Cloud C3 WebServer did not bring it up" else "")
        }
        return r
    }

    /** [ladder] on a worker thread; [done] is called on that thread, so a caller posts to its own. */
    fun resolveAsync(url: String, start: () -> Boolean = { false }, done: (Result) -> Unit) {
        POOL.execute { ladder(url, start = start)?.let(done) }
    }

    companion object {
        val NONE = PrivateNames("", 0, emptyMap())

        /** After starting the service: this many polls, this far apart (about 3 s in all) before the page says so. */
        const val ATTEMPTS = 12
        const val WAIT_MS = 250L

        /** Does 127.x:[port] accept a connection right now? A refused loopback connect returns at once. */
        fun tcpOpen(ip: String, port: Int, timeoutMs: Int = 300): Boolean =
            runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs); true } }.getOrDefault(false)

        private val URL_RE = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#@:]*)((?:[/?#].*)?)$""", RegexOption.DOT_MATCHES_ALL)
        private val POOL = Executors.newSingleThreadExecutor { r -> Thread(r, "private-names").apply { isDaemon = true } }

        /** `http://<name>/a?b#c` -> `http://<loopback ip>:<declared port>/a?b#c`. Loopback servers speak plain http. */
        fun rewrite(url: String, ip: String, port: Int): String {
            val m = URL_RE.matchEntire(url.trim()) ?: return url
            val rest = m.groupValues[3]
            val path = if (rest.isEmpty() || rest[0] == '?' || rest[0] == '#') "/$rest" else rest
            return "http://$ip:$port$path"
        }

        fun isLoopbackLiteral(host: String): Boolean = host == "127.0.0.1" || host == "::1"

        /** 127.0.0.0/8 only. */
        fun isLoopbackAddress(ip: String): Boolean {
            val p = ip.split('.')
            return p.size == 4 && p[0] == "127" && p.all { s -> s.toIntOrNull()?.let { it in 0..255 } == true }
        }

        // ── the wire: one A query, one answer, over a connected loopback datagram socket ──

        /** A standard query for [name], type A, class IN, recursion desired. */
        fun buildQuery(id: Int, name: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(id shr 8); out.write(id and 0xFF)
            out.write(byteArrayOf(0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
            for (label in name.trimEnd('.').split('.')) {
                require(label.length in 1..63) { "bad label in $name" }
                out.write(label.length); out.write(label.toByteArray(Charsets.US_ASCII))
            }
            out.write(0)
            out.write(byteArrayOf(0, 1, 0, 1))
            return out.toByteArray()
        }

        /**
         * The first A record of a NOERROR answer to query [id], as dotted text; null for anything else (wrong id,
         * not a response, refused, no A record, truncated).
         */
        fun parseAnswer(b: ByteArray, len: Int, id: Int): String? {
            if (len < 12) return null
            fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            if (u16(0) != id) return null
            val flags = u16(2)
            if (flags and 0x8000 == 0 || flags and 0x000F != 0) return null
            val qd = u16(4); val an = u16(6)
            var i = 12
            repeat(qd) {
                while (true) {
                    if (i >= len) return null
                    val l = b[i].toInt() and 0xFF
                    if (l == 0) { i += 1; break }
                    if (l and 0xC0 == 0xC0) { i += 2; break }
                    i += 1 + l
                }
                i += 4
            }
            repeat(an) {
                if (i >= len) return null
                while (true) {
                    if (i >= len) return null
                    val l = b[i].toInt() and 0xFF
                    if (l == 0) { i += 1; break }
                    if (l and 0xC0 == 0xC0) { i += 2; break }
                    i += 1 + l
                }
                if (i + 10 > len) return null
                val type = u16(i); val rdlen = u16(i + 8)
                i += 10
                if (i + rdlen > len) return null
                if (type == 1 && rdlen == 4) return "${b[i].toInt() and 0xFF}.${b[i + 1].toInt() and 0xFF}.${b[i + 2].toInt() and 0xFF}.${b[i + 3].toInt() and 0xFF}"
                i += rdlen
            }
            return null
        }

        /**
         * Ask the app's DNS server (loopback literal, no name lookup of its own) for [name]'s address.
         * Null on any failure: the server is not running, a timeout, a refusal.
         */
        fun lookupA(host: String, port: Int, name: String, timeoutMs: Int): String? {
            if (!isLoopbackLiteral(host)) return null
            return runCatching {
                val server = if (host == "::1") InetAddress.getByAddress(ByteArray(15) + byteArrayOf(1)) else InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
                val id = java.util.concurrent.ThreadLocalRandom.current().nextInt(0x10000)
                DatagramSocket().use { s ->
                    s.soTimeout = timeoutMs
                    s.connect(server, port)
                    val q = buildQuery(id, name)
                    s.send(DatagramPacket(q, q.size))
                    val buf = ByteArray(512)
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    parseAnswer(buf, p.length, id)
                }
            }.getOrNull()
        }

        /** The page a private name shows when it cannot be resolved. Escaped; a name is never sent to the system. */
        fun errorPage(f: Result.Fail): String {
            fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            return "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
                "<body style=\"font:15px system-ui;background:#0b0e14;color:#e6e9ef;padding:18px\">" +
                "<h3>${esc(f.name)} is a private name of this phone</h3>" +
                "<p>${esc(f.why)}.</p>" +
                "<p style=\"color:#8b95a7\">It stands for a server on this phone and is resolved only by the apps that own it " +
                "(Cloud C3 WebServer runs the servers and the DNS). It is never looked up on the internet, where ${esc(f.name)} is somebody else's site. " +
                "Cloud Browser already tried to start Cloud C3 WebServer; open it once, then reload.</p>"
        }

        /**
         * Parse the baked `private_names` block: {dns:{host,port}, names:{<host>: <port>, ...},
         * owned:{<host>: <loopback ip>}, service:{package, class}}. `owned` and `service` are optional.
         */
        fun parse(o: org.json.JSONObject?): PrivateNames {
            if (o == null) return NONE
            val dns = o.optJSONObject("dns") ?: return NONE
            val names = o.optJSONObject("names") ?: return NONE
            val map = LinkedHashMap<String, Int>()
            for (k in names.keys()) {
                val p = names.optInt(k, -1)
                if (p in 1024..65535 && k == k.lowercase() && k.isNotBlank()) map[k] = p
            }
            val own = LinkedHashMap<String, String>()
            o.optJSONObject("owned")?.let { j ->
                for (k in j.keys()) {
                    val ip = j.optString(k)
                    // Only a declared name, only a loopback address: a bad row is dropped, never trusted.
                    if (k in map && isLoopbackAddress(ip)) own[k] = ip
                }
            }
            val svc = o.optJSONObject("service")?.let {
                val pkg = it.optString("package"); val cls = it.optString("class")
                if (pkg.isNotBlank() && cls.isNotBlank()) ServiceTarget(pkg, cls) else null
            }
            return PrivateNames(dns.optString("host"), dns.optInt("port", 0), map, owned = own, service = svc)
        }
    }
}
