package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.net.ConnectivityManager
import com.diegonmarcos.superapp.updater.source.DownloadFailure
import com.diegonmarcos.superapp.updater.source.MeshMirror
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * #860/#866/#874 The Store's downloads resolve THROUGH THE FLEET'S DNS BRIDGE.
 *
 * The downloader (libs:updater) opens plain HttpURLConnections, which resolve
 * on the process default; the DNS page's Test resolved beside it, and the two
 * disagreed on one phone ("cannot resolve github.com" under a page that said
 * github.com → 140.82.121.4). The lib stays untouched: a ProxySelector,
 * consulted by every HttpURLConnection, asks [resolve] — the host's one
 * resolver, libs:sysdns FleetDnsBridge, which walks the active preset — for
 * every download host (the release, ghcr and mesh legs, and the Store's own
 * check, which HEADs the same hosts) and carries the connection through a
 * loopback tunnel to the address it answered (TLS stays end to end, the
 * hostname is still verified; a plain-http mesh request is relayed as sent).
 * No answer = DIRECT, so the lookup fails as before and [lastFailure] names
 * every route the bridge tried. Nothing here resolves by itself.
 *
 * Lives in libs:appstore so SuperApp and Cloud Store walk ONE path; the host
 * sets [resolve] to its bridge (SuperApp's reads the DNS page's preset; Cloud
 * Store's keeps the bridge's Mirror default).
 */
object StoreDns {
    /** The host's resolver: every address for a name, or throws. Unset = DIRECT. */
    @Volatile var resolve: (String) -> List<InetAddress> = { throw UnsupportedOperationException("no DNS bridge wired") }
    /** What the host's resolver is called, for the failure wording ("bridge 127.0.0.1:2053"). */
    @Volatile var resolverLabel: () -> String = { "no DNS bridge" }
    /** The routes the host's last failed lookup walked, or null. */
    @Volatile var tried: () -> String? = { null }
    /** The host's one-line summary of what Android resolves with, or null. */
    @Volatile var networkSummary: (Context) -> String? = { ctx ->
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm?.getLinkProperties(cm.activeNetwork)?.dnsServers?.joinToString(", ") { it.hostAddress.orEmpty() }
            ?.ifEmpty { "no DNS servers on the active network" }
    }

    /** The hosts the Store downloads from (plus the mesh leg's origins); everything else is never touched. */
    private val STORE_HOSTS = listOf("github.com", "githubusercontent.com", "ghcr.io")
    fun isStoreHost(host: String): Boolean {
        val h = host.trimEnd('.').lowercase()
        return STORE_HOSTS.any { h == it || h.endsWith(".$it") } ||
            MeshMirror.bases.any { runCatching { URI(it).host.equals(h, ignoreCase = true) }.getOrDefault(false) }
    }

    /** "cannot resolve X" names this: the routes the last failed lookup went through. */
    @Volatile var lastFailure: String? = null
        private set

    // ── live wiring ──────────────────────────────────────────────────────

    private val pending = ConcurrentHashMap<String, List<InetAddress>>()
    @Volatile private var port = 0

    /**
     * Both halves for a host, off the main thread: the proxy selector, and the
     * failure wording that names the bridge and the routes it tried.
     */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ install(app) }, "store-dns-install").start()
        DownloadFailure.activeResolver = {
            val net = runCatching { networkSummary(app) }.getOrNull()
            val used = lastFailure?.let { "${resolverLabel()} tried $it" } ?: resolverLabel()
            used + (net?.let { n -> "; network: $n" } ?: "")
        }
    }

    /** Install once, at the app's start. Any failure leaves the default selector in place. */
    fun install(ctx: Context) = runCatching {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        Thread({ while (true) runCatching { server.accept() }.getOrNull()?.let { s -> Thread({ tunnel(s) }, "store-dns-tunnel").start() } },
            "store-dns-proxy").apply { isDaemon = true }.start()
        val previous = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                val host = uri.host
                if ((uri.scheme == "https" || uri.scheme == "http") && host != null && isStoreHost(host)) {
                    val addrs = runCatching { resolve(host) }.getOrDefault(emptyList())
                    lastFailure = if (addrs.isEmpty()) tried() else null
                    if (addrs.isNotEmpty()) {
                        pending[host.lowercase()] = addrs
                        return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)))
                    }
                }
                return previous?.select(uri) ?: listOf(Proxy.NO_PROXY)
            }
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
                previous?.connectFailed(uri, sa, ioe)
            }
        })
    }.onFailure { android.util.Log.w("StoreDns", "download resolver not installed", it) }

    private val CONNECT = Regex("^CONNECT ([^: ]+):(\\d+) ")
    private val ABSOLUTE = Regex("^[A-Z]+ http:/{2}([^/: ]+)(?::(\\d+))?/")

    /** One request: dial the bridge's address for its host, then splice (a CONNECT after its 200, a plain-http request as sent). */
    private fun tunnel(client: Socket) = runCatching {
        client.use { c ->
            val inp = c.getInputStream()
            val head = ArrayList<String>()
            while (true) { val l = readLine(inp); if (l.isEmpty()) break; head += l }
            val line = head.firstOrNull().orEmpty()
            val connect = CONNECT.find(line)
            val target = connect ?: ABSOLUTE.find(line)
            val addrs = target?.let { pending[it.groupValues[1].lowercase()] }
            if (target == null || addrs == null) { c.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); return@use }
            val p = target.groupValues[2].toIntOrNull() ?: 80
            val up = addrs.firstNotNullOfOrNull { a ->
                runCatching { Socket().apply { connect(InetSocketAddress(a, p), 15_000) } }.getOrNull()
            } ?: run { c.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); return@use }
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
