package com.diegonmarcos.superapp.network.mesh

import android.content.Intent

/**
 * #877 Everything the Cloud Mesh page asks of the tunnel, as one interface. [AndroidMeshPort] is the
 * only implementation and it is a thin shell over the EXISTING paths - WgState.backend (the engine's
 * getState / setState / getStatistics), WireGuardPrefs, AccountMesh, FleetDns. There is no second
 * tunnel path: a test fakes this interface, the app never has two of it.
 *
 * Every method blocks on prefs or the engine binder, so the store calls them off the main thread.
 * A method that cannot do what was asked THROWS with the reason; the store turns it into the notice.
 */
interface MeshPort {
    // ── reads ──
    fun config(): ConfigView
    fun engineInstalled(): Boolean
    fun sample(nowMs: Long): Sample
    /** One round of latency probes: the hub (first fleet resolver) and each peer's own mesh resolver. */
    fun probeLatency(cfg: ConfigView, timeoutMs: Int, nowMs: Long): LatencyReading
    /** True when the tunnel is up on a config older than the stored one (reconnect to apply). */
    fun pendingApply(): Boolean
    /** Android's Always-on VPN / lockdown for the engine's VPN profile; null when the engine cannot say. */
    fun alwaysOn(): Boolean?
    fun lockdown(): Boolean?
    /** The VPN-consent intent, or null when consent is held (or the engine is absent). */
    fun consentIntent(): Intent?

    // ── tunnel state (WgState.backend.setState) ──
    fun connect(): String
    fun disconnect(): String
    fun reconnect(): String

    // ── interface fields (WireGuardPrefs) ──
    fun mtu(): String
    fun setMtu(v: String): String
    fun keepalive(): String
    /** Persistent keepalive for EVERY peer. */
    fun setKeepalive(v: String): String
    fun setTunnelName(v: String): String
    fun setAddress(v: String): String
    fun setListenPort(v: String): String
    fun hasPrivateKey(): Boolean
    fun setPrivateKey(v: String): String
    /** A fresh key pair stored as this device's; returns its public half. */
    fun generateKey(): String
    fun excludedApps(): List<String>
    fun setExcludedApps(v: List<String>): String
    /** Launchable apps as (package, label), for the exclusion picker's suggestions. */
    fun installedApps(): List<Pair<String, String>>

    // ── peers ──
    fun addPeer(): String
    fun removePeer(index: Int): String
    fun updatePeer(index: Int, p: PeerCfg): String

    // ── DNS (FleetDns) ──
    fun dnsPreset(): String
    /** Choose the preset and, with the tunnel up, re-apply it; returns what Android now resolves with. */
    fun setDnsPreset(id: String): String
    /** (id, label) of every declared preset, with the reason when one cannot be chosen. */
    fun dnsChoices(): List<Triple<String, String, String>>

    // ── provider + profiles ──
    fun provider(): String
    fun matchesCloudPreset(): Boolean
    fun applyCloudPreset(): String
    fun setProviderCustom(): String
    fun storedProfiles(): Map<String, String>
    fun activeProfile(): String
    /** Make stored profile [name] the tunnel; re-applies when the tunnel is up. */
    fun activateProfile(name: String): String
    /** Store the declared template [id] (this device's key spliced at parse time) and make it the tunnel. */
    fun installDeclared(id: String): String
    /** [conf] through the same parser the .conf import uses, then stored under [name]. */
    fun importText(name: String, conf: String): String

    // ── fallbacks (network/MeshTransport.kt: pinned IPs, DoH, the TLS-443 relay) ──
    /** Which way the tunnel reaches its hubs, as the last connect chose it. */
    fun transport(): TransportView
    /** auto | direct | relay; applies at the next connect. */
    fun setTransportMode(v: String): String
    fun hasRelayKey(): Boolean
    /** Store the relay's key (blank clears it); never echoed back. */
    fun setRelayKey(v: String): String
    /** Every rung probed on its own, in ladder order: Direct UDP, system DNS, pinned IP, DoH, TLS-443 relay. */
    fun testFallbacks(): List<ProbeLine>
}

/** What the page needs from its host fragment: the Android surfaces a composable cannot open itself. */
interface MeshHost {
    /** Ask for VPN consent, then run [then]; the host owns the activity-result launcher. */
    fun requestConsent(intent: Intent, then: () -> Unit)
    fun copy(label: String, text: String)
    fun paste(): String
    fun openVpnSettings()
    fun openAccount()
    fun importConf()
    fun exportConf()
    fun exportProfiles()
}

/**
 * Which way the tunnel reaches its hubs. [path]: direct | pinned | doh | relay | none, "" before the first
 * connect; [detail] says how (the name resolved, the relay address and its source, or why nothing worked);
 * [relay] is the engine's relay legs, "" when none runs.
 */
data class TransportView(val mode: String, val path: String, val detail: String, val relay: String)

/** One rung of Test fallbacks ([id]: udp | dns | pinned | doh | relay); [ok] null = not testable right now, [detail] says why. */
data class ProbeLine(val id: String, val ok: Boolean?, val detail: String)
