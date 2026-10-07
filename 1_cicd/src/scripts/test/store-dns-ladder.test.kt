// store-dns-ladder.test — a dead DNS bridge must not take the Store's downloads with it.
//
// Compiled against the SHIPPED libs:appstore DnsLadder.kt (by path, never a
// copy) and run: real sockets, a real HttpURLConnection through the real
// loopback tunnel, a local "release server" and a local DoH server. The bridge
// rung is simulated dead (throws / hangs), the system resolver cannot answer the
// test hosts, so the only way a download succeeds is the ladder's fallback.
@file:JvmName("StoreDnsLadderTest")

import com.diegonmarcos.superapp.appstore.DnsLadder
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

private var failures = 0
private fun check(name: String, ok: Boolean, extra: String = "") {
    if (ok) println("PASS   $name") else { failures++; println("FAIL   $name $extra") }
}

private val APK = ByteArray(300_000) { (it * 31 + 7).toByte() }
private val LOOP = listOf(InetAddress.getByName("127.0.0.1"))

private fun releaseServer(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/") { x -> x.sendResponseHeaders(200, APK.size.toLong()); x.responseBody.use { it.write(APK) } }
    start()
}

/** A DoH endpoint: answers A with 127.0.0.1, AAAA with no record. [hits] counts queries. */
private fun dohServer(hits: AtomicInteger): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/dns-query") { x ->
        hits.incrementAndGet()
        val q = Base64.getUrlDecoder().decode(x.requestURI.query.substringAfter("dns="))
        var end = 12; while (q[end].toInt() != 0) end += 1 + (q[end].toInt() and 0xFF)
        val qtype = ((q[end + 1].toInt() and 0xFF) shl 8) or (q[end + 2].toInt() and 0xFF)
        val head = q.copyOf(end + 5).also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }
        val a = if (qtype == 1) { head[7] = 1; head + byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 127, 0, 0, 1) } else head
        x.responseHeaders.add("content-type", "application/dns-message")
        x.sendResponseHeaders(200, a.size.toLong()); x.responseBody.use { it.write(a) }
    }
    start()
}

private fun download(host: String, port: Int): ByteArray {
    val c = URL("http://$host:$port/morpheus.apk").openConnection() as HttpURLConnection
    c.connectTimeout = 10_000; c.readTimeout = 10_000
    return c.inputStream.use { it.readBytes() }
}

private fun relayFor(host: String, rungs: () -> List<DnsLadder.Rung>, forgot: MutableList<String> = mutableListOf()): DnsLadder.Relay =
    DnsLadder.Relay({ it == host }, rungs) { rung, h -> forgot += "$rung/$h" }.also { it.install() }

private val notFound: (String) -> List<InetAddress> = { h -> throw java.net.UnknownHostException("Unable to resolve host \"$h\": no address") }

fun main() {
    DnsLadder.rungBudgetMs = 1_500; DnsLadder.reprobeBudgetMs = 300
    val release = releaseServer(); val relPort = release.address.port
    val dohHits = AtomicInteger(); val doh = dohServer(dohHits)
    val dohEndpoint = "http://127.0.0.1:${doh.address.port}/dns-query"
    val dead: (String) -> List<InetAddress> = { throw java.io.IOException("DNS bridge not running: 127.0.0.1:2053 taken") }

    // 1. A dead bridge, a system resolver that has no answer: DoH by IP carries the download.
    run {
        val host = "one.store.test"
        val r = relayFor(host, { DnsLadder.ladder("bridge 127.0.0.1:2053", dead, listOf("DoH 1.1.1.1" to dohEndpoint), notFound, notFound) })
        val got = runCatching { download(host, relPort) }
        check("dead bridge: the download succeeds through DoH", got.getOrNull()?.contentEquals(APK) == true, got.exceptionOrNull().toString())
        check("dead bridge: DoH was actually asked", dohHits.get() > 0)
        check("dead bridge: nothing left in the failure slot after success", r.failureFor(host) == null || r.lastFailure == null)
    }

    // 2. The same walk, in order, with the order and the reasons visible.
    run {
        val res = DnsLadder.walk("two.store.test", DnsLadder.ladder("bridge 127.0.0.1:2053", dead, listOf("DoH 1.1.1.1" to dohEndpoint), notFound, notFound))
        check("ladder order: bridge, system, DoH, and DoH answers", res.via == "DoH 1.1.1.1" && res.attempts.map { it.rung } == listOf("bridge 127.0.0.1:2053", "system", "DoH 1.1.1.1"), res.trail)
        check("the bridge's decline is named with its reason", res.attempts[0].detail.contains("DNS bridge not running"), res.trail)
    }

    // 3. Everything down: the row error names EVERY rung, in order, with why.
    run {
        val host = "three.store.test"
        val r = relayFor(host, { DnsLadder.ladder("bridge 127.0.0.1:2053", dead, listOf("DoH 1.1.1.1" to "http://127.0.0.1:1/dns-query", "DoH 9.9.9.9" to "http://127.0.0.1:1/dns-query"), 
            { throw java.io.IOException("mesh down: the wg0 tunnel is not up") }, notFound) })
        val got = runCatching { download(host, relPort) }
        val t = r.failureFor(host).orEmpty()
        val order = listOf("bridge 127.0.0.1:2053: ", "system: ", "DoH 1.1.1.1: ", "DoH 9.9.9.9: ", "mesh: mesh down").map { t.indexOf(it) }
        check("all rungs down: the download fails", got.isFailure)
        check("all rungs down: the error names bridge, system, both DoH, mesh in order", order.all { it >= 0 } && order == order.sorted(), t)
    }

    // 4. The bridge is re-probed, never cached as bad: down on the first walk, back on the second.
    run {
        val host = "four.store.test"; var up = false; val asked = AtomicInteger()
        val r = relayFor(host, { DnsLadder.ladder("bridge", { asked.incrementAndGet(); if (up) LOOP else throw java.io.IOException("no answer") }, listOf("DoH 1.1.1.1" to dohEndpoint), notFound, notFound) })
        check("re-probe: first download via DoH while the bridge is down", download(host, relPort).contentEquals(APK))
        val before = asked.get(); val dohBefore = dohHits.get(); up = true
        check("re-probe: the next download uses the recovered bridge", download(host, relPort).contentEquals(APK) && asked.get() > before && dohHits.get() == dohBefore)
        check("re-probe: a failed bridge was asked again on every walk", asked.get() >= 2)
    }

    // 5. A bridge that HANGS costs its budget, then a short one, and is still asked each time.
    run {
        val host = "five.store.test"; val asked = AtomicInteger()
        relayFor(host, { DnsLadder.ladder("bridge", { asked.incrementAndGet(); Thread.sleep(30_000); LOOP }, listOf("DoH 1.1.1.1" to dohEndpoint), notFound, notFound) })
        val t0 = System.currentTimeMillis(); val a = download(host, relPort); val first = System.currentTimeMillis() - t0
        val t1 = System.currentTimeMillis(); val b = download(host, relPort); val second = System.currentTimeMillis() - t1
        check("hung bridge: downloads still succeed", a.contentEquals(APK) && b.contentEquals(APK))
        check("hung bridge: each walk gives up on it within its budget, and the second still asks", asked.get() == 2 && first < 6_000 && second < 4_000 && second < first, "first=$first second=$second asked=${asked.get()}")
    }

    // 6. Retry re-resolves: every connection walks the ladder afresh.
    run {
        val host = "six.store.test"; val asked = AtomicInteger()
        relayFor(host, { DnsLadder.ladder("bridge", { asked.incrementAndGet(); LOOP }, emptyList(), notFound, notFound) })
        repeat(3) { download(host, relPort) }
        check("retry: three downloads, three lookups (no pass-long cache of the choice)", asked.get() == 3, "asked=${asked.get()}")
    }

    // 7. The bridge answers an address nothing listens on: the next rung is tried and the bridge's cache forgotten.
    run {
        val host = "seven.store.test"; val forgot = mutableListOf<String>()
        relayFor(host, { DnsLadder.ladder("bridge", { listOf(InetAddress.getByName("127.0.0.2")) }, listOf("DoH 1.1.1.1" to dohEndpoint), notFound, notFound) }, forgot)
        val got = runCatching { download(host, relPort) }
        check("unreachable answer: the download falls through to DoH", got.getOrNull()?.contentEquals(APK) == true, got.exceptionOrNull().toString())
        check("unreachable answer: the bridge's cached answer is forgotten", forgot == listOf("bridge/$host"), forgot.toString())
    }

    // 8. Other hosts are never touched.
    run {
        val r = relayFor("eight.store.test", { throw AssertionError("a foreign host reached the ladder") })
        check("a host the Store does not download from is left to the default selector",
            java.net.ProxySelector.getDefault().select(java.net.URI("https://example.org/")).none { (it.address() as? InetSocketAddress)?.port == r.port })
    }

    release.stop(0); doh.stop(0)
    println(if (failures == 0) "ALL PASS" else "$failures FAILED")
    System.exit(if (failures == 0) 0 else 1)
}
