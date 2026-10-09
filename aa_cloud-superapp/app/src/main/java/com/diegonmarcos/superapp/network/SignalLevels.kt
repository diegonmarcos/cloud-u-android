package com.diegonmarcos.superapp.network

/**
 * The status strip's 4-step signal levels (0..4, -1 = no level to show), as pure rules so the
 * mapping is tested without a radio. The strip draws them as the dots under an icon
 * (ui/SignalDotsView) and the network popup prints the same number ("Signal 3/4 (-67 dBm)").
 */
object SignalLevels {
    const val NONE = -1
    const val MAX = 4

    /** Cellular: SignalStrength.getLevel() is already 0..4; anything else is "unknown". */
    fun cell(level: Int?): Int = if (level == null || level < 0) NONE else level.coerceAtMost(MAX)

    /** The "no RSSI" value WifiInfo reports (WifiInfo.INVALID_RSSI) and the floor below it. */
    private const val INVALID_RSSI = -127
    private const val MIN_RSSI = -100
    private const val MAX_RSSI = -55

    /**
     * Wi-Fi: the platform's 5-level formula (WifiManager.calculateSignalLevel(rssi, 5)), which is
     * already 0..4. Restated here because the static one is deprecated and the instance one
     * (API 30+) follows a per-device table that a unit test cannot pin; the two agree on stock
     * Android. [rssi] null, 0 or invalid = not on Wi-Fi.
     */
    fun wifi(rssi: Int?): Int = when {
        rssi == null || rssi >= 0 || rssi <= INVALID_RSSI -> NONE
        rssi <= MIN_RSSI -> 0
        rssi >= MAX_RSSI -> MAX
        else -> (rssi - MIN_RSSI) * MAX / (MAX_RSSI - MIN_RSSI)
    }

    /**
     * WireGuard: link quality from the freshest peer handshake. WireGuard re-handshakes every
     * 2 min while traffic (or keepalive) flows and drops a session at 3 min (REJECT_AFTER_TIME),
     * so: under 2 min = 4, under 3 min = 3, under 5 min = 2, any older handshake = 1, none (or the
     * tunnel down) = 0. [latencyMs], when the engine knows one, caps the level (>= 300 ms -> 2,
     * >= 150 ms -> 3). [up] false = the tunnel is down: no dots at all (the icon is dim anyway).
     */
    fun wg(up: Boolean, handshakeAgeMs: Long?, latencyMs: Long? = null): Int {
        if (!up) return NONE
        val age = handshakeAgeMs ?: return 0
        if (age < 0) return 0
        val byAge = when {
            age < 120_000L -> 4
            age < 180_000L -> 3
            age < 300_000L -> 2
            else -> 1
        }
        val cap = when {
            latencyMs == null -> MAX
            latencyMs >= 300 -> 2
            latencyMs >= 150 -> 3
            else -> MAX
        }
        return minOf(byAge, cap)
    }

    /** "3/4", or "—" when there is no level. */
    fun label(level: Int): String = if (level < 0) "—" else "$level/$MAX"
}
