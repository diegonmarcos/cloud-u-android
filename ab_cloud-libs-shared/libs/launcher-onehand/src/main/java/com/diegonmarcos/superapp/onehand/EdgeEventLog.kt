package com.diegonmarcos.superapp.onehand

/**
 * Last [CAP] edge-strip events (down / hold / push / fire / cancel / pass / lifecycle / error), each with a wall-clock
 * time and the REASON it ended. Pure Kotlin: the Diagnostics page and its remote endpoint read it, so "the menu does
 * nothing" on a real phone is answered by what the strip actually saw instead of by a guess.
 */
object EdgeEventLog {
    const val CAP = 20

    data class Event(val atMs: Long, val kind: String, val handle: String, val reason: String, val x: Float, val y: Float)

    private val ring = ArrayDeque<Event>()

    @Synchronized fun record(kind: String, handle: String = "", reason: String = "", x: Float = 0f, y: Float = 0f,
                             now: Long = System.currentTimeMillis()) {
        ring.addLast(Event(now, kind, handle, reason, x, y))
        while (ring.size > CAP) ring.removeFirst()
    }

    /** Like [record] but a repeat of the newest entry is dropped, so a per-resume check cannot flood the ring. */
    @Synchronized fun recordIfChanged(kind: String, reason: String) {
        val last = ring.lastOrNull()
        if (last == null || last.kind != kind || last.reason != reason) record(kind, reason = reason)
    }

    /** Oldest first. */
    @Synchronized fun snapshot(): List<Event> = ring.toList()

    @Synchronized fun clear() = ring.clear()
}
