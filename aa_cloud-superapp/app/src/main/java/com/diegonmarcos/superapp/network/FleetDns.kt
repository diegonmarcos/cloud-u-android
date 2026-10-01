package com.diegonmarcos.superapp.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * #740 The fleet's DNS — ONE choice, applied ONCE, at the SuperApp's VPN.
 *
 * Every fleet app and engine resolves through the Android system resolver (no
 * app names a DNS server of its own), so whatever DNS servers the VPN carries
 * are what the whole fleet uses. The VPN that is actually running is the Cloud
 * Mesh tunnel (net-wg GoBackend → VpnService.Builder.addDnsServer); firestack
 * is compiled but not yet the active engine. So the choice reaches the phone
 * through [WireGuardPrefs.toTunnelConfig], which every connect path calls.
 *
 * The presets, Android's Private DNS menu, the mesh zones and the test names
 * are `build.json::ui.dns`, baked as BuildConfig.UI_DNS_B64 — no resolver
 * address is written here. [vpnServers] and [upstreamsFor] are pure so the
 * mapping is asserted in FleetDnsTest against the declaration this build ships.
 */
object FleetDns {

    data class Preset(
        val id: String,
        val label: String,
        val subtitle: String,
        val kind: String,
        val servers: List<String>,
        val fallback: List<String>,
        val available: Boolean,
        val unavailableReason: String,
        val fallbackAllowed: Boolean,
        val fallbackChoices: List<String>,
        val defaultFallbacks: List<String>,
        val encryption: String,
        val tlsHostnames: List<String>,
    )

    data class Decl(
        val defaultPreset: String,
        val meshZones: List<String>,
        val testPublic: String,
        val testMesh: String,
        val timeoutMs: Int,
        val androidModes: List<Pair<String, String>>,
        val hostnameSuggestions: List<String>,
        val presets: List<Preset>,
    ) {
        fun preset(id: String?): Preset? = presets.firstOrNull { it.id == id }
    }

    const val KIND_MIRROR = "mirror"
    const val KIND_PUBLIC = "public"
    const val KIND_PRIVATE = "private"

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    fun parse(json: String): Decl {
        val o = JSONObject(json)
        val tn = o.optJSONObject("test_names") ?: JSONObject()
        val apd = o.optJSONObject("android_private_dns") ?: JSONObject()
        val modes = apd.optJSONArray("modes") ?: JSONArray()
        val ps = o.getJSONArray("presets")
        return Decl(
            defaultPreset = o.getString("default_preset"),
            meshZones = o.optJSONArray("mesh_zones").strings(),
            testPublic = tn.optString("public"),
            testMesh = tn.optString("mesh"),
            timeoutMs = o.optInt("lookup_timeout_ms", 2500),
            androidModes = (0 until modes.length()).map {
                val m = modes.getJSONObject(it); m.getString("id") to m.getString("label")
            },
            hostnameSuggestions = apd.optJSONArray("hostname_suggestions").strings(),
            presets = (0 until ps.length()).map {
                val p = ps.getJSONObject(it)
                Preset(
                    id = p.getString("id"),
                    label = p.getString("label"),
                    subtitle = p.optString("subtitle"),
                    kind = p.getString("kind"),
                    servers = p.optJSONArray("servers").strings(),
                    fallback = p.optJSONArray("fallback").strings(),
                    available = p.optBoolean("available", true),
                    unavailableReason = p.optString("unavailable_reason"),
                    fallbackAllowed = p.optBoolean("fallback_allowed", false),
                    fallbackChoices = p.optJSONArray("fallback_choices").strings(),
                    defaultFallbacks = p.optJSONArray("default_fallbacks").strings(),
                    encryption = p.optString("encryption"),
                    tlsHostnames = p.optJSONArray("tls_hostnames").strings(),
                )
            },
        )
    }

    val decl: Decl by lazy { parse(String(Base64.decode(BuildConfig.UI_DNS_B64, Base64.DEFAULT))) }

    /** The preset in force: the stored id when it is declared and usable, else the declared default. */
    fun effective(d: Decl, presetId: String?): Preset =
        d.preset(presetId)?.takeIf { it.available } ?: d.preset(d.defaultPreset)!!

    /** "10.0.0.1, 10.1.0.1" → [10.0.0.1, 10.1.0.1] — the Cloud Mesh page's DNS field. */
    fun splitServers(csv: String): List<String> = csv.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The ordered DNS server list the VPN carries for [presetId]. Mirror is
     * EMPTY on purpose: a VPN with no DNS server leaves Android on the
     * underlying network's resolver and its Private DNS. Private presets put the
     * fleet resolver first and — only when the preset allows it — the user's
     * ordered fallbacks after it; an unavailable or non-public fallback is
     * never used. A private preset with no fleet resolver THROWS: an empty list
     * would silently become Mirror, and Private-only must fail loudly instead.
     */
    fun vpnServers(d: Decl, presetId: String?, fallbacks: List<String>, fleetResolvers: List<String>): List<String> {
        val p = effective(d, presetId)
        return when (p.kind) {
            KIND_MIRROR -> emptyList()
            KIND_PUBLIC -> p.servers + p.fallback
            KIND_PRIVATE -> {
                check(fleetResolvers.isNotEmpty()) {
                    "${p.label} needs the fleet resolver, but the Cloud Mesh DNS field is empty"
                }
                val extra = if (!p.fallbackAllowed) emptyList<String>() else fallbacks
                    .filter { it in p.fallbackChoices }
                    .mapNotNull { d.preset(it) }
                    .filter { it.available && it.kind == KIND_PUBLIC }
                    .flatMap { it.servers + it.fallback }
                fleetResolvers + extra
            }
            else -> error("preset ${p.id} has kind ${p.kind}, which maps to no upstream")
        }.distinct()
    }

    /** True when [name] sits in one of the fleet resolver's own zones (zone boundary, not a suffix match). */
    fun isMeshName(d: Decl, name: String): Boolean {
        val n = name.trimEnd('.').lowercase()
        return d.meshZones.any { z -> n == z || n.endsWith(".$z") }
    }

    /**
     * Split DNS: which upstreams answer [name]. Mesh names go to the fleet
     * resolver whenever the mesh is up, whatever the preset; everything else
     * goes to [presetServers] (empty = the system resolver, i.e. Mirror).
     */
    fun upstreamsFor(d: Decl, name: String, meshUp: Boolean, presetServers: List<String>, fleetResolvers: List<String>): List<String> =
        if (meshUp && fleetResolvers.isNotEmpty() && isMeshName(d, name)) fleetResolvers else presetServers

    // ── the user's choice ────────────────────────────────────────────────

    class Prefs(context: Context) {
        private val sp = context.applicationContext.getSharedPreferences("fleet_dns_prefs", Context.MODE_PRIVATE)
        var preset: String
            get() = sp.getString("preset", null) ?: FleetDns.decl.defaultPreset
            set(v) { sp.edit().putString("preset", v).apply() }
        /** Ordered: the order the user ticked them in. */
        var fallbacks: List<String>
            get() = sp.getString("fallbacks", null)?.let { splitServers(it) }
                ?: FleetDns.decl.presets.firstOrNull { it.fallbackAllowed }?.defaultFallbacks.orEmpty()
            set(v) { sp.edit().putString("fallbacks", v.joinToString(",")).apply() }
        var lastLookup: String
            get() = sp.getString("last_lookup", "") ?: ""
            set(v) { sp.edit().putString("last_lookup", v).apply() }
    }

    /** What the tunnel carries right now for this phone's choice. */
    fun vpnServers(ctx: Context, fleetDnsCsv: String): List<String> {
        val p = Prefs(ctx)
        return vpnServers(decl, p.preset, p.fallbacks, splitServers(fleetDnsCsv))
    }

    // ── Android's own Private DNS ────────────────────────────────────────

    data class AndroidDns(
        val mode: String?,
        val specifier: String?,
        val privateDnsActive: Boolean?,
        val privateDnsServer: String?,
        val activeServers: List<String>,
        val onVpn: Boolean,
    )

    fun readAndroid(ctx: Context): AndroidDns {
        val cr = ctx.contentResolver
        // Settings.Global may refuse a non-@Readable key to an app targeting S+;
        // the shell channel can always read it when it is armed.
        fun global(key: String): String? =
            runCatching { Settings.Global.getString(cr, key) }.getOrNull()
                ?: runCatching {
                    ShellChannels.active(ctx)?.exec(ctx, "settings get global $key")?.trim()
                        ?.takeIf { it.isNotEmpty() && it != "null" }
                }.getOrNull()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork
        val lp = net?.let { cm.getLinkProperties(it) }
        val vpn = net?.let { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
        val p28 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        return AndroidDns(
            mode = global("private_dns_mode"),
            specifier = global("private_dns_specifier"),
            privateDnsActive = if (p28) lp?.isPrivateDnsActive else null,
            privateDnsServer = if (p28) lp?.privateDnsServerName else null,
            activeServers = lp?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            onVpn = vpn,
        )
    }

    private val HOSTNAME = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")

    /**
     * Change Android's Private DNS through the privileged shell channel.
     * Returns null when no channel is armed (the caller deep-links to Android's
     * own settings instead). [mode] must be a declared mode and [host] a plain
     * hostname — both reach a shell, so neither is passed through unchecked.
     */
    fun writeAndroid(ctx: Context, mode: String, host: String): String? {
        require(decl.androidModes.any { it.first == mode }) { "undeclared Private DNS mode $mode" }
        val ch = ShellChannels.active(ctx) ?: return null
        val cmds = mutableListOf("settings put global private_dns_mode $mode")
        if (mode == "hostname") {
            require(HOSTNAME.matches(host)) { "not a hostname: $host" }
            cmds += "settings put global private_dns_specifier $host"
        }
        return cmds.joinToString("\n") { ch.exec(ctx, it) ?: "(channel refused: $it)" }
    }

    // ── test lookup ──────────────────────────────────────────────────────

    /**
     * One plain-DNS A query to [server] over UDP, so the page can say WHICH
     * upstream answered — the system resolver never reports that. Returns the
     * first A record, "NXDOMAIN" or "rcode N"; throws on timeout / no route.
     */
    fun query(server: String, name: String, timeoutMs: Int): String {
        val id = (System.nanoTime() and 0xFFFF).toInt()
        val q = ByteArrayOutputStream().apply {
            write(byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            for (label in name.trimEnd('.').split('.')) { val b = label.toByteArray(); write(b.size); write(b) }
            write(byteArrayOf(0, 0, 1, 0, 1)) // root, QTYPE=A, QCLASS=IN
        }.toByteArray()
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(1500)
            val pkt = DatagramPacket(buf, buf.size)
            s.receive(pkt)
            return parseAnswer(buf, pkt.length, id, q.size)
        }
    }

    fun parseAnswer(b: ByteArray, len: Int, id: Int, questionEnd: Int): String {
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        check(len >= 12 && u16(0) == id) { "reply does not match the query" }
        val rcode = b[3].toInt() and 0x0F
        if (rcode != 0) return if (rcode == 3) "NXDOMAIN" else "rcode $rcode"
        var i = questionEnd
        repeat(u16(6)) {
            while (true) { // owner name: labels, or a compression pointer
                val l = b[i].toInt() and 0xFF
                if (l == 0) { i++; break }
                if ((l and 0xC0) == 0xC0) { i += 2; break }
                i += l + 1
            }
            val type = u16(i); val rdlen = u16(i + 8); val rd = i + 10
            if (type == 1 && rdlen == 4) return (0..3).joinToString(".") { (b[rd + it].toInt() and 0xFF).toString() }
            i = rd + rdlen
        }
        return "no A record"
    }
}
