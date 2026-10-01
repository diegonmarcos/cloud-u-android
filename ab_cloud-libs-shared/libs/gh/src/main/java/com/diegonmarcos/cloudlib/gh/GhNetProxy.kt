package com.diegonmarcos.cloudlib.gh

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
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
            // The real cause, for gh's error line and the log: an unresolvable name, a refused
            // or timed-out connection — Android's words, not a guess.
            val why = "502 cannot reach $host: ${e.message ?: e.javaClass.simpleName}"
            log("gh-net: $why")
            reply(client.getOutputStream(), why)
            upstream.close()
            return@use
        }
        upstream.use {
            log("gh-net: tunnel open to $target (${upstream.inetAddress.hostAddress})")
            reply(client.getOutputStream(), "200 Connection established")
            val toGitHub = thread(isDaemon = true, name = "gh-net-up") {
                pump(input, upstream.getOutputStream())
                runCatching { upstream.shutdownOutput() }
            }
            pump(upstream.getInputStream(), client.getOutputStream())
            runCatching { client.shutdownOutput() }
            toGitHub.join(CONNECT_TIMEOUT_MS.toLong())
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val HEAD_MAX = 8192

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

        private fun pump(from: InputStream, to: OutputStream) = runCatching {
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        }
    }
}
