package com.diegonmarcos.superapp.network.mesh

import android.content.Context
import android.content.Intent
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.firewall.FirewallController
import com.diegonmarcos.superapp.network.AccountMesh
import com.diegonmarcos.superapp.network.FleetDns
import com.diegonmarcos.superapp.network.WgState
import com.diegonmarcos.superapp.network.WireGuardPrefs
import com.diegonmarcos.superapp.network.WireGuardProfiles
import com.diegonmarcos.superapp.profile.AccountModel
import com.diegonmarcos.superapp.profile.VaultCockpit
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import java.io.BufferedReader
import java.io.StringReader

/**
 * #877 [MeshPort] over the paths the app already has: [WgState.backend] (the engine's binder client),
 * [WireGuardPrefs], [AccountMesh] and [FleetDns]. It adds none of its own; every connect brings the
 * tunnel up with [WireGuardPrefs.toTunnelConfig], the config the DNS page and the device controls use.
 */
class AndroidMeshPort(context: Context) : MeshPort {

    private val ctx: Context = context.applicationContext
    private val prefs get() = WgState.prefs(ctx)
    private val backend get() = WgState.backend(ctx)

    @Volatile private var lastUp = false
    /** Hash of the config the tunnel was last brought up with by THIS process; null = unknown. */
    @Volatile private var applied: Int? = null
    private var keyCache: Pair<String, String>? = null
    private var dnsText = ""
    private var dnsAtNs = 0L

    // ── reads ──

    private fun derivedKey(): String {
        val priv = prefs.interfacePrivateKey
        keyCache?.takeIf { it.first == priv }?.let { return it.second }
        return prefs.derivedInterfacePublicKey().also { keyCache = priv to it }
    }

    private fun dnsInEffect(): String {
        val now = System.nanoTime()
        if (dnsText.isEmpty() || now - dnsAtNs > DNS_TTL_NS) {
            dnsText = runCatching {
                val d = FleetDns.decl
                val label = FleetDns.effective(d, FleetDns.Prefs(ctx).preset).label
                "$label · ${FleetDns.resolverSummary(ctx)}"
            }.getOrDefault("")
            dnsAtNs = now
        }
        return dnsText
    }

    private fun PeerCfg.toData() = WireGuardPrefs.PeerData(name, publicKey, presharedKey, endpoint, allowedIps, keepalive)

    override fun config(): ConfigView = ConfigView(
        tunnelName = prefs.tunnelName,
        address = prefs.interfaceAddress,
        publicKey = derivedKey(),
        dns = prefs.interfaceDns,
        mtu = prefs.interfaceMtu,
        listenPort = prefs.interfaceListenPort,
        activeProfile = prefs.activeProfile,
        dnsInEffect = dnsInEffect(),
        peers = prefs.peers().map { PeerCfg(it.name, it.publicKey, it.presharedKey, it.endpoint, it.allowedIps, it.persistentKeepalive) },
    )

    override fun engineInstalled(): Boolean = runCatching { backend.isEngineInstalled() }.getOrDefault(false)

    override fun sample(nowMs: Long): Sample {
        val engine = engineInstalled()
        val up = engine && runCatching { backend.getState(WgState.tunnel) == Tunnel.State.UP }.getOrDefault(false)
        lastUp = up
        if (!up) applied = null
        val stats = if (up) runCatching { backend.getStatistics(WgState.tunnel) }.getOrNull() else null
        val peers = prefs.peers().mapNotNull { p ->
            val live = runCatching { stats?.peer(Key.fromBase64(p.publicKey)) }.getOrNull() ?: return@mapNotNull null
            PeerCounters(p.publicKey, live.rxBytes(), live.txBytes(), live.latestHandshakeEpochMillis())
        }
        return Sample(nowMs, engine, up, peers)
    }

    override fun probeLatency(cfg: ConfigView, timeoutMs: Int, nowMs: Long): LatencyReading {
        val resolvers = FleetDns.splitServers(cfg.dns)
        val name = FleetDns.decl.testMesh.ifBlank { "example.org" }
        val seen = HashMap<String, Int?>()
        fun rtt(ip: String): Int? = seen.getOrPut(ip) {
            runCatching {
                val t = System.nanoTime()
                FleetDns.query(ip, name, timeoutMs)
                ((System.nanoTime() - t) / 1_000_000).toInt()
            }.getOrNull()
        }
        val hub = resolvers.firstOrNull()?.let(::rtt)
        return LatencyReading(nowMs, hub, LatencyTargets.forPeers(cfg.peers, resolvers).mapValues { rtt(it.value) })
    }

    private fun sig(): Int? = runCatching { prefs.toTunnelConfig().toWgQuickString().hashCode() }.getOrNull()

    override fun pendingApply(): Boolean = lastUp && applied != null && sig() != applied

    override fun alwaysOn(): Boolean? = if (!engineInstalled()) null else runCatching { backend.isAlwaysOn }.getOrNull()
    override fun lockdown(): Boolean? = if (!engineInstalled()) null else runCatching { backend.isLockdownEnabled }.getOrNull()
    override fun consentIntent(): Intent? = runCatching { backend.consentIntent() }.getOrNull()

    // ── tunnel state ──

    private fun requireEngine() {
        check(backend.isEngineInstalled()) {
            "WireGuard engine not installed - install Cloud-Lib-Net-Wg from Configs > Store > Cloud Constellation > Libs"
        }
    }

    private fun bringUp(): Tunnel.State {
        val cfg = prefs.toTunnelConfig()
        val r = backend.setState(WgState.tunnel, Tunnel.State.UP, cfg)
        prefs.tunnelEnabled = r == Tunnel.State.UP
        applied = if (r == Tunnel.State.UP) cfg.toWgQuickString().hashCode() else null
        lastUp = r == Tunnel.State.UP
        return r
    }

    override fun connect(): String {
        requireEngine()
        val r = bringUp()
        check(r == Tunnel.State.UP) { "the engine answered $r" }
        return "connected: ${prefs.tunnelName}"
    }

    override fun disconnect(): String {
        requireEngine()
        backend.setState(WgState.tunnel, Tunnel.State.DOWN, null)
        prefs.tunnelEnabled = false
        applied = null
        lastUp = false
        return "disconnected"
    }

    override fun reconnect(): String {
        requireEngine()
        backend.setState(WgState.tunnel, Tunnel.State.DOWN, null)
        val r = bringUp()
        check(r == Tunnel.State.UP) { "the engine answered $r after the restart" }
        return "reconnected: ${prefs.tunnelName}"
    }

    /** With the tunnel up, bring it up again on the new config; otherwise the value waits for the next connect. */
    private fun reapplyIfUp(done: String): String {
        if (!lastUp) return "$done (applies at the next connect)"
        return runCatching { bringUp(); "$done (applied)" }.getOrElse { "$done, but re-applying failed: ${it.message}" }
    }

    // ── interface fields ──

    override fun mtu() = prefs.interfaceMtu

    override fun setMtu(v: String): String {
        val t = v.trim()
        require(t.isEmpty() || t.toIntOrNull() in 1280..1500) { "MTU must be 1280-1500 (or empty for the engine default)" }
        prefs.interfaceMtu = t
        return "MTU ${t.ifEmpty { "default" }} saved"
    }

    override fun keepalive(): String = prefs.peers().firstOrNull()?.persistentKeepalive.orEmpty()

    override fun setKeepalive(v: String): String {
        val t = v.trim()
        require(t.isEmpty() || t.toIntOrNull() in 0..65535) { "keepalive must be 0-65535 seconds" }
        prefs.savePeers(prefs.peers().map { it.copy(persistentKeepalive = t) })
        return "keepalive ${t.ifEmpty { "off" }}s on every peer saved"
    }

    override fun setTunnelName(v: String): String {
        val t = v.trim()
        require(TUNNEL_NAME.matches(t)) { "a tunnel name is 1-${Tunnel.NAME_MAX_LENGTH} of A-Z a-z 0-9 _ = + . -" }
        prefs.tunnelName = t
        return "tunnel name $t saved"
    }

    override fun setAddress(v: String): String {
        require(v.isNotBlank()) { "an interface address is required" }
        prefs.interfaceAddress = v.trim()
        return "address saved"
    }

    override fun setListenPort(v: String): String {
        val t = v.trim()
        require(t.isEmpty() || t.toIntOrNull() in 1..65535) { "listen port must be 1-65535 (or empty)" }
        prefs.interfaceListenPort = t
        return "listen port ${t.ifEmpty { "automatic" }} saved"
    }

    override fun hasPrivateKey() = prefs.interfacePrivateKey.isNotBlank()

    override fun setPrivateKey(v: String): String {
        val t = v.trim()
        require(runCatching { Key.fromBase64(t); t.length == 44 }.getOrDefault(false)) { "not a base64 32-byte key" }
        prefs.interfacePrivateKey = t
        return "private key stored; public half ${MeshFormat.shortKey(derivedKey())}"
    }

    override fun generateKey(): String {
        val pub = prefs.generateInterfaceKeyPair()
        return pub
    }

    override fun excludedApps() = prefs.excludedApps

    override fun setExcludedApps(v: List<String>): String {
        prefs.excludedApps = v
        return reapplyIfUp("${prefs.excludedApps.size} app(s) outside the tunnel")
    }

    override fun installedApps(): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }.sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
    }

    // ── peers ──

    override fun addPeer(): String {
        prefs.savePeers(prefs.peers() + WireGuardPrefs.PeerData.EMPTY)
        return "empty peer added - fill it in"
    }

    override fun removePeer(index: Int): String {
        val l = prefs.peers()
        require(index in l.indices) { "no such peer" }
        val gone = l.removeAt(index)
        prefs.savePeers(l)
        return "removed ${gone.name.ifBlank { "peer ${index + 1}" }}"
    }

    override fun updatePeer(index: Int, p: PeerCfg): String {
        val l = prefs.peers()
        require(index in l.indices) { "no such peer" }
        l[index] = p.toData()
        prefs.savePeers(l)
        return "peer saved"
    }

    // ── DNS ──

    override fun dnsPreset(): String = FleetDns.Prefs(ctx).preset

    override fun setDnsPreset(id: String): String {
        val preset = FleetDns.decl.preset(id) ?: error("undeclared DNS preset $id")
        check(preset.available) { preset.unavailableReason.ifBlank { "${preset.label} is not available" } }
        FleetDns.Prefs(ctx).preset = id
        val up = FleetDns.meshUp(ctx)
        if (up) bringUp()
        val v = FleetDns.syncAndCheck(ctx, raiseNow = !FirewallController.isEnabled(ctx))
        dnsAtNs = 0L
        return if (v.ok) "${preset.label}: in effect - ${v.actual.joinToString(", ")}" else "${preset.label} saved - ${v.why}"
    }

    override fun dnsChoices(): List<Triple<String, String, String>> =
        FleetDns.decl.presets.map { Triple(it.id, it.label, if (it.available) "" else it.unavailableReason) }

    // ── provider + profiles ──

    override fun provider() = prefs.configProvider
    override fun matchesCloudPreset() = prefs.matchesCloudPreset()

    override fun applyCloudPreset(): String {
        // #573 the baked preset is ONE device's identity (its Address line). A phone the Account
        // declares as another device keeps its own: the preset is refused, never re-identifies it.
        // ponytail: a guard; per-device baked presets when a second phone wants the Cloud provider.
        declaredAddresses()?.let { mine ->
            val baked = BuildConfig.UI_WG_INTERFACE_ADDRESS.split(',').map { it.trim().substringBefore('/') }
            if (baked.none { it in mine })
                return "✗ Cloud preset is ${BuildConfig.UI_WG_INTERFACE_ADDRESS} - this phone is declared as ${mine.joinToString(", ")}; apply its own profiles from Account › Mesh"
        }
        prefs.applyCloudPreset()
        prefs.configProvider = WireGuardPrefs.PROVIDER_CLOUD
        return "Cloud preset applied - this device still needs its own private key"
    }

    /** The EXPLICITLY picked device's declared wg addresses; null when nothing is picked or no
     *  bundle is landed (the live tunnel is never consulted: it is what this guard protects). */
    private fun declaredAddresses(): Set<String>? {
        val picked = VaultCockpit.selectedDevice(ctx).ifBlank { return null }
        val bundle = AccountModel.get(ctx).server()?.body ?: return null
        val d = runCatching { VaultCockpit.devices(bundle) }.getOrNull()?.firstOrNull { it.id == picked } ?: return null
        return setOf(d.wgIp, d.wgIpv6).filter { it.isNotBlank() }.toSet()
    }

    override fun setProviderCustom(): String {
        prefs.configProvider = WireGuardPrefs.PROVIDER_CUSTOM
        return "Provider: Custom - your WireGuard settings are untouched"
    }

    override fun storedProfiles() = prefs.profiles()
    override fun activeProfile() = prefs.activeProfile

    override fun activateProfile(name: String): String {
        val cfg = prefs.activateProfile(name)
        return reapplyIfUp("$name is the tunnel: ${cfg.peers.size} peers")
    }

    override fun installDeclared(id: String): String {
        val prof = WireGuardProfiles.byId(id) ?: error("no declared profile $id")
        val key = prefs.interfacePrivateKey
        check(key.isNotBlank()) { "no private key on this device - generate or paste one in Controls first" }
        val text = WireGuardProfiles.render(prof).replace(Regex("(?m)^PrivateKey = *$"), "PrivateKey = ${WireGuardPrefs.PROVIDED_BY_DEVICE}")
        runCatching { Config.parse(BufferedReader(StringReader(text.replace(WireGuardPrefs.PROVIDED_BY_DEVICE, key)))) }
            .onFailure { error("${prof.fileName} is a template the WireGuard parser rejects (${it.message}) - import your own .conf for it") }
        val name = prof.fileName.removeSuffix(".conf")
        prefs.saveProfiles(prefs.profiles() + (name to text))
        return activateProfile(name)
    }

    override fun importText(name: String, conf: String): String {
        val r = AccountMesh.applyMesh(prefs, name, conf)
        check(r.startsWith("✓")) { r }
        prefs.saveProfiles(prefs.profiles() + (name to conf))
        prefs.activeProfile = name
        return reapplyIfUp(r)
    }

    private companion object {
        const val DNS_TTL_NS = 5_000_000_000L
        val TUNNEL_NAME = Regex("[A-Za-z0-9_=+.-]{1,${Tunnel.NAME_MAX_LENGTH}}")
    }
}
