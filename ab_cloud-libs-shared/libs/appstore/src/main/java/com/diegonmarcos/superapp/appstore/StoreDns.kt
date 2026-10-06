package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.diegonmarcos.superapp.updater.source.DownloadFailure
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
import javax.net.SocketFactory

/**
 * #860 The Store's downloads resolve THE WAY THE ACTIVE PRESET SAYS.
 *
 * The downloader (libs:updater) opens plain HttpURLConnections on the process
 * default, so with the VPN slot carrying a list the preset no longer names
 * (a stale public tunnel's resolvers), every Store source failed with
 * "cannot resolve github.com" while Android's own resolver — what Mirror means —
 * answered. The lib stays untouched: a ProxySelector, consulted by every
 * HttpURLConnection, walks the preset's [plan] for the download hosts. The
 * system resolver answering = DIRECT, unchanged. Otherwise the first route that
 * resolves carries the connection through a loopback CONNECT tunnel bound to
 * that route (TLS stays end to end, the hostname is still verified). No route =
 * DIRECT, so the lookup fails as before and [lastFailure] names every resolver
 * that was actually tried.
 *
 * Mirror Android: the system resolver, then the same resolver on each
 * underlying (non-VPN) network, then ONLY the preset's own servers. Never a
 * fixed public list, never another preset's servers. Public/Private presets ARE
 * what the VPN carries, so their single route is the system resolver, labelled
 * with the preset.
 *
 * #866 Lives in libs:appstore so SuperApp and Cloud Store walk ONE path. What
 * only a host knows comes through the hooks below: SuperApp answers [presetOf]
 * from its DNS page (FleetDns) and [query] from FleetDns.query; Cloud Store has
 * no DNS page, so it keeps the defaults (Mirror: the system resolver, then
 * Android's resolver on each underlying network).
 */
object StoreDns {
    /** The host's active preset, Android-free. [mirror] = "Mirror Android". */
    class Preset(val label: String, val mirror: Boolean, val servers: List<String> = emptyList(), val fallback: List<String> = emptyList())

    /** The preset in force. Default: Mirror with no servers of its own. */
    @Volatile var presetOf: (Context) -> Preset = { Preset("Mirror Android", true) }
    /** One plain-DNS A query: server, host, timeout ms. Throws or returns a non-address on a miss. */
    @Volatile var query: (String, String, Int) -> String = { _, _, _ -> throw UnsupportedOperationException("no DNS query hook") }
    @Volatile var timeoutMs: Int = 2500
    /** The host's one-line summary of what Android resolves with, or null. */
    @Volatile var networkSummary: (Context) -> String? = { ctx ->
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm?.getLinkProperties(cm.activeNetwork)?.dnsServers?.joinToString(", ") { it.hostAddress.orEmpty() }
            ?.ifEmpty { "no DNS servers on the active network" }
    }

    /** A planned route, Android-free so the order is testable. */
    data class Step(val label: String, val kind: String, val servers: List<String> = emptyList(), val network: Int = -1)

    const val SYSTEM = "system"
    const val UNDERLYING = "underlying"
    const val PRESET_SERVERS = "preset-servers"

    fun plan(p: Preset, underlying: List<String>): List<Step> = when {
        p.mirror -> buildList {
            add(Step("Android system resolver (${p.label})", SYSTEM))
            underlying.forEachIndexed { i, n -> add(Step("Android resolver on $n", UNDERLYING, network = i)) }
            val own = (p.servers + p.fallback).distinct()
            if (own.isNotEmpty()) add(Step("${p.label} fallback ${own.joinToString(", ")}", PRESET_SERVERS, own))
        }
        else -> listOf(Step("${p.label} via VPN (Android system resolver)", SYSTEM))
    }

    /** The hosts the Store downloads from; everything else is never touched. */
    private val STORE_HOSTS = listOf("github.com", "githubusercontent.com", "ghcr.io")
    fun isStoreHost(host: String): Boolean {
        val h = host.trimEnd('.').lowercase()
        return STORE_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /** "cannot resolve X" names this: the routes the last failed lookup went through. */
    @Volatile var lastFailure: String? = null
        private set

    /** The first route of [steps] whose resolver answers [host], or null with [lastFailure] set. */
    fun <T> pick(host: String, steps: List<Pair<Step, T>>, resolves: (Step, T) -> Boolean): Pair<Step, T>? {
        val hit = steps.firstOrNull { (s, t) -> runCatching { resolves(s, t) }.getOrDefault(false) }
        lastFailure = if (hit == null) steps.joinToString(" → ") { it.first.label }.ifEmpty { null } else null
        return hit
    }

    // ── live wiring ──────────────────────────────────────────────────────

    private class Via(val addrs: List<InetAddress>, val sockets: SocketFactory)

    private fun underlyingNetworks(ctx: Context): List<Pair<String, Network>> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        @Suppress("DEPRECATION")
        return cm.allNetworks.mapNotNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "network"
            } to n
        }
    }

    /** Resolve [host] along the preset's plan: null = DIRECT (system answered, or nothing did). */
    private fun route(ctx: Context, host: String): Via? {
        val p = presetOf(ctx)
        val nets = if (p.mirror) underlyingNetworks(ctx) else emptyList()
        var via: Via? = null
        val hit = pick(host, plan(p, nets.map { it.first }).map { it to Unit }) { s, _ ->
            when (s.kind) {
                SYSTEM -> InetAddress.getAllByName(host).isNotEmpty()
                UNDERLYING -> nets[s.network].second.let { n ->
                    n.getAllByName(host).toList().takeIf { it.isNotEmpty() }?.also { via = Via(it, n.socketFactory) } != null
                }
                PRESET_SERVERS -> s.servers.firstNotNullOfOrNull { srv ->
                    runCatching { query(srv, host, timeoutMs) }.getOrNull()
                        ?.takeIf { a -> a.split('.').size == 4 && a.split('.').all { it.toIntOrNull() != null } }
                }?.let { a ->
                    via = Via(listOf(InetAddress.getByName(a)), nets.firstOrNull()?.second?.socketFactory ?: SocketFactory.getDefault())
                } != null
                else -> false
            }
        }
        return if (hit == null || hit.first.kind == SYSTEM) null else via
    }

    private val pending = ConcurrentHashMap<String, Via>()
    @Volatile private var port = 0

    /**
     * Both halves of #860/#866 for a host, off the main thread: the proxy
     * selector, and the failure wording that names the resolvers tried.
     */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ install(app) }, "store-dns-install").start()
        DownloadFailure.activeResolver = {
            val net = runCatching { networkSummary(app) }.getOrNull()
            lastFailure?.let { "tried $it" + (net?.let { n -> "; network: $n" } ?: "") } ?: net
        }
    }

    /** Install once, at the app's start. Any failure leaves the default selector in place. */
    fun install(ctx: Context) = runCatching {
        val app = ctx.applicationContext
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        Thread({ while (true) runCatching { server.accept() }.getOrNull()?.let { s -> Thread({ tunnel(s) }, "store-dns-tunnel").start() } },
            "store-dns-proxy").apply { isDaemon = true }.start()
        val previous = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                val host = uri.host
                // Android's system resolver answering = DIRECT, decided BEFORE any
                // preset is read: the SuperApp's own self-update (same hosts)
                // never depends on FleetDns while the system resolver works.
                if (uri.scheme == "https" && host != null && isStoreHost(host) &&
                    !runCatching { InetAddress.getAllByName(host).isNotEmpty() }.getOrDefault(false)) {
                    val via = runCatching { route(app, host) }.getOrNull()
                    if (via != null) {
                        pending[host.lowercase()] = via
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

    /** One CONNECT: dial the route's address on its network, then splice. */
    private fun tunnel(client: Socket) = runCatching {
        client.use { c ->
            val inp = c.getInputStream()
            val line = readLine(inp)
            while (readLine(inp).isNotEmpty()) Unit
            val target = Regex("^CONNECT ([^: ]+):(\\d+) ").find(line)
            val via = target?.let { pending[it.groupValues[1].lowercase()] }
            if (target == null || via == null) { c.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); return@use }
            val up = via.addrs.firstNotNullOfOrNull { a ->
                runCatching { via.sockets.createSocket().apply { connect(InetSocketAddress(a, target.groupValues[2].toInt()), 15_000) } }.getOrNull()
            } ?: run { c.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); return@use }
            up.use { u ->
                c.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); flush() }
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
