package com.diegonmarcos.superapp.decisions.core

/**
 * After [failures] consecutive failed calls the breaker OPENS for [openMs]: nothing is sent, so a dead or
 * rate-limited API is not hammered by every screen. When the time is up it lets exactly ONE trial through
 * (half open); that call's outcome closes it or opens it again for another full period.
 */
class CircuitBreaker(private val failures: Int, private val openMs: Long, private val clock: () -> Long) {

    private var consecutive = 0
    private var openUntil = 0L
    private var trial = false

    @Synchronized
    fun allow(): Boolean {
        if (consecutive < failures) return true
        if (clock() < openUntil) return false
        if (trial) return false
        trial = true
        return true
    }

    @Synchronized
    fun success() {
        consecutive = 0
        trial = false
    }

    @Synchronized
    fun failure() {
        consecutive++
        trial = false
        if (consecutive >= failures) openUntil = clock() + openMs
    }

    @Synchronized
    fun isOpen(): Boolean = consecutive >= failures && clock() < openUntil
}
