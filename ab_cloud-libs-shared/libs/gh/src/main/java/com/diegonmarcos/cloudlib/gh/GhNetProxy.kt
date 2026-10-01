package com.diegonmarcos.cloudlib.gh

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * HOW gh REACHES GITHUB FROM INSIDE AN APP. The official linux gh resolves names with Go's own
 * resolver, which reads /etc/resolv.conf; an Android app has none, so every lookup went to
 * 127.0.0.1:53, nothing answered, and the sign-in died before it reached GitHub, printing gh's
 * "check your internet connection" (data/gh-binary.json::_doc_sandbox has the sources).
 *
 * This is a CONNECT proxy on loopback, in the engine's own process. gh is given
 * `HTTPS_PROXY=http://<user>:<pass>@127.0.0.1:<port>`, so Go dials the proxy by IP (no lookup) and
 * asks it for a tunnel; the proxy resolves the name with Android's resolver, connects, and copies
 * bytes. TLS is end to end between gh and GitHub, so all the proxy ever sees is the host name.
 *
 * NOT AN OPEN RELAY. Loopback is shared by every app on the phone, so two checks run before a
 * tunnel opens: the request must carry this process's random credential (only gh children of
 * this engine are given it), and the target must be one of [hosts] on [tlsPort]. Each refusal is
 * a status line naming the reason, which Go turns into gh's own error line, and a log line.
 */
class GhNetProxy(
    private val hosts: Set<String>,
    /** HTTPS. A parameter only so GhLoginTest can tunnel to a local echo server. */
    private val tlsPort: Int = 443,
    /** How long a tunnel GitHub has answered may sit silent before it is ended (gh-binary.json::idle_close_ms). */
    private val idleMs: Int,
    private val log: (String) -> Unit,
) {

    private val user = "gh"
    private val pass = ByteArray(18).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    /** What gh gets as HTTPS_PROXY. Carries the credential: never log it. */
    val url: String = "http://$user:$pass@127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "gh-net") {
            while (true) {
                val client = try { server.accept() } catch (e: IOException) { log("gh-net: stopped: ${e.message}"); break }
                thread(isDaemon = true, name = "gh-net-conn") { serve(client) }
            }
        }
        log("gh-net: listening on 127.0.0.1:${server.localPort} for ${hosts.joinToString()}")
    }

    private fun serve(client: Socket) = client.use {
        val input = client.getInputStream().buffered()
        val head = readHead(input) ?: return@use
        val request = head.first().split(' ')
        val target = request.getOrNull(1).orEmpty()
        val host = target.substringBeforeLast(':')
        val port = target.substringAfterLast(':').toIntOrNull()
        val auth = head.drop(1).firstOrNull { it.startsWith("proxy-authorization:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val refusal = when {
            auth != expected -> "407 Proxy Authentication Required"
            request.firstOrNull() != "CONNECT" -> "405 the gh engine only tunnels CONNECT"
            host !in hosts || port != tlsPort -> "403 the gh engine does not tunnel to $target"
            else -> null
        }
        if (refusal != null) {
            log("gh-net: refused ${request.firstOrNull()} $target: $refusal")
            reply(client.getOutputStream(), refusal)
            return@use
        }
        val upstream = Socket()
        try {
            upstream.connect(InetSocketAddress(host, tlsPort), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            // The real cause, for gh's error line and the log: which layer failed, in Android's words.
            val why = "502 cannot reach $host: ${cause(host, tlsPort, e)}"
            log("gh-net: $why")
            reply(client.getOutputStream(), why)
            upstream.close()
            return@use
        }
        upstream.use {
            val via = "$target (${upstream.inetAddress.hostAddress})"
            log("gh-net: tunnel open to $via")
            reply(client.getOutputStream(), "200 Connection established")
            val ended = relay(client, input, upstream)
            log("gh-net: tunnel to $via $ended")
            if (ended.startsWith(BROKEN)) lastDrop = "tunnel to $via $ended"
        }
    }

    /**
     * Why the last tunnel the NETWORK broke ended, for the engine check on a failed sign-in: gh
     * itself only ever sees a connection that stopped. Null while none has.
     */
    @Volatile var lastDrop: String? = null
        private set

    /**
     * #729 COPY BOTH WAYS UNTIL ONE OF THREE ENDINGS, and say which. The phone's failure was gh's
     * "Post .../access_token: unexpected EOF": Go's TLS reads a TCP end with no close_notify as
     * exactly that, and this relay used to hand gh a clean end for EVERY ending, so a connection
     * the network killed read like GitHub hanging up, and nothing said where it died.
     * - GitHub closed: passed on as a clean end (FIN), as before.
     * - quiet: GitHub has answered and nothing has moved either way for [idleMs]. The tunnel is
     *   ended cleanly, so Go drops its idle connection and the next poll dials a fresh one instead
     *   of writing into an upstream that died while it waited (data/gh-binary.json::_doc_idle_close_ms).
     *   A request still waiting for its answer (gh spoke last) is never cut.
     * - broken: the upstream failed (reset, destroyed, unreachable). gh's side is RESET, not
     *   ended, so gh reports a broken connection rather than a server that finished, and the
     *   cause is kept in [lastDrop] for the engine check.
     */
    private fun relay(client: Socket, fromGh: InputStream, upstream: Socket): String {
        val start = now()
        val sent = AtomicLong()
        val received = AtomicLong()
        // WHO SPOKE LAST, set before the bytes are passed on: gh can only answer what it has been
        // given, so its next request always lands after GitHub's mark (a timestamp could tie).
        // Starts true: until GitHub has said something, nothing is quiet.
        val ghWaiting = AtomicBoolean(true)
        val lastMove = AtomicLong(now())
        val upFailure = AtomicReference<IOException?>()
        upstream.soTimeout = idleMs // wakes the loop below to look at the clock; not a deadline
        val toGitHub = thread(isDaemon = true, name = "gh-net-up") {
            val buf = ByteArray(BUF)
            val out = upstream.getOutputStream()
            while (true) {
                // gh going away, cleanly or not, is gh's end of the request: pass it on as one.
                val n = try { fromGh.read(buf) } catch (e: IOException) { -1 }
                if (n < 0) { runCatching { upstream.shutdownOutput() }; break }
                ghWaiting.set(true)
                lastMove.set(now())
                try {
                    out.write(buf, 0, n)
                    out.flush()
                    sent.addAndGet(n.toLong())
                } catch (e: IOException) {
                    // A dead upstream fails here first when gh writes into it: close it so the
                    // loop below stops waiting on it and resets gh with this cause.
                    upFailure.set(e)
                    runCatching { upstream.close() }
                    break
                }
            }
        }
        val toGh = client.getOutputStream()
        val fromGitHub = upstream.getInputStream()
        val buf = ByteArray(BUF)
        var how: String? = null
        while (how == null) {
            val n = try {
                fromGitHub.read(buf)
            } catch (e: SocketTimeoutException) {
                QUIET_CHECK
            } catch (e: IOException) {
                how = "$BROKEN (${(upFailure.get() ?: e).let { "${it.javaClass.simpleName}: ${it.message}" }})"
                break
            }
            if (n == QUIET_CHECK) {
                if (now() - lastMove.get() >= idleMs) how = QUIET // MUTATION 3b: quiet ignores a request still waiting
            } else if (n < 0) {
                how = "closed by GitHub"
            } else {
                ghWaiting.set(false)
                lastMove.set(now())
                try {
                    toGh.write(buf, 0, n)
                    toGh.flush()
                } catch (e: IOException) {
                    how = "closed by gh"
                    break
                }
                received.addAndGet(n.toLong())
            }
        }
        if (how!!.startsWith(BROKEN)) {
            // RST, not FIN: gh must not read a broken tunnel as a finished answer.
            runCatching { client.setSoLinger(true, 0) }
            runCatching { client.close() }
        } else {
            runCatching { client.shutdownOutput() }
        }
        toGitHub.join(CONNECT_TIMEOUT_MS.toLong())
        return "$how after ${now() - start} ms, ${sent.get()} bytes up, ${received.get()} down"
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val HEAD_MAX = 8192
        private const val BUF = 16 * 1024
        /** A read that timed out: not a byte count, a cue to look at the clock. */
        private const val QUIET_CHECK = -2
        internal const val QUIET = "ended: quiet after GitHub's answer"
        internal const val BROKEN = "BROKEN by the network"

        private fun now() = System.nanoTime() / 1_000_000

        /**
         * #726 WHICH LAYER FAILED, never "check your internet connection": gh prints that one
         * sentence for every failed lookup, so a firewall, a dead resolver and a refused port all
         * read the same on the card. Android's own message follows the layer.
         */
        internal fun cause(host: String, port: Int, e: IOException): String = when (e) {
            is UnknownHostException -> "DNS: no address for $host from Android's resolver (this uid, Private DNS honoured)"
            is SocketTimeoutException -> "TCP: $host:$port did not answer"
            is ConnectException -> "TCP: $host:$port refused the connection"
            is NoRouteToHostException -> "TCP: no route to $host:$port"
            else -> e.javaClass.simpleName
        } + " (${e.message})"

        /** Can THIS process reach [host]:[port]? Resolved with Android's resolver, as a tunnel would be. */
        fun probe(host: String, port: Int = 443): String = Socket().use { s ->
            try {
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                "$host:$port reachable (${s.inetAddress.hostAddress})"
            } catch (e: IOException) {
                cause(host, port, e)
            }
        }

        /** The request line and headers, up to the blank line; null if the client gave up first. */
        internal fun readHead(input: InputStream): List<String>? {
            val bytes = java.io.ByteArrayOutputStream()
            var last4 = 0 // the last four bytes read; an Int shift drops the oldest
            while (true) {
                if (bytes.size() >= HEAD_MAX) return null
                val b = input.read()
                if (b < 0) return null
                bytes.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0d0a0d0a || (last4 and 0xffff) == 0x0a0a) break
            }
            // ISO-8859-1: a head is bytes, and this never mis-decodes one.
            return bytes.toString("ISO-8859-1").split("\r\n", "\n").filter { it.isNotEmpty() }.ifEmpty { null }
        }

        /** One status line, with a reason that cannot break the response (no CR/LF in it). */
        private fun reply(out: OutputStream, status: String) = runCatching {
            out.write("HTTP/1.1 ${status.replace(Regex("[\\r\\n]+"), " ")}\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }
}
