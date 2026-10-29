package com.diegonmarcos.superapp.wifi

/**
 * Android's Wi-Fi scan throttling (#1263), mirrored so the page can say when the next scan is allowed: a foreground app gets
 * [limit] scans per [windowMs] (4 per 2 minutes since Android 9), a background app one per 30 minutes. The developer option
 * "Wi-Fi scan throttling" (Android 10+; WifiManager.isScanThrottleEnabled on API 30+) turns it off. Pure: JVM-tested.
 */
class ScanThrottle(val limit: Int = 4, val windowMs: Long = 120_000) {
    private val stamps = ArrayDeque<Long>()
    var enabled: Boolean = true

    fun record(now: Long) { stamps.addLast(now); trim(now) }
    private fun trim(now: Long) { while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) stamps.removeFirst() }
    fun used(now: Long): Int { trim(now); return stamps.size }
    fun remaining(now: Long): Int = if (!enabled) Int.MAX_VALUE else (limit - used(now)).coerceAtLeast(0)
    fun canScan(now: Long): Boolean = remaining(now) > 0

    /** When the next scan fits the window (now if it already does). */
    fun nextAllowedAt(now: Long): Long { trim(now); return if (!enabled || stamps.size < limit) now else stamps.first() + windowMs }
}
