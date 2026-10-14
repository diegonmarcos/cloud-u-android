package com.diegonmarcos.superapp.devtools

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * #ipc the pure half of the fleet IPC layer (a0_docs/eng-specs/fleet-ipc.md): the wire
 * words, the provider's gate and the client's ladder. No android.* here, so a plain-JVM
 * test drives every decision; [FleetIpc] is the thin Android half (Bundle, provider).
 *
 * App to app on one phone is a provider call on the callee's `<pkg>.fleet`
 * ([FleetTokenProvider], signature-guarded), never a loopback socket. HTTP is kept only as
 * the version-skew rung: a member built before this layer answers a provider call with
 * nothing, and is then asked on its old debug port.
 */
object FleetIpcWire {
    /** The one provider method. Versioned: a v2 is a new method, never a changed v1. */
    const val METHOD = "fleet.ipc.v1"
    const val K_STATUS = "status"
    const val K_BODY = "body"
    const val K_TYPE = "type"
    const val K_PIPE = "pipe"
    /** A request body rides the extras under the same name an app route reads it by. */
    const val K_BODY_IN = "_body"
    /** A call that waits longer is answered 504: a cold start measured ~10 s (#762). */
    const val DEFAULT_TIMEOUT_MS = 15_000L
    /** Bodies above this many chars leave by a pipe, not the binder buffer (1 MB, shared). */
    const val INLINE_MAX = 128 * 1024

    enum class Via { IPC, SELF, HTTP, NONE }

    data class Reply(val status: Int, val body: String, val type: String = "text/plain",
                     val via: Via = Via.IPC, val note: String = "") {
        val ok: Boolean get() = status in 200..299
    }

    const val REFUSED = "caller is not a fleet app: needs com.diegonmarcos.cloud.permission.CONSTELLATION_DATA (signature)\n"

    /** The provider's whole access decision: null lets the call through. */
    fun gate(granted: Boolean): Reply? = if (granted) null else Reply(403, REFUSED, note = "refused")

    /**
     * The client's ladder. [ipc] is the provider call: a Reply, or null when the callee
     * has no IPC door (not installed, or a build from before this layer), or it throws
     * SecurityException (refused: final, never retried over HTTP) / TimeoutException
     * (the callee hung: final, HTTP would reach the same process). [http] is the
     * version-skew rung, null when there is none.
     */
    fun ladder(ipc: () -> Reply?, http: (() -> Reply?)?): Reply {
        val r = try {
            ipc()
        } catch (e: SecurityException) {
            return Reply(403, REFUSED, note = "refused: ${e.message.orEmpty().take(120)}")
        } catch (e: TimeoutException) {
            return Reply(504, "no answer over IPC in time\n", note = "timeout")
        } catch (e: Exception) {
            null
        }
        if (r != null) return r
        val h = runCatching { http?.invoke() }.getOrNull()
            ?: return Reply(503, "no IPC door and no HTTP answer\n", via = Via.NONE, note = "unreachable")
        return h.copy(via = Via.HTTP, note = "version-skew fallback: old build, HTTP debug port")
    }

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "fleet-ipc").apply { isDaemon = true } }

    /** [work] with a deadline. A binder call cannot be interrupted; it finishes on the pool. */
    fun <T> within(timeoutMs: Long, work: () -> T): T {
        val f = pool.submit<T> { work() }
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        } catch (e: TimeoutException) {
            f.cancel(true); throw e
        }
    }

    /** Exactly [bytes] UTF-8 bytes off [reader] (which already holds what followed the headers),
     *  or null when there is no body. Counted in bytes, not chars: Content-Length is bytes. The
     *  gateway's (SuperApp DevControlServer) request-body reader. */
    fun readBody(reader: java.io.Reader, bytes: Int): String? {
        if (bytes <= 0) return null
        val sb = StringBuilder()
        var n = 0
        while (n < bytes) {
            val c = reader.read()
            if (c < 0) break
            sb.append(c.toChar())
            n += when {
                c < 0x80 -> 1
                c < 0x800 || Character.isSurrogate(c.toChar()) -> 2
                else -> 3
            }
        }
        return sb.toString()
    }

    /** `path?k=v` for the HTTP rung, from an op and its query. */
    fun path(op: String, query: Map<String, String>): String =
        "/api/" + op.trim('/') + if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") {
            java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
        }

    /**
     * The HTTP rung: one GET on a loopback debug port, with the fleet token. Raw socket,
     * not HttpURLConnection, so loopback cleartext is not subject to the host's
     * network-security policy. Null when nothing answered.
     */
    fun httpGet(port: Int, path: String, token: String?, readMs: Int = 3000): Reply? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 400)
            s.soTimeout = readMs
            s.getOutputStream().write(("GET $path HTTP/1.0\r\nHost: 127.0.0.1\r\n" +
                (if (token != null) "Authorization: Bearer $token\r\n" else "") + "\r\n").toByteArray())
            val raw = s.getInputStream().bufferedReader().readText()
            val code = raw.substringAfter(' ', "").take(3).toIntOrNull() ?: return@use null
            val type = Regex("(?im)^Content-Type:\\s*([^;\\r\\n]+)").find(raw)?.groupValues?.get(1) ?: "text/plain"
            Reply(code, raw.substringAfter("\r\n\r\n", ""), type, Via.HTTP)
        }
    }.getOrNull()
}
