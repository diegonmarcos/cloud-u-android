package com.diegonmarcos.superapp.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge
import com.diegonmarcos.superapp.appstore.StoreMesh
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.devtools.FleetPeers
import com.diegonmarcos.superapp.devtools.FleetToken
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * #794 Configs ▸ Setup ▸ Network ▸ DNS ▸ Bridges and DNS servers, and
 * GET /api/net/dns/overview on this app's debug API. ONE collector: the page
 * renders [collect]'s JSON as text, so the screen and a screen-locked curl
 * cannot disagree.
 *
 * Per app, every fleet member answers for ITSELF through its own debug API
 * (libs:devtools AppDebugServer, fleet token), found the way Store ▸ Apps Mesh
 * finds it ([StoreMesh.locate], every installed fleet peer): GET /api/net/dns is the network and DNS
 * servers that member's uid gets (#758); GET /api/sysdns/state is its terminal
 * DNS bridge (libs:sysdns SystemDnsBridge) — a 404 means it has none; and a
 * bridge holder's /api/diagnostics/crashes is read for the #791 class (a
 * resolver answer sent on the main looper killed the terminal).
 *
 * Every DNS server the page knows is probed once, now: the presets' servers
 * and fallbacks, the fleet resolvers, Android's network DNS and its Private
 * DNS host. The shaping ([paths], [servers], [answering], [bridgeCrashes]) is
 * pure, so DnsOverviewTest drives it with phones it invents.
 */
object DnsOverview {

    fun register(ctx: Context) {
        val app = ctx.applicationContext
        AppDebugServer.route("net", listOf(AppDebugServer.Op("dns/overview", "",
            "#794 the DNS page as JSON: the preset and whether it is in effect (needs_consent when the engine " +
                "owning the VPN slot has no VPN permission), every member's resolution path with its flags and " +
                "terminal bridge state, every known DNS server with role, protocol, presets, reachability now and " +
                "which one is answering"))) { op, _ -> if (op == "dns/overview") collect(app).toString() else null }
        // #874 the same state a terminal serves for its bridge, for this app's own.
        AppDebugServer.route("sysdns", listOf(AppDebugServer.Op("state", "",
            "#874 this app's DNS bridge (libs:sysdns FleetDnsBridge): listening port, query counters, the route that answered last and the routes a failed lookup tried"))) { op, _ ->
            if (op == "state") FleetDnsBridge.stateJson() else null
        }
    }

    // ── per app ──────────────────────────────────────────────────────────

    /** One member's answers; null = it did not answer that route. */
    class Member(val pkg: String, val label: String, val port: Int, val dns: JSONObject?, val bridge: JSONObject?, val bridgeCrashes: Int)

    /** #889 libs:devtools /api/net/dns "network" for a uid netpolicy cut off (its activeNetwork is null, NetworkInfo BLOCKED). */
    const val BLOCKED = "blocked"

    private val CRASH_SEPARATOR = Regex("\n\n─{5,}\n\n")
    private val BRIDGE_FRAMES = listOf("cloudlib.sysdns", "SystemDnsBridge")

    /** Stored crash reports (a /api/diagnostics/crashes body) whose trace runs through the DNS bridge. */
    fun bridgeCrashes(crashes: String?): Int =
        if (crashes.isNullOrBlank()) 0 else crashes.split(CRASH_SEPARATOR).count { r -> BRIDGE_FRAMES.any { it in r } }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

    /**
     * Every member's path to an answer and the addresses at its end. [onVpn] and
     * [active] are the SuperApp's own view — the fleet choice — so a member that
     * sees anything else is flagged, as is every declared self-resolver, a bridge
     * that is not listening or has failed, and a #791 crash.
     */
    fun paths(d: FleetDns.Decl, members: List<Member>, onVpn: Boolean, active: List<String>): JSONArray {
        val out = JSONArray()
        for (m in members.sortedBy { it.label.lowercase() }) {
            val dns = m.dns
            val net = dns?.let { if (it.isNull("network")) null else it.optString("network") }
            val ups = dns?.optJSONArray("upstreams").strings()
            val list = ups.joinToString(", ").ifEmpty { "none" }
            val resolver = when {
                dns == null -> "?"
                net == BLOCKED -> "no network: blocked for this uid by Android netpolicy"
                net == "vpn" -> "VPN DNS $list"
                else -> "mirror: network DNS $list"
            }
            val bridge = m.bridge?.let { "127.0.0.1:${it.optInt("port")} SystemDnsBridge → " }.orEmpty()
            val flags = JSONArray()
            when {
                dns == null -> flags.put("no /api/net/dns answer: the app predates #758 or has not adopted the fleet token")
                net == BLOCKED -> flags.put("network blocked by netpolicy (doze / background restriction): every lookup fails " +
                    "until the app is on the doze allowlist (device tuning) or works under a foreground service")
                onVpn && net != "vpn" -> flags.put("outside the VPN: resolves with the network's DNS ($list), not the fleet preset")
                ups.toSet() != active.toSet() ->
                    flags.put("resolves with $list, not ${active.joinToString(", ").ifEmpty { "none" }} as the SuperApp does")
            }
            d.selfResolvers.filter { it.pkg == m.pkg }.forEach { flags.put("resolves by itself: ${it.what} — ${it.how}") }
            m.bridge?.let { b ->
                if (!b.optBoolean("listening")) flags.put("bridge not listening: ${b.optString("why", "closed")}")
                if (b.optLong("errors") > 0) flags.put("bridge errors: ${b.optLong("errors")}, last: ${b.optString("last_error")}")
            }
            if (m.bridgeCrashes > 0) flags.put("#791 crash class: ${m.bridgeCrashes} stored crash report(s) through the DNS bridge")
            out.put(JSONObject()
                .put("pkg", m.pkg).put("label", m.label).put("port", m.port)
                .put("path", "${bridge}Android system resolver → $resolver")
                .put("network", net ?: JSONObject.NULL)
                .put("upstreams", JSONArray(ups))
                .put("private_dns", dns?.optJSONObject("private_dns") ?: JSONObject.NULL)
                .put("bridge", m.bridge ?: JSONObject.NULL)
                .put("bridge_crashes", m.bridgeCrashes)
                .put("flags", flags))
        }
        return out
    }

    // ── DNS servers ──────────────────────────────────────────────────────

    /** One server, once, with every role it plays. [testName] is what a probe asks it; [dot] = probe TLS, not port 53. */
    class Server(val address: String, val protocol: String, val roles: List<String>, val presets: List<String>, val testName: String, val dot: Boolean)

    /**
     * Every DNS server the page knows: each preset's servers (primary) and
     * fallbacks, the fleet resolvers (mesh-only; the private presets use them),
     * Android's network DNS, what the active network hands out now ([active] —
     * another VPN's servers land here), and Android's strict Private DNS host.
     */
    fun servers(d: FleetDns.Decl, fleet: List<String>, network: List<String>, active: List<String>, privateHost: String?): List<Server> {
        class Acc(var test: String) {
            val protocols = LinkedHashSet<String>(); val roles = LinkedHashSet<String>(); val presets = LinkedHashSet<String>()
            var dot = false
        }
        val acc = LinkedHashMap<String, Acc>()
        fun add(a: String, protocol: String, role: String, preset: String?) = acc.getOrPut(a) { Acc(d.testPublic) }.apply {
            protocols += protocol; roles += role; preset?.let { presets += it }
        }
        for (p in d.presets) {
            val proto = if (p.encryption.startsWith("dot")) "DoT (opportunistic: when Android Private DNS is Automatic)" else "plain"
            p.servers.forEach { add(it, proto, "primary", p.id) }
            p.fallback.forEach { add(it, proto, "fallback", p.id) }
        }
        val privateIds = d.presets.filter { it.kind == FleetDns.KIND_PRIVATE }.map { it.id }
        for (a in fleet) {
            add(a, "plain", "mesh-only", null).apply { presets += privateIds; if (d.testMesh.isNotEmpty()) test = d.testMesh }
        }
        network.forEach { add(it, "plain", "Android network DNS", null) }
        active.forEach { add(it, "plain", "handed out by the active network now", null) }
        privateHost?.takeIf { it.isNotBlank() }?.let { add(it, "DoT", "Android Private DNS (strict)", null).dot = true }
        return acc.map { (a, x) -> Server(a, x.protocols.joinToString(" / "), x.roles.toList(), x.presets.toList(), x.test, x.dot) }
    }

    class Probe(val reachable: Boolean, val ms: Long?, val result: String)

    /**
     * The server answering this app now: Android's strict Private DNS host when
     * it is in use, else the first reachable server of the active network's list
     * (the order Android's resolver tries them), else none.
     */
    fun answering(servers: List<Server>, probes: List<Probe>, active: List<String>, privateActive: Boolean?, privateServer: String?): String? {
        if (privateActive == true && !privateServer.isNullOrBlank()) return privateServer
        return active.firstOrNull { a -> servers.indexOfFirst { it.address == a }.let { i -> i >= 0 && probes[i].reachable } }
    }

    private const val DOT_PORT = 853

    private fun probe(s: Server, timeoutMs: Int): Probe {
        val t0 = System.nanoTime()
        return runCatching {
            if (s.dot) Socket().use { it.connect(InetSocketAddress(s.address, DOT_PORT), timeoutMs); "TLS port $DOT_PORT open" }
            else "${s.testName} → ${FleetDns.query(s.address, s.testName, timeoutMs)}"
        }.fold(
            { Probe(true, (System.nanoTime() - t0) / 1_000_000, it) },
            { Probe(false, null, it.javaClass.simpleName + (it.message?.let { m -> ": $m" } ?: "")) },
        )
    }

    // ── collect ──────────────────────────────────────────────────────────

    /** DNS of every non-VPN network with internet: what Android falls back to (Mirror). */
    @Suppress("DEPRECATION")
    private fun networkDns(ctx: Context): List<String> = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.filter { n ->
            cm.getNetworkCapabilities(n)?.let {
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true
        }.flatMap { n -> cm.getLinkProperties(n)?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty() }.distinct()
    }.getOrDefault(emptyList())

    private fun members(ctx: Context): List<Member> {
        val token = FleetToken.get(ctx)
        val own = AppDebugServer.boundPort()
        val pm = ctx.packageManager
        fun label(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        fun json(port: Int, path: String) = StoreMesh.get(port, path, token)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val pool = Executors.newFixedThreadPool(8)
        val others = try {
            // This app's own server is the one serving this very request when it
            // came in over the debug API: asking it would wait out the timeout.
            StoreMesh.locate(FleetPeers.list(ctx).filter { it != ctx.packageName }).filter { (_, port) -> port != own }
                .map { (pkg, port) -> pool.submit(Callable {
                    val bridge = json(port, "/api/sysdns/state")
                    val crashes = if (bridge == null) 0 else bridgeCrashes(StoreMesh.get(port, "/api/diagnostics/crashes", token))
                    Member(pkg, label(pkg), port, json(port, "/api/net/dns"), bridge, crashes)
                }) }
                .mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally { pool.shutdownNow() }
        val a = FleetDns.readAndroid(ctx)
        val self = JSONObject().put("network", if (a.onVpn) "vpn" else "direct").put("upstreams", JSONArray(a.activeServers))
            .put("private_dns", JSONObject().put("active", a.privateDnsActive ?: JSONObject.NULL).put("server", a.privateDnsServer ?: JSONObject.NULL))
        // #874 this app resolves through its own bridge too; its state is read in-process.
        val bridge = runCatching { JSONObject(FleetDnsBridge.stateJson()) }.getOrNull()
        return others + Member(ctx.packageName, label(ctx.packageName), own, self, bridge, 0)
    }

    /** The whole overview. Blocks for a sweep and a probe round (a few seconds): off the main thread. */
    fun collect(ctx: Context): JSONObject {
        val app = ctx.applicationContext
        val d = FleetDns.decl
        val live = FleetDns.live(app)
        val a = live.android
        val v = live.verdict
        val p = FleetDns.effective(d, FleetDns.Prefs(app).preset)
        val network = networkDns(app)
        val privateHost = a.privateDnsServer ?: a.specifier.takeIf { a.mode == "hostname" }
        val servers = servers(d, FleetDns.fleetResolvers(app), network, a.activeServers, privateHost)
        val pool = Executors.newFixedThreadPool(servers.size.coerceIn(1, 16))
        // The probes run while the fleet is swept: both wait on timeouts, neither on the other.
        val (probes, apps) = try {
            val pending = servers.map { s -> pool.submit(Callable { probe(s, d.timeoutMs) }) }
            val rows = paths(d, members(app), a.onVpn, a.activeServers)
            pending.map { it.get() } to rows
        } finally { pool.shutdownNow() }
        val answering = answering(servers, probes, a.activeServers, a.privateDnsActive, a.privateDnsServer)
        fun nul(x: Any?) = x ?: JSONObject.NULL
        return JSONObject()
            .put("preset", JSONObject().put("id", p.id).put("label", p.label).put("chosen", FleetDns.Prefs(app).chosen))
            .put("mesh_up", live.meshUp)
            .put("engine_idle", live.idle)
            .put("verdict", JSONObject().put("ok", v.ok).put("needs_consent", v.needsConsent).put("why", v.why)
                .put("promised", JSONArray(v.promised)).put("actual", JSONArray(v.actual))
                .put("engine", FleetDns.engineLabel(app)))
            .put("android", JSONObject().put("private_dns_mode", nul(a.mode)).put("specifier", nul(a.specifier))
                .put("private_dns_active", nul(a.privateDnsActive)).put("private_dns_server", nul(a.privateDnsServer))
                .put("on_vpn", a.onVpn).put("active_servers", JSONArray(a.activeServers)).put("network_dns", JSONArray(network)))
            .put("apps", apps)
            .put("servers", JSONArray().apply {
                servers.forEachIndexed { i, s ->
                    val pr = probes[i]
                    put(JSONObject().put("address", s.address).put("protocol", s.protocol)
                        .put("roles", JSONArray(s.roles)).put("presets", JSONArray(s.presets))
                        .put("reachable", pr.reachable).put("ms", nul(pr.ms)).put("probe", pr.result)
                        .put("in_use_now", s.address in a.activeServers || (s.dot && a.privateDnsActive == true))
                        .put("answering_now", s.address == answering))
                }
            })
            .put("answering", nul(answering))
    }

    // ── text, for the page ───────────────────────────────────────────────

    fun pathsText(o: JSONObject): String {
        val apps = o.optJSONArray("apps") ?: return "—"
        return (0 until apps.length()).joinToString("\n\n") { i ->
            val r = apps.getJSONObject(i)
            buildString {
                append("${r.optString("label")}  (${r.optString("pkg")} :${r.optInt("port")})\n  ${r.optString("path")}")
                r.optJSONObject("bridge")?.let { b ->
                    if (b.optBoolean("listening")) append("\n  bridge: listening · ${b.optLong("queries")} queries, " +
                        "${b.optLong("answered")} answered, ${b.optLong("servfail")} SERVFAIL, ${b.optLong("errors")} errors · last query " +
                        (b.optLong("last_query_ms").takeIf { it > 0 }?.let { java.text.DateFormat.getTimeInstance().format(java.util.Date(it)) } ?: "never"))
                }
                val f = r.optJSONArray("flags").strings()
                f.forEach { append("\n  ⚠ $it") }
            }
        }.ifEmpty { "no fleet member answered" }
    }

    fun serversText(o: JSONObject): String {
        val s = o.optJSONArray("servers") ?: return "—"
        return (0 until s.length()).joinToString("\n\n") { i ->
            val r = s.getJSONObject(i)
            val presets = r.optJSONArray("presets").strings()
            buildString {
                append("${r.optString("address")} — ${r.optJSONArray("roles").strings().joinToString(" · ")}\n")
                append("  ${r.optString("protocol")}")
                if (presets.isNotEmpty()) append(" · presets: ${presets.joinToString(", ")}")
                append("\n  ")
                append(if (r.optBoolean("reachable")) "reachable, ${r.optLong("ms")} ms (${r.optString("probe")})" else "UNREACHABLE (${r.optString("probe")})")
                if (r.optBoolean("in_use_now")) append(" · IN USE")
                if (r.optBoolean("answering_now")) append(" · ANSWERING NOW")
            }
        }
    }
}
