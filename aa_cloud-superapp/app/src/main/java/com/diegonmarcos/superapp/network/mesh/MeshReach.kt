package com.diegonmarcos.superapp.network.mesh

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * #1153 Status > Reachability: can this phone actually use the private mesh names? For each declared mesh
 * URL three steps run in order - DNS resolve, TCP connect to :443, HTTPS request - each with its own
 * pass/fail and milliseconds. A step whose predecessor failed is reported failed ("skipped") rather than
 * hidden, so the table never has holes. Any HTTP answer below 500 passes the HTTPS step: an auth-gated
 * service answering 401/403 is reachable, which is what is being measured.
 */
object MeshReach {
    const val PORT = 443

    data class Step(val id: String, val ok: Boolean, val ms: Long, val detail: String)
    data class Result(val host: String, val steps: List<Step>) {
        val ok: Boolean get() = steps.isNotEmpty() && steps.all { it.ok }
    }

    /** The three network operations, injectable so the logic is asserted on the JVM. */
    interface Io {
        fun resolve(host: String): List<String>
        fun connect(address: String, port: Int, timeoutMs: Int)
        /** The HTTP status code of a GET of [url]. */
        fun https(url: String, timeoutMs: Int): Int
    }

    object SystemIo : Io {
        override fun resolve(host: String) = InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
        override fun connect(address: String, port: Int, timeoutMs: Int) {
            Socket().use { it.connect(InetSocketAddress(InetAddress.getByName(address), port), timeoutMs) }
        }
        override fun https(url: String, timeoutMs: Int): Int {
            val c = URL(url).openConnection() as HttpURLConnection
            return try {
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.instanceFollowRedirects = false
                c.requestMethod = "GET"
                c.responseCode
            } finally { c.disconnect() }
        }
    }

    fun run(hosts: List<String>, io: Io = SystemIo, timeoutMs: Int = 4000, nowMs: () -> Long = System::currentTimeMillis): List<Result> =
        hosts.map { host -> probe(host, io, timeoutMs, nowMs) }

    private inline fun timed(nowMs: () -> Long, id: String, body: () -> String): Step {
        val t0 = nowMs()
        return try {
            val d = body(); Step(id, true, nowMs() - t0, d)
        } catch (t: Throwable) {
            Step(id, false, nowMs() - t0, t.message ?: t.javaClass.simpleName)
        }
    }

    fun probe(host: String, io: Io, timeoutMs: Int, nowMs: () -> Long): Result {
        var addrs: List<String> = emptyList()
        val dns = timed(nowMs, "dns") {
            addrs = io.resolve(host)
            check(addrs.isNotEmpty()) { "no address" }
            addrs.joinToString(", ")
        }
        val tcp = if (!dns.ok) Step("tcp", false, 0, "skipped: no address") else timed(nowMs, "tcp") {
            io.connect(addrs.first(), PORT, timeoutMs); "${addrs.first()}:$PORT"
        }
        val http = if (!tcp.ok) Step("https", false, 0, "skipped: no TCP") else timed(nowMs, "https") {
            val code = io.https("https://$host/", timeoutMs)
            check(code in 100..499) { "HTTP $code" }
            "HTTP $code"
        }
        return Result(host, listOf(dns, tcp, http))
    }

    /** One line per host and step: `host  dns PASS 12 ms (ip) | tcp ... | https ...`. */
    fun text(results: List<Result>): String = results.joinToString("\n") { r ->
        r.host + "  " + r.steps.joinToString(" | ") { s -> "${s.id} ${if (s.ok) "PASS" else "FAIL"} ${s.ms} ms (${s.detail})" }
    }
}
