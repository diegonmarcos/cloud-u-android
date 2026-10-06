package com.diegonmarcos.superapp.network.mesh

import android.content.Intent

/** A scripted [MeshPort]: the tunnel is a few fields, every write is recorded so a test sees what the page asked for. */
class FakePort(
    var engine: Boolean = true,
    var up: Boolean = false,
    var key: Boolean = true,
    var consent: Intent? = null,
) : MeshPort {
    val calls = mutableListOf<String>()
    var peers = mutableListOf(
        PeerCfg("gcp-proxy", "KEY-HUB", "", "35.0.0.1:443", "10.0.0.0/24", "25"),
        PeerCfg("oci-analytics", "KEY-OCI", "", "1.2.3.4:51821", "10.1.0.0/24, 2603:c026:c104:8f00:ff9b::/96", "25"),
    )
    var mtu = "1380"; var failWith: String? = null
    var pendingApply = false
    var stored = mutableMapOf<String, String>()
    var excluded = listOf<String>()
    var preset = "private_only"
    var hs = 1L

    private fun rec(s: String): String { calls += s; failWith?.let { error(it) }; return "ok:$s" }

    override fun config() = ConfigView("wg-mesh", "10.0.0.9/24", "PUBKEY", "10.0.0.1, 10.1.0.1", mtu, "", "", "Private only · 10.0.0.1", peers.toList())
    override fun engineInstalled() = engine
    override fun sample(nowMs: Long) = Sample(nowMs, engine, engine && up, if (engine && up) peers.map { PeerCounters(it.publicKey, 1000, 500, hs) } else emptyList())
    override fun probeLatency(cfg: ConfigView, timeoutMs: Int, nowMs: Long) = LatencyReading(nowMs, 12, peers.associate { it.publicKey to 20 })
    override fun pendingApply() = pendingApply
    override fun alwaysOn(): Boolean? = if (engine) false else null
    override fun lockdown(): Boolean? = if (engine) false else null
    override fun consentIntent() = consent
    override fun connect() = rec("connect").also { up = true }
    override fun disconnect() = rec("disconnect").also { up = false }
    override fun reconnect() = rec("reconnect").also { up = true }
    override fun mtu() = mtu
    override fun setMtu(v: String) = rec("mtu=$v").also { mtu = v }
    override fun keepalive() = peers.firstOrNull()?.keepalive.orEmpty()
    override fun setKeepalive(v: String) = rec("keepalive=$v").also { peers = peers.map { it.copy(keepalive = v) }.toMutableList() }
    override fun setTunnelName(v: String) = rec("name=$v")
    override fun setAddress(v: String) = rec("address=$v")
    override fun setListenPort(v: String) = rec("port=$v")
    override fun hasPrivateKey() = key
    override fun setPrivateKey(v: String) = rec("key").also { key = true }
    override fun generateKey() = rec("genkey").also { key = true }
    override fun excludedApps() = excluded
    override fun setExcludedApps(v: List<String>) = rec("excluded=${v.joinToString(",")}").also { excluded = v }
    override fun installedApps() = listOf("com.example.a" to "Alpha", "org.b" to "Beta")
    override fun addPeer() = rec("peer.add").also { peers.add(PeerCfg("", "", "", "", "", "")) }
    override fun removePeer(index: Int) = rec("peer.remove=$index").also { peers.removeAt(index) }
    override fun updatePeer(index: Int, p: PeerCfg) = rec("peer.update=$index").also { peers[index] = p }
    override fun dnsPreset() = preset
    override fun setDnsPreset(id: String) = rec("dns=$id").also { preset = id }
    override fun dnsChoices() = listOf(Triple("private_only", "Private only", ""), Triple("warp", "WARP", "needs a second upstream"))
    override fun provider() = "cloud"
    override fun matchesCloudPreset() = true
    override fun applyCloudPreset() = rec("cloud")
    override fun setProviderCustom() = rec("custom")
    override fun storedProfiles(): Map<String, String> = stored
    override fun activeProfile() = ""
    override fun activateProfile(name: String) = rec("activate=$name")
    override fun installDeclared(id: String) = rec("install=$id")
    override fun importText(name: String, conf: String) = rec("import=$name")
}

class FakeHost : MeshHost {
    val events = mutableListOf<String>()
    var clip = ""
    var consentThen: (() -> Unit)? = null
    override fun requestConsent(intent: Intent, then: () -> Unit) { events += "consent"; consentThen = then }
    override fun copy(label: String, text: String) { events += "copy:$label"; clip = text }
    override fun paste() = clip
    override fun openVpnSettings() { events += "vpn" }
    override fun openAccount() { events += "account" }
    override fun importConf() { events += "importConf" }
    override fun exportConf() { events += "exportConf" }
    override fun exportProfiles() { events += "exportProfiles" }
}
