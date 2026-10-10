package com.diegonmarcos.superapp.network

/**
 * Is this phone ON the mesh right now? One answer, shared by the status strip's WG icon + dots,
 * the network popup's mesh section and the Mesh badge, as a pure rule so it is tested without a
 * device.
 *
 * "A VPN is up" is NOT the answer: since #751 the engine raises a peerless DNS-only tunnel
 * whenever the mesh tunnel is down, so Android reports a VPN transport while the phone is off
 * the mesh. "The mesh tunnel is up" is not the answer either: a tunnel whose hubs stopped
 * answering (UDP blocked, no path) stays up with nothing behind it. Only a recent WireGuard
 * handshake with a hub proves the mesh is reachable.
 *
 *   OFF     — the mesh tunnel is not running.
 *   NO_MESH — it runs, but no peer has handshaken within [FRESH_MS] (WireGuard re-keys every
 *             2 min while anything flows, so 3 min gives one missed re-key of slack).
 *   ON      — at least one peer handshake within [FRESH_MS].
 */
object WgLink {

    enum class State { OFF, NO_MESH, ON }

    /** [level] is the dots' 0..4 (NONE when not ON, so the dots always agree with the icon). */
    data class Reading(val state: State, val level: Int, val reason: String) {
        val onMesh: Boolean get() = state == State.ON
    }

    /** Freshness window for a hub handshake. */
    const val FRESH_MS = 180_000L
    /** A handshake stamped this far in the future still counts (engine rounding); further = the phone clock moved back. */
    const val SKEW_MS = 5_000L

    /** MeshTransport.Path.NONE's label: the last connect found no way to the hubs. */
    const val NO_PATH = "No path"

    /** The strip's two tints, shared by every icon: white when on, the faint grey when not. */
    const val TINT_ON: Int = 0xFFFFFFFF.toInt()
    const val TINT_OFF: Int = 0x44FFFFFF

    /** NO_MESH has no tint of its own: the strip shows degraded as off (never white). */
    fun tint(state: State): Int = if (state == State.ON) TINT_ON else TINT_OFF

    /**
     * [tunnelUp] the engine's state of the MESH tunnel (not "any VPN"); [handshakesMs] each peer's
     * last handshake, epoch millis, 0 = never; [nowMs] the same wall clock the engine stamps with;
     * [path] the fallback rung's label ("No path" names why there is no mesh); [vpnTransport]
     * whether Android shows a VPN anyway — only used to explain an OFF.
     */
    fun derive(
        tunnelUp: Boolean,
        handshakesMs: List<Long>,
        nowMs: Long,
        path: String = "",
        vpnTransport: Boolean = false,
    ): Reading {
        if (!tunnelUp) return Reading(State.OFF, SignalLevels.NONE,
            if (vpnTransport) "Mesh tunnel down (the VPN slot holds the DNS-only tunnel or another VPN) — off mesh"
            else "Mesh tunnel down — off mesh")
        val newest = handshakesMs.filter { it > 0L }.maxOrNull()
        val noPath = path == NO_PATH
        if (newest == null) return Reading(State.NO_MESH, SignalLevels.NONE,
            "Tunnel up, no handshake yet" + (if (noPath) " (no path to the hubs)" else "") + " — off mesh")
        val age = nowMs - newest
        if (age < -SKEW_MS) return Reading(State.NO_MESH, SignalLevels.NONE,
            "Tunnel up, last handshake is stamped ahead of the phone clock — off mesh until the next one")
        if (age >= FRESH_MS) return Reading(State.NO_MESH, SignalLevels.NONE,
            "Tunnel up, no handshake for ${ago(age)}" + (if (noPath) " (no path to the hubs)" else "") + " — off mesh")
        // A fresh handshake is the hub answering: it outranks a "No path" the last connect recorded,
        // which itself says the tunnel stays on UDP and recovers when the network lets it through.
        val a = age.coerceAtLeast(0L)
        return Reading(State.ON, SignalLevels.wg(true, a), "On mesh — handshake ${ago(a)} ago")
    }

    /** "12 s", "7 min", "3 h". */
    fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600} h"
        }
    }
}
