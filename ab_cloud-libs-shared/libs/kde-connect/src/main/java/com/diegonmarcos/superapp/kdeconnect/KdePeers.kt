package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

/**
 * #733 WHICH peer Peer Control (the KDE page) talks to. The candidates are the
 * two declarations this module already bakes, in this order:
 *   - build.json::ui.kde_connect.devices — the PCs and phones that run KDE
 *     Connect, each with its wg IP
 *   - data/mesh.json nodes — every VM on the WireGuard mesh, the same snapshot
 *     the Probe section pings; a node already declared as a device (same wg IP)
 *     is not listed twice
 * No peer is written in Kotlin: a new device or VM appears by being declared.
 * The choice persists, so every action on the page — connect, pair, ping,
 * clipboard, remote input, media, share, self-test — targets it until changed.
 */
object KdePeers {

    fun all(devices: List<KdeConnectConfig.Device>, nodes: List<KdeMesh.Node>): List<KdeConnectConfig.Device> {
        val declared = devices.map { it.wgIp }.toSet()
        return devices + nodes.filter { it.wgIp !in declared }.map {
            KdeConnectConfig.Device(id = it.name,
                label = listOf(it.alias.ifEmpty { it.name }, it.role).filter { r -> r.isNotEmpty() }.joinToString(" · "),
                wgIp = it.wgIp, primary = false)
        }
    }

    fun all(): List<KdeConnectConfig.Device> = all(KdeConnectConfig.get().devices, KdeMesh.nodes())

    /** The persisted choice, else the declared primary device, else the first peer. */
    fun selected(ctx: Context, peers: List<KdeConnectConfig.Device> = all()): KdeConnectConfig.Device? {
        val id = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return peers.firstOrNull { it.id == id } ?: peers.firstOrNull { it.primary } ?: peers.firstOrNull()
    }

    fun select(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, id).apply()
    }

    private const val PREFS = "kdeconnect_peer_selection"
    private const val KEY = "selected"
}
