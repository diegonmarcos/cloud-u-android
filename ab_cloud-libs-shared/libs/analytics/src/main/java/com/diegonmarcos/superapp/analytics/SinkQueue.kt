package com.diegonmarcos.superapp.analytics

import java.util.ArrayDeque

/** One queued analytics event: the event name, and its string properties. */
internal typealias AnalyticsEvent = Pair<String, Map<String, String>>

/**
 * One analytics backend's OWN bounded retry queue.
 *
 * WHY EACH SINK NEEDS ITS OWN. Umami and Matomo used to share a single queue
 * and a single verdict:
 *
 *     val ok = sendUmami(name, props) or sendMatomo(name, props)
 *
 * Read that precisely: Kotlin's `or` does NOT short-circuit, so both sinks
 * really did fire every time. Nothing was ever skipped, and changing `or` to
 * anything else would have fixed nothing. The defect is that TWO outcomes
 * collapsed into ONE boolean. Whenever Umami succeeded and Matomo did not,
 * `ok` was true, the event was treated as delivered and left the queue, and
 * the Matomo copy was gone for good — with no way for the caller to tell
 * "both landed" from "one landed".
 *
 * This module is embedded in all twelve shipped applications, so every one of
 * their event streams was silently half-lossy for as long as either backend
 * was down. Per #353 one backend was destroyed outright for a period, which is
 * exactly when the loss was worst and least visible.
 *
 * Giving each sink its own queue is what makes "delivered to Umami" and
 * "delivered to Matomo" separately true or false. That separation is the whole
 * fix: it is the only thing that lets a failed copy be RETRIED rather than
 * discarded because the other sink happened to succeed.
 *
 * WHY BOUNDED, AND AT WHAT. [capacity] events per sink, oldest evicted first.
 * A phone can sit offline for a day, and a queue that grows for that whole day
 * is a worse bug than the one this fixes. The bound is per sink, so two sinks
 * hold at most two times [capacity] events between them — see
 * [Analytics.MAX_QUEUE_PER_SINK] for the number and the reasoning behind it.
 *
 * WHY THIS IS A SEPARATE, ANDROID-FREE FILE. It names no Context, no resources
 * and no `R` — nothing from the Android platform at all. That keeps it inside
 * the `libs/` module boundary, and it means the retry rule can be EXECUTED by
 * a plain JVM test rather than asserted on by reading the source. A guard that
 * greps for the shape of this code would pass for exactly as long as it takes
 * the text and the behaviour to drift.
 */
internal class SinkQueue(val label: String, private val capacity: Int) {

    private val pending = ArrayDeque<AnalyticsEvent>()

    /**
     * Queue one event for this sink.
     *
     * Evicts the OLDEST first when full: on a phone, recent activity is the
     * part worth keeping, and dropping the newest would mean a long outage
     * froze the stream at its stalest point.
     */
    fun offer(event: AnalyticsEvent) = synchronized(pending) {
        while (pending.size >= capacity) pending.pollFirst()
        pending.addLast(event)
    }

    /**
     * Send what is queued, oldest first, until the queue is empty or one send
     * fails.
     *
     * A failed event goes BACK at the front and the drain stops there. That
     * preserves order, and it stops a backend that is already down from being
     * handed the rest of the queue one timeout at a time.
     *
     * Crucially it stops only THIS sink. The other sink's queue is a different
     * object with a different drain, so it delivers regardless — which is the
     * entire point of the split and the property the retry test pins down.
     */
    fun drain(send: (AnalyticsEvent) -> Boolean) {
        while (true) {
            val event = synchronized(pending) { pending.pollFirst() } ?: return
            if (!send(event)) {
                synchronized(pending) { if (pending.size < capacity) pending.addFirst(event) }
                return
            }
        }
    }

    /** Drop everything undelivered. Used when consent is withdrawn. */
    fun clear() = synchronized(pending) { pending.clear() }

    /** How many events this sink still owes. The retry test asserts on this. */
    fun size(): Int = synchronized(pending) { pending.size }
}
