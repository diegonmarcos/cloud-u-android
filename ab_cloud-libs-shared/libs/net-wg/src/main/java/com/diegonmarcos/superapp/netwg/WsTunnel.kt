package com.diegonmarcos.superapp.netwg

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The wire the fleet's wstunnel server speaks (erebe/wstunnel v11, infra-net_wireguard-mesh-ws-tunnel),
 * written out so the engine needs no binary of its own. Measured against that server, 2026-10-09:
 *
 *  - the upgrade is `GET /<path prefix>/events` (tunnel/transport/websocket.rs); a wrong prefix is
 *    answered 400 by `--restrict-http-upgrade-path-prefix`, the right one 101;
 *  - the tunnel's destination rides in `Sec-WebSocket-Protocol: v1, authorization.bearer.<JWT>`,
 *    claims {id, p: {"Udp":{"timeout":null}}, r: host, rp: port} (tunnel/transport/jwt.rs). The
 *    server decodes it without checking the signature (wstunnel signs with a per-process random
 *    key itself), and `--restrict-to` decides what it may reach;
 *  - after the 101 every WebSocket binary message is one UDP datagram, both ways.
 *
 * Frames go out UNMASKED, as wstunnel's own client sends them: the server runs with
 * `--websocket-mask-frame` off (its default), so fastwebsockets does not unmask, and a masked payload
 * reached the hub as noise (measured: 148 bytes out, 148 bytes of garbage back). A server that does
 * unmask skips frames that carry no mask, so unmasked works against both; inside TLS the mask buys
 * nothing. Incoming frames are unmasked when they carry one. The server's pings are answered.
 */
internal object WsTunnel {
    const val OP_CONT = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAX_HEAD = 16 * 1024
    private const val MAX_MESSAGE = 1 shl 20
    private val rnd = SecureRandom()
    private val b64url = Base64.getUrlEncoder().withoutPadding()

    class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    /** The upgrade was refused: [code] is the HTTP status (0 = not HTTP at all). */
    class Refused(val code: Int, message: String) : IOException(message)

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** The tunnel ticket for UDP to [host]:[port], in the shape wstunnel's JwtTunnelConfig deserialises. */
    fun jwt(host: String, port: Int): String {
        val head = b64url.encodeToString("""{"typ":"JWT","alg":"HS256"}""".toByteArray())
        val claims = b64url.encodeToString(
            """{"id":"${UUID.randomUUID()}","p":{"Udp":{"timeout":null}},"r":"${esc(host)}","rp":$port}""".toByteArray())
        val key = ByteArray(32).also(rnd::nextBytes)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return "$head.$claims." + b64url.encodeToString(mac.doFinal("$head.$claims".toByteArray()))
    }

    /** "127.0.0.1:51820" / "[::1]:51820" -> host, port. */
    fun splitRemote(remote: String): Pair<String, Int> {
        val i = remote.lastIndexOf(':')
        require(i > 0) { "remote $remote has no port" }
        return remote.substring(0, i).removePrefix("[").removeSuffix("]") to remote.substring(i + 1).toInt()
    }

    /** Ask the server at [sock] for a UDP tunnel to [remote]; returns once it answered 101, throws [Refused] otherwise. */
    fun upgrade(sock: Socket, host: String, prefix: String, remote: String) {
        val (rh, rp) = splitRemote(remote)
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(rnd::nextBytes))
        val req = "GET /$prefix/events HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "Sec-WebSocket-Protocol: v1, authorization.bearer.${jwt(rh, rp)}\r\n" +
            "User-Agent: Mozilla/5.0\r\n\r\n"
        sock.getOutputStream().apply { write(req.toByteArray()); flush() }
        val head = readHead(sock.getInputStream())
        val status = head.lineSequence().firstOrNull().orEmpty()
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        if (code != 101) throw Refused(code, when (code) {
            0 -> "the relay did not answer HTTP (${status.take(40)})"
            400, 404 -> "the relay refused the upgrade (HTTP $code): the relay key is wrong or missing, or the route is not one it allows"
            else -> "the relay answered HTTP $code instead of 101"
        })
        val accept = head.lineSequence().firstOrNull { it.startsWith("sec-websocket-accept:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val want = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()))
        if (accept != want) throw Refused(101, "the relay's 101 carries a wrong Sec-WebSocket-Accept")
    }

    /** The response head, byte by byte up to the blank line, so not one frame byte is consumed. */
    private fun readHead(i: InputStream): String {
        val o = ByteArrayOutputStream()
        var m = 0
        while (m < 4) {
            val b = i.read()
            if (b < 0) throw EOFException("the relay closed the connection during the upgrade")
            o.write(b)
            m = when {
                (m == 0 || m == 2) && b == '\r'.code -> m + 1
                (m == 1 || m == 3) && b == '\n'.code -> m + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (o.size() > MAX_HEAD) throw IOException("the relay's response head is too long")
        }
        return o.toString(Charsets.ISO_8859_1.name())
    }

    /** One unmasked frame (see the class doc). Callers serialise writes on the socket. */
    fun writeFrame(out: OutputStream, opcode: Int, data: ByteArray, off: Int = 0, len: Int = data.size) {
        val h = ByteArrayOutputStream(10 + len)
        h.write(0x80 or opcode)
        when {
            len < 126 -> h.write(len)
            len < 65536 -> { h.write(126); h.write(len shr 8); h.write(len and 0xFF) }
            else -> { h.write(127); for (s in 56 downTo 0 step 8) h.write(((len.toLong() shr s) and 0xFF).toInt()) }
        }
        h.write(data, off, len)
        out.write(h.toByteArray())
        out.flush()
    }

    private fun readFully(i: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = i.read(b, got, n - got)
            if (r < 0) throw EOFException("the relay closed the tunnel")
            got += r
        }
        return b
    }

    fun readFrame(i: InputStream): Frame {
        val h = readFully(i, 2)
        val fin = h[0].toInt() and 0x80 != 0
        val op = h[0].toInt() and 0x0F
        val masked = h[1].toInt() and 0x80 != 0
        var len = (h[1].toInt() and 0x7F).toLong()
        if (len == 126L) len = readFully(i, 2).let { ((it[0].toLong() and 0xFF) shl 8) or (it[1].toLong() and 0xFF) }
        else if (len == 127L) len = readFully(i, 8).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xFF) }
        if (len < 0 || len > MAX_MESSAGE) throw IOException("frame of $len bytes")
        val mask = if (masked) readFully(i, 4) else null
        val p = readFully(i, len.toInt())
        if (mask != null) for (k in p.indices) p[k] = (p[k].toInt() xor mask[k and 3].toInt()).toByte()
        return Frame(fin, op, p)
    }

    /**
     * The next data message, whole (continuations joined); a ping is answered through [pong] on
     * the way. Throws [EOFException] when the server closes.
     */
    fun readMessage(i: InputStream, pong: (ByteArray) -> Unit): ByteArray {
        var acc: ByteArrayOutputStream? = null
        while (true) {
            val f = readFrame(i)
            when (f.opcode) {
                OP_PING -> pong(f.payload)
                OP_PONG -> {}
                OP_CLOSE -> throw EOFException("the relay closed the tunnel")
                OP_BINARY, OP_TEXT, OP_CONT -> {
                    if (f.fin && acc == null) return f.payload
                    val a = acc ?: ByteArrayOutputStream().also { acc = it }
                    a.write(f.payload)
                    if (a.size() > MAX_MESSAGE) throw IOException("message over $MAX_MESSAGE bytes")
                    if (f.fin) return a.toByteArray()
                }
                else -> throw IOException("unknown frame opcode ${f.opcode}")
            }
        }
    }
}
