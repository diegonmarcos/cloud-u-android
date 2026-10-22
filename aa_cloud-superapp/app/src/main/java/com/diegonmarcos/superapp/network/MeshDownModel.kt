package com.diegonmarcos.superapp.network

import java.util.Locale
import java.util.TimeZone

/**
 * #1120 When the mesh counts as DOWN, and what the alert says. PURE: a reading goes in, a step comes out.
 *
 * Down = the owner wants the mesh ([wanted]) and either the tunnel is down, or it is up but no peer has a
 * handshake inside [staleS]. A brief blip never alerts: the alert is raised only after the mesh has stayed
 * down for [DEBOUNCE_MS] without a single good reading in between, and it is withdrawn on the first good
 * reading (auto-clear). A deliberate Disconnect is not "down" at all.
 */
object MeshDownModel {
    const val DEBOUNCE_MS = 45_000L

    data class PeerReading(val name: String, val handshakeAgeS: Long?)

    data class Reading(
        val wanted: Boolean,
        val engineInstalled: Boolean,
        val up: Boolean,
        val tunnel: String,
        /** In config order; the first is the hub. */
        val peers: List<PeerReading>,
        val staleS: Long,
    ) {
        fun stalePeers(): List<PeerReading> =
            if (!up) peers else peers.filter { it.handshakeAgeS == null || it.handshakeAgeS > staleS }

        val down: Boolean
            get() = wanted && engineInstalled && (!up || (peers.isNotEmpty() && stalePeers().size == peers.size))
    }

    data class State(val downSinceMs: Long = 0L, val raised: Boolean = false)

    enum class Effect { NONE, RAISE, CLEAR }

    fun step(s: State, r: Reading, nowMs: Long): Pair<State, Effect> {
        if (!r.down) return State() to (if (s.raised) Effect.CLEAR else Effect.NONE)
        val since = if (s.downSinceMs == 0L) nowMs else s.downSinceMs
        if (s.raised) return s.copy(downSinceMs = since) to Effect.NONE
        return if (nowMs - since >= DEBOUNCE_MS) State(since, true) to Effect.RAISE else State(since, false) to Effect.NONE
    }

    /** Title and text of the alert: which hub / peers are down and since when. */
    fun title(r: Reading): String {
        val hub = r.peers.firstOrNull()?.name?.ifBlank { null }
        return if (!r.up) "Mesh down" + (hub?.let { " - $it unreachable" } ?: "")
        else "Mesh down - no peer answers"
    }

    fun text(r: Reading, sinceMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val clock = clock(sinceMs, tz)
        val names = r.stalePeers().joinToString(", ") { it.name.ifBlank { "peer" } }
        return if (!r.up) "Tunnel ${r.tunnel} is down since $clock" + (if (names.isNotEmpty()) " ($names)" else "") + ". Tap Reconnect."
        else "Tunnel ${r.tunnel} is up but no handshake from $names since $clock. Tap Reconnect."
    }

    private fun clock(epochMs: Long, tz: TimeZone): String {
        val s = Math.floorMod((epochMs + tz.getOffset(epochMs)) / 1000, 86_400L)
        return String.format(Locale.ROOT, "%02d:%02d", s / 3600, s % 3600 / 60)
    }
}
