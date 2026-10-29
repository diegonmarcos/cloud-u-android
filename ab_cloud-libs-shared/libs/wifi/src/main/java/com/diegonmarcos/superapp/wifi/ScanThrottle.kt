package com.diegonmarcos.superapp.wifi

/**
 * Android's Wi-Fi scan throttling (#1263), mirrored so the page can say when the next scan is allowed: a foreground app gets
 * [limit] scans per [windowMs] (4 per 2 minutes since Android 9), a background app one per 30 minutes. The developer option
 * "Wi-Fi scan throttling" (Android 10+; WifiManager.isScanThrottleEnabled on API 30+) turns it off. Pure: JVM-tested.
 */
class ScanThrottle(val limit: Int = 4, val windowMs: Long = 120_000) {
    private val stamps = ArrayDeque<Long>()
    var enabled: Boolean = true

    /** After Android refused a scan, no startScan before this (its own counter may be ahead of ours). */
    var blockedUntil: Long = 0L
        private set

    /** Record a scan Android ACCEPTED. A refused call does not count against Android's budget, so it is not recorded. */
    fun record(now: Long) { stamps.addLast(now); trim(now) }

    /** Android refused: back off one slot (window / limit) instead of retrying into the same refusal (#1274). */
    fun refuse(now: Long) { blockedUntil = now + slotMs() }

    /** The spacing that keeps a steady stream inside the budget: 2 min / 4 = 30 s. */
    fun slotMs(): Long = if (limit <= 0) windowMs else windowMs / limit
    private fun trim(now: Long) { while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) stamps.removeFirst() }
    fun used(now: Long): Int { trim(now); return stamps.size }
    fun remaining(now: Long): Int = if (!enabled) Int.MAX_VALUE else (limit - used(now)).coerceAtLeast(0)
    fun canScan(now: Long): Boolean = now >= blockedUntil && remaining(now) > 0

    /**
     * Whether Auto mode may scan now (#1274): never closer than max([intervalMs], one slot) to its last attempt, and it
     * leaves [reserve] scans of the window for the Scan button, so Auto alone can never drain the budget to 0.
     */
    fun autoDue(now: Long, lastAutoAt: Long, intervalMs: Long, reserve: Int = 1): Boolean {
        if (!canScan(now)) return false
        if (enabled && remaining(now) <= reserve) return false
        val gap = if (enabled) maxOf(intervalMs, slotMs()) else intervalMs
        return lastAutoAt <= 0L || now - lastAutoAt >= gap
    }

    /** When the next scan fits the window (now if it already does). */
    fun nextAllowedAt(now: Long): Long { trim(now); val t = if (!enabled || stamps.size < limit) now else stamps.first() + windowMs; return maxOf(t, blockedUntil) }
}
