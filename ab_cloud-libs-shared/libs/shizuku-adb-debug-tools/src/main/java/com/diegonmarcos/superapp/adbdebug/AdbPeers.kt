package com.diegonmarcos.superapp.adbdebug

/**
 * The apps that carry an adb channel of their own and must run side by side on one phone (SuperApp, Cloud Store).
 *
 * Nothing about a channel is shared between them: each app has its own key (AdbIdentity), its own paired-devices entry,
 * its own prefs, its own local-server port and process name (build.json::shizuku_diagnostics.local_server) and its own
 * notification channel. What the phone shares is ONE adbd and ONE pairing window, so the only things two apps can collide
 * on are (1) the pairing window (two apps both waiting for the same six digits) and (2) a reading of the other's state.
 * This file is the pure rule for both; [AdbPeerReader] and [AdbStatusProvider] carry it across the app boundary.
 *
 * Pure (no Android): [AdbPeersTest] holds it.
 */
data class AdbPeer(val label: String, val applicationId: String) {
    /** The authority of that app's [AdbStatusProvider] (declared once, in this lib's manifest, as `${applicationId}.adbstatus`). */
    val authority: String get() = applicationId + AdbPeers.AUTHORITY_SUFFIX
}

/** How one app's channel stands, as the other app (and the home status strip) sees it. */
enum class PeerLink { UP, DEGRADED, CONNECTING, DOWN, UNREACHABLE }

data class PeerStatus(val peer: AdbPeer, val link: PeerLink, val pairing: Boolean = false, val pairingSince: Long = 0L)

object AdbPeers {
    const val AUTHORITY_SUFFIX = ".adbstatus"
    const val METHOD_STATUS = "adb_status"
    const val KEY_LINK = "link"
    const val KEY_PAIRING = "pairing"
    const val KEY_PAIRING_SINCE = "pairing_since"

    /** A pairing claim older than this is a crashed or abandoned attempt, not a held window. */
    const val PAIRING_CLAIM_MS = 3 * 60_000L

    /** Every app that runs a channel of its own, in the order the home status strip draws its dots. */
    val FLEET: List<AdbPeer> = listOf(
        AdbPeer("SuperApp", "com.diegonmarcos.superapp"),
        AdbPeer("Cloud Store", "com.diegonmarcos.cloudstore"),
    )

    fun linkOf(state: ConnState): PeerLink = when (state) {
        ConnState.UP -> PeerLink.UP
        ConnState.DEGRADED -> PeerLink.DEGRADED
        ConnState.CONNECTING -> PeerLink.CONNECTING
        ConnState.DOWN -> PeerLink.DOWN
    }

    fun parseLink(name: String?): PeerLink = PeerLink.values().firstOrNull { it.name == name } ?: PeerLink.UNREACHABLE

    /** The page's one-word state for [link]. */
    fun title(link: PeerLink): String = when (link) {
        PeerLink.UP -> "up"
        PeerLink.DEGRADED -> "degraded"
        PeerLink.CONNECTING -> "connecting"
        PeerLink.DOWN -> "down"
        PeerLink.UNREACHABLE -> "not installed or not answering"
    }

    fun dot(link: PeerLink): Dot = when (link) {
        PeerLink.UP -> Dot.OK
        PeerLink.DEGRADED, PeerLink.CONNECTING -> Dot.WARN
        PeerLink.DOWN -> Dot.BAD
        PeerLink.UNREACHABLE -> Dot.UNKNOWN
    }

    /** Is [s] holding the pairing window at [now]? A claim from the future (clock change) or older than [PAIRING_CLAIM_MS] is not. */
    fun holdsPairing(s: PeerStatus, now: Long): Boolean = s.pairing && now - s.pairingSince in 0..PAIRING_CLAIM_MS

    /** The OTHER app that holds the pairing window right now, or null when [ownApplicationId] may pair. */
    fun pairingHeldElsewhere(ownApplicationId: String, statuses: List<PeerStatus>, now: Long): PeerStatus? =
        statuses.firstOrNull { it.peer.applicationId != ownApplicationId && holdsPairing(it, now) }

    /** What the refused app tells the person. */
    fun pairingRefusal(holder: PeerStatus): String =
        "${holder.peer.label} is pairing right now: finish there (or cancel its pairing notification), then pair here. " +
            "Two apps cannot share one pairing code."
}

/**
 * The pairing window this app holds, if any. One process = one app, so a plain field is the claim; it dies with the
 * process, so a crashed pairing never holds the window for more than [AdbPeers.PAIRING_CLAIM_MS].
 */
object PairingClaim {
    @Volatile var since: Long = 0L
        private set
    fun take(now: Long = System.currentTimeMillis()) { since = now }
    fun release() { since = 0L }
    val held: Boolean get() = since > 0L
}
