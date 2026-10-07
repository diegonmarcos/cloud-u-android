package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.net.ConnectivityManager
import com.diegonmarcos.superapp.updater.source.DownloadFailure
import com.diegonmarcos.superapp.updater.source.MeshMirror
import java.net.InetAddress
import java.net.URI

/**
 * #860/#866/#874 The Store's downloads resolve through a LADDER that starts at
 * the fleet's DNS bridge and does not end there.
 *
 * The downloader (libs:updater) opens plain HttpURLConnections, which resolve
 * on the process default; the DNS page's Test resolved beside it, and the two
 * disagreed on one phone. The lib stays untouched: a ProxySelector, consulted
 * by every HttpURLConnection, walks [DnsLadder] for every download host (the
 * release, ghcr and mesh legs, and the Store's own check) and carries the
 * connection through a loopback tunnel to the address the first answering rung
 * gave (TLS stays end to end, the hostname is still verified).
 *
 * #899 MEASURED 2026-10-07: a bridge that did not answer meant "cannot resolve"
 * on every leg, because the bridge was the only resolver. The ladder is, in
 * order: the bridge ([resolve], libs:sysdns FleetDnsBridge, which walks the
 * active preset), the system resolver, direct DoH by IP ([dohServers]), the mesh
 * ([meshResolve]). Each walk starts afresh (a Retry re-resolves), nothing is
 * cached as bad, and the row error names every rung and why it declined.
 *
 * Lives in libs:appstore so SuperApp and Cloud Store walk ONE path; the host
 * sets the hooks (SuperApp's bridge reads the DNS page's preset and it has a
 * mesh resolver; Cloud Store's keeps the bridge's Mirror default and has none).
 */
object StoreDns {
    /** The host's bridge: every address for a name, or throws. Unset = the rung always declines. */
    @Volatile var resolve: (String) -> List<InetAddress> = { throw UnsupportedOperationException("no DNS bridge wired") }
    /** What the host's bridge is called, for the failure wording ("bridge 127.0.0.1:2053"). */
    @Volatile var resolverLabel: () -> String = { "no DNS bridge" }
    /** The routes the bridge's last failed lookup walked, or null. */
    @Volatile var tried: () -> String? = { null }
    /** Drop whatever the bridge cached for a host (its answer did not connect). */
    @Volatile var forget: (String) -> Unit = {}
    /** The mesh's resolver (the fleet resolvers over the tunnel): addresses or throws. */
    @Volatile var meshResolve: (String) -> List<InetAddress> = { throw UnsupportedOperationException("no mesh resolver in this app") }
    /** Direct DoH endpoints, IP literals: reached without any DNS. */
    @Volatile var dohServers: List<String> = listOf("1.1.1.1", "9.9.9.9")
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

    /** The ladder for one lookup, in order: bridge, system, DoH by IP, mesh. Built per walk, so the bridge's port is current. */
    fun rungs(): List<DnsLadder.Rung> = DnsLadder.ladder(
        bridgeLabel = runCatching { resolverLabel() }.getOrDefault("bridge"),
        bridge = { h -> try { resolve(h) } catch (e: Exception) { throw java.io.IOException((e.message ?: e.javaClass.simpleName) + (runCatching { tried() }.getOrNull()?.let { " [$it]" } ?: ""), e) } },
        doh = dohServers.map { "DoH $it" to "https://$it/dns-query" },
        mesh = { h -> meshResolve(h) },
    )

    private val relay = DnsLadder.Relay(::isStoreHost, ::rungs) { _, host -> runCatching { forget(host) } }

    /** "cannot resolve X" names this: every rung the last failed lookup of the host went through. */
    val lastFailure: String? get() = relay.lastFailure

    /**
     * Both halves for a host, off the main thread: the proxy selector, and the
     * failure wording that names every rung tried.
     */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ install(app) }, "store-dns-install").start()
        val named: (String?) -> String? = { host ->
            val net = runCatching { networkSummary(app) }.getOrNull()
            val used = relay.failureFor(host)?.let { "resolvers tried: $it" } ?: runCatching { resolverLabel() }.getOrDefault("bridge")
            used + (net?.let { n -> "; network: $n" } ?: "")
        }
        DownloadFailure.activeResolverFor = named
        DownloadFailure.activeResolver = { named(null) }
    }

    /** Install once, at the app's start. Any failure leaves the default selector in place. */
    fun install(@Suppress("UNUSED_PARAMETER") ctx: Context) = runCatching { relay.install() }
        .onFailure { android.util.Log.w("StoreDns", "download resolver not installed", it) }
}
