package com.diegonmarcos.superapp.appstore

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The Store's resolver LADDER, Android-free so it runs under a plain JVM test.
 *
 * MEASURED 2026-10-07, Cloud Store's fleet pass: "could not download morpheus:
 * release → DNS: cannot resolve github.com (active resolver: bridge
 * 127.0.0.1:2053) | ghcr → … | mesh → …", the same for c3-watchdog and
 * c3-watchtower, while cloud-browser downloaded in the same pass. The Store had
 * ONE resolver — the process's DNS bridge — and when it did not answer (its walk
 * timed out under a batch, or it was not running) the connection went DIRECT to
 * the same Android resolver the bridge had just failed on, so a miss was final
 * and every leg of the app read as "cannot resolve". Now a download host walks a
 * LADDER, in order: the bridge, the system resolver, a direct DoH query by IP
 * (1.1.1.1, 9.9.9.9 over HTTPS, no DNS needed to reach them), the mesh. The
 * first rung that answers carries the connection through a loopback tunnel to
 * its address; every attempt is recorded, so the row error names each rung and
 * why it declined.
 *
 *  - Nothing is remembered as bad. Every connection walks the ladder afresh, so
 *    a Retry re-resolves; a rung that failed lately is only given a SHORT
 *    budget for [reprobeWindowMs] (it is still asked, so a recovered bridge is
 *    used again at once).
 *  - An address that answers but does not connect is not the end: the tunnel
 *    walks the ladder again skipping the rung that gave it.
 */
object DnsLadder {
    /** One way to resolve: [resolve] returns addresses or throws. */
    class Rung(val label: String, val resolve: (String) -> List<InetAddress>)
    class Attempt(val rung: String, val ok: Boolean, val detail: String)

    class Result(val host: String, val addrs: List<InetAddress>, val via: String?, val attempts: List<Attempt>) {
        /** "bridge 127.0.0.1:2053: no answer in 6000 ms → system: … → DoH 1.1.1.1: …": every attempt, in order. */
        val trail: String get() = attempts.joinToString(" → ") { "${it.rung}: ${it.detail}" }
    }

    /** A rung may take this long to answer one host. */
    @Volatile var rungBudgetMs = 6_000L
    /** A rung that failed within [reprobeWindowMs] is still asked, but only for this long. */
    @Volatile var reprobeBudgetMs = 1_500L
    @Volatile var reprobeWindowMs = 30_000L
    @Volatile var connectTimeoutMs = 8_000

    private val failedAt = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "store-dns-rung").apply { isDaemon = true } }

    /** Ask each rung in order until one answers; [skip] names rungs whose last answer did not connect. */
    fun walk(host: String, rungs: List<Rung>, skip: Set<String> = emptySet()): Result {
        val attempts = ArrayList<Attempt>()
        for (r in rungs) {
            if (r.label in skip) { attempts += Attempt(r.label, false, "skipped: its address did not connect"); continue }
            val recent = failedAt[r.label]?.let { System.currentTimeMillis() - it < reprobeWindowMs } == true
            val budget = if (recent) reprobeBudgetMs else rungBudgetMs
            val t0 = System.currentTimeMillis()
            val job = pool.submit<List<InetAddress>> { r.resolve(host) }
            val addrs = try { job.get(budget, TimeUnit.MILLISECONDS).sortedBy { it !is Inet4Address } }
            catch (e: TimeoutException) { job.cancel(true); attempts += Attempt(r.label, false, "no answer in $budget ms"); failedAt[r.label] = System.currentTimeMillis(); continue }
            catch (e: Exception) {
                val c = e.cause ?: e
                attempts += Attempt(r.label, false, (c.message ?: c.javaClass.simpleName).replace(Regex("\\s+"), " ").take(160))
                failedAt[r.label] = System.currentTimeMillis(); continue
            }
            if (addrs.isEmpty()) { attempts += Attempt(r.label, false, "no address"); failedAt[r.label] = System.currentTimeMillis(); continue }
            failedAt.remove(r.label)
            attempts += Attempt(r.label, true, addrs.joinToString(", ") { it.hostAddress.orEmpty() } + " in ${System.currentTimeMillis() - t0} ms")
            return Result(host, addrs, r.label, attempts)
        }
        return Result(host, emptyList(), null, attempts)
    }

    /**
     * THE ORDER, in one place: the bridge, the system resolver, each direct DoH endpoint, the mesh.
     * [doh] maps a rung label to its endpoint (production: "DoH 1.1.1.1" → https://1.1.1.1/dns-query).
     */
    fun ladder(
        bridgeLabel: String, bridge: (String) -> List<InetAddress>,
        doh: List<Pair<String, String>>, mesh: (String) -> List<InetAddress>,
        system: (String) -> List<InetAddress> = { h -> InetAddress.getAllByName(h).toList() },
    ): List<Rung> = listOf(Rung(bridgeLabel, bridge), Rung("system", system)) +
        doh.map { (label, endpoint) -> doh(label, endpoint) } + Rung("mesh", mesh)

    // ── DoH by IP ────────────────────────────────────────────────────────

    /**
     * A direct DNS-over-HTTPS rung: RFC 8484 GET of [endpoint] (an IP literal,
     * so reaching it needs no DNS; the certificate's IP SAN is verified). A, then AAAA.
     */
    fun doh(label: String, endpoint: String, timeoutMs: Int = 3_000): Rung = Rung(label) { host ->
        val out = ArrayList<InetAddress>()
        var err: Exception? = null
        for (type in intArrayOf(1, 28)) {
            try { out += Wire.addresses(get("$endpoint?dns=" + Base64.getUrlEncoder().withoutPadding().encodeToString(Wire.query(host, type)), timeoutMs)) }
            catch (e: Exception) { if (type == 1) err = e }
        }
        if (out.isEmpty()) throw (err ?: IOException("NXDOMAIN or no record for $host"))
        out
    }

    private fun get(url: String, timeoutMs: Int): ByteArray {
        val c = URL(url).openConnection(Proxy.NO_PROXY) as java.net.HttpURLConnection
        try {
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("accept", "application/dns-message")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    /** The few DNS bytes the DoH rung needs. */
    object Wire {
        fun query(name: String, type: Int): ByteArray {
            val o = java.io.ByteArrayOutputStream()
            o.write(byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            for (l in name.trimEnd('.').split('.')) { val b = l.toByteArray(); o.write(b.size); o.write(b) }
            o.write(byteArrayOf(0, (type shr 8).toByte(), type.toByte(), 0, 1))
            return o.toByteArray()
        }
        private fun u16(m: ByteArray, i: Int) = ((m[i].toInt() and 0xFF) shl 8) or (m[i + 1].toInt() and 0xFF)
        private fun skip(m: ByteArray, i: Int): Int {
            var p = i
            while (p < m.size) { val n = m[p].toInt() and 0xFF; if (n == 0) return p + 1; if (n and 0xC0 == 0xC0) return p + 2; p += 1 + n }
            return p
        }
        fun addresses(m: ByteArray): List<InetAddress> = runCatching {
            if (m.size < 12 || (m[3].toInt() and 0x0F) != 0) return emptyList()
            var p = 12
            repeat(u16(m, 4)) { p = skip(m, p) + 4 }
            val out = ArrayList<InetAddress>()
            repeat(u16(m, 6)) {
                p = skip(m, p)
                val t = u16(m, p); val len = u16(m, p + 8); p += 10
                if ((t == 1 && len == 4) || (t == 28 && len == 16)) out += InetAddress.getByAddress(m.copyOfRange(p, p + len))
                p += len
            }
            out
        }.getOrDefault(emptyList())
    }

    // ── the loopback tunnel ──────────────────────────────────────────────

    private class Via(val addrs: List<InetAddress>, val rung: String)

    /**
     * One proxy for the process: [install] puts a ProxySelector in front of the default; every
     * connection to a host [accepts] walks [rungs] and is tunnelled to what answered.
     */
    class Relay(private val accepts: (String) -> Boolean, private val rungs: () -> List<Rung>, private val forget: (rung: String, host: String) -> Unit = { _, _ -> }) {
        private val pending = ConcurrentHashMap<String, Via>()
        private val failures = ConcurrentHashMap<String, String>()
        @Volatile var port = 0; private set
        /** The most recent failed host's trail, for a hook that does not know the host. */
        @Volatile var lastFailure: String? = null; private set

        /** Every attempt the last failed lookup of [host] made, or null if the last one worked. */
        fun failureFor(host: String?): String? = host?.let { failures[it.trimEnd('.').lowercase()] } ?: lastFailure

        fun install() {
            val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            port = server.localPort
            Thread({ while (true) runCatching { server.accept() }.getOrNull()?.let { s -> Thread({ tunnel(s) }, "store-dns-tunnel").start() } },
                "store-dns-proxy").apply { isDaemon = true }.start()
            val previous = ProxySelector.getDefault()
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    val host = uri.host
                    if ((uri.scheme == "https" || uri.scheme == "http") && host != null && accepts(host) && resolveFor(host))
                        return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)))
                    return previous?.select(uri) ?: listOf(Proxy.NO_PROXY)
                }
                override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) { previous?.connectFailed(uri, sa, ioe) }
            })
        }

        /** A fresh walk for [host]; true = a rung answered and the tunnel is ready for it. */
        fun resolveFor(host: String, skip: Set<String> = emptySet()): Boolean {
            val key = host.trimEnd('.').lowercase()
            val r = walk(host, rungs(), skip)
            if (r.via == null) { val t = r.trail; failures[key] = t; lastFailure = t; pending.remove(key); return false }
            failures.remove(key); if (lastFailure != null && failures.isEmpty()) lastFailure = null
            pending[key] = Via(r.addrs, r.via)
            return true
        }

        private val CONNECT = Regex("^CONNECT ([^: ]+):(\\d+) ")
        private val ABSOLUTE = Regex("^[A-Z]+ http:/{2}([^/: ]+)(?::(\\d+))?/")

        /** One request: dial the answered address for its host, then splice (a CONNECT after its 200, plain http as sent). */
        private fun tunnel(client: Socket) = runCatching {
            client.use { c ->
                val inp = c.getInputStream()
                val head = ArrayList<String>()
                while (true) { val l = readLine(inp); if (l.isEmpty()) break; head += l }
                val line = head.firstOrNull().orEmpty()
                val connect = CONNECT.find(line)
                val target = connect ?: ABSOLUTE.find(line)
                val host = target?.groupValues?.get(1)?.lowercase()
                val p = target?.groupValues?.get(2)?.toIntOrNull() ?: 80
                if (target == null || host == null || pending[host] == null) { c.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); return@use }
                var via = pending[host]!!
                var up = dial(via, p)
                if (up == null) {
                    // The rung answered, but nothing at its address connects: forget it and ask the next rung.
                    forget(via.rung, host)
                    val first = failures[host]
                    if (resolveFor(host, setOf(via.rung))) { via = pending[host]!!; up = dial(via, p) }
                    if (up == null) {
                        val t = "${via.rung}: answered ${via.addrs.joinToString(", ") { it.hostAddress.orEmpty() }} but no connection on port $p" +
                            (failures[host] ?: first)?.let { " → $it" }.orEmpty()
                        failures[host] = t; lastFailure = t
                        c.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); return@use
                    }
                }
                up.use { u ->
                    if (connect != null) c.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); flush() }
                    else u.getOutputStream().apply { write((head.joinToString("\r\n") + "\r\n\r\n").toByteArray()); flush() }
                    val back = Thread({ pipe(u.getInputStream(), c.getOutputStream()) }, "store-dns-back").apply { start() }
                    pipe(inp, u.getOutputStream())
                    runCatching { u.shutdownOutput() }
                    back.join()
                }
            }
        }

        private fun dial(via: Via, port: Int): Socket? = via.addrs.firstNotNullOfOrNull { a ->
            runCatching { Socket().apply { connect(InetSocketAddress(a, port), connectTimeoutMs) } }.getOrNull()
        }

        private fun readLine(i: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = i.read()
                if (b < 0 || b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 8192) throw IOException("header line too long")
            }
        }

        private fun pipe(i: InputStream, o: OutputStream) = runCatching {
            val buf = ByteArray(64 * 1024)
            while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() }
        }
    }
}
