package com.diegonmarcos.superapp.network

import java.util.Locale

/**
 * The "Mesh" badge as a pure function of the mesh state: [Snapshot] in,
 * [Card] out. No Android type is touched here, so the labels, the action set
 * and the expanded text are unit-testable without a device, and the service
 * that posts the notification (notificationcenter/NetworkBadgeService.kt) has
 * nothing to decide - it gathers a Snapshot through INetBackend and draws the
 * Card.
 */
object NetworkBadgeModel {

    /** One configured peer joined with whatever the engine reports about it. */
    data class PeerRow(
        val name: String,
        val endpoint: String,
        val allowedIps: String,
        /** Epoch millis of the last handshake; 0 = never (or tunnel down). */
        val lastHandshakeMs: Long,
        val rx: Long,
        val tx: Long,
    )

    data class Snapshot(
        val engineInstalled: Boolean,
        val connected: Boolean,
        val tunnel: String,
        /** The named profile in force; shown only when it differs from [tunnel]. */
        val profile: String = "",
        val addresses: List<String> = emptyList(),
        val dns: List<String> = emptyList(),
        val peers: List<PeerRow> = emptyList(),
        /** The SYSTEM always-on VPN profile, as the engine reports it. */
        val alwaysOn: Boolean = false,
        /** When this process first saw the tunnel up; 0 = unknown. */
        val upSinceMs: Long = 0,
        /** True when [upSinceMs] is the real moment it came up, false when it
         *  is only when we first looked (so uptime is a lower bound). */
        val upSinceExact: Boolean = false,
        val nowMs: Long = 0,
        /** Why the last action did not do what was asked; "" when nothing to say. */
        val note: String = "",
        /** DNS servers Android resolves with right now (the active network's). */
        val resolvers: List<String> = emptyList(),
        /** True when the active network is the VPN, so [resolvers] came through the mesh. */
        val resolversOnVpn: Boolean = false,
        /** The fleet DNS bridge line, see [bridgeLine]; "" = not asked. */
        val bridge: String = "",
        /** Android's Private DNS line, see [privateDnsLine]; "" = not asked. */
        val privateDns: String = "",
        /** Which fallback rung carries the tunnel (MeshTransport: Direct UDP / Pinned IP / DoH / TLS-443 relay); "" = not decided. */
        val path: String = "",
        /** How that path was reached (the name resolved, the relay address and its source); "" = nothing to add. */
        val pathDetail: String = "",
    )

    enum class Act { ALWAYS_ON, TOGGLE, MORE }

    data class Action(val act: Act, val label: String)

    data class Card(
        val title: String,
        /** The collapsed line. */
        val text: String,
        /** The expanded body, one fact per line. */
        val expanded: String,
        /** Exactly three, in this order: Always On, Connect/Disconnect, More. */
        val actions: List<Action>,
    )

    fun card(s: Snapshot): Card = Card(
        title = title(s),
        text = collapsed(s),
        expanded = expanded(s),
        actions = actions(s),
    )

    fun title(s: Snapshot): String =
        "Mesh · " + if (s.connected) "Connected" else "Disconnected"

    fun alwaysOnLabel(s: Snapshot) = "Always On: " + if (s.alwaysOn) "ON" else "OFF"

    fun toggleLabel(s: Snapshot) = if (s.connected) "Disconnect" else "Connect"

    fun actions(s: Snapshot): List<Action> = listOf(
        Action(Act.ALWAYS_ON, alwaysOnLabel(s)),
        Action(Act.TOGGLE, toggleLabel(s)),
        Action(Act.MORE, "More"),
    )

    /** This device's mesh IP: the first interface address without its prefix. */
    fun meshIp(s: Snapshot): String =
        s.addresses.firstOrNull { it.isNotBlank() }?.substringBefore('/')?.trim().orEmpty()

    fun collapsed(s: Snapshot): String {
        val parts = mutableListOf<String>()
        if (s.note.isNotBlank()) parts += s.note
        if (!s.engineInstalled) {
            parts += "Cloud-Lib-Net-Wg is not installed"
            return parts.joinToString(SEP)
        }
        parts += s.tunnel.ifBlank { "no tunnel" }
        meshIp(s).takeIf { it.isNotEmpty() }?.let { parts += it }
        parts += peerCount(s.peers.size)
        if (s.connected && s.path.isNotBlank()) parts += s.path
        if (s.connected) {
            parts += "↓${bytes(s.peers.sumOf { it.rx })} ↑${bytes(s.peers.sumOf { it.tx })}"
        }
        return parts.joinToString(SEP)
    }

    fun expanded(s: Snapshot): String {
        val l = mutableListOf<String>()
        if (s.note.isNotBlank()) l += s.note
        l += "Tunnel: ${s.tunnel.ifBlank { "-" }}" +
            if (s.profile.isNotBlank() && s.profile != s.tunnel) " (profile ${s.profile})" else ""
        l += "Interface: " + s.addresses.filter { it.isNotBlank() }.joinToString(", ").ifEmpty { "-" }
        if (s.path.isNotBlank()) l += "Path: ${s.path}" + if (s.pathDetail.isNotBlank()) " (${s.pathDetail})" else ""
        l += "Mesh DNS: " + s.dns.filter { it.isNotBlank() }.joinToString(", ").ifEmpty { "-" }
        l += "Resolvers: " + s.resolvers.filter { it.isNotBlank() }.joinToString(", ").ifEmpty { "-" } +
            if (s.resolversOnVpn && s.resolvers.isNotEmpty()) " (via VPN)" else ""
        if (s.bridge.isNotBlank()) l += "Bridge: ${s.bridge}"
        if (s.privateDns.isNotBlank()) l += "Private DNS: ${s.privateDns}"
        l += "Always On: " + if (s.alwaysOn) "on" else "off"
        l += "Uptime: " + uptime(s)
        l += if (s.peers.isEmpty()) "Peers: none configured" else "Peers (${s.peers.size}):"
        for (p in s.peers) {
            val hs = if (s.connected) "handshake ${relative(p.lastHandshakeMs, s.nowMs)}" else "not connected"
            l += "• ${p.name.ifBlank { "peer" }} · $hs"
            l += "   endpoint ${p.endpoint.ifBlank { "-" }}"
            l += "   allowed ${p.allowedIps.ifBlank { "-" }}"
        }
        return l.joinToString("\n")
    }

    /** "127.0.0.1:2053 listening, last route Android resolver on Wi-Fi" / "127.0.0.1:2053 not listening (taken)". */
    fun bridgeLine(listening: Boolean, port: Int, route: String?, why: String?): String {
        val where = "127.0.0.1:" + if (port > 0) port.toString() else "-"
        return if (listening) where + " listening" + (route?.takeIf { it.isNotBlank() }?.let { ", last route $it" } ?: ", no query yet")
        else where + " not listening" + (why?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
    }

    /** Android's Private DNS: "off", "automatic", "hostname dns.example (active)". */
    fun privateDnsLine(mode: String?, specifier: String?, active: Boolean?, server: String?): String = when (mode) {
        null, "" -> "unknown"
        "off" -> "off"
        "opportunistic" -> "automatic" + if (active == true) " (active${server?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""})" else " (not in use)"
        "hostname" -> "hostname ${specifier.orEmpty().ifBlank { "-" }}" + if (active == true) " (active)" else " (not active)"
        else -> mode
    }

    fun uptime(s: Snapshot): String {
        if (!s.connected) return "-"
        if (s.upSinceMs <= 0) return "unknown"
        val d = duration(s.nowMs - s.upSinceMs)
        return if (s.upSinceExact) d else "≥ $d"
    }

    /** "never", "just now", "12s ago", "3m ago", "2h ago", "4d ago". */
    fun relative(thenMs: Long, nowMs: Long): String {
        if (thenMs <= 0) return "never"
        val sec = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
        return when {
            sec < 5 -> "just now"
            sec < 60 -> "${sec}s ago"
            sec < 3600 -> "${sec / 60}m ago"
            sec < 86_400 -> "${sec / 3600}h ago"
            else -> "${sec / 86_400}d ago"
        }
    }

    /** "45s", "5m 3s", "2h 5m", "1d 3h": the two largest units. */
    fun duration(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return when {
            sec < 60 -> "${sec}s"
            sec < 3600 -> "${sec / 60}m ${sec % 60}s"
            sec < 86_400 -> "${sec / 3600}h ${(sec % 3600) / 60}m"
            else -> "${sec / 86_400}d ${(sec % 86_400) / 3600}h"
        }
    }

    fun bytes(n: Long): String {
        val v = n.coerceAtLeast(0).toDouble()
        return when {
            v < 1024 -> "${n.coerceAtLeast(0)} B"
            v < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", v / 1024)
            v < 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", v / (1024 * 1024))
            else -> String.format(Locale.US, "%.2f GB", v / (1024.0 * 1024 * 1024))
        }
    }

    private fun peerCount(n: Int) = if (n == 1) "1 peer" else "$n peers"

    private const val SEP = " · "
}
